import { AgGridReact } from 'ag-grid-react'
import type { ColDef, ICellRendererParams } from 'ag-grid-community'
import type { NeedRow, QuoteRow } from './api'
import { trimDecimals as trim } from './format'

type Props = { need: NeedRow; quotes: QuoteRow[] }

const PRIORITY: Record<string, string> = { SPEED: 'speed', PRICE: 'price', BALANCED: 'balanced' }

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

  const columns: ColDef<QuoteRow>[] = [
    { field: 'rank', headerName: '#', maxWidth: 70, sort: 'asc' },
    { colId: 'supplier', headerName: 'Supplier', flex: 1.6, cellRenderer: SupplierCell, valueGetter: (p) => p.data?.supplier.name },
    {
      colId: 'unitPrice',
      headerName: 'Price / unit',
      type: 'rightAligned',
      maxWidth: 140,
      valueGetter: (p) => (p.data ? `${p.data.unitPrice.amount} / ${p.data.unit}` : ''),
      comparator: (_a, _b, nodeA, nodeB) => Number(nodeA.data?.unitPrice.amount) - Number(nodeB.data?.unitPrice.amount),
    },
    {
      colId: 'quantity',
      headerName: 'Offered',
      type: 'rightAligned',
      maxWidth: 130,
      valueGetter: (p) => (p.data ? `${trim(p.data.quantity)} ${p.data.unit}${p.data.partial ? ' (partial)' : ''}` : ''),
      cellClassRules: { 'cell-warn': (p) => !!p.data?.partial },
    },
    {
      colId: 'total',
      headerName: 'Total',
      type: 'rightAligned',
      maxWidth: 140,
      valueGetter: (p) => (p.data ? `${p.data.total.amount} ${p.data.total.currency}` : ''),
      comparator: (_a, _b, nodeA, nodeB) => Number(nodeA.data?.total.amount) - Number(nodeB.data?.total.amount),
    },
    {
      field: 'deliveryDate',
      headerName: 'Delivery',
      maxWidth: 150,
      valueFormatter: (p) => (p.value && deliverBy && p.value > deliverBy ? `${p.value} (late)` : (p.value ?? '')),
      cellClassRules: { 'cell-warn': (p) => !!(p.value && deliverBy && p.value > deliverBy) },
    },
    {
      colId: 'stock',
      headerName: 'Stock',
      type: 'rightAligned',
      maxWidth: 120,
      valueGetter: (p) => (p.data ? `${trim(p.data.availableQuantity)} ${p.data.unit}` : ''),
    },
    { field: 'note', headerName: 'Note', flex: 3, tooltipField: 'note', sortable: false },
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
