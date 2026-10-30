import { useState } from 'react'
import { decideApproval, getRecommendation, startCounter, type DeskId, type Recommendation } from './api'
import { trimDecimals } from './format'

type Props = {
  desk: DeskId
  recommendation: Recommendation | null
  onChange: (recommendation: Recommendation) => void
  onError: (message: string) => void
}

const ORDER: Record<string, string> = {
  SENDING: 'Order sent to the supplier, waiting for its PayPal invoice…',
  INVOICED: 'The supplier sent its PayPal invoice. It is under Invoices to pay.',
}

/**
 * The buyer agent's recommendation for one need: the counter round as it happens, Java's pick with a one-line reason,
 * and the policy engine's decision. Above the approval limit the owner taps Approve or Reject; nothing is sent to the
 * supplier before that tap.
 */
export default function RecommendationCard({ desk, recommendation, onChange, onError }: Props) {
  const [busy, setBusy] = useState(false)

  if (!recommendation || recommendation.stage === 'WAITING_FOR_QUOTES') return null

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true)
    try {
      await action()
    } catch (e) {
      onError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  if (recommendation.stage === 'READY_TO_COUNTER') {
    return (
      <div className="card recommendation">
        <p className="muted">Every supplier has answered.</p>
        <button disabled={busy} onClick={() => run(async () => onChange(await startCounter(desk, recommendation.needId)))}>
          Counter the top quotes
        </button>
      </div>
    )
  }
  if (recommendation.stage === 'COUNTERING') {
    return (
      <div className="card recommendation">
        <p>
          <strong>Negotiating.</strong> The buyer agent sent one counter-offer to each of the top 3 quotes. Answers
          show up in the Counter column as they arrive.
        </p>
      </div>
    )
  }
  if (recommendation.stage === 'NO_OFFER' || !recommendation.pick) {
    return (
      <div className="card recommendation">
        <p>No quote is left to accept: none arrived, or they all expired. Post the need again for fresh quotes.</p>
      </div>
    )
  }

  const { pick, approval } = recommendation
  const decide = (decision: 'APPROVE' | 'REJECT') =>
    run(async () => {
      if (!approval) return
      try {
        await decideApproval(desk, approval.id, decision)
      } finally {
        // Events bring the rest (the order, the invoice); this catches a refusal even if the stream is down.
        onChange(await getRecommendation(desk, recommendation.needId))
      }
    })

  return (
    <div className={`card recommendation decision-${approval?.decision ?? 'NONE'}`}>
      <div className="form-head">
        <h2>
          Recommended: {pick.supplier.name}
          {pick.supplier.simulated && <span className="badge">SIMULATED</span>}
        </h2>
        <span className="muted small">Picked by the desk&apos;s ranking, not by the AI</span>
      </div>
      <p className="pick-terms">
        {trimDecimals(pick.quantity)} {pick.unit} at {pick.unitPrice.amount} {pick.unitPrice.currency}/{pick.unit} ={' '}
        <strong>
          {pick.total.amount} {pick.total.currency}
        </strong>
        , delivered {pick.deliveryDate}
      </p>
      {recommendation.reason && (
        <p className="reason">
          {recommendation.reason}{' '}
          <span className="muted small">
            ({recommendation.reasonSource === 'AI' ? 'written by the buyer agent' : 'written by the desk'})
          </span>
        </p>
      )}
      {approval && (
        <div className="policy-decision">
          <p>
            <span className={`pill pill-${approval.status}`}>{approval.status.replaceAll('_', ' ')}</span>{' '}
            {approval.reasons.join(' ')}
          </p>
          {approval.status === 'PENDING' && (
            <div className="actions">
              <button className="secondary" disabled={busy} onClick={() => decide('REJECT')}>
                Reject
              </button>
              <button disabled={busy} onClick={() => decide('APPROVE')}>
                Approve {approval.total.amount} {approval.total.currency}
              </button>
            </div>
          )}
          {ORDER[approval.order] && <p className="muted">{ORDER[approval.order]}</p>}
          {(approval.order === 'REFUSED' || approval.order === 'FAILED') && (
            <p className="error">{approval.orderDetail ?? 'The order did not go through.'}</p>
          )}
          {approval.order === 'FAILED' && (approval.status === 'APPROVED' || approval.status === 'AUTO_APPROVED') && (
            <div className="actions">
              <button disabled={busy} onClick={() => decide('APPROVE')}>
                Try again
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
