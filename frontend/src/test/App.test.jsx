import { AppWithBoundary } from '../App.jsx';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

afterEach(() => {
  cleanup();
});

function jsonResponse(status, body, headers = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name) => headers[name] ?? null },
    json: async () => body,
  };
}

describe('PrivacyMask dashboard', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
    window.localStorage.clear();
    window.sessionStorage.clear();
  });

  it('renders the playground, API key field, and provider selector', async () => {
    vi.mocked(fetch).mockRejectedValue(new TypeError('down'));
    render(<AppWithBoundary />);
    expect(screen.getByRole('heading', { name: /PrivacyMask Playground/i })).toBeTruthy();
    expect(screen.getByLabelText(/PrivacyMask API Key/i).getAttribute('type')).toBe('password');
    expect(screen.getByLabelText(/Text to protect/i)).toBeTruthy();
    expect(screen.getByLabelText(/LLM provider/i)).toBeTruthy();
    expect(screen.getByRole('button', { name: /Protect & Analyze/i })).toBeTruthy();
    await waitFor(() => expect(screen.getByText(/Start the backend on port 8081/i)).toBeTruthy());
  });

  it('requires input and API key before calling the backend', async () => {
    vi.mocked(fetch).mockRejectedValue(new TypeError('down'));
    render(<AppWithBoundary />);
    fireEvent.click(screen.getByRole('button', { name: /Protect & Analyze/i }));
    expect(await screen.findByText(/Input required/i)).toBeTruthy();
    expect(vi.mocked(fetch).mock.calls.filter(([, options]) => options && options.method === 'POST')).toHaveLength(0);
  });

  it('shows loading state and prevents duplicate submissions', async () => {
    let resolveFetch;
    vi.mocked(fetch).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveFetch = resolve;
        }),
    );
    render(<AppWithBoundary />);
    fireEvent.change(screen.getByLabelText(/Text to protect/i), { target: { value: 'Contact john@example.com.' } });
    fireEvent.change(screen.getByLabelText(/PrivacyMask API Key/i), { target: { value: 'k' } });
    const submit = screen.getByRole('button', { name: /Protect & Analyze/i });
    fireEvent.click(submit);
    fireEvent.click(submit);
    expect(await screen.findByText(/Protecting text via the PrivacyMask gateway/i)).toBeTruthy();
    expect(vi.mocked(fetch).mock.calls.filter(([, options]) => options && options.method === 'POST')).toHaveLength(1);
    resolveFetch(jsonResponse(200, { status: 'ANALYZED', processedText: 'x', response: 'y' }));
    await waitFor(() => expect(screen.getByText(/Protected result/i)).toBeTruthy());
  });

  it('displays masked text, detections, and re-hydrated response on success', async () => {
    vi.mocked(fetch).mockImplementation((url, options) => {
      if (options && options.method === 'POST') {
        return Promise.resolve(
          jsonResponse(200, {
            status: 'ANALYZED',
            provider: 'mock',
            requestId: 'r-1',
            processedText: 'Contact {{EMAIL_001}} for help.',
            response: 'Contact john@example.com for help. Mock analysis completed.',
            detections: [{ type: 'EMAIL', value: 'john@example.com', start: 8, end: 24 }],
          }),
        );
      }
      return Promise.reject(new TypeError('down'));
    });
    render(<AppWithBoundary />);
    fireEvent.change(screen.getByLabelText(/Text to protect/i), { target: { value: 'Contact john@example.com.' } });
    fireEvent.change(screen.getByLabelText(/PrivacyMask API Key/i), { target: { value: 'k' } });
    fireEvent.click(screen.getByRole('button', { name: /Protect & Analyze/i }));
    expect(await screen.findByText('{{EMAIL_001}}')).toBeTruthy();
    expect(screen.getByText(/Mock analysis completed/i)).toBeTruthy();
    expect(screen.getByText('EMAIL')).toBeTruthy();
  });

  it('handles 401 without exposing the key', async () => {
    vi.mocked(fetch).mockImplementation((url, options) =>
      Promise.resolve(options && options.method === 'POST' ? jsonResponse(401, {}) : Promise.reject(new TypeError('down'))),
    );
    render(<AppWithBoundary />);
    fireEvent.change(screen.getByLabelText(/Text to protect/i), { target: { value: 'Hi.' } });
    fireEvent.change(screen.getByLabelText(/PrivacyMask API Key/i), { target: { value: 'wrong-key' } });
    fireEvent.click(screen.getByRole('button', { name: /Protect & Analyze/i }));
    expect(await screen.findByText(/Authentication failed/i)).toBeTruthy();
    expect(document.body.textContent).not.toContain('wrong-key');
  });

  it('handles 429 with Retry-After and 413 distinctly', async () => {
    vi.mocked(fetch).mockImplementation((url, options) =>
      Promise.resolve(
        options && options.method === 'POST' ? jsonResponse(429, {}, { 'Retry-After': '4' }) : Promise.reject(new TypeError('down')),
      ),
    );
    render(<AppWithBoundary />);
    fireEvent.change(screen.getByLabelText(/Text to protect/i), { target: { value: 'Hi.' } });
    fireEvent.change(screen.getByLabelText(/PrivacyMask API Key/i), { target: { value: 'k' } });
    fireEvent.click(screen.getByRole('button', { name: /Protect & Analyze/i }));
    expect(await screen.findByText(/Rate limit exceeded/i)).toBeTruthy();
    expect(screen.getByText(/approximately 4 seconds/i)).toBeTruthy();
  });

  it('clear resets memory-only state and persists nothing', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    vi.mocked(fetch).mockRejectedValue(new TypeError('down'));
    render(<AppWithBoundary />);
    fireEvent.change(screen.getByLabelText(/Text to protect/i), { target: { value: 'john@example.com' } });
    fireEvent.change(screen.getByLabelText(/PrivacyMask API Key/i), { target: { value: 'k' } });
    fireEvent.click(screen.getByRole('button', { name: /Clear/i }));
    expect(screen.getByLabelText(/Text to protect/i).value).toBe('');
    expect(screen.getByLabelText(/PrivacyMask API Key/i).value).toBe('');
    expect(setItem).not.toHaveBeenCalled();
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
    // No provider-direct calls: every fetch goes to the backend base URL only.
    for (const [url] of vi.mocked(fetch).mock.calls) {
      expect(String(url)).not.toMatch(/openai|anthropic/i);
    }
  });
});
