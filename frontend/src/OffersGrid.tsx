import { AgGridReact } from 'ag-grid-react'
import type { ColDef, ICellRendererParams } from 'ag-grid-community'
import type { NeedRow, QuoteRow } from './api'
import { trimDecimals as trim } from './format'

type Props = { need: NeedRow; quotes: QuoteRow[] }

const PRIORITY: Record<string, string> = { SPEED: 'speed', PRICE: 'price', BALANCED: 'balanced' }

/** "2026-10-30" -> "Fri Oct 30", read as a calendar date (no time zone shift). */
const shortDate = (iso: string) =>
  new Date(`${iso}T12:00:00`).toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' })

function SupplierCell({ data }: ICellRendererParams<QuoteRow>) {
  if (!data) return null
  return (
    <span title={data.supplier.domain}>
      {data.supplier.name}
      {data.supplier.simulated && <span className="badge">SIMULATED</span>}
    </span>
  )
}

/**
 * The café's offers grid. Rows arrive already ranked by the desk's Java code for the need's priority; the grid keeps
 * that order and only displays it.
 */
export default function OffersGrid({ need, quotes }: Props) {
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
      colId: 'unitPrice',
      headerName: `${currency} / ${unit}`,
      type: 'rightAligned',
      width: 100,
      flex: 0,
      valueGetter: (p) => p.data?.unitPrice.amount,
      comparator: (a, b) => Number(a) - Number(b),
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
    { field: 'note', headerName: 'Note', minWidth: 260, tooltipField: 'note', sortable: false },
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
            : `${quotes.length} of ${need.suppliersAsked} suppliers quoted`}
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
          rowClassRules={{ 'row-best': (p) => p.data?.rank === 1 }}
          overlayNoRowsTemplate={waiting ? 'Waiting for the first quote…' : 'No supplier quoted.'}
          tooltipShowDelay={300}
          animateRows
        />
      </div>
    </div>
  )
}
