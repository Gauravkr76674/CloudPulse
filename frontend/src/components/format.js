/** Display helpers. The backend sends ISO-8601 instants and raw numbers. */

export function formatTime(instant) {
  if (!instant) {
    return '—';
  }
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) {
    return String(instant);
  }
  return date.toLocaleString();
}

export function formatClock(instant) {
  if (!instant) {
    return '—';
  }
  const date = new Date(instant);
  return Number.isNaN(date.getTime())
    ? String(instant)
    : date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export function formatAgo(instant) {
  if (!instant) {
    return '—';
  }
  const then = new Date(instant).getTime();
  if (Number.isNaN(then)) {
    return String(instant);
  }
  const seconds = Math.round((Date.now() - then) / 1000);
  if (seconds < 60) {
    return `${Math.max(seconds, 0)}s ago`;
  }
  if (seconds < 3600) {
    return `${Math.round(seconds / 60)}m ago`;
  }
  if (seconds < 86400) {
    return `${Math.round(seconds / 3600)}h ago`;
  }
  return `${Math.round(seconds / 86400)}d ago`;
}

export function formatNumber(value, digits = 2) {
  if (value === null || value === undefined) {
    return '—';
  }
  const number = Number(value);
  if (Number.isNaN(number)) {
    return String(value);
  }
  return Number.isInteger(number) ? String(number) : number.toFixed(digits);
}

/** CloudWatch network metrics arrive in bytes and are unreadable raw. */
export function formatMetricValue(value, unit) {
  if (value === null || value === undefined) {
    return '—';
  }
  if (unit === 'Percent') {
    return `${formatNumber(value, 1)}%`;
  }
  if (unit === 'Bytes' && Math.abs(value) >= 1024) {
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let scaled = Number(value);
    let index = 0;
    while (Math.abs(scaled) >= 1024 && index < units.length - 1) {
      scaled /= 1024;
      index += 1;
    }
    return `${formatNumber(scaled, 1)} ${units[index]}`;
  }
  return formatNumber(value, 2);
}

export function formatDuration(millis) {
  if (millis === null || millis === undefined) {
    return '—';
  }
  if (millis < 1000) {
    return `${Math.round(millis)} ms`;
  }
  return `${(millis / 1000).toFixed(1)} s`;
}

export function formatConfidence(confidence) {
  if (confidence === null || confidence === undefined) {
    return '—';
  }
  return `${Math.round(confidence * 100)}%`;
}

/** Enum names are SCREAMING_SNAKE on the wire; make them readable in a table. */
export function humanize(value) {
  if (!value) {
    return '—';
  }
  return String(value)
    .toLowerCase()
    .split('_')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ');
}
