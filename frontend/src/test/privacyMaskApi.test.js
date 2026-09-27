import { analyzeText, apiBaseUrl, fetchStatus } from '../services/privacyMaskApi.js';
import { beforeEach, describe, expect, it, vi } from 'vitest';

function jsonResponse(status, body, headers = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name) => headers[name] ?? null },
    json: async () => body,
  };
}

describe('privacyMaskApi service', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
  });

  it('targets the configured backend base URL only', async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse(200, { status: 'UP' }));
    await fetchStatus(undefined);
    const url = vi.mocked(fetch).mock.calls[0][0];
    expect(String(url).startsWith(apiBaseUrl())).toBe(true);
    expect(String(url)).not.toMatch(/openai|anthropic/i);
  });

  it('maps a successful analysis', async () => {
    const data = { status: 'ANALYZED', processedText: 'Hi {{EMAIL_001}}.', response: 'Hi john done.' };
    vi.mocked(fetch).mockResolvedValue(jsonResponse(200, data));
    const result = await analyzeText({ text: 'Hi john@example.com.', provider: 'mock', apiKey: 'k' });
    expect(result).toEqual({ kind: 'success', data });
    const [, options] = vi.mocked(fetch).mock.calls[0];
    expect(options.headers['X-API-Key']).toBe('k');
    expect(JSON.parse(options.body)).toEqual({ text: 'Hi john@example.com.', provider: 'mock' });
  });

  it('maps 401 without reflecting the key', async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse(401, { message: 'Unauthorized' }));
    const result = await analyzeText({ text: 'x', provider: 'mock', apiKey: 'secret-key' });
    expect(result.kind).toBe('unauthorized');
    expect(JSON.stringify(result)).not.toContain('secret-key');
  });

  it('maps 429 with Retry-After seconds', async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse(429, { message: 'Too many requests.' }, { 'Retry-After': '7' }));
    const result = await analyzeText({ text: 'x', provider: 'mock', apiKey: 'k' });
    expect(result.kind).toBe('rate-limited');
    expect(result.retryAfterSeconds).toBe(7);
    expect(result.detail).toContain('7 seconds');
  });

  it('maps 429 without Retry-After', async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse(429, { message: 'Too many requests.' }));
    const result = await analyzeText({ text: 'x', provider: 'mock', apiKey: 'k' });
    expect(result.kind).toBe('rate-limited');
    expect(result.retryAfterSeconds).toBeNull();
  });

  it('maps 413, provider errors, and network failure', async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse(413, {}));
    expect((await analyzeText({ text: 'x', provider: 'mock', apiKey: 'k' })).kind).toBe('too-large');
    vi.mocked(fetch).mockResolvedValue(jsonResponse(502, {}));
    expect((await analyzeText({ text: 'x', provider: 'openai', apiKey: 'k' })).kind).toBe('provider-error');
    vi.mocked(fetch).mockRejectedValue(new TypeError('network down'));
    expect((await analyzeText({ text: 'x', provider: 'mock', apiKey: 'k' })).kind).toBe('unavailable');
  });

  it('reports safe status payloads', async () => {
    vi.mocked(fetch).mockResolvedValue(
      jsonResponse(200, { application: 'PrivacyMask', status: 'UP', securityConfigured: true }),
    );
    const result = await fetchStatus(undefined);
    expect(result.outcome).toBe('connected');
    expect(result.status.securityConfigured).toBe(true);
  });
});
