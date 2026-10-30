import { useCallback, useEffect, useRef, useState } from 'react'
import {
  getRecommendation,
  listInvoices,
  listNeeds,
  listPayables,
  listQuotes,
  logout,
  subscribe,
  type Approval,
  type DeskEvent,
  type DeskId,
  type InvoiceRow,
  type Me,
  type NeedRow,
  type PayableRow,
  type QuoteRow,
  type Recommendation,
} from './api'
import { trimDecimals } from './format'
import Ledger from './Ledger'
import NeedBox from './NeedBox'
import NewInvoiceForm from './NewInvoiceForm'
import OffersGrid from './OffersGrid'
import Payables from './Payables'
import PolicyForm from './PolicyForm'
import RecommendationCard from './RecommendationCard'

type Props = { desk: DeskId; user: Me; onLogout: () => void }

/** Replaces the row with the same id, or adds it at the top (the ledger is newest first). */
function upsert<T extends { id: string }>(rows: T[], row: T): T[] {
  return rows.some((r) => r.id === row.id) ? rows.map((r) => (r.id === row.id ? row : r)) : [row, ...rows]
}

/**
 * Quote lists only grow, and each event carries the need's whole ranked list. So when a reload and an event race,
 * the longer list is the newer one.
 */
function newer(current: QuoteRow[] | undefined, incoming: QuoteRow[]): QuoteRow[] {
  return current && current.length > incoming.length ? current : incoming
}

/** A need only moves forward (COLLECTING to COMPLETE, more quotes), so the further-along copy is the newer one. */
function furthest(a: NeedRow, b: NeedRow): NeedRow {
  if (a.status !== b.status) return a.status === 'COMPLETE' ? a : b
  return a.quotesReceived >= b.quotesReceived ? a : b
}

/** Adds or updates one need without ever taking it back to an older state. */
function upsertNeed(needs: NeedRow[], need: NeedRow): NeedRow[] {
  const current = needs.find((n) => n.id === need.id)
  return upsert(needs, current ? furthest(current, need) : need)
}

/**
 * Merges a reload into what the stream already delivered: an event that arrived while the reload was in flight
 * (a newer status, a need posted after the snapshot) is kept, not overwritten. Newest first.
 */
