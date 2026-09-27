const STEPS = [
  'Request arrives at the gateway',
  'API key authenticated (401 otherwise)',
  'Rate limit checked (429 otherwise)',
  'PII detected by the backend engine',
  'Sensitive values replaced with opaque tokens',
  'Token mapping encrypted (AES-256-GCM)',
  'Only masked text sent to the selected LLM provider',
  'Provider reply received (tokens only)',
  'Original values re-hydrated server-side',
  'Final response returned to you',
];

export function PipelineDiagram() {
  return (
    <section className="card" aria-label="How PrivacyMask protects your data">
      <h2>How PrivacyMask protects your data</h2>
      <ol className="pipeline">
        {STEPS.map((step, index) => (
          <li key={step}>
            <span className="pipeline-index" aria-hidden="true">
              {index + 1}
            </span>
            <span>{step}</span>
          </li>
        ))}
      </ol>
      <p className="footnote">
        Authentication and rate limiting run before any PII processing. Providers never receive original values,
        mappings, or keys.
      </p>
    </section>
  );
}
