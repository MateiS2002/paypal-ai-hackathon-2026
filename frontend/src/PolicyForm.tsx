import { useEffect, useState, type FormEvent } from 'react'
import { getPolicy, savePolicy, type DeskId, type Policy } from './api'

type Props = { desk: DeskId; onClose: () => void }

const CURRENCY = 'USD'

type Draft = { maxUnitPrice: string; approvalAbove: string; maxOrderTotal: string }

/**
 * The owner's limits. The desk's policy engine (plain Java) checks every order against them before anything is sent
 * to a supplier; a prompt can't change them.
 */
export default function PolicyForm({ desk, onClose }: Props) {
  const [draft, setDraft] = useState<Draft | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    getPolicy(desk)
      .then((policy) =>
        setDraft({
          maxUnitPrice: policy.maxUnitPrice.amount,
          approvalAbove: policy.approvalAbove.amount,
          maxOrderTotal: policy.maxOrderTotal.amount,
        }),
      )
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }, [desk])

  const save = async (event: FormEvent) => {
    event.preventDefault()
    if (!draft) return
    const money = (amount: string) => ({ amount: amount.trim(), currency: CURRENCY })
    const policy: Policy = {
      maxUnitPrice: money(draft.maxUnitPrice),
      approvalAbove: money(draft.approvalAbove),
      maxOrderTotal: money(draft.maxOrderTotal),
    }
    setSaving(true)
    setError(null)
    try {
      await savePolicy(desk, policy)
      setSaved(true)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  const field = (key: keyof Draft, label: string, hint: string) => (
    <label>
      {label} ({CURRENCY})
      <input
        inputMode="decimal"
        value={draft?.[key] ?? ''}
        onChange={(e) => {
          setSaved(false)
          setDraft((current) => (current ? { ...current, [key]: e.target.value } : current))
        }}
        pattern="\d+(\.\d{1,2})?"
        title="An amount with up to 2 decimals"
        required
      />
      <span className="muted small">{hint}</span>
    </label>
  )

  return (
    <form className="card policy-form" onSubmit={save}>
      <div className="form-head">
        <h2>Buying limits</h2>
        <span className="muted small">Checked in code before every order. Changes apply to the next decision.</span>
      </div>
      {draft ? (
        <div className="row">
          {field('maxUnitPrice', 'Max price per unit', 'Never buy above this')}
          {field('approvalAbove', 'Ask me above', 'Orders above this wait for your tap')}
          {field('maxOrderTotal', 'Max order', 'Never buy above this total')}
        </div>
      ) : (
        !error && <p className="muted">Loading…</p>
      )}
      {error && <p className="error">{error}</p>}
      <div className="actions">
        {saved && <span className="status live">Saved</span>}
        <button type="button" className="secondary" onClick={onClose}>
          Close
        </button>
        <button type="submit" disabled={!draft || saving}>
          {saving ? 'Saving…' : 'Save limits'}
        </button>
      </div>
    </form>
  )
}
