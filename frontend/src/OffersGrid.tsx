import { AgGridReact } from 'ag-grid-react'
import type { ColDef, ICellRendererParams } from 'ag-grid-community'
import type { NeedRow, QuoteRow } from './api'
import { trimDecimals as trim } from './format'

type Props = { need: NeedRow; quotes: QuoteRow[]; pickId?: string | null }

const PRIORITY: Record<string, string> = { SPEED: 'speed', PRICE: 'price', BALANCED: 'balanced' }

/** "2026-10-30" -> "Fri Oct 30", read as a calendar date (no time zone shift). */
const shortDate = (iso: string) =>
  new Date(`${iso}T12:00:00`).toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' })

const OUTCOME: Record<string, string> = {
  PENDING: 'countering…',
  MET: 'met',
  PARTLY_MET: 'partly met',
  HELD: 'held',
  NO_ANSWER: 'no answer',
}

/** "11.40 → 10.83 · met", "asked 10.83 · countering…", or a dash before the counter round reaches this quote. */
function counterText(quote: QuoteRow | undefined): string {
  const counter = quote?.counter
  if (!quote || !counter) return '—'
  const moved = counter.outcome === 'MET' || counter.outcome === 'PARTLY_MET'
  return moved
    ? `${counter.previousUnitPrice.amount} → ${quote.unitPrice.amount} · ${OUTCOME[counter.outcome]}`
    : `asked ${counter.targetUnitPrice.amount} · ${OUTCOME[counter.outcome]}`
}

function SupplierCell({ data }: ICellRendererParams<QuoteRow>) {
  if (!data) return null
  return (
    <span title={data.supplier.domain}>
      {data.supplier.name}
      {data.supplier.simulated && <span className="badge">SIMULATED</span>}
    </span>
  )
}

/** The policy engine's verdict (S8). Blocked quotes say why in the engine's own words; there is no action on the row. */
function PolicyCell({ data }: ICellRendererParams<QuoteRow>) {
  if (!data) return null
  if (data.policyStatus === 'BLOCKED') {
    return (
      <span className="policy-blocked" title={data.policyReasons.join(' ')}>
        <span className="pill pill-BLOCKED">Blocked by policy</span> {data.policyReasons[0]}
      </span>
    )
  }
  if (data.policyStatus === 'NEEDS_APPROVAL') {
    return (
      <span title={data.policyReasons.join(' ')}>
        <span className="pill pill-PENDING">Needs your approval</span>
      </span>
    )
  }
  return <span className="muted small">Within your limits</span>
}

/** Supplier-written text. On a blocked quote it is shown verbatim, as the attack it is, and labelled. */
function NoteCell({ data }: ICellRendererParams<QuoteRow>) {
  if (!data) return null
  if (data.policyStatus !== 'BLOCKED') return <span title={data.note}>{data.note}</span>
  return (
    <span title="Written by the supplier. Never shown to the AI model.">
      <span className="untrusted-label">UNTRUSTED</span> <q className="untrusted">{data.note}</q>
    </span>
  )
}

/** Splits `code` spans out of a reason, so a field name reads as a field name. */
function Reason({ text }: { text: string }) {
  return (
    <>
      {text.split('`').map((part, i) => (i % 2 === 1 ? <code key={i}>{part}</code> : part))}
    </>
  )
}

/**
 * The 20-second security moment: for each blocked quote, the Java rule that fired and why the injected text changed
 * nothing. Also lists suppliers whose whole message was refused for carrying a field the schema doesn't have.
 */
function WhyBlocked({ need, quotes }: { need: NeedRow; quotes: QuoteRow[] }) {
  const blocked = quotes.filter((q) => q.policyStatus === 'BLOCKED')
  if (blocked.length === 0 && need.refused.length === 0) return null
  return (
    <div className="why-blocked" role="region" aria-label="Why this was blocked">
      {blocked.map((q) => (
        <div className="why-card" key={q.id}>
          <h3>
            Why this was blocked: {q.supplier.name}
            {q.supplier.simulated && <span className="badge">SIMULATED</span>}
          </h3>
          <p className="small">The policy engine, plain Java, checked this quote against your limits:</p>
          <ul>
            {q.policyReasons.map((r) => (
              <li key={r}>{r}</li>
            ))}
          </ul>
          <p className="small">
            The supplier&apos;s note, word for word: <span className="untrusted-label">UNTRUSTED</span>{' '}
            <q className="untrusted">{q.note}</q>
          </p>
          <p className="small">
            The note was never shown to the AI model, and nothing it says can change this decision: no counter was
            sent, it can&apos;t be recommended, and the desk has no way to accept it.
          </p>
        </div>
      ))}
      {need.refused.map((r) => (
        <div className="why-card" key={r.domain}>
          <h3>
            {r.domain}: <span className="pill pill-BLOCKED">Message refused</span>
          </h3>
          <p className="small">
            <Reason text={r.reason} />. The desk reads agent messages strictly: a message with anything the schema
            doesn&apos;t list is refused whole, and nothing in it is used.
          </p>
        </div>
      ))}
    </div>
  )
}

