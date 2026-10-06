import { useCallback, useEffect, useState } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';

import * as api from '../api/api';
import { useAuth } from '../auth/AuthContext';
import { formatAgo } from './format';
import { Badge } from './ui';

const NOTIFICATION_POLL_MS = 30000;

function NavItem({ to, label }) {
  return (
    <NavLink to={to} className={({ isActive }) => (isActive ? 'nav-link active' : 'nav-link')}>
      {label}
    </NavLink>
  );
}

function NotificationTray({ open, onClose }) {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!open) {
      return;
    }
    setLoading(true);
    api
      .listNotifications(false, 15)
      .then(setItems)
      .catch(() => setItems([]))
      .finally(() => setLoading(false));
  }, [open]);

  if (!open) {
    return null;
  }

  return (
    <div className="tray">
      <div className="tray-head">
        <strong>Notifications</strong>
        <button
          type="button"
          className="link"
          onClick={() => api.markAllNotificationsRead().then(() => onClose(true))}
        >
          mark all read
        </button>
      </div>
      {loading && <p className="muted small">Loading…</p>}
      {!loading && items.length === 0 && <p className="muted small">Nothing yet.</p>}
      <ul className="tray-list">
        {items.map((item) => (
          <li key={item.id} className={item.read ? 'read' : 'unread'}>
            <div className="tray-row">
              <Badge value={item.severity} />
              <span className="small muted">{formatAgo(item.createdAt)}</span>
            </div>
            <div>
              {item.incidentId ? (
                <Link to={`/incidents/${item.incidentId}`} onClick={() => onClose(false)}>
                  {item.title}
                </Link>
              ) : (
                <span>{item.title}</span>
              )}
            </div>
            <p className="small muted">{item.message}</p>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function Layout() {
  const { user, signOut, isAdmin, atLeast } = useAuth();
  const navigate = useNavigate();
  const [unread, setUnread] = useState(0);
  const [trayOpen, setTrayOpen] = useState(false);

  const refreshUnread = useCallback(() => {
    api
      .unreadNotificationCount()
      .then((response) => setUnread(response.unread || 0))
      .catch(() => {});
  }, []);

  useEffect(() => {
    refreshUnread();
    const timer = window.setInterval(refreshUnread, NOTIFICATION_POLL_MS);
    return () => window.clearInterval(timer);
  }, [refreshUnread]);

  const closeTray = (refresh) => {
    setTrayOpen(false);
    if (refresh) {
      refreshUnread();
    }
  };

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <Link to="/">CloudOps AI Agent</Link>
          <span className="small muted">autonomous monitoring with a human in the loop</span>
        </div>
        <div className="topbar-right">
          <button
            type="button"
            className="bell"
            onClick={() => setTrayOpen((open) => !open)}
            title="Notifications"
          >
            Alerts{unread > 0 ? ` (${unread})` : ''}
          </button>
          <span className="who">
            {user ? user.username : ''} <Badge value={user ? user.role : null} />
          </span>
          <button
            type="button"
            onClick={() => {
              signOut();
              navigate('/login', { replace: true });
            }}
          >
            Sign out
          </button>
          <NotificationTray open={trayOpen} onClose={closeTray} />
        </div>
      </header>

      <nav className="nav">
        <NavItem to="/" label="Dashboard" />
        <NavItem to="/resources" label="Resources" />
        <NavItem to="/incidents" label="Incidents" />
        <NavItem to="/approvals" label="Approvals" />
        <NavItem to="/actions" label="Actions" />
        {isAdmin && <NavItem to="/audit" label="Audit" />}
        <NavItem to="/system" label="System" />
        {isAdmin && <NavItem to="/users" label="Users" />}
        {!atLeast('OPERATOR') && <span className="small muted read-only">read-only account</span>}
      </nav>

      <main className="content">
        <Outlet />
      </main>

      <footer className="footer small muted">
        Every remediation is a predefined action from the server-side catalog and needs an
        administrator's approval. The model can only choose from that catalog — it can never
        issue an AWS command of its own.
      </footer>
    </div>
  );
}
