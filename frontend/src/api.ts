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

// Needs and quotes (S2). Shapes match contracts/desk-api.md. Every number here was set and checked by the desks'
// Java code; the UI only shows it.

export type Unit = 'kg' | 'lb'

export type Priority = 'SPEED' | 'PRICE' | 'BALANCED'

export type PurchaseRequest = {
  product: string | null
  origin: string | null
  quantity: string | null
  unit: Unit | null
  deliverBy: string | null
  priority: Priority | null
  maxUnitPrice: Money | null
}

export type ParseResult = {
  complete: boolean
  request: PurchaseRequest
  missing: string[]
  question: string | null
}

export type NeedRow = {
  id: string
  request: PurchaseRequest
  status: 'COLLECTING' | 'COMPLETE'
  suppliersAsked: number
  quotesReceived: number
  createdAt: string
}

export type QuoteRow = {
  id: string
  needId: string
  rank: number
  supplier: { domain: string; name: string; simulated: boolean }
  unitPrice: Money
  quantity: string
  availableQuantity: string
  unit: Unit
  total: Money
  deliveryDate: string
  validUntil: string
  partial: boolean
  note: string
  receivedAt: string
}

async function sendJson<T>(desk: DeskId, method: string, path: string, body: unknown, fallback: string,
    headers: Record<string, string> = {}): Promise<T> {
  const token = await csrf(desk)
  const res = await fetch(`${base(desk)}${path}`, {
    method,
    credentials: 'same-origin',
    headers: { [token.headerName]: token.token, 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  })
  if (!res.ok) throw await problem(res, fallback)
  return res.json()
}

/** The buyer agent's reading of the owner's words. Stores and sends nothing. */
export const parseNeed = (desk: DeskId, text: string) =>
  sendJson<ParseResult>(desk, 'POST', '/needs/parse', { text }, 'Reading the need failed')

/** Posts a confirmed need; the desk then sends an RFQ to every supplier. Reuse the key when retrying. */
export const postNeed = (desk: DeskId, idempotencyKey: string, request: PurchaseRequest) =>
  sendJson<NeedRow>(desk, 'POST', '/needs', request, 'Posting the need failed', { 'Idempotency-Key': idempotencyKey })

export const listNeeds = (desk: DeskId) => getJson<NeedRow[]>(desk, '/needs')

/** Ranked best first by the desk. */
export const listQuotes = (desk: DeskId, needId: string) => getJson<QuoteRow[]>(desk, `/needs/${needId}/quotes`)

/** Opens the desk's SSE stream. Call the returned function to close it. */
export function subscribe(desk: DeskId, onEvent: (event: DeskEvent) => void, onStatus: (open: boolean) => void) {
  const source = new EventSource(`${base(desk)}/events`)
  source.onopen = () => onStatus(true)
  source.onerror = () => onStatus(false)
  source.onmessage = (message) => onEvent(JSON.parse(message.data) as DeskEvent)
  return () => source.close()
}
