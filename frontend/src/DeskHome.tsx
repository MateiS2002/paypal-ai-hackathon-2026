import { useCallback, useEffect, useRef, useState } from 'react'
import { listInvoices, logout, subscribe, type DeskEvent, type DeskId, type InvoiceRow, type Me } from './api'
import Ledger from './Ledger'
import NewInvoiceForm from './NewInvoiceForm'

type Props = { desk: DeskId; user: Me; onLogout: () => void }

/** Replaces the row with the same id, or adds it at the top (the ledger is newest first). */
function upsert(rows: InvoiceRow[], row: InvoiceRow): InvoiceRow[] {
  return rows.some((r) => r.id === row.id) ? rows.map((r) => (r.id === row.id ? row : r)) : [row, ...rows]
}

export default function DeskHome({ desk, user, onLogout }: Props) {
  const [live, setLive] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [rows, setRows] = useState<InvoiceRow[]>([])
  const [creating, setCreating] = useState(false)
  // Kept here, not in the form, so closing and reopening the form still retries with the same idempotency key.
  const attempt = useRef<{ body: string; key: string } | null>(null)
  // Only the roaster sells and sends PayPal invoices in S1; the café pays them on PayPal's page.
  const sends = desk === 'roaster'

  const reload = useCallback(() => {
    listInvoices(desk)
      .then((found) => {
        setRows(found)
        setError(null)
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }, [desk])

  useEffect(() => {
    const onEvent = (event: DeskEvent) => {
      if (event.type === 'invoice.updated') setRows((current) => upsert(current, event.data as InvoiceRow))
    }
    const onStatus = (open: boolean) => {
      setLive(open)
      // Catch up on anything that changed while the stream was down.
      if (open) reload()
    }
    return subscribe(desk, onEvent, onStatus)
  }, [desk, reload])

  const signOut = async () => {
    // Only leave the dashboard once the server has really ended the session.
    try {
      await logout(desk)
      onLogout()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  return (
    <section>
      <div className="desk-header">
        <div>
          <h1>
            {user.deskName}
            {user.simulated && <span className="badge">SIMULATED</span>}
          </h1>
          <p className="muted">
            {user.domain} · logged in as {user.username}
          </p>
        </div>
        <div className="desk-actions">
          <span className={live ? 'status live' : 'status'}>{live ? 'Live' : 'Offline'}</span>
          <button className="secondary" onClick={signOut}>
            Log out
          </button>
        </div>
      </div>
      {error && <p className="error">{error}</p>}
      <div className="section-head">
        <h2>Ledger</h2>
        {sends && !creating && <button onClick={() => setCreating(true)}>New invoice</button>}
      </div>
      {creating && (
        <NewInvoiceForm
          desk={desk}
          keyFor={(body) => {
            if (attempt.current?.body !== body) attempt.current = { body, key: crypto.randomUUID() }
            return attempt.current.key
          }}
          onCreated={(row) => {
            attempt.current = null
            setRows((current) => upsert(current, row))
            setCreating(false)
          }}
          onClose={() => setCreating(false)}
        />
      )}
      <Ledger rows={rows} />
    </section>
  )
}
