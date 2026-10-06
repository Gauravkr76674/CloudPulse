import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';

import * as api from '../api/api';
import { useAuth } from '../auth/AuthContext';
import { MetricChart } from '../components/MetricChart';
import { RecommendationCard } from '../components/RecommendationCard';
import {
  formatAgo,
  formatConfidence,
  formatDuration,
  formatNumber,
  formatTime,
} from '../components/format';
import { useAction, useLoad } from '../components/useLoad';
import {
  Badge,
  Empty,
  ErrorBanner,
  InfoBanner,
  Loading,
  Panel,
  ResourceLink,
} from '../components/ui';

const STAGE_ORDER = ['DETECTION', 'DIAGNOSIS', 'RECOMMENDATION', 'VERIFICATION'];

function AnalysisCard({ analysis }) {
  const [showRaw, setShowRaw] = useState(false);
  return (
    <div className={analysis.successful ? 'stage' : 'stage failed'}>
      <div className="stage-head">
        <strong>{analysis.stage}</strong>
        {!analysis.successful && <Badge value="call failed" tone="danger" />}
        {analysis.suggestedSeverity && <Badge value={analysis.suggestedSeverity} />}
        <span className="small muted">
          {analysis.provider} / {analysis.modelName}
        </span>
        <span className="small muted">confidence {formatConfidence(analysis.confidence)}</span>
        <span className="small muted">{formatDuration(analysis.latencyMs)}</span>
        {(analysis.promptTokens || analysis.completionTokens) && (
          <span className="small muted">
            {analysis.promptTokens || 0} in / {analysis.completionTokens || 0} out tokens
          </span>
        )}
        <span className="small muted">{formatAgo(analysis.createdAt)}</span>
      </div>

      {analysis.errorMessage && <p className="small">{analysis.errorMessage}</p>}
      {analysis.summary && <p>{analysis.summary}</p>}
      {analysis.rootCause && (
        <p>
          <strong>Root cause: </strong>
          {analysis.rootCause}
        </p>
      )}
      {analysis.reasoning && <p className="small muted">{analysis.reasoning}</p>}

      {analysis.rawResponse && (
        <>
          <button type="button" className="link" onClick={() => setShowRaw((open) => !open)}>
            {showRaw ? 'hide raw model output' : 'show raw model output'}
          </button>
          {showRaw && <pre className="raw">{analysis.rawResponse}</pre>}
        </>
      )}
    </div>
  );
}

