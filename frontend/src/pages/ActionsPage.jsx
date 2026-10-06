import { useState } from 'react';

import * as api from '../api/api';
import { formatAgo, formatDuration, formatTime } from '../components/format';
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

const STATUSES = ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELLED'];

export function ActionsPage() {
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const history = useLoad(() => api.actionHistory({ status, page, size: 20 }), [status, page]);
  const catalog = useLoad(() => api.actionCatalog(), []);

  return (
    <>
      <h1>Action history</h1>
      <ErrorBanner message={history.error || catalog.error} />

      <Panel
        title="Everything the agent has attempted"
        subtitle="Blocked rows are attempts the guardrails stopped before any AWS call"
        actions={
          <div className="row">
            <select
              value={status}
              onChange={(event) => {
                setPage(0);
                setStatus(event.target.value);
              }}
            >
              <option value="">any status</option>
              {STATUSES.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
            <button type="button" onClick={history.reload}>
              Refresh
            </button>
          </div>
        }
      >
        {history.loading && !history.data && <Loading />}
        {history.data && history.data.content.length === 0 && (
          <Empty>No action has been attempted yet.</Empty>
        )}
        {history.data && history.data.content.length > 0 && (
          <>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>#</th>
                    <th>Action</th>
                    <th>Resource</th>
                    <th>Incident</th>
                    <th>Status</th>
                    <th>Dry run</th>
                    <th>Requested / approved</th>
                    <th>Outcome</th>
                    <th>Duration</th>
                    <th>When</th>
                  </tr>
                </thead>
                <tbody>
                  {history.data.content.map((item) => (
                    <tr key={item.id}>
                      <td>{item.id}</td>
                      <td>
                        {item.actionDisplayName || item.actionType}
                        <div className="small muted mono">{item.actionType}</div>
                      </td>
                      <td>
                        <ResourceLink id={item.resourceId}>{item.resourceName}</ResourceLink>
                        <div className="small muted mono">{item.resourceIdentifier}</div>
                      </td>
                      <td>
                        <IncidentLink id={item.incidentId} />
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
                      <td className="small muted" title={formatTime(item.createdAt)}>
                        {formatAgo(item.createdAt)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager page={history.data.page} totalPages={history.data.totalPages} onChange={setPage} />
          </>
        )}
      </Panel>

      <Panel
        title="The safe action catalog"
        subtitle="The complete set of operations this agent can ever perform — the model chooses from this list and nothing else"
      >
        {catalog.loading && !catalog.data && <Loading />}
        {catalog.data && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Action</th>
                  <th>What it does</th>
                  <th>Changes AWS</th>
                  <th>Needs approval</th>
                  <th>Default risk</th>
                  <th>Executable now</th>
                </tr>
              </thead>
              <tbody>
                {catalog.data.map((entry) => (
                  <tr key={entry.actionType}>
                    <td>
                      {entry.displayName}
                      <div className="small muted mono">{entry.actionType}</div>
                    </td>
                    <td className="small">{entry.description}</td>
                    <td>
                      <Badge
                        value={entry.mutating ? 'yes' : 'no'}
                        tone={entry.mutating ? 'warn' : 'muted-tone'}
                      />
                    </td>
                    <td>
                      <Badge
                        value={entry.approvalRequired ? 'yes' : 'no'}
                        tone={entry.approvalRequired ? 'info' : 'muted-tone'}
                      />
                    </td>
                    <td>
                      <Badge value={entry.defaultRisk} />
                    </td>
                    <td className="small">
                      <Badge
                        value={entry.executable ? 'yes' : 'no'}
                        tone={entry.executable ? 'ok' : 'danger'}
                      />
                      {entry.executionNote && (
                        <div className="muted">{entry.executionNote}</div>
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
