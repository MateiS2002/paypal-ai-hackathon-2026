// Client for the desk API (contracts/desk-api.md). The browser only ever talks to its own origin:
// nginx (or the Vite dev proxy) forwards /cafe/api/** and /roaster/api/** to the right desk.

export type DeskId = 'cafe' | 'roaster'

export const DESKS: { id: DeskId; label: string; blurb: string }[] = [
  { id: 'cafe', label: 'Café', blurb: 'Buy side: post a need, compare quotes, pay invoices.' },
  { id: 'roaster', label: 'Roaster', blurb: 'Sell side: answer quotes, send PayPal invoices, track the ledger.' },
]

export type Me = {
  username: string
  deskId: string
  deskName: string
  domain: string
  simulated: boolean
}

export type DeskEvent = { type: string; data: Record<string, unknown> }

type CsrfToken = { headerName: string; token: string }

const base = (desk: DeskId) => `/${desk}/api`

async function csrf(desk: DeskId): Promise<CsrfToken> {
  const res = await fetch(`${base(desk)}/csrf`, { credentials: 'same-origin' })
  if (!res.ok) throw new Error(`Could not reach the ${desk} desk (${res.status})`)
  return res.json()
}

async function post(desk: DeskId, path: string, body?: URLSearchParams): Promise<Response> {
  const token = await csrf(desk)
  return fetch(`${base(desk)}${path}`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { [token.headerName]: token.token },
    body,
  })
}

/** The logged-in user, or null before login. */
export async function me(desk: DeskId): Promise<Me | null> {
  const res = await fetch(`${base(desk)}/me`, { credentials: 'same-origin' })
  if (res.status === 401) return null
  if (!res.ok) throw new Error(`The ${desk} desk answered ${res.status}`)
  return res.json()
}

/** Returns false on wrong credentials. */
export async function login(desk: DeskId, username: string, password: string): Promise<boolean> {
  const res = await post(desk, '/login', new URLSearchParams({ username, password }))
  if (res.status === 401) return false
  if (!res.ok) throw new Error(`Login failed (${res.status})`)
  return true
}

export async function logout(desk: DeskId): Promise<void> {
  const res = await post(desk, '/logout')
  if (!res.ok) throw new Error(`Logout failed (${res.status})`)
}

// Invoices (S1). Shapes match contracts/desk-api.md; money stays a decimal string, never a JS number.

export type Money = { amount: string; currency: string }

export type Customer = { name: string; email: string }

export type InvoiceStatus =
  | 'DRAFT'
  | 'SENT'
  | 'PAID'
  | 'PARTIALLY_PAID'
  | 'UNPAID'
  | 'PAYMENT_PENDING'
  | 'CANCELLED'
  | 'REFUNDED'
  | 'AWAITING_APPROVAL'
  | 'OTHER'

export type InvoiceRow = {
  id: string
  invoiceNumber: string | null
  customer: Customer
  lines: { description: string; quantity: string; unitPrice: Money; lineTotal: Money }[]
  total: Money
  status: InvoiceStatus
  source: 'MANUAL' | 'NEGOTIATION' | 'MESSY_ORDER'
  dueDate: string | null
  payerViewUrl: string | null
  createdAt: string
  updatedAt: string
  paidAt: string | null
}

export type NewInvoice = {
  customer: Customer
  lines: { description: string; quantity: string; unitPrice: Money }[]
}

/** The desk's readable message from a problem body (contracts/desk-api.md, Errors), or a generic one. */
async function problem(res: Response, fallback: string): Promise<Error> {
  try {
    const body = (await res.json()) as { detail?: string }
    if (body.detail) return new Error(body.detail)
  } catch {
    // Not JSON (for example nginx's own error page).
  }
  return new Error(`${fallback} (${res.status})`)
}

async function getJson<T>(desk: DeskId, path: string): Promise<T> {
  const res = await fetch(`${base(desk)}${path}`, { credentials: 'same-origin' })
  if (!res.ok) throw await problem(res, `The ${desk} desk answered`)
  return res.json()
}

export const listInvoices = (desk: DeskId) => getJson<InvoiceRow[]>(desk, '/invoices')

export const listCustomers = (desk: DeskId) => getJson<Customer[]>(desk, '/customers')

/**
 * Creates and sends an invoice. Reuse the same idempotencyKey when retrying the same invoice: the desk then
 * resumes it instead of creating a second PayPal invoice.
 */
export async function createInvoice(desk: DeskId, idempotencyKey: string, invoice: NewInvoice): Promise<InvoiceRow> {
  const token = await csrf(desk)
  const res = await fetch(`${base(desk)}/invoices`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      [token.headerName]: token.token,
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
    body: JSON.stringify(invoice),
  })
  if (!res.ok) throw await problem(res, 'Creating the invoice failed')
  return res.json()
}

/** Opens the desk's SSE stream. Call the returned function to close it. */
export function subscribe(desk: DeskId, onEvent: (event: DeskEvent) => void, onStatus: (open: boolean) => void) {
  const source = new EventSource(`${base(desk)}/events`)
  source.onopen = () => onStatus(true)
  source.onerror = () => onStatus(false)
  source.onmessage = (message) => onEvent(JSON.parse(message.data) as DeskEvent)
  return () => source.close()
}
