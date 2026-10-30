import { AgGridReact } from 'ag-grid-react'
import type { ColDef, ICellRendererParams } from 'ag-grid-community'
import type { PayableRow } from './api'

type Props = { rows: PayableRow[] }

function SupplierCell({ data }: ICellRendererParams<PayableRow>) {
  if (!data) return null
  return (
    <span title={data.supplier.domain}>
      {data.supplier.name}
      {data.supplier.simulated && <span className="badge">SIMULATED</span>}
    </span>
  )
}

function StatusCell({ value }: ICellRendererParams<PayableRow, string>) {
  return <span className={`pill pill-${value}`}>{value?.replaceAll('_', ' ')}</span>
}

function PayCell({ data }: ICellRendererParams<PayableRow>) {
  if (!data) return null
  if (data.status === 'PAID') return <span className="muted">Paid</span>
  // The desk only stores https://www.sandbox.paypal.com/ links; anything else was refused before it got here.
  return (
    <a href={data.payerViewUrl} target="_blank" rel="noopener noreferrer">
      Pay on PayPal
    </a>
  )
}

const columns: ColDef<PayableRow>[] = [
  { colId: 'supplier', headerName: 'Supplier', minWidth: 220, cellRenderer: SupplierCell, valueGetter: (p) => p.data?.supplier.name },
  { field: 'description', headerName: 'For', flex: 2, tooltipField: 'description' },
  {
    colId: 'total',
    headerName: 'Total',
    type: 'rightAligned',
    maxWidth: 140,
    valueGetter: (p) => (p.data ? `${p.data.total.amount} ${p.data.total.currency}` : ''),
    comparator: (_a, _b, nodeA, nodeB) => Number(nodeA.data?.total.amount) - Number(nodeB.data?.total.amount),
  },
  { field: 'status', headerName: 'Status', maxWidth: 170, cellRenderer: StatusCell, enableCellChangeFlash: true },
  // The café can't read the supplier's PayPal invoice, so the owner compares this id and the agreed total with
  // PayPal's page before paying.
  { field: 'paypalInvoiceId', headerName: 'PayPal invoice', maxWidth: 220, tooltipField: 'paypalInvoiceId' },
  { colId: 'pay', headerName: 'PayPal', maxWidth: 160, cellRenderer: PayCell, sortable: false, filter: false },
  {
    field: 'updatedAt',
    headerName: 'Updated',
    maxWidth: 190,
    valueFormatter: (p) => (p.value ? new Date(p.value).toLocaleString() : ''),
  },
]

/** "Invoices to pay": PayPal invoices suppliers sent after an accepted order, with live status from the supplier. */
export default function Payables({ rows }: Props) {
  return (
    <div className="grid payables-grid">
      <AgGridReact<PayableRow>
        rowData={rows}
        columnDefs={columns}
        getRowId={(p) => p.data.id}
        defaultColDef={{ flex: 1, sortable: true, filter: false }}
        rowClassRules={{ 'row-paid': (p) => p.data?.status === 'PAID' }}
        overlayNoRowsTemplate="Nothing to pay yet."
        tooltipShowDelay={300}
      />
    </div>
  )
}
