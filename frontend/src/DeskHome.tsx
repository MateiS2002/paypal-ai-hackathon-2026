import { useEffect, useState } from 'react'
import { AgGridReact } from 'ag-grid-react'
import type { ColDef } from 'ag-grid-community'
import { logout, subscribe, type DeskId, type Me } from './api'

type Props = { desk: DeskId; user: Me; onLogout: () => void }

type LedgerRow = { invoice: string; counterparty: string; amount: string; status: string; updated: string }

// The ledger fills up in S1 (invoice to paid); S0 only proves AG Grid and the live stream are wired.
const columns: ColDef<LedgerRow>[] = [
  { field: 'invoice', headerName: 'Invoice' },
  { field: 'counterparty', headerName: 'Counterparty' },
  { field: 'amount', headerName: 'Amount', type: 'rightAligned' },
  { field: 'status', headerName: 'Status' },
  { field: 'updated', headerName: 'Updated' },
]

export default function DeskHome({ desk, user, onLogout }: Props) {
  const [live, setLive] = useState(false)

  useEffect(() => subscribe(desk, () => setLive(true), setLive), [desk])

  const signOut = async () => {
    try {
      await logout(desk)
    } finally {
      onLogout()
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
      <h2>Ledger</h2>
      <div className="grid">
        <AgGridReact<LedgerRow>
          rowData={[]}
          columnDefs={columns}
          defaultColDef={{ flex: 1, sortable: true, filter: true }}
          overlayNoRowsTemplate="No invoices yet."
        />
      </div>
    </section>
  )
}