export function IncidentDetailPage() {
  const { id } = useParams();
  const { atLeast, isAdmin } = useAuth();
  const [notice, setNotice] = useState(null);

  const detail = useLoad(() => api.incidentDetail(id), [id]);
  const incident = detail.data ? detail.data.incident : null;

  const series = useLoad(
    () =>
      incident
        ? api.metricSeries(incident.resourceId, incident.metricName || 'CPUUtilization', 180)
        : Promise.resolve(null),
    [incident ? incident.resourceId : null, incident ? incident.metricName : null],
  );

  const action = useAction(() => {
    detail.reload();
    series.reload();
  });

  const [note, setNote] = useState('');

  const analyses = detail.data ? detail.data.analyses : [];
  const sorted = [...analyses].sort((a, b) => {
    const stageDiff = STAGE_ORDER.indexOf(a.stage) - STAGE_ORDER.indexOf(b.stage);
    return stageDiff !== 0 ? stageDiff : new Date(a.createdAt) - new Date(b.createdAt);
  });

  const recommendations = detail.data ? detail.data.recommendations : [];
  const open = recommendations.filter((rec) => rec.status === 'PROPOSED');
  const blocked = recommendations.filter((rec) => rec.status === 'REJECTED_UNSAFE');
  const decided = recommendations.filter(
    (rec) => rec.status !== 'PROPOSED' && rec.status !== 'REJECTED_UNSAFE',
  );

  if (detail.loading && !detail.data) {
    return <Loading />;
  }

  return (
    <>
      <h1>
        {incident ? incident.title : `Incident ${id}`}{' '}
        {incident && <Badge value={incident.status} />} {incident && <Badge value={incident.severity} />}
      </h1>
      <ErrorBanner message={detail.error || action.error} onDismiss={action.clearError} />
      <InfoBanner message={notice} onDismiss={() => setNotice(null)} />

      {incident && (
        <Panel
          title="What happened"
          actions={
            <>
              {atLeast('OPERATOR') && (
                <>
                  <button
                    type="button"
                    className="primary"
                    disabled={action.busy}
                    onClick={() =>
                      action.run(async () => {
                        const result = await api.analyzeIncident(id);
                        setNotice(
                          `The pipeline recorded ${result.length} analysis step(s). ` +
                            'Any recommendation still needs an administrator to approve it.',
                        );
                        return result;
                      })
                    }
                  >
                    {action.busy ? 'Working…' : 'Run AI analysis'}
                  </button>
                  <button
                    type="button"
                    disabled={action.busy}
                    onClick={() => action.run(() => api.verifyIncident(id))}
                  >
                    Verify recovery
                  </button>
                  <button
                    type="button"
                    disabled={action.busy || Boolean(incident.acknowledgedBy)}
                    onClick={() => action.run(() => api.acknowledgeIncident(id))}
                  >
                    Acknowledge
                  </button>
                </>
              )}
              <button type="button" onClick={detail.reload} disabled={action.busy}>
                Refresh
              </button>
            </>
          }
        >
          <dl className="rec-grid">
            <dt>Resource</dt>
            <dd>
              <ResourceLink id={incident.resourceId}>{incident.resourceName}</ResourceLink>{' '}
              <span className="mono muted">{incident.resourceIdentifier}</span>
            </dd>
            <dt>Rule</dt>
            <dd>{incident.ruleName}</dd>
            <dt>Trigger</dt>
            <dd>
              {incident.metricName} reached {formatNumber(incident.triggerValue, 2)}
              {incident.thresholdValue !== null && (
                <> against a limit of {formatNumber(incident.thresholdValue, 2)}</>
              )}{' '}
              · seen {incident.occurrenceCount} time(s)
            </dd>
            <dt>Opened</dt>
            <dd>
              {formatTime(incident.openedAt)}{' '}
              <span className="muted small">({formatAgo(incident.openedAt)})</span>
            </dd>
            <dt>Last seen</dt>
            <dd>{formatTime(incident.lastSeenAt)}</dd>
            {incident.acknowledgedBy && (
              <>
                <dt>Acknowledged</dt>
                <dd>
                  {incident.acknowledgedBy} · {formatTime(incident.acknowledgedAt)}
                </dd>
              </>
            )}
            {incident.resolvedBy && (
              <>
                <dt>Resolved</dt>
                <dd>
                  {incident.resolvedBy} · {formatTime(incident.resolvedAt)}
                </dd>
              </>
            )}
          </dl>

          {detail.data.description && <p className="small muted">{detail.data.description}</p>}
          {detail.data.resolutionNote && (
            <p className="small">
              <strong>Resolution note: </strong>
              {detail.data.resolutionNote}
            </p>
          )}

          {atLeast('OPERATOR') && (
            <div className="approve-box">
              <input
                type="text"
                placeholder="note for the resolution or closure (optional)"
                value={note}
                onChange={(event) => setNote(event.target.value)}
                maxLength={2000}
              />
              <button
                type="button"
                disabled={action.busy}
                onClick={() => action.run(() => api.resolveIncident(id, note || null))}
              >
                Mark resolved
              </button>
              {isAdmin && (
                <button
                  type="button"
                  className="danger"
                  disabled={action.busy}
                  onClick={() => action.run(() => api.closeIncident(id, note || null))}
                >
                  Close without fixing
                </button>
              )}
            </div>
          )}
        </Panel>
      )}

      {series.data && (
        <Panel
          title={`${series.data.metricName} around the incident`}
          subtitle="Red points are the datapoints the detector flagged"
        >
          <MetricChart
            points={series.data.points}
            threshold={series.data.thresholdValue}
            unit={series.data.unit}
          />
        </Panel>
      )}

      <Panel
        title="What the models said"
        subtitle="Each stage is a separate call, stored whether it succeeded or not"
      >
        {sorted.length === 0 ? (
          <Empty>
            No analysis yet.{' '}
            {atLeast('OPERATOR')
              ? 'Use “Run AI analysis” above.'
              : 'An operator can trigger the pipeline.'}
          </Empty>
        ) : (
          sorted.map((analysis) => <AnalysisCard key={analysis.id} analysis={analysis} />)
        )}
      </Panel>

      <Panel
        title="Recommended actions"
        subtitle="Only actions from the server-side catalog can be approved"
        actions={<Link to="/approvals">approval queue →</Link>}
      >
        {recommendations.length === 0 && <Empty>The pipeline has not proposed anything yet.</Empty>}
        {open.map((rec) => (
          <RecommendationCard
            key={rec.id}
            recommendation={rec}
            onDone={() => {
              detail.reload();
              series.reload();
            }}
          />
        ))}
        {blocked.length > 0 && (
          <>
            <h3>Refused by the safety layer</h3>
            {blocked.map((rec) => (
              <RecommendationCard key={rec.id} recommendation={rec} />
            ))}
          </>
        )}
        {decided.length > 0 && (
          <>
            <h3>Already decided</h3>
            {decided.map((rec) => (
              <RecommendationCard key={rec.id} recommendation={rec} />
            ))}
          </>
        )}
      </Panel>

      <Panel title="Actions taken">
        {detail.data && detail.data.actions.length === 0 ? (
          <Empty>Nothing has been executed for this incident.</Empty>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>#</th>
                  <th>Action</th>
                  <th>Status</th>
                  <th>Dry run</th>
                  <th>Requested / approved</th>
                  <th>Result</th>
                  <th>Duration</th>
                  <th>When</th>
                </tr>
              </thead>
              <tbody>
                {detail.data.actions.map((item) => (
                  <tr key={item.id}>
                    <td>{item.id}</td>
                    <td>
                      {item.actionDisplayName || item.actionType}
                      <div className="small muted mono">{item.actionType}</div>
                    </td>
                    <td>
                      <Badge value={item.status} />
                    </td>
                    <td>{item.dryRun ? 'yes' : 'no'}</td>
                    <td className="small">
                      {item.requestedBy}
                      {item.approvedBy ? ` / ${item.approvedBy}` : ''}
                    </td>
                    <td className="small">
                      {item.resultMessage || item.blockedReason || item.errorMessage || '—'}
                      {item.providerRequestId && (
                        <div className="muted mono">{item.providerRequestId}</div>
                      )}
                    </td>
                    <td className="small">{formatDuration(item.durationMs)}</td>
                    <td className="small muted">{formatAgo(item.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      <Panel title="Datapoints stored around the incident">
        {detail.data && detail.data.recentMetrics.length === 0 ? (
          <Empty>No datapoints stored.</Empty>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Metric</th>
                  <th>Value</th>
                  <th>Threshold</th>
                  <th>Baseline</th>
                  <th>z-score</th>
                  <th>Flagged</th>
                  <th>Timestamp</th>
                </tr>
              </thead>
              <tbody>
                {detail.data.recentMetrics.map((metric) => (
                  <tr key={metric.id}>
                    <td>{metric.metricName}</td>
                    <td>{formatNumber(metric.value, 2)}</td>
                    <td>{formatNumber(metric.thresholdValue, 2)}</td>
                    <td>{formatNumber(metric.baselineMean, 2)}</td>
                    <td>{formatNumber(metric.zScore, 2)}</td>
                    <td>
                      <Badge
                        value={metric.anomalous ? 'yes' : 'no'}
                        tone={metric.anomalous ? 'danger' : 'ok'}
                      />
                    </td>
                    <td className="small muted">{formatTime(metric.timestamp)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>
    </>
  );
}
