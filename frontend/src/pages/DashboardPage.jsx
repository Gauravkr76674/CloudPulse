import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';

import * as api from '../api/api';
import { useAuth } from '../auth/AuthContext';
import { formatAgo, formatNumber } from '../components/format';
import { useAction, useLoad } from '../components/useLoad';
import {
  Badge,
  Empty,
  ErrorBanner,
  IncidentLink,
  InfoBanner,
  Loading,
  Panel,
  ResourceLink,
  Stat,
} from '../components/ui';

const REFRESH_MS = 30000;

export function DashboardPage() {
  const { atLeast } = useAuth();
  const { data, loading, error, reload } = useLoad(() => api.dashboardSummary(), []);
  const [notice, setNotice] = useState(null);
  const action = useAction(() => reload());

  // A dashboard that goes stale silently is worse than no dashboard.
  useEffect(() => {
    const timer = window.setInterval(reload, REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [reload]);

  const collect = () =>
    action.run(async () => {
      const result = await api.collectAllMetrics();
      setNotice(
        `Polled ${result.resourcesPolled} resource(s), stored ${result.datapointsStored} datapoints, ` +
          `${result.anomaliesDetected} anomaly(ies), ${result.incidentsOpened} new incident(s).`,
      );
      return result;
    });

  const sync = () =>
    action.run(async () => {
      const result = await api.syncResources();
      setNotice(
        `Discovered ${result.discovered} resource(s) from ${result.provider}: ` +
          `${result.created} new, ${result.updated} updated.`,
      );
      return result;
    });

  if (loading && !data) {
    return <Loading />;
  }

  return (
    <>
      <h1>Operations overview</h1>
      <ErrorBanner message={error || action.error} onDismiss={action.clearError} />
      <InfoBanner message={notice} onDismiss={() => setNotice(null)} />

      {data && (
        <>
          <Panel
            title="Right now"
            subtitle={
              data.system
                ? `${data.system.cloudProvider} / ${data.system.region || 'no region'} · model ${
                    data.system.aiDefaultModel || 'not configured'
                  }`
                : undefined
            }
            actions={
              atLeast('OPERATOR') && (
                <>
                  <button type="button" onClick={collect} disabled={action.busy}>
                    {action.busy ? 'Working…' : 'Collect metrics now'}
                  </button>
                  <button type="button" onClick={sync} disabled={action.busy}>
                    Sync resources
                  </button>
                  <button type="button" onClick={reload} disabled={action.busy}>
                    Refresh
                  </button>
                </>
              )
            }
          >
            <div className="stats">
              <Stat
                label="Active incidents"
                value={data.incidents.active}
                tone={data.incidents.active > 0 ? 'danger' : 'ok'}
                hint={`${data.incidents.openedLast24h} opened in 24h`}
              />
              <Stat
                label="Awaiting approval"
                value={data.incidents.awaitingApproval}
                tone={data.incidents.awaitingApproval > 0 ? 'warn' : ''}
                hint={`${data.actions.pending} action(s) pending`}
              />
              <Stat label="Remediating" value={data.incidents.remediating} />
              <Stat
                label="Resolved (24h)"
                value={data.incidents.resolvedLast24h}
                tone="ok"
                hint={
                  data.incidents.meanTimeToResolveSeconds
                    ? `MTTR ${formatNumber(data.incidents.meanTimeToResolveSeconds / 60, 1)} min`
                    : 'no MTTR yet'
                }
              />
              <Stat
                label="Monitored resources"
                value={`${data.resources.monitored}/${data.resources.total}`}
                hint={`${data.resources.running} running · ${data.resources.stopped} stopped`}
              />
              <Stat
                label="Actions succeeded"
                value={data.actions.succeeded}
                tone="ok"
                hint={`${data.actions.failed} failed · ${data.actions.blocked} blocked`}
              />
              <Stat
                label="AI analyses"
                value={data.ai.analyses}
                hint={`${data.ai.failedAnalyses} failed call(s)`}
              />
              <Stat
                label="Unsafe suggestions blocked"
                value={data.ai.recommendationsRejectedUnsafe}
                tone={data.ai.recommendationsRejectedUnsafe > 0 ? 'danger' : ''}
                hint={`${data.ai.recommendationsProposed} proposed from the catalog`}
              />
            </div>

            {data.incidentsBySeverity && Object.keys(data.incidentsBySeverity).length > 0 && (
              <p style={{ marginTop: 12 }}>
                Active by severity:{' '}
                {Object.entries(data.incidentsBySeverity).map(([severity, count]) => (
                  <span key={severity} style={{ marginRight: 8 }}>
                    <Badge value={severity} /> {count}
                  </span>
                ))}
              </p>
            )}
          </Panel>

          <div className="grid grid-2">
            <Panel
              title="Waiting on an administrator"
              subtitle="Nothing here executes until it is approved"
              actions={<Link to="/approvals">open approvals →</Link>}
            >
              {data.pendingApprovals.length === 0 ? (
                <Empty>No recommendation is waiting for a decision.</Empty>
              ) : (
                <div className="table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th>Incident</th>
                        <th>Action</th>
                        <th>Risk</th>
                        <th>Proposed</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.pendingApprovals.map((rec) => (
                        <tr key={rec.id}>
                          <td>
                            <IncidentLink id={rec.incidentId} />
                          </td>
                          <td>
                            {rec.actionDisplayName || rec.title}
                            <div className="small muted">{rec.actionType}</div>
                          </td>
                          <td>
                            <Badge value={rec.riskLevel} />
                          </td>
                          <td className="small muted">{formatAgo(rec.createdAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Panel>

            <Panel title="Recent incidents" actions={<Link to="/incidents">all incidents →</Link>}>
              {data.recentIncidents.length === 0 ? (
                <Empty>No incidents recorded yet.</Empty>
              ) : (
                <div className="table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th>#</th>
                        <th>Resource</th>
                        <th>Trigger</th>
                        <th>Severity</th>
                        <th>Status</th>
                        <th>Opened</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.recentIncidents.map((incident) => (
                        <tr key={incident.id}>
                          <td>
                            <IncidentLink id={incident.id} />
                          </td>
                          <td>
                            <ResourceLink id={incident.resourceId}>
                              {incident.resourceName}
                            </ResourceLink>
                          </td>
                          <td className="small">
                            {incident.metricName} {formatNumber(incident.triggerValue, 1)}
                            {incident.thresholdValue !== null && (
                              <span className="muted">
                                {' '}
                                / {formatNumber(incident.thresholdValue, 1)}
                              </span>
                            )}
                          </td>
                          <td>
                            <Badge value={incident.severity} />
                          </td>
                          <td>
                            <Badge value={incident.status} />
                          </td>
                          <td className="small muted">{formatAgo(incident.openedAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Panel>
          </div>

          <Panel title="Recent actions" actions={<Link to="/actions">action history →</Link>}>
            {data.recentActions.length === 0 ? (
              <Empty>No remediation has been attempted yet.</Empty>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>Action</th>
                      <th>Resource</th>
                      <th>Status</th>
                      <th>Approved by</th>
                      <th>Result</th>
                      <th>When</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.recentActions.map((item) => (
                      <tr key={item.id}>
                        <td>{item.id}</td>
                        <td>
                          {item.actionDisplayName || item.actionType}
                          {item.dryRun && <span className="badge badge-info"> dry run</span>}
                        </td>
                        <td>
                          <ResourceLink id={item.resourceId}>{item.resourceName}</ResourceLink>
                        </td>
                        <td>
                          <Badge value={item.status} />
                        </td>
                        <td>{item.approvedBy || <span className="muted">—</span>}</td>
                        <td className="small">
                          {item.resultMessage || item.blockedReason || item.errorMessage || '—'}
                        </td>
                        <td className="small muted">{formatAgo(item.createdAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
        </>
      )}
    </>
  );
}
