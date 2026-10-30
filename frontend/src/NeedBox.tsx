import { useRef, useState, type FormEvent } from 'react'
import { parseNeed, postNeed, type DeskId, type NeedRow, type ParseResult, type Priority, type PurchaseRequest, type Unit } from './api'
import { trimDecimals } from './format'

type Props = {
  desk: DeskId
  onPosted: (need: NeedRow) => void
}

/** The editable fields of the parsed request, as the owner types them. */
type Draft = {
  product: string
  origin: string
  quantity: string
  unit: Unit | ''
  deliverBy: string
  priority: Priority
  maxUnitPrice: string
}

const CURRENCY = 'USD'

const LABELS: Record<string, string> = { product: 'Product', quantity: 'Quantity', unit: 'Unit', deliverBy: 'Deliver by' }

function toDraft(request: PurchaseRequest): Draft {
  return {
    product: request.product ?? '',
    origin: request.origin ?? '',
    quantity: request.quantity ? trimDecimals(request.quantity) : '',
    unit: request.unit ?? '',
    deliverBy: request.deliverBy ?? '',
    priority: request.priority ?? 'BALANCED',
    maxUnitPrice: request.maxUnitPrice?.amount ?? '',
  }
}

function toRequest(draft: Draft): PurchaseRequest {
  return {
    product: draft.product.trim(),
    origin: draft.origin.trim() || null,
    quantity: draft.quantity.trim(),
    unit: draft.unit || null,
    deliverBy: draft.deliverBy,
    priority: draft.priority,
    maxUnitPrice: draft.maxUnitPrice.trim() ? { amount: draft.maxUnitPrice.trim(), currency: CURRENCY } : null,
  }
}

/**
 * "Post a need": the owner types in plain words, the buyer agent proposes a structured request, and the owner checks
 * and fixes it before anything is sent. Missing fields come back as a question from the desk; nothing is guessed.
 */
export default function NeedBox({ desk, onPosted }: Props) {
  const [text, setText] = useState('')
  const [parsed, setParsed] = useState<ParseResult | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<'reading' | 'sending' | null>(null)
  // One key per request body, kept across retries so a double click or a timeout never sends a second round of RFQs.
  const attempt = useRef<{ body: string; key: string } | null>(null)

  const read = async (event: FormEvent) => {
    event.preventDefault()
    setBusy('reading')
    setError(null)
    try {
      const result = await parseNeed(desk, text)
      setParsed(result)
      setDraft(toDraft(result.request))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(null)
    }
  }

  const send = async (event: FormEvent) => {
    event.preventDefault()
    if (!draft) return
    const request = toRequest(draft)
    const body = JSON.stringify(request)
    if (attempt.current?.body !== body) attempt.current = { body, key: crypto.randomUUID() }
    setBusy('sending')
    setError(null)
    try {
      const need = await postNeed(desk, attempt.current.key, request)
      attempt.current = null
      setParsed(null)
      setDraft(null)
      setText('')
      onPosted(need)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(null)
    }
  }

  const set = (change: Partial<Draft>) => setDraft((current) => (current ? { ...current, ...change } : current))
  const missing = new Set(parsed?.missing ?? [])
  const flag = (field: string) => (missing.has(field) ? 'missing' : undefined)

  return (
    <div className="card need-box">
      <form className="need-text" onSubmit={read}>
        <label>
          Post a need
          <textarea
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder="50 kg Brazilian beans by Friday, prioritise speed"
            rows={2}
            maxLength={1000}
            required
          />
        </label>
        <button type="submit" disabled={busy !== null || !text.trim()}>
          {busy === 'reading' ? 'Reading…' : 'Read it'}
        </button>
      </form>

      {parsed && draft && (
        <form className="parsed" onSubmit={send}>
          <div className="form-head">
            <h2>Check the request</h2>
            <span className="muted small">Read by the buyer agent. Fix anything before it goes to suppliers.</span>
          </div>
          {parsed.question && (
            <p className="question">
              {parsed.question} <span className="muted">(missing: {parsed.missing.map((m) => LABELS[m] ?? m).join(', ')})</span>
            </p>
          )}
          <div className="row">
            <label className={flag('product')}>
              Product
              <input value={draft.product} onChange={(e) => set({ product: e.target.value })} required maxLength={100} />
            </label>
            <label>
              Origin
              <input value={draft.origin} onChange={(e) => set({ origin: e.target.value })} placeholder="Any" maxLength={60} />
            </label>
            <label className={flag('quantity')}>
              Quantity
              <input
                inputMode="decimal"
                value={draft.quantity}
                onChange={(e) => set({ quantity: e.target.value })}
                pattern="\d+(\.\d{1,3})?"
                title="A number with up to 3 decimals"
                required
              />
            </label>
            <label className={flag('unit')}>
              Unit
              <select value={draft.unit} onChange={(e) => set({ unit: e.target.value as Unit | '' })} required>
                <option value="">Pick…</option>
                <option value="kg">kg</option>
                <option value="lb">lb</option>
              </select>
            </label>
            <label className={flag('deliverBy')}>
              Deliver by
              <input type="date" value={draft.deliverBy} onChange={(e) => set({ deliverBy: e.target.value })} required />
            </label>
            <label>
              Priority
              <select value={draft.priority} onChange={(e) => set({ priority: e.target.value as Priority })}>
                <option value="SPEED">Speed</option>
                <option value="PRICE">Price</option>
                <option value="BALANCED">Balanced</option>
              </select>
            </label>
            <label>
              Max price per unit ({CURRENCY})
              <input
                inputMode="decimal"
                value={draft.maxUnitPrice}
                onChange={(e) => set({ maxUnitPrice: e.target.value })}
                placeholder="No limit"
                pattern="\d+(\.\d{1,2})?"
                title="An amount with up to 2 decimals"
              />
            </label>
          </div>
          <p className="muted small">
            Suppliers see the product, origin, quantity, unit and date only. Your priority and budget stay on this desk.
          </p>
          {error && <p className="error">{error}</p>}
          <div className="actions">
            <button type="button" className="secondary" onClick={() => { setParsed(null); setDraft(null) }}>
              Discard
            </button>
            <button type="submit" disabled={busy !== null}>
              {busy === 'sending' ? 'Sending…' : 'Send RFQs'}
            </button>
          </div>
        </form>
      )}
      {!parsed && error && <p className="error">{error}</p>}
    </div>
  )
}
