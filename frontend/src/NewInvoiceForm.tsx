import { useEffect, useState, type FormEvent } from 'react'
import { createInvoice, listCustomers, type Customer, type DeskId, type InvoiceRow, type NewInvoice } from './api'

type Props = {
  desk: DeskId
  /**
   * The idempotency key for this request body. The parent keeps it, so it outlives this form being closed and
   * reopened: a retry of the same body reuses it and the desk resumes instead of duplicating.
   */
  keyFor: (body: string) => string
  onCreated: (row: InvoiceRow) => void
  onClose: () => void
}

type Line = { description: string; quantity: string; unitPrice: string }

const CURRENCY = 'USD'
const NEW_CUSTOMER = '__new__'
const emptyLine = (): Line => ({ description: '', quantity: '', unitPrice: '' })

/**
 * The owner's invoice form. Totals are not computed here: the desk prices every line in Java (BigDecimal) and the
 * ledger shows its result.
 */
export default function NewInvoiceForm({ desk, keyFor, onCreated, onClose }: Props) {
  const [customers, setCustomers] = useState<Customer[]>([])
  const [picked, setPicked] = useState(NEW_CUSTOMER)
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [lines, setLines] = useState<Line[]>([emptyLine()])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    listCustomers(desk)
      .then((found) => {
        setCustomers(found)
        if (found.length > 0) setPicked(found[0].email)
      })
      .catch(() => setCustomers([]))
  }, [desk])

  const customer: Customer =
    picked === NEW_CUSTOMER ? { name, email } : (customers.find((c) => c.email === picked) ?? { name: '', email: '' })

  const setLine = (index: number, change: Partial<Line>) =>
    setLines((current) => current.map((line, i) => (i === index ? { ...line, ...change } : line)))

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const invoice: NewInvoice = {
      customer: { name: customer.name.trim(), email: customer.email.trim() },
      lines: lines.map((line) => ({
        description: line.description.trim(),
        quantity: line.quantity.trim(),
        unitPrice: { amount: line.unitPrice.trim(), currency: CURRENCY },
      })),
    }
    const body = JSON.stringify(invoice)
    const key = keyFor(body)

    setBusy(true)
    setError(null)
    try {
      const row = await createInvoice(desk, key, invoice)
      onCreated(row)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="card invoice-form" onSubmit={submit}>
      <div className="form-head">
        <h2>New invoice</h2>
        <span className="muted small">Sent through the PayPal sandbox · {CURRENCY}</span>
      </div>

      <div className="row">
        <label>
          Customer
          <select value={picked} onChange={(e) => setPicked(e.target.value)}>
            {customers.map((c) => (
              <option key={c.email} value={c.email}>
                {c.name} ({c.email})
              </option>
            ))}
            <option value={NEW_CUSTOMER}>New customer…</option>
          </select>
        </label>
        {picked === NEW_CUSTOMER && (
          <>
            <label>
              Name
              <input value={name} onChange={(e) => setName(e.target.value)} required maxLength={100} />
            </label>
            <label>
              Email
              <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
            </label>
          </>
        )}
      </div>

      <table className="lines">
        <thead>
          <tr>
            <th>Description</th>
            <th>Quantity</th>
            <th>Unit price</th>
            <th aria-label="Remove line" />
          </tr>
        </thead>
        <tbody>
          {lines.map((line, i) => (
            <tr key={i}>
              <td>
                <input
                  aria-label={`Line ${i + 1} description`}
                  value={line.description}
                  onChange={(e) => setLine(i, { description: e.target.value })}
                  placeholder="Santos natural, kg"
                  required
                  maxLength={200}
                />
              </td>
              <td>
                <input
                  aria-label={`Line ${i + 1} quantity`}
                  inputMode="decimal"
                  value={line.quantity}
                  onChange={(e) => setLine(i, { quantity: e.target.value })}
                  placeholder="0.333"
                  pattern="\d+(\.\d{1,3})?"
                  title="A number with up to 3 decimals"
                  required
                />
              </td>
              <td>
                <input
                  aria-label={`Line ${i + 1} unit price`}
                  inputMode="decimal"
                  value={line.unitPrice}
                  onChange={(e) => setLine(i, { unitPrice: e.target.value })}
                  placeholder="18.00"
                  pattern="\d+(\.\d{1,2})?"
                  title="An amount with up to 2 decimals"
                  required
                />
              </td>
              <td>
                <button
                  type="button"
                  className="link"
                  onClick={() => setLines((current) => current.filter((_, j) => j !== i))}
                  disabled={lines.length === 1}
                >
                  Remove
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <button type="button" className="link add-line" onClick={() => setLines((current) => [...current, emptyLine()])}>
        + Add line
      </button>

      {error && <p className="error">{error}</p>}
      <div className="actions">
        <button type="button" className="secondary" onClick={onClose}>
          Cancel
        </button>
        <button type="submit" disabled={busy}>
          {busy ? 'Sending to PayPal…' : 'Create and send'}
        </button>
      </div>
    </form>
  )
}
