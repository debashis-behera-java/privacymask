import { fetchStatus } from '../services/privacyMaskApi.js';
import { useCallback, useEffect, useRef, useState } from 'react';

function Indicator({ label, value, state }) {
  return (
    <div className={`indicator indicator-${state}`} role="status">
      <span className="indicator-label">{label}</span>
      <span className="indicator-value">{value}</span>
    </div>
  );
}

function boolText(flag) {
  return flag ? 'Configured' : 'Not configured';
}

function boolState(flag, connected) {
  if (!connected) {
    return 'neutral';
  }
  return flag ? 'good' : 'warn';
}

// Backend connectivity + safe operational state. Fetched once on mount with
// a manual refresh button; never polled. Displays only the safe fields the
// public status endpoint already exposes.
export function StatusCard() {
  const [state, setState] = useState({ phase: 'checking', status: null, detail: '' });
  const abortRef = useRef(null);

  const load = useCallback(async () => {
    if (abortRef.current) {
      abortRef.current.abort();
    }
    const controller = new AbortController();
    abortRef.current = controller;
    setState({ phase: 'checking', status: null, detail: '' });
    const result = await fetchStatus(controller.signal);
    if (controller.signal.aborted) {
      return;
    }
    if (result.outcome === 'connected') {
      setState({ phase: 'ready', status: result.status, detail: '' });
    } else {
      setState({ phase: 'failed', status: null, detail: result.detail });
    }
  }, []);

  useEffect(() => {
    load();
    return () => {
      if (abortRef.current) {
        abortRef.current.abort();
      }
    };
  }, [load]);

  const connected = state.phase === 'ready';
  const status = state.status ?? {};

  return (
    <section className="card" aria-label="Backend status">
      <div className="card-header">
        <h2>Gateway status</h2>
        <button type="button" className="btn btn-secondary btn-small" onClick={load} disabled={state.phase === 'checking'}>
          {state.phase === 'checking' ? 'Checking…' : 'Refresh'}
        </button>
      </div>
      {state.phase === 'checking' ? <p className="muted">Contacting the PrivacyMask backend…</p> : null}
      {state.phase === 'failed' ? (
        <div className="banner banner-error" role="alert">
          <strong>Backend unavailable. </strong>
          <span>{state.detail}</span>
        </div>
      ) : null}
      {connected ? (
        <div className="indicator-grid">
          <Indicator label="Backend" value={`${status.application ?? 'PrivacyMask'} · ${status.status ?? 'UP'}`} state="good" />
          <Indicator label="Version" value={String(status.version ?? 'unknown')} state="neutral" />
          <Indicator label="Security" value={boolText(status.securityConfigured)} state={boolState(status.securityConfigured, connected)} />
          <Indicator label="Rate limiting" value={boolText(status.rateLimitConfigured)} state={boolState(status.rateLimitConfigured, connected)} />
          <Indicator label="OpenAI" value={boolText(status.openaiConfigured)} state="neutral" />
          <Indicator label="Anthropic" value={boolText(status.anthropicConfigured)} state="neutral" />
        </div>
      ) : null}
    </section>
  );
}
