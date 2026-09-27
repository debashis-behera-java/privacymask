// PrivacyMask API service (Phase 13).
//
// The browser talks to the PrivacyMask backend ONLY. It never calls OpenAI,
// Anthropic, or any other provider directly, and it never handles provider
// secrets or encryption keys. Request/response bodies may contain PII, so
// this module never logs them and never persists anything.

export const STATUS_PATH = '/api/v1/privacymask/status';
export const ANALYZE_PATH = '/api/v1/privacymask/analyze';

export function apiBaseUrl() {
  const configured = import.meta.env.VITE_PRIVACYMASK_API_URL;
  const base = (typeof configured === 'string' && configured.trim() !== '' ? configured : 'http://localhost:8081').trim();
  return base.endsWith('/') ? base.slice(0, -1) : base;
}

function safeErrorMessage(data, fallback) {
  if (data && typeof data === 'object' && typeof data.message === 'string' && data.message.trim() !== '') {
    return data.message;
  }
  return fallback;
}

async function readJson(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

/**
 * GET the public status endpoint. No credentials needed.
 * Returns { outcome: 'connected'|'unavailable', status?, detail? }.
 */
export async function fetchStatus(signal) {
  const url = apiBaseUrl() + STATUS_PATH;
  let response;
  try {
    response = await fetch(url, { method: 'GET', headers: { Accept: 'application/json' }, signal });
  } catch (error) {
    if (error && error.name === 'AbortError') {
      return { outcome: 'unavailable', detail: 'Status request was cancelled.' };
    }
    return { outcome: 'unavailable', detail: 'Backend unavailable. Start the backend on port 8081.' };
  }
  if (!response.ok) {
    return { outcome: 'unavailable', detail: `Backend returned HTTP ${response.status}.` };
  }
  const data = await readJson(response);
  if (!data || typeof data !== 'object') {
    return { outcome: 'unavailable', detail: 'Backend returned an unexpected status payload.' };
  }
  return { outcome: 'connected', status: data };
}

/**
 * POST text to the protected analyze endpoint.
 * Returns a discriminated result; see RequestState in App for rendering.
 */
export async function analyzeText({ text, provider, apiKey, signal }) {
  const url = apiBaseUrl() + ANALYZE_PATH;
  let response;
  try {
    response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'X-API-Key': apiKey },
      body: JSON.stringify({ text, provider }),
      signal,
    });
  } catch (error) {
    if (error && error.name === 'AbortError') {
      return { kind: 'unknown', title: 'Request cancelled', detail: 'The request was cancelled.' };
    }
    return {
      kind: 'unavailable',
      title: 'Backend unavailable',
      detail: 'Could not reach the PrivacyMask backend. Start it on port 8081 and retry.',
    };
  }

  if (response.status === 401) {
    return {
      kind: 'unauthorized',
      title: 'Authentication failed',
      detail: 'Check your PrivacyMask API key and retry.',
    };
  }
  if (response.status === 429) {
    const retryAfter = response.headers.get('Retry-After');
    const seconds = retryAfter !== null && retryAfter.trim() !== '' && !Number.isNaN(Number(retryAfter)) ? Number(retryAfter) : null;
    return {
      kind: 'rate-limited',
      title: 'Rate limit exceeded',
      detail:
        seconds === null
          ? 'The backend rate limiter rejected this request. Wait a moment and retry.'
          : `The backend rate limiter rejected this request. Please retry after approximately ${seconds} second${seconds === 1 ? '' : 's'}.`,
      retryAfterSeconds: seconds,
    };
  }
  if (response.status === 413) {
    return {
      kind: 'too-large',
      title: 'Request too large',
      detail: 'The backend rejected this request for exceeding its size limit. Shorten the input and retry.',
    };
  }
  if (response.status === 400) {
    const data = await readJson(response);
    return { kind: 'bad-request', title: 'Invalid request', detail: safeErrorMessage(data, 'The backend rejected this request.') };
  }
  if (response.status === 502 || response.status === 504) {
    return {
      kind: 'provider-error',
      title: 'Provider error',
      detail: 'The selected LLM provider failed. No raw text was sent. Try again or select another provider.',
    };
  }
  if (!response.ok) {
    return {
      kind: 'unknown',
      title: 'Unexpected error',
      detail: `The backend returned HTTP ${response.status}.`,
    };
  }
  const data = await readJson(response);
  if (!data || typeof data !== 'object') {
    return { kind: 'unknown', title: 'Unexpected error', detail: 'The backend returned an unexpected payload.' };
  }
  return { kind: 'success', data };
}