function mergeNeeds(current: NeedRow[], found: NeedRow[]): NeedRow[] {
  const merged = current.reduce(upsertNeed, found)
  return [...merged].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
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
  // Only the café buys: it posts needs and compares quotes (S2).
  const buys = desk === 'cafe'
  const [needs, setNeeds] = useState<NeedRow[]>([])
  const [quotes, setQuotes] = useState<Record<string, QuoteRow[]>>({})
  const [selected, setSelected] = useState<string | null>(null)
  // S3: the buyer agent's recommendation per need, the invoices to pay, and the limits screen.
  const [recommendations, setRecommendations] = useState<Record<string, Recommendation>>({})
  const [payables, setPayables] = useState<PayableRow[]>([])
  const [showPolicy, setShowPolicy] = useState(false)
  // Read by the stream's reconnect handler, which is set up once per desk.
  const selectedRef = useRef<string | null>(null)
  useEffect(() => {
    selectedRef.current = selected
  }, [selected])

  const loadRecommendation = useCallback(
    (needId: string) => {
      getRecommendation(desk, needId)
        .then((found) => setRecommendations((current) => ({ ...current, [needId]: found })))
        .catch((e) => setError(e instanceof Error ? e.message : String(e)))
    },
    [desk],
  )

  const reloadPayables = useCallback(() => {
    if (!buys) return
    listPayables(desk)
      .then((found) => setPayables((current) => current.reduce(upsert, found)))
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }, [buys, desk])

  const loadQuotes = useCallback(
    (needId: string) => {
      listQuotes(desk, needId)
        .then((found) => setQuotes((current) => ({ ...current, [needId]: newer(current[needId], found) })))
        .catch((e) => setError(e instanceof Error ? e.message : String(e)))
    },
    [desk],
  )

  const reloadNeeds = useCallback(() => {
    if (!buys) return
    listNeeds(desk)
      .then((found) => {
        setNeeds((current) => mergeNeeds(current, found))
        // Needs are never deleted, so the owner's pick stays valid even when this snapshot is older than it (a need
        // posted while the reload was in flight). Without a pick, show the newest need.
        setSelected((current) => current ?? found[0]?.id ?? null)
        found.slice(0, 5).forEach((need) => loadQuotes(need.id))
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }, [buys, desk, loadQuotes])

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
      if (event.type === 'need.updated') {
        const need = event.data as NeedRow
        setNeeds((current) => upsertNeed(current, need))
        return
      }
      if (event.type === 'quote.received') {
        const { needId, quotes: ranked } = event.data as { needId: string; quotes: QuoteRow[] }
        setQuotes((current) => ({ ...current, [needId]: newer(current[needId], ranked) }))
        return
      }
      if (event.type === 'recommendation.updated') {
        const recommendation = event.data as Recommendation
        setRecommendations((current) => ({ ...current, [recommendation.needId]: recommendation }))
        return
      }
      if (event.type === 'approval.needed') {
        loadRecommendation((event.data as Approval).needId)
        return
      }
      if (event.type === 'payable.updated') {
        const payable = event.data as PayableRow
        setPayables((current) => upsert(current, payable))
        return
      }
      if (event.type !== 'invoice.updated') return
      const row = event.data as InvoiceRow
      pending.current?.push(row)
      setRows((current) => upsert(current, row))
    }
    const onStatus = (open: boolean) => {
      setLive(open)
      // Catch up on anything that changed while the stream was down.
      if (open) {
        reload()
        reloadNeeds()
        reloadPayables()
        // Counter answers only change prices, not the list's length, so reload the selected need's quotes too.
        const current = selectedRef.current
        if (current) {
          loadQuotes(current)
          loadRecommendation(current)
        }
      }
    }
    return subscribe(desk, onEvent, onStatus)
  }, [desk, reload, reloadNeeds, reloadPayables, loadQuotes, loadRecommendation])

  const need = needs.find((n) => n.id === selected) ?? null

  // Fetch the selected need's quotes when none are cached, or when the cache is behind the need's own count
  // (quote events missed while the stream was down are not replayed).
  const cached = selected !== null ? quotes[selected] : undefined
  const caughtUp = cached !== undefined && cached.length >= (need?.quotesReceived ?? 0)
  useEffect(() => {
    if (selected && !caughtUp) loadQuotes(selected)
  }, [selected, caughtUp, loadQuotes])

  // The recommendation follows the need: fetch it when a need is picked or finishes collecting.
  const needStatus = need?.status
  useEffect(() => {
    if (buys && selected && needStatus === 'COMPLETE') loadRecommendation(selected)
  }, [buys, selected, needStatus, loadRecommendation])
  const recommendation = selected ? (recommendations[selected] ?? null) : null

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
          {buys && (
            <button className="secondary" onClick={() => setShowPolicy((open) => !open)}>
              Limits
            </button>
          )}
          <button className="secondary" onClick={signOut}>
            Log out
          </button>
        </div>
      </div>
      {error && <p className="error">{error}</p>}
      {buys && showPolicy && <PolicyForm desk={desk} onClose={() => setShowPolicy(false)} />}
      {buys && (
        <>
          <NeedBox
            desk={desk}
            onPosted={(posted) => {
              // The fan-out can finish (need.updated: COMPLETE) before this response arrives; keep the newer state.
              setNeeds((current) => upsertNeed(current, posted))
              setSelected(posted.id)
            }}
          />
          {needs.length > 1 && (
            <label className="need-picker">
              Need
              <select value={selected ?? ''} onChange={(e) => setSelected(e.target.value)}>
                {needs.map((n) => (
                  <option key={n.id} value={n.id}>
                    {new Date(n.createdAt).toLocaleString()} · {trimDecimals(n.request.quantity ?? '')} {n.request.unit}{' '}
                    {n.request.product} · {n.quotesReceived} quotes
                  </option>
                ))}
              </select>
            </label>
          )}
          {need && (
            <OffersGrid need={need} quotes={quotes[need.id] ?? []} pickId={recommendation?.pick?.id ?? null} />
          )}
          <RecommendationCard
            desk={desk}
            recommendation={recommendation}
            onChange={(updated) => setRecommendations((current) => ({ ...current, [updated.needId]: updated }))}
            onError={setError}
          />
          <div className="section-head">
            <h2>Invoices to pay</h2>
          </div>
          <Payables rows={payables} />
        </>
      )}
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
