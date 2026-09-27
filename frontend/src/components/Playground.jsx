import { analyzeText } from '../services/privacyMaskApi.js';
import { useRef, useState } from 'react';
import { ResultPanel } from './ResultPanel.jsx';

const PROVIDERS = ['mock', 'openai', 'anthropic'];

const EXAMPLES = [
  {
    label: 'Email + phone',
    text: 'Hello, my name is John.\nMy email is john@example.com.\nMy phone number is +91 9876543210.',
  },
  {
    label: 'Card + SSN',
    text: 'Customer john@example.com has card 4111 1111 1111 1111 and SSN 123-45-6789.',
  },
];

function ErrorBanner({ result, onDismiss }) {
  if (!result || result.kind === 'success' || result.kind === 'idle' || result.kind === 'loading') {
    return null;
  }
  return (
    <div className="banner banner-error" role="alert">
      <div>
        <strong>{result.title}. </strong>
        <span>{result.detail}</span>
      </div>
      <button type="button" className="btn btn-secondary btn-small" onClick={onDismiss}>
        Dismiss
      </button>
    </div>
  );
}

// The PrivacyMask playground: text in, protected result out. Everything is
// memory-only — input, API key, and responses are never persisted (no
// localStorage/sessionStorage/cookies) and never logged.
export function Playground() {
  const [text, setText] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [provider, setProvider] = useState('mock');
  const [phase, setPhase] = useState('idle');
  const [result, setResult] = useState(null);
  const abortRef = useRef(null);

  const loading = phase === 'loading';

  async function handleSubmit(event) {
    event.preventDefault();
    if (loading) {
      return;
    }
    if (text.trim() === '') {
      setResult({ kind: 'bad-request', title: 'Input required', detail: 'Enter some text before analyzing.' });
      return;
    }
    if (apiKey.trim() === '') {
      setResult({
        kind: 'unauthorized',
        title: 'API key required',
        detail: 'Enter your PrivacyMask API key. It stays in memory only and is sent solely to the backend.',
      });
      return;
    }
    if (abortRef.current) {
      abortRef.current.abort();
    }
    const controller = new AbortController();
    abortRef.current = controller;
    setPhase('loading');
    setResult(null);
    const outcome = await analyzeText({ text, provider, apiKey, signal: controller.signal });
    if (controller.signal.aborted) {
      return;
    }
    setResult(outcome);
    setPhase('idle');
  }

  function handleClear() {
    if (abortRef.current) {
      abortRef.current.abort();
    }
    setText('');
    setApiKey('');
    setProvider('mock');
    setResult(null);
    setPhase('idle');
  }

  return (
    <section className="card" aria-label="PrivacyMask playground">
      <h2>Playground</h2>
      <form onSubmit={handleSubmit}>
        <label htmlFor="pg-input">Text to protect</label>
        <textarea
          id="pg-input"
          rows={7}
          value={text}
          onChange={(event) => setText(event.target.value)}
          placeholder={'Contact john@example.com about card 4111 1111 1111 1111.'}
          disabled={loading}
        />
        <div className="example-row">
          <span className="muted">Test data:</span>
          {EXAMPLES.map((example) => (
            <button
              key={example.label}
              type="button"
              className="btn btn-secondary btn-small"
              disabled={loading}
              onClick={() => setText(example.text)}
            >
              {example.label}
            </button>
          ))}
        </div>

        <div className="form-grid">
          <div>
            <label htmlFor="pg-key">PrivacyMask API Key</label>
            <input
              id="pg-key"
              type="password"
              autoComplete="off"
              value={apiKey}
              onChange={(event) => setApiKey(event.target.value)}
              placeholder="Enter service API key"
              disabled={loading}
            />
            <p className="footnote">Memory only. Never stored, never logged, sent only to the backend.</p>
          </div>
          <div>
            <label htmlFor="pg-provider">LLM provider</label>
            <select id="pg-provider" value={provider} onChange={(event) => setProvider(event.target.value)} disabled={loading}>
              {PROVIDERS.map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </select>
            <p className="footnote">Provider credentials stay on the backend. The browser never calls providers.</p>
          </div>
        </div>

        <div className="button-row">
          <button type="submit" className="btn btn-primary" disabled={loading}>
            {loading ? 'Protecting…' : 'Protect & Analyze'}
          </button>
          <button type="button" className="btn btn-secondary" onClick={handleClear} disabled={loading && text === '' && apiKey === ''}>
            Clear
          </button>
        </div>
      </form>

      {loading ? (
        <p className="loading" role="status" aria-live="polite">
          <span className="spinner" aria-hidden="true" /> Protecting text via the PrivacyMask gateway…
        </p>
      ) : null}

      <ErrorBanner result={result} onDismiss={() => setResult(null)} />
      <ResultPanel result={result} />
    </section>
  );
}
