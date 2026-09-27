// Renders backend-provided masked text with {{TOKENS}} highlighted.
// Pure display formatting of backend tokens — NOT PII detection.
const TOKEN_PATTERN = /(\{\{[A-Z_]+_\d+\}\})/g;

export function MaskedText({ text }) {
  if (typeof text !== 'string' || text === '') {
    return <span className="muted">No masked text returned.</span>;
  }
  const parts = text.split(TOKEN_PATTERN);
  return (
    <span>
      {parts.map((part, index) =>
        index % 2 === 1 ? (
          <code key={index} className="token">
            {part}
          </code>
        ) : (
          <span key={index}>{part}</span>
        ),
      )}
    </span>
  );
}

function Detections({ detections }) {
  if (!Array.isArray(detections) || detections.length === 0) {
    return <p className="muted">No PII detected in this request.</p>;
  }
  return (
    <ul className="chip-list" aria-label="Detected PII types">
      {detections.map((detection, index) => (
        <li key={`${detection.type}-${detection.start}-${index}`} className="chip">
          <span className="chip-type">{String(detection.type)}</span>
          {typeof detection.start === 'number' && typeof detection.end === 'number' ? (
            <span className="chip-range">
              chars {detection.start}–{detection.end}
            </span>
          ) : null}
        </li>
      ))}
    </ul>
  );
}

export function ResultPanel({ result }) {
  if (!result || result.kind !== 'success') {
    return null;
  }
  const data = result.data;
  return (
    <section className="card" aria-live="polite" aria-label="Analysis result">
      <h2>Protected result</h2>
      <dl className="meta-grid">
        <div>
          <dt>Processing status</dt>
          <dd>{String(data.status ?? 'unknown')}</dd>
        </div>
        <div>
          <dt>Provider</dt>
          <dd>{String(data.provider ?? 'unknown')}</dd>
        </div>
        <div>
          <dt>Request ID</dt>
          <dd className="mono">{String(data.requestId ?? 'unknown')}</dd>
        </div>
      </dl>
      <h3>Text sent to the provider (masked)</h3>
      <p className="panel-text">
        <MaskedText text={data.processedText} />
      </p>
      <h3>Final response (re-hydrated)</h3>
      <p className="panel-text">{String(data.response ?? '')}</p>
      <h3>Backend-reported detections</h3>
      <Detections detections={data.detections} />
      <p className="footnote">
        Detections and masked text come from the backend. The browser performs no PII detection.
      </p>
    </section>
  );
}
