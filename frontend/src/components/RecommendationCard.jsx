import { useState } from 'react';

import * as api from '../api/api';
import { useAuth } from '../auth/AuthContext';
import { formatConfidence, formatTime } from './format';
import { useAction } from './useLoad';
import { Badge, ErrorBanner, IncidentLink } from './ui';

/**
 * One AI recommendation plus the approval gate.
 *
 * Three cases are visually distinct on purpose:
 *  - PROPOSED with an actionType: a catalog action an administrator may approve;
 *  - REJECTED_UNSAFE: the model asked for something outside the catalog. It is shown as
 *    evidence with no approve button at all, because the server would refuse it anyway;
 *  - anything already decided: read-only history.
 */
export function RecommendationCard({ recommendation, onDone, showIncident = false }) {
  const { isAdmin, atLeast } = useAuth();
  const [dryRun, setDryRun] = useState(true);
  const [note, setNote] = useState('');
  const action = useAction(onDone);

  const unsafe = recommendation.status === 'REJECTED_UNSAFE';
  const decidable = recommendation.status === 'PROPOSED' && Boolean(recommendation.actionType);

  return (
    <article className={unsafe ? 'rec unsafe' : 'rec'}>
      <div className="rec-head">
        <strong>{recommendation.title || recommendation.actionDisplayName || 'Recommendation'}</strong>
        <Badge value={recommendation.status} />
        <Badge value={recommendation.riskLevel} />
        {recommendation.mutating && <Badge value="changes AWS" tone="warn" />}
        {recommendation.requiresApproval && <Badge value="needs approval" tone="info" />}
        <span className="small muted">priority {recommendation.priority}</span>
        {showIncident && (
          <span className="small">
            incident <IncidentLink id={recommendation.incidentId} />
          </span>
        )}
      </div>

      <dl className="rec-grid">
        <dt>Catalog action</dt>
        <dd>
          {recommendation.actionType ? (
            <>
              <span className="mono">{recommendation.actionType}</span>
              {recommendation.actionDisplayName && (
                <span className="muted"> — {recommendation.actionDisplayName}</span>
              )}
            </>
          ) : (
            <span className="muted">none — nothing can be executed for this suggestion</span>
          )}
        </dd>

        <dt>Model asked for</dt>
        <dd className="mono">{recommendation.aiSuggestedAction || '—'}</dd>

        {recommendation.rationale && (
          <>
            <dt>Why</dt>
            <dd>{recommendation.rationale}</dd>
          </>
        )}
        {recommendation.expectedImpact && (
          <>
            <dt>Expected impact</dt>
            <dd>{recommendation.expectedImpact}</dd>
          </>
        )}
        <dt>Confidence</dt>
        <dd>{formatConfidence(recommendation.confidence)}</dd>

        {recommendation.rejectionReason && (
          <>
            <dt>Refused because</dt>
            <dd>{recommendation.rejectionReason}</dd>
          </>
        )}
        {recommendation.decidedBy && (
          <>
            <dt>Decided</dt>
            <dd>
              {recommendation.decidedBy} · {formatTime(recommendation.decidedAt)}
              {recommendation.decisionNote ? ` — ${recommendation.decisionNote}` : ''}
            </dd>
          </>
        )}
      </dl>

      <ErrorBanner message={action.error} onDismiss={action.clearError} />

      {unsafe && (
        <p className="small">
          The safety layer refused this suggestion: it is not one of the predefined actions the
          agent is allowed to perform. It is kept here as evidence and can never be executed.
        </p>
      )}

      {decidable && (
        <div className="approve-box">
          {isAdmin ? (
            <>
              <label className="check">
                <input
                  type="checkbox"
                  checked={dryRun}
                  onChange={(event) => setDryRun(event.target.checked)}
                />
                dry run (validate everything, call nothing)
              </label>
              <input
                type="text"
                placeholder="approval note (optional)"
                value={note}
                onChange={(event) => setNote(event.target.value)}
                maxLength={500}
              />
              <button
                type="button"
                className="primary"
                disabled={action.busy}
                onClick={() =>
                  action.run(() =>
                    api.approveRecommendation(recommendation.id, dryRun, note || null),
                  )
                }
              >
                {action.busy ? 'Working…' : dryRun ? 'Approve (dry run)' : 'Approve and execute'}
              </button>
            </>
          ) : (
            <span className="small muted">
              Only an administrator can approve an action. Ask one to review this.
            </span>
          )}
          {atLeast('OPERATOR') && (
            <button
              type="button"
              className="danger"
              disabled={action.busy}
              onClick={() =>
                action.run(() => api.rejectRecommendation(recommendation.id, note || null))
              }
            >
              Reject
            </button>
          )}
        </div>
      )}
    </article>
  );
}
