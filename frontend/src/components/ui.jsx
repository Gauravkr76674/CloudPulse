import { Link } from 'react-router-dom';

/** A titled block with an optional right-hand action area. */
export function Panel({ title, subtitle, actions, children, className = '' }) {
  return (
    <section className={`panel ${className}`}>
      {(title || actions) && (
        <header className="panel-head">
          <div>
            {title && <h2>{title}</h2>}
            {subtitle && <p className="muted small">{subtitle}</p>}
          </div>
          {actions && <div className="panel-actions">{actions}</div>}
        </header>
      )}
      <div className="panel-body">{children}</div>
    </section>
  );
}

export function Stat({ label, value, tone = '', hint }) {
  return (
    <div className={`stat ${tone}`}>
      <span className="stat-label">{label}</span>
      <span className="stat-value">{value}</span>
      {hint && <span className="stat-hint">{hint}</span>}
    </div>
  );
}

/**
 * Status colouring is centralised so REJECTED_UNSAFE is red on every screen it appears on.
 * The tone names map to classes in styles.css.
 */
const TONES = {
  CRITICAL: 'danger',
  HIGH: 'warn',
  MEDIUM: 'info',
  LOW: 'muted-tone',
  OPEN: 'danger',
  ANALYZING: 'info',
  AWAITING_APPROVAL: 'warn',
  REMEDIATING: 'info',
  RESOLVED: 'ok',
  CLOSED: 'muted-tone',
  FAILED: 'danger',
  PROPOSED: 'warn',
  APPROVED: 'info',
  EXECUTED: 'ok',
  REJECTED: 'muted-tone',
  REJECTED_UNSAFE: 'danger',
  EXPIRED: 'muted-tone',
  PENDING: 'warn',
  RUNNING: 'info',
  SUCCEEDED: 'ok',
  BLOCKED: 'danger',
  CANCELLED: 'muted-tone',
  SUCCESS: 'ok',
  DENIED: 'danger',
  FAILURE: 'danger',
  ADMIN: 'danger',
  OPERATOR: 'info',
  VIEWER: 'muted-tone',
  RUNNING_RESOURCE: 'ok',
};

export function Badge({ value, tone, title }) {
  if (value === null || value === undefined || value === '') {
    return <span className="muted">—</span>;
  }
  const resolved = tone || TONES[value] || 'neutral';
  return (
    <span className={`badge badge-${resolved}`} title={title || String(value)}>
      {String(value).replace(/_/g, ' ')}
    </span>
  );
}

export function Loading({ label = 'Loading…' }) {
  return <p className="muted">{label}</p>;
}

export function ErrorBanner({ message, onDismiss }) {
  if (!message) {
    return null;
  }
  return (
    <div className="banner banner-error" role="alert">
      <span>{message}</span>
      {onDismiss && (
        <button type="button" className="link" onClick={onDismiss}>
          dismiss
        </button>
      )}
    </div>
  );
}

export function InfoBanner({ message, onDismiss }) {
  if (!message) {
    return null;
  }
  return (
    <div className="banner banner-info" role="status">
      <span>{message}</span>
      {onDismiss && (
        <button type="button" className="link" onClick={onDismiss}>
          dismiss
        </button>
      )}
    </div>
  );
}

export function Empty({ children = 'Nothing to show yet.' }) {
  return <p className="muted">{children}</p>;
}

export function IncidentLink({ id, children }) {
  if (!id) {
    return <span className="muted">—</span>;
  }
  return <Link to={`/incidents/${id}`}>{children || `#${id}`}</Link>;
}

export function ResourceLink({ id, children }) {
  if (!id) {
    return <span className="muted">—</span>;
  }
  return <Link to={`/resources/${id}`}>{children || `#${id}`}</Link>;
}

/** Simple pager for the endpoints that return PageResponse. */
export function Pager({ page, totalPages, onChange }) {
  const safeTotal = Math.max(totalPages || 0, 1);
  return (
    <div className="pager">
      <button type="button" disabled={page <= 0} onClick={() => onChange(page - 1)}>
        ← Previous
      </button>
      <span className="muted small">
        Page {page + 1} of {safeTotal}
      </span>
      <button type="button" disabled={page >= safeTotal - 1} onClick={() => onChange(page + 1)}>
        Next →
      </button>
    </div>
  );
}
