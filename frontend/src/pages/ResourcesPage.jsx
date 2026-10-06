import { useState } from 'react';

import * as api from '../api/api';
import { useAuth } from '../auth/AuthContext';
import { formatAgo, formatNumber } from '../components/format';
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

export function ResourcesPage() {
  const { atLeast } = useAuth();
  const [search, setSearch] = useState('');
  const [applied, setApplied] = useState('');
  const { data, loading, error, reload } = useLoad(() => api.listResources(applied), [applied]);
  const [notice, setNotice] = useState(null);
  const action = useAction(() => reload());

  const sync = () =>
    action.run(async () => {
      const result = await api.syncResources();
      setNotice(
        `${result.provider}: ${result.discovered} discovered, ${result.created} new, ` +
          `${result.updated} updated, ${result.skipped} skipped.`,
      );
      return result;
    });

  const toggleMonitored = (resource) =>
    action.run(() => api.setResourceMonitored(resource.id, !resource.monitored));

  const collectOne = (resource) =>
    action.run(async () => {
      const result = await api.collectResourceMetrics(resource.id);
      setNotice(
        `${resource.name}: stored ${result.datapointsStored} datapoints, ` +
          `${result.anomaliesDetected} anomaly(ies), ${result.incidentsOpened} new incident(s).`,
      );
      return result;
    });

  return (
    <>
      <h1>Cloud resources</h1>
      <ErrorBanner message={error || action.error} onDismiss={action.clearError} />
      <InfoBanner message={notice} onDismiss={() => setNotice(null)} />

      <Panel
        title="Inventory"
        subtitle="Discovered from the configured provider; only monitored resources are polled"
        actions={
          <>
            <form
              className="row"
              onSubmit={(event) => {
                event.preventDefault();
                setApplied(search.trim());
              }}
            >
              <input
                placeholder="name, id or tag"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
              />
              <button type="submit">Search</button>
              {applied && (
                <button
                  type="button"
                  onClick={() => {
                    setSearch('');
                    setApplied('');
                  }}
                >
                  Clear
                </button>
              )}
            </form>
            {atLeast('OPERATOR') && (
              <button type="button" className="primary" onClick={sync} disabled={action.busy}>
                {action.busy ? 'Working…' : 'Sync from provider'}
              </button>
            )}
          </>
        }
      >
        {loading && !data && <Loading />}
        {data && data.length === 0 && (
          <Empty>
            No resources yet. Use “Sync from provider” to discover the EC2 instances the backend
            can see.
          </Empty>
        )}
        {data && data.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Instance</th>
                  <th>Type</th>
                  <th>State</th>
                  <th>CPU limit</th>
                  <th>Monitored</th>
                  <th>Active incidents</th>
                  <th>Last polled</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {data.map((resource) => (
                  <tr key={resource.id}>
                    <td>
                      <ResourceLink id={resource.id}>{resource.name}</ResourceLink>
                      <div className="small muted">{resource.region || '—'}</div>
                    </td>
                    <td className="mono">{resource.resourceId}</td>
                    <td className="small">
                      {resource.instanceType || '—'}
                      <div className="muted">{resource.type}</div>
                    </td>
                    <td>
                      <Badge
                        value={resource.status}
                        tone={resource.status === 'RUNNING' ? 'ok' : 'muted-tone'}
                      />
                    </td>
                    <td>{formatNumber(resource.cpuThresholdPercent, 0)}</td>
                    <td>
                      <Badge
                        value={resource.monitored ? 'yes' : 'no'}
                        tone={resource.monitored ? 'ok' : 'muted-tone'}
                      />
                    </td>
                    <td className={resource.activeIncidents > 0 ? 'right' : 'right muted'}>
                      {resource.activeIncidents}
                    </td>
                    <td className="small muted">{formatAgo(resource.lastCheckedAt)}</td>
                    <td>
                      {atLeast('OPERATOR') && (
                        <div className="row">
                          <button
                            type="button"
                            onClick={() => toggleMonitored(resource)}
                            disabled={action.busy}
                          >
                            {resource.monitored ? 'Stop polling' : 'Start polling'}
                          </button>
                          <button
                            type="button"
                            onClick={() => collectOne(resource)}
                            disabled={action.busy}
                          >
                            Poll now
                          </button>
                        </div>
                      )}
                    </td>
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
