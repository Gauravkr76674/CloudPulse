import { useState } from 'react';

import * as api from '../api/api';
import { useAuth } from '../auth/AuthContext';
import { formatTime } from '../components/format';
import { useAction, useLoad } from '../components/useLoad';
import { Badge, ErrorBanner, InfoBanner, Loading, Panel } from '../components/ui';

const ROLES = ['VIEWER', 'OPERATOR', 'ADMIN'];
const EMPTY_FORM = { username: '', email: '', password: '', fullName: '', role: 'VIEWER' };

export function UsersPage() {
  const { user } = useAuth();
  const { data, loading, error, reload } = useLoad(() => api.listUsers(), []);
  const [form, setForm] = useState(EMPTY_FORM);
  const [notice, setNotice] = useState(null);
  const action = useAction(() => reload());

  const field = (name) => (event) => setForm({ ...form, [name]: event.target.value });

  const create = (event) => {
    event.preventDefault();
    action.run(async () => {
      const created = await api.createUser(form);
      setNotice(`Created ${created.username} as ${created.role}.`);
      setForm(EMPTY_FORM);
      return created;
    });
  };

  const resetPassword = (target) => {
    const next = window.prompt(`New password for ${target.username} (at least 8 characters)`);
    if (!next) {
      return;
    }
    action.run(async () => {
      await api.resetUserPassword(target.id, next);
      setNotice(`Password reset for ${target.username}.`);
      return null;
    });
  };

  return (
    <>
      <h1>Users and roles</h1>
      <ErrorBanner message={error || action.error} onDismiss={action.clearError} />
      <InfoBanner message={notice} onDismiss={() => setNotice(null)} />

      <Panel
        title="Accounts"
        subtitle="VIEWER reads · OPERATOR runs the pipeline · ADMIN approves actions"
        actions={
          <button type="button" onClick={reload}>
            Refresh
          </button>
        }
      >
        {loading && !data && <Loading />}
        {data && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Username</th>
                  <th>Name</th>
                  <th>Email</th>
                  <th>Role</th>
                  <th>Enabled</th>
                  <th>Last login</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {data.map((account) => {
                  const self = user && account.id === user.id;
                  return (
                    <tr key={account.id}>
                      <td>
                        {account.username}
                        {self && <span className="small muted"> (you)</span>}
                      </td>
                      <td className="small">{account.fullName || '—'}</td>
                      <td className="small">{account.email}</td>
                      <td>
                        <select
                          value={account.role}
                          disabled={action.busy || self}
                          onChange={(event) =>
                            action.run(() => api.changeUserRole(account.id, event.target.value))
                          }
                        >
                          {ROLES.map((role) => (
                            <option key={role} value={role}>
                              {role}
                            </option>
                          ))}
                        </select>
                      </td>
                      <td>
                        <Badge
                          value={account.enabled ? 'enabled' : 'disabled'}
                          tone={account.enabled ? 'ok' : 'muted-tone'}
                        />
                      </td>
                      <td className="small muted">{formatTime(account.lastLoginAt)}</td>
                      <td>
                        <div className="row">
                          <button
                            type="button"
                            disabled={action.busy || self}
                            onClick={() =>
                              action.run(() => api.setUserEnabled(account.id, !account.enabled))
                            }
                          >
                            {account.enabled ? 'Disable' : 'Enable'}
                          </button>
                          <button
                            type="button"
                            disabled={action.busy}
                            onClick={() => resetPassword(account)}
                          >
                            Reset password
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      <Panel title="Add an account">
        <form onSubmit={create}>
          <div className="row">
            <div className="field">
              <label htmlFor="new-username">Username</label>
              <input
                id="new-username"
                value={form.username}
                onChange={field('username')}
                minLength={3}
                maxLength={60}
                required
              />
            </div>
            <div className="field">
              <label htmlFor="new-email">Email</label>
              <input
                id="new-email"
                type="email"
                value={form.email}
                onChange={field('email')}
                required
              />
            </div>
            <div className="field">
              <label htmlFor="new-fullname">Full name</label>
              <input id="new-fullname" value={form.fullName} onChange={field('fullName')} />
            </div>
            <div className="field">
              <label htmlFor="new-password">Password</label>
              <input
                id="new-password"
                type="password"
                value={form.password}
                onChange={field('password')}
                minLength={8}
                required
              />
            </div>
            <div className="field">
              <label htmlFor="new-role">Role</label>
              <select id="new-role" value={form.role} onChange={field('role')}>
                {ROLES.map((role) => (
                  <option key={role} value={role}>
                    {role}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <button type="submit" className="primary" disabled={action.busy}>
                {action.busy ? 'Saving…' : 'Create account'}
              </button>
            </div>
          </div>
        </form>
      </Panel>
    </>
  );
}
