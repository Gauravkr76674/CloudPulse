import { useState } from 'react';

import * as api from '../api/api';
import { formatAgo, formatNumber } from '../components/format';
import { useLoad } from '../components/useLoad';
import {
  Badge,
  Empty,
  ErrorBanner,
  IncidentLink,
  Loading,
  Pager,
  Panel,
  ResourceLink,
} from '../components/ui';

const STATUSES = [
  'OPEN',
  'ANALYZING',
  'AWAITING_APPROVAL',
  'REMEDIATING',
  'RESOLVED',
  'CLOSED',
  'FAILED',
];
const SEVERITIES = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

export function IncidentsPage() {
  const [status, setStatus] = useState('');
  const [severity, setSeverity] = useState('');
  const [activeOnly, setActiveOnly] = useState(true);
  const [page, setPage] = useState(0);

  const { data, loading, error, reload } = useLoad(
    () => api.searchIncidents({ status, severity, activeOnly, page, size: 20 }),
    [status, severity, activeOnly, page],
  );

  const onFilterChange = (setter) => (value) => {
    setPage(0);
    setter(value);
  };

  return (
    <>
      <h1>Incidents</h1>
      <ErrorBanner message={error} />

      <Panel
        title="Detected by the monitoring loop"
        subtitle="An incident is opened only after the configured number of consecutive breaches"
        actions={
          <div className="row">
            <label className="check">
              <input
                type="checkbox"
                checked={activeOnly}
                onChange={(event) => onFilterChange(setActiveOnly)(event.target.checked)}
              />
              active only
            </label>
            <select
              value={status}
              onChange={(event) => onFilterChange(setStatus)(event.target.value)}
            >
              <option value="">any status</option>
              {STATUSES.map((value) => (
                <option key={value} value={value}>
                  {value.replace(/_/g, ' ')}
                </option>
              ))}
            </select>
            <select
              value={severity}
              onChange={(event) => onFilterChange(setSeverity)(event.target.value)}
            >
              <option value="">any severity</option>
              {SEVERITIES.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
            <button type="button" onClick={reload}>
              Refresh
            </button>
          </div>
        }
      >
        {loading && !data && <Loading />}
        {data && data.content.length === 0 && (
          <Empty>
            No incident matches these filters. Collect metrics from the dashboard to let the
            detector run.
          </Empty>
        )}
        {data && data.content.length > 0 && (
          <>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>#</th>
                    <th>Title</th>
                    <th>Resource</th>
                    <th>Rule</th>
                    <th>Trigger / limit</th>
                    <th>Severity</th>
                    <th>Status</th>
                    <th>Seen</th>
                    <th>AI</th>
                    <th>Opened</th>
                  </tr>
                </thead>
                <tbody>
                  {data.content.map((incident) => (
                    <tr key={incident.id}>
                      <td>
                        <IncidentLink id={incident.id} />
                      </td>
                      <td>
                        <IncidentLink id={incident.id}>{incident.title}</IncidentLink>
                      </td>
                      <td>
                        <ResourceLink id={incident.resourceId}>
                          {incident.resourceName}
                        </ResourceLink>
                        <div className="small muted mono">{incident.resourceIdentifier}</div>
                      </td>
                      <td className="small">{incident.ruleName}</td>
                      <td className="small">
                        {incident.metricName} {formatNumber(incident.triggerValue, 1)}
                        {incident.thresholdValue !== null && (
                          <span className="muted"> / {formatNumber(incident.thresholdValue, 1)}</span>
                        )}
                      </td>
                      <td>
                        <Badge value={incident.severity} />
                      </td>
                      <td>
                        <Badge value={incident.status} />
                      </td>
                      <td className="right">{incident.occurrenceCount}</td>
                      <td className="small">
                        {incident.analysisCount} analysis
                        <div className="muted">
                          {incident.pendingRecommendationCount} pending / {incident.actionCount}{' '}
                          action(s)
                        </div>
                      </td>
                      <td className="small muted">{formatAgo(incident.openedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager page={data.page} totalPages={data.totalPages} onChange={setPage} />
          </>
        )}
      </Panel>
    </>
  );
}