/**
 * The café's offers grid. Rows arrive already ranked by the desk's Java code for the need's priority; the grid keeps
 * that order and only displays it.
 */
export default function OffersGrid({ need, quotes, pickId }: Props) {
  const deliverBy = need.request.deliverBy

  const unit = need.request.unit ?? ''
  const currency = quotes[0]?.unitPrice.currency ?? 'USD'

  const columns: ColDef<QuoteRow>[] = [
    { field: 'rank', headerName: '#', width: 60, flex: 0, sort: 'asc' },
    {
      colId: 'supplier',
      headerName: 'Supplier',
      minWidth: 260,
      cellRenderer: SupplierCell,
      valueGetter: (p) => p.data?.supplier.name,
    },
    {
      colId: 'policy',
      headerName: 'Your limits',
      width: 400,
      flex: 0,
      cellRenderer: PolicyCell,
      valueGetter: (p) => (p.data ? `${p.data.policyStatus} ${p.data.policyReasons.join(' ')}` : ''),
      tooltipValueGetter: (p) => p.data?.policyReasons.join(' '),
      sortable: false,
    },
    {
      colId: 'unitPrice',
      headerName: `${currency} / ${unit}`,
      type: 'rightAligned',
      width: 100,
      flex: 0,
      valueGetter: (p) => p.data?.unitPrice.amount,
      comparator: (a, b) => Number(a) - Number(b),
    },
    {
      colId: 'counter',
      headerName: 'Counter',
      width: 190,
      flex: 0,
      valueGetter: (p) => counterText(p.data),
      cellClassRules: {
        'cell-good': (p) => p.data?.counter?.outcome === 'MET' || p.data?.counter?.outcome === 'PARTLY_MET',
        'cell-warn': (p) => p.data?.counter?.outcome === 'NO_ANSWER',
      },
      sortable: false,
    },
    {
      colId: 'quantity',
      headerName: 'Offered',
      width: 140,
      flex: 0,
      valueGetter: (p) => (p.data ? `${trim(p.data.quantity)} ${p.data.unit}${p.data.partial ? ' · partial' : ''}` : ''),
      cellClassRules: { 'cell-warn': (p) => !!p.data?.partial },
    },
    {
      colId: 'total',
      headerName: `Total ${currency}`,
      type: 'rightAligned',
      width: 110,
      flex: 0,
      valueGetter: (p) => p.data?.total.amount,
      comparator: (a, b) => Number(a) - Number(b),
    },
    {
      field: 'deliveryDate',
      headerName: 'Delivery',
      width: 140,
      flex: 0,
      valueFormatter: (p) => (p.value ? `${shortDate(p.value)}${deliverBy && p.value > deliverBy ? ' · late' : ''}` : ''),
      cellClassRules: { 'cell-warn': (p) => !!(p.value && deliverBy && p.value > deliverBy) },
    },
    {
      colId: 'stock',
      headerName: 'Stock',
      type: 'rightAligned',
      width: 100,
      flex: 0,
      valueGetter: (p) => (p.data ? `${trim(p.data.availableQuantity)} ${p.data.unit}` : ''),
    },
    { colId: 'note', headerName: 'Note', minWidth: 300, cellRenderer: NoteCell, valueGetter: (p) => p.data?.note, sortable: false },
  ]

  const r = need.request
  const waiting = need.status === 'COLLECTING'

  return (
    <div className="offers">
      <div className="section-head">
        <h2>
          Offers for {trim(r.quantity ?? '')} {r.unit} {r.product}
          {r.origin ? ` from ${r.origin}` : ''} by {r.deliverBy}
        </h2>
        <span className={waiting ? 'status' : 'status live'}>
          {waiting
            ? `Waiting for suppliers… ${quotes.length} of ${need.suppliersAsked}`
            : `${quotes.length} of ${need.suppliersAsked} suppliers quoted${
                need.refused.length > 0 ? ` · ${need.refused.length} message refused` : ''
              }`}
          {' · ranked by '}
          {PRIORITY[r.priority ?? 'BALANCED']}
        </span>
      </div>
      <div className="grid offers-grid">
        <AgGridReact<QuoteRow>
          rowData={quotes}
          columnDefs={columns}
          getRowId={(p) => p.data.id}
          defaultColDef={{ flex: 1, sortable: true, filter: false }}
          // Java's pick once there is one (it skips expired quotes), else the top-ranked row.
          rowClassRules={{
            'row-best': (p) => p.data?.policyStatus !== 'BLOCKED' && (pickId ? p.data?.id === pickId : p.data?.rank === 1),
            'row-blocked': (p) => p.data?.policyStatus === 'BLOCKED',
          }}
          overlayNoRowsTemplate={waiting ? 'Waiting for the first quote…' : 'No supplier quoted.'}
          tooltipShowDelay={300}
          animateRows
        />
      </div>
      <WhyBlocked need={need} quotes={quotes} />
    </div>
  )
}
