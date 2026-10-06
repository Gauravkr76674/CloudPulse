import { useState } from 'react';
import { useParams } from 'react-router-dom';

import * as api from '../api/api';
import { useAuth } from '../auth/AuthContext';
import { MetricChart } from '../components/MetricChart';
import { formatAgo, formatMetricValue, formatNumber, formatTime } from '../components/format';
import { useAction, useLoad } from '../components/useLoad';
import { Badge, Empty, ErrorBanner, InfoBanner, Loading, Panel } from '../components/ui';

const WINDOWS = [60, 180, 720, 1440];

export function ResourceDetailPage() {
  const { id } = useParams();
  const { atLeast } = useAuth();
  const [metricName, setMetricName] = useState('CPUUtilization');
  const [windowMinutes, setWindowMinutes] = useState(180);
  const [notice, setNotice] = useState(null);

  const resource = useLoad(() => api.getResource(id), [id]);
  const latest = useLoad(() => api.latestMetrics(id), [id]);
  const series = useLoad(
    () => api.metricSeries(id, metricName, windowMinutes),
    [id, metricName, windowMinutes],
  );

  const action = useAction(() => {
    resource.reload();
    latest.reload();
    series.reload();
  });

  const poll = () =>
    action.run(async () => {
      const result = await api.collectResourceMetrics(id);
      setNotice(
        `Stored ${result.datapointsStored} datapoints, ${result.anomaliesDetected} anomaly(ies), ` +
          `${result.incidentsOpened} new incident(s).`,
      );
      return result;
    });

  const metricNames = (latest.data || []).map((metric) => metric.metricName);
  const options = metricNames.includes(metricName) ? metricNames : [metricName, ...metricNames];

  return (
    <>
      <h1>{resource.data ? resource.data.name : `Resource ${id}`}</h1>
      <ErrorBanner
        message={resource.error || series.error || action.error}
        onDismiss={action.clearError}
      />
      <InfoBanner message={notice} onDismiss={() => setNotice(null)} />

      {resource.loading && !resource.data && <Loading />}

      {resource.data && (
        <Panel
          title="Details"
          actions={
            atLeast('OPERATOR') && (
              <>
                <button type="button" onClick={poll} disabled={action.busy}>
                  {action.busy ? 'Polling…' : 'Poll now'}
                </button>
                <button
                  type="button"
                  onClick={() => action.run(() => api.refreshResource(id))}
                  disabled={action.busy}
                >
                  Refresh state
                </button>
                <button
                  type="button"
                  onClick={() =>
                    action.run(() => api.setResourceMonitored(id, !resource.data.monitored))
                  }
                  disabled={action.busy}
                >
                  {resource.data.monitored ? 'Stop polling' : 'Start polling'}
                </button>
              </>
            )
          }
        >
          <dl className="rec-grid">
            <dt>Instance id</dt>
            <dd className="mono">{resource.data.resourceId}</dd>
            <dt>State</dt>
            <dd>
              <Badge
                value={resource.data.status}
                tone={resource.data.status === 'RUNNING' ? 'ok' : 'muted-tone'}
              />
            </dd>
            <dt>Type / region</dt>
            <dd>
              {resource.data.instanceType || '—'} · {resource.data.region || '—'}
              {resource.data.availabilityZone ? ` (${resource.data.availabilityZone})` : ''}
            </dd>
            <dt>Addresses</dt>
            <dd className="mono">
              {resource.data.privateIp || '—'}
              {resource.data.publicIp ? ` / ${resource.data.publicIp}` : ''}
            </dd>
            <dt>Source</dt>
            <dd>{resource.data.source}</dd>
            <dt>Monitored</dt>
            <dd>{resource.data.monitored ? 'yes' : 'no'}</dd>
            <dt>CPU threshold</dt>
            <dd>{formatNumber(resource.data.cpuThresholdPercent, 0)}%</dd>
            <dt>Breaches to open</dt>
            <dd>{resource.data.consecutiveBreachesToOpen ?? 'default'}</dd>
            <dt>Launched</dt>
            <dd>{formatTime(resource.data.launchTime)}</dd>
            <dt>Last polled</dt>
            <dd>
              {formatTime(resource.data.lastCheckedAt)}{' '}
              <span className="muted small">({formatAgo(resource.data.lastCheckedAt)})</span>
            </dd>
            <dt>Active incidents</dt>
            <dd>{resource.data.activeIncidents}</dd>
          </dl>
        </Panel>
      )}

      <Panel
        title="Metric history"
        actions={
          <div className="row">
            <select value={metricName} onChange={(event) => setMetricName(event.target.value)}>
              {[...new Set(options)].map((name) => (
                <option key={name} value={name}>
                  {name}
                </option>
              ))}
            </select>
            <select
              value={windowMinutes}
              onChange={(event) => setWindowMinutes(Number(event.target.value))}
            >
              {WINDOWS.map((minutes) => (
                <option key={minutes} value={minutes}>
                  last {minutes >= 60 ? `${minutes / 60}h` : `${minutes}m`}
                </option>
              ))}
            </select>
            <button type="button" onClick={series.reload}>
              Reload
            </button>
          </div>
        }
      >
        {series.loading && !series.data && <Loading />}
        {series.data && (
          <>
            <MetricChart
              points={series.data.points}
              threshold={series.data.thresholdValue}
              unit={series.data.unit}
            />
            <p className="small muted">
              {series.data.points.length} datapoint(s) · {series.data.metricName} in{' '}
              {series.data.unit || 'unknown unit'} · red points breached the threshold
            </p>
          </>
        )}
      </Panel>

      <Panel title="Latest value per metric">
        {latest.loading && !latest.data && <Loading />}
        {latest.data && latest.data.length === 0 && (
          <Empty>Nothing collected yet for this resource.</Empty>
        )}
        {latest.data && latest.data.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Metric</th>
                  <th>Value</th>
                  <th>Threshold</th>
                  <th>Baseline</th>
                  <th>z-score</th>
                  <th>Anomalous</th>
                  <th>Timestamp</th>
                </tr>
              </thead>
              <tbody>
                {latest.data.map((metric) => (
                  <tr key={metric.id}>
                    <td>
                      {metric.metricName}
                      <div className="small muted">{metric.statistic}</div>
                    </td>
                    <td>{formatMetricValue(metric.value, metric.unit)}</td>
                    <td>{formatMetricValue(metric.thresholdValue, metric.unit)}</td>
                    <td>{formatMetricValue(metric.baselineMean, metric.unit)}</td>
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
