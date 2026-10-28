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

/** Opens the desk's SSE stream. Call the returned function to close it. */
export function subscribe(desk: DeskId, onEvent: (event: DeskEvent) => void, onStatus: (open: boolean) => void) {
  const source = new EventSource(`${base(desk)}/events`)
  source.onopen = () => onStatus(true)
  source.onerror = () => onStatus(false)
  source.onmessage = (message) => onEvent(JSON.parse(message.data) as DeskEvent)
  return () => source.close()
}
