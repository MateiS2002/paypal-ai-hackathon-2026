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
  // Which opening of the form is on screen, so a late response from a closed one can't close a newer one.
  const [formId, setFormId] = useState(0)
  const openForm = useRef(0)
  // Kept here, not in the form, so closing and reopening the form still retries with the same idempotency key.
  const attempt = useRef<{ body: string; key: string } | null>(null)
  // Events that arrive while a reload is in flight; replayed on top of its (possibly older) result.
  const pending = useRef<InvoiceRow[] | null>(null)
  // Only the roaster sells and sends PayPal invoices in S1; the café pays them on PayPal's page.
  const sends = desk === 'roaster'

  const reload = useCallback(() => {
    const buffered: InvoiceRow[] = []
    pending.current = buffered
    listInvoices(desk)
      .then((found) => {
        if (pending.current !== buffered) return // a newer reload started; its result wins
        setRows(buffered.reduce(upsert, found))
        setError(null)
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => {
        if (pending.current === buffered) pending.current = null
      })
  }, [desk])

  useEffect(() => {
    const onEvent = (event: DeskEvent) => {
      if (event.type !== 'invoice.updated') return
      const row = event.data as InvoiceRow
      pending.current?.push(row)
      setRows((current) => upsert(current, row))
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
        {sends && !creating && (
          <button
            onClick={() => {
              openForm.current = formId + 1
              setFormId(formId + 1)
              setCreating(true)
            }}
          >
            New invoice
          </button>
        )}
      </div>
      {creating && (
        <NewInvoiceForm
          desk={desk}
          keyFor={(body) => {
            if (attempt.current?.body !== body) attempt.current = { body, key: crypto.randomUUID() }
            return attempt.current.key
          }}
          onCreated={(row, key) => {
            // Keep the new row even if a reload that started before it finishes later.
            pending.current?.push(row)
            setRows((current) => upsert(current, row))
            if (attempt.current?.key === key) attempt.current = null
            // A late response from a form that was closed must not close the one open now.
            if (openForm.current !== formId) return
            openForm.current = 0
            setCreating(false)
          }}
          onClose={() => {
            openForm.current = 0
            setCreating(false)
          }}
        />
      )}
      <Ledger rows={rows} />
    </section>
  )
}
