import { formatClock, formatMetricValue } from './format';

/**
 * A line chart drawn as plain SVG.
 *
 * A charting library would be a heavier dependency than this screen needs: the data is one
 * numeric series plus a horizontal threshold. Breaching points are drawn red so the reason
 * an incident exists is visible at a glance.
 */
export function MetricChart({ points, threshold, unit, height = 200 }) {
  const series = (points || []).filter((point) => point && point.value !== null);
  if (series.length === 0) {
    return <p className="muted">No datapoints in this window.</p>;
  }

  const width = 720;
  const padding = { top: 12, right: 16, bottom: 26, left: 52 };
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = height - padding.top - padding.bottom;

  const values = series.map((point) => Number(point.value));
  const candidates = threshold === null || threshold === undefined ? values : [...values, threshold];
  const rawMax = Math.max(...candidates);
  const rawMin = Math.min(...candidates, 0);
  // a flat series would divide by zero
  const max = rawMax === rawMin ? rawMax + 1 : rawMax;
  const min = rawMin;

  const x = (index) =>
    padding.left + (series.length === 1 ? plotWidth / 2 : (index / (series.length - 1)) * plotWidth);
  const y = (value) => padding.top + plotHeight - ((Number(value) - min) / (max - min)) * plotHeight;

  const path = series.map((point, index) => `${x(index)},${y(point.value)}`).join(' ');
  const thresholdY =
    threshold === null || threshold === undefined ? null : y(threshold);

  const ticks = [min, min + (max - min) / 2, max];
  const labelEvery = Math.max(1, Math.ceil(series.length / 6));

  return (
    <svg
      className="chart"
      viewBox={`0 0 ${width} ${height}`}
      role="img"
      aria-label="Metric over time"
    >
      {ticks.map((tick) => (
        <g key={`tick-${tick}`}>
          <line
            x1={padding.left}
            x2={width - padding.right}
            y1={y(tick)}
            y2={y(tick)}
            className="chart-grid"
          />
          <text x={padding.left - 8} y={y(tick) + 4} className="chart-axis" textAnchor="end">
            {formatMetricValue(tick, unit)}
          </text>
        </g>
      ))}

      {thresholdY !== null && (
        <g>
          <line
            x1={padding.left}
            x2={width - padding.right}
            y1={thresholdY}
            y2={thresholdY}
            className="chart-threshold"
          />
          <text x={width - padding.right} y={thresholdY - 5} className="chart-axis" textAnchor="end">
            threshold {formatMetricValue(threshold, unit)}
          </text>
        </g>
      )}

      <polyline className="chart-line" points={path} />

      {series.map((point, index) => (
        <circle
          key={`${point.timestamp}-${index}`}
          cx={x(index)}
          cy={y(point.value)}
          r={point.anomalous ? 4 : 2.5}
          className={point.anomalous ? 'chart-dot chart-dot-anomalous' : 'chart-dot'}
        >
          <title>{`${formatClock(point.timestamp)} — ${formatMetricValue(point.value, unit)}`}</title>
        </circle>
      ))}

      {series.map((point, index) =>
        index % labelEvery === 0 ? (
          <text
            key={`label-${point.timestamp}-${index}`}
            x={x(index)}
            y={height - 8}
            className="chart-axis"
            textAnchor="middle"
          >
            {formatClock(point.timestamp)}
          </text>
        ) : null,
      )}
    </svg>
  );
}
