import { useState } from 'react';

import * as api from '../api/api';
import { formatTime } from '../components/format';
import { useLoad } from '../components/useLoad';
import { Badge, Empty, ErrorBanner, Loading, Pager, Panel } from '../components/ui';

export function AuditPage() {
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState('');
  const { data, loading, error, reload } = useLoad(() => api.auditLog({ page, size: 50 }), [page]);

  const rows = (data ? data.content : []).filter((entry) => {
    if (!filter) {
      return true;
    }
    const needle = filter.toLowerCase();
    return [entry.action, entry.username, entry.targetType, entry.targetId, entry.details]
      .filter(Boolean)
      .some((field) => String(field).toLowerCase().includes(needle));
  });

  return (
    <>
      <h1>Audit trail</h1>
      <ErrorBanner message={error} />

      <Panel
        title="Append-only record"
        subtitle="Who asked for what, what was denied, and what the agent actually did"
        actions={
          <div className="row">
            <input
              placeholder="filter this page"
              value={filter}
              onChange={(event) => setFilter(event.target.value)}
            />
            <button type="button" onClick={reload}>
              Refresh
            </button>
          </div>
        }
      >
        {loading && !data && <Loading />}
        {data && rows.length === 0 && <Empty>No entries match.</Empty>}
        {data && rows.length > 0 && (
          <>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>When</th>
                    <th>User</th>
                    <th>Role</th>
                    <th>Action</th>
                    <th>Target</th>
                    <th>Outcome</th>
                    <th>Details</th>
                    <th>From</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((entry) => (
                    <tr key={entry.id}>
                      <td className="small muted">{formatTime(entry.createdAt)}</td>
                      <td>{entry.username}</td>
                      <td>
                        <Badge value={entry.role} />
                      </td>
                      <td className="mono small">{entry.action}</td>
                      <td className="small">
                        {entry.targetType || '—'}
                        {entry.targetId ? ` #${entry.targetId}` : ''}
                      </td>
                      <td>
                        <Badge value={entry.outcome} />
                      </td>
                      <td className="small">{entry.details || '—'}</td>
                      <td className="small muted mono">{entry.ipAddress || '—'}</td>
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
