import { AgGridReact } from 'ag-grid-react'
import type { ColDef, ICellRendererParams } from 'ag-grid-community'
import type { InvoiceRow, Money } from './api'

type Props = { rows: InvoiceRow[] }

const money = (m: Money) => `${m.amount} ${m.currency}`

const items = (row: InvoiceRow) =>
  row.lines.map((line) => `${line.description} × ${line.quantity} @ ${line.unitPrice.amount}`).join('; ')

function StatusCell({ value }: ICellRendererParams<InvoiceRow, string>) {
  return <span className={`pill pill-${value}`}>{value?.replaceAll('_', ' ')}</span>
}

function PayLinkCell({ value }: ICellRendererParams<InvoiceRow, string | null>) {
  return value ? (
    <a href={value} target="_blank" rel="noopener noreferrer">
      Open on PayPal
    </a>
  ) : (
    <span className="muted">—</span>
  )
}

const columns: ColDef<InvoiceRow>[] = [
  { field: 'invoiceNumber', headerName: 'Invoice', maxWidth: 110, valueFormatter: (p) => p.value ?? '—' },
  { colId: 'customer', headerName: 'Customer', valueGetter: (p) => p.data?.customer.name, tooltipValueGetter: (p) => p.data?.customer.email },
  { colId: 'items', headerName: 'Items', flex: 2, valueGetter: (p) => (p.data ? items(p.data) : ''), tooltipValueGetter: (p) => (p.data ? items(p.data) : '') },
  {
    colId: 'total',
    headerName: 'Total',
    type: 'rightAligned',
    maxWidth: 140,
    valueGetter: (p) => (p.data ? money(p.data.total) : ''),
    // Sort by the decimal string's value, not alphabetically.
    comparator: (_a, _b, nodeA, nodeB) => Number(nodeA.data?.total.amount) - Number(nodeB.data?.total.amount),
  },
  { field: 'status', headerName: 'Status', maxWidth: 170, cellRenderer: StatusCell, enableCellChangeFlash: true },
  { field: 'dueDate', headerName: 'Due', maxWidth: 130, valueFormatter: (p) => p.value ?? '—' },
  { field: 'payerViewUrl', headerName: 'PayPal', maxWidth: 160, cellRenderer: PayLinkCell, sortable: false, filter: false },
]

export default function Ledger({ rows }: Props) {
  return (
    <div className="grid">
      <AgGridReact<InvoiceRow>
        rowData={rows}
        columnDefs={columns}
        getRowId={(p) => p.data.id}
        defaultColDef={{ flex: 1, sortable: true, filter: true }}
        rowClassRules={{ 'row-paid': (p) => p.data?.status === 'PAID' }}
        overlayNoRowsTemplate="No invoices yet."
        tooltipShowDelay={300}
      />
    </div>
  )
}
