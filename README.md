# PrivacyMask

**PrivacyMask is a real-time PII anonymizer and privacy gateway for AI pipelines.** It sits between your application and external LLM providers: before customer text reaches an external AI, PrivacyMask detects personally identifiable information (PII), replaces it with opaque tokens, and keeps an AES-256-GCM-encrypted mapping so the original values can be restored in the LLM response. **External providers only ever receive masked text — never raw PII, mappings, or keys.**

## Problem

Applications increasingly send user/customer text to external LLM providers. That text often contains PII — names in emails, email addresses, phone numbers, account IDs, SSNs, credit cards. Sending raw PII to third parties creates privacy, compliance, and breach-impact risks.

## Solution

PrivacyMask acts as a gateway/proxy in front of any LLM provider:

```text
Client
  -> PrivacyMask Gateway
  -> PII Detection
  -> Tokenization / Masking
  -> Encrypted Token Mapping (AES-256-GCM, server-side)
  -> Sanitized request to external/mock LLM (masked text ONLY)
  -> LLM Response (tokens only)
  -> Token Re-hydration (server-side)
  -> Client
```

The LLM never receives the original email address, phone number, or card number. `processedText` always stays masked; only the provider reply is re-hydrated, atomically.

## Key Features

Everything below exists and is regression-tested (361 backend tests, 14 frontend tests):

- **PII detection** — regex engine covering `EMAIL`, `PHONE`, `CREDIT_CARD` (Luhn-gated), `SSN`, `IP_ADDRESS`, `URL`, with exact source offsets and deterministic overlap resolution
- **Masking/tokenization** — opaque `{{TYPE_NNN}}` tokens (e.g. `{{EMAIL_001}}`), per-request sequencing, duplicates reuse the first token
- **AES-256-GCM protected mappings** — 12-byte random nonce per encryption, 128-bit auth tag, in-memory request-scoped store with TTL, fail-closed (never plaintext fallback)
- **API-key authentication** — `X-API-Key` header, constant-time comparison, stateless, `GET /status` public / `POST /analyze` protected
- **Rate limiting** — in-memory per-client token bucket, enforced after auth and before any PII work (`429` + `Retry-After`)
- **Provider abstraction** — one `LlmProvider` interface with `mock` (local, no credentials), `openai` (Responses API), `anthropic` (Messages API); case-insensitive names, no fallback, no retries, bounded timeouts
- **Response re-hydration** — server-side, request-scoped, atomic; unknown/expired tokens left untouched, never guessed
- **Reactive WebFlux pipeline** — non-blocking end to end (a source scan test forbids `block()`/`sleep()`/manual threads)
- **CORS allow-list** — exact origins only, off by default, `*` is dropped never honored, credentials never allowed
- **Request-size limits** — configurable cap with `413` before any sensitive work
- **Sanitized error responses** — uniform JSON envelope, no stack traces, no key/PII/ciphertext leakage, metadata-only logging
- **React playground** — status card, pipeline diagram, and protect-and-analyze UI; memory-only API key, backend-only calls, no provider/encryption secrets in the browser

## Technology Stack

Backend:

- Java 21
- Spring Boot 3.3.5
- Spring WebFlux (reactive, non-blocking pipeline)
- Spring Security (API-key authentication boundary)
- Jakarta Validation (request contracts)
- Maven

Frontend:

- React 18
- Vite
- Vitest + Testing Library

Security:

- AES-256-GCM (encrypted token mappings)
- API-key authentication (`X-API-Key`, constant-time comparison)
- In-memory token-bucket rate limiting
- Restricted CORS (exact origins, off by default)

## Architecture

```mermaid
flowchart TB
    UI[React Frontend\nmemory-only API key] -- "X-API-Key" --> GW[PrivacyMask Gateway\nSpring WebFlux / Netty]
    GW --> AUTH[Authentication\nAPI key, constant-time]
    AUTH --> RL[Rate Limiter\ntoken bucket per client]
    RL --> VAL[Request Validation\nshape + size cap]
    VAL --> DET[PII Detection\nregex engine]
    DET --> MASK[Tokenization / Masking\n{{TYPE_NNN}}]
    MASK --> ENC[AES-256-GCM Mapping\nserver-side only]
    ENC --> PROV[Provider Abstraction]
    PROV --> MOCK[Mock\nlocal, deterministic]
    PROV --> OAI[OpenAI\nResponses API]
    PROV --> ANT[Anthropic\nMessages API]
    MOCK & OAI & ANT --> RE[Response Rehydration\natomic, request-scoped]
    RE --> CLIENT[Client]

    style PROV fill:#e8f5e9
    note1["Raw PII NEVER crosses\nthe provider boundary"]
    note1 -.- PROV
```

Ordering guarantee: **authentication → rate limiting → validation → PII detection → masking → encrypted mapping → provider → re-hydration.** Authentication and rate limiting run before any PII processing; 401s consume no quota and 429s never reach detection, encryption, or any provider.

## Data Flow

Concrete example with `provider: "mock"`:

```text
Input (your text):
  Customer john@example.com needs help with his refund.

Masked provider input (only this leaves PrivacyMask):
  Customer {{EMAIL_001}} needs help with his refund.

Provider response (tokens only; mock echoes sanitized input + suffix):
  Customer {{EMAIL_001}} needs help with his refund. Mock analysis completed.

Final response (re-hydrated server-side):
  Customer john@example.com needs help with his refund. Mock analysis completed.
```

The `{{EMAIL_001}} → john@example.com` mapping is AES-256-GCM-encrypted and stays server-side for the life of the request (TTL-bounded). The provider never receives the mapping, the key, or the raw text.

## API Documentation

Base URL (local development): `http://localhost:8081` (see [Local Development](#local-development)).

### `GET /api/v1/privacymask/status` → `200 OK`

- **Purpose:** liveness + safe operational state for dashboards and load-balancer checks. Performs no external calls.
- **Authentication:** none required; never rate-limited.
- **Response fields:** `application`, `status`, `version`, plus presence-only booleans `openaiConfigured`, `anthropicConfigured`, `securityConfigured`, `rateLimitConfigured` (never key material or bucket internals).

```json
{
  "application": "PrivacyMask",
  "status": "UP",
  "version": "0.1.0",
  "openaiConfigured": false,
  "anthropicConfigured": false,
  "securityConfigured": true,
  "rateLimitConfigured": true
}
```

### `POST /api/v1/privacymask/analyze` → `200 OK`

- **Purpose:** run the full privacy pipeline (detect → mask → seal → provider → re-hydrate).
- **Authentication:** required — `X-API-Key` header (see below). Consumes one rate-limit token.
- **Content-Type:** `application/json`.
- **Request schema** (`AnalyzeRequest` — both fields required, non-blank; `requestId` is always generated server-side and never accepted from the client):

```json
{
  "text": "Customer john@example.com needs help with his refund.",
  "provider": "mock"
}
```

`provider` is one of `mock` / `openai` / `anthropic` (case-insensitive); anything else is rejected before any pipeline work.

- **Response schema** (`AnalyzeResponse`):

```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "originalText": "Customer john@example.com needs help with his refund.",
  "processedText": "Customer {{EMAIL_001}} needs help with his refund.",
  "provider": "mock",
  "status": "ANALYZED",
  "detections": [
    { "type": "EMAIL", "value": "john@example.com", "start": 9, "end": 25 }
  ],
  "response": "Customer john@example.com needs help with his refund. Mock analysis completed."
}
```

> Demo contract note: `originalText` and `detections[].value` are echoed for development transparency (see [Security Model](#security-model)). Token mappings (`token → originalValue`) are never part of any response, log, or file.

## Authentication

```http
X-API-Key: <your-api-key>
```

- `POST /analyze` requires the key configured via `PRIVACYMASK_API_KEY`. Missing/empty/wrong key → `401` JSON envelope, before any PII processing or provider call.
- `GET /status` is public.
- `test-service-key` appears in this repo **only as an explicitly documented local-development/test value** (backend `src/test/resources/application.yml`, frontend tests). Production must set a real secret in the environment — never commit one.

## Configuration

All backend settings are environment-overridable (`privacymask-backend/.env.example` is the copy-paste template; frontend uses `frontend/.env.example`). Every variable below is optional to *set* — the app always starts — but several are required for specific paths to succeed.

| Variable | Purpose | Required for | Default / example |
|---|---|---|---|
| `PRIVACYMASK_API_KEY` | Service credential for `POST /analyze` (`X-API-Key`) | Authenticated calls | _(empty → protected calls 401)_ |
| `PRIVACYMASK_ENCRYPTION_KEY` | AES-256 key for token mappings (**Base64 of exactly 32 random bytes**) | Any request containing PII | _(empty → PII requests fail closed 500; clean text still works)_ |
| `PRIVACYMASK_CORS_ALLOWED_ORIGINS` | Browser CORS allow-list, exact origins comma-separated | Browser (frontend) calls | _(empty → no CORS headers; dev: `http://localhost:5173`)_ |
| `PRIVACYMASK_MAPPING_TTL` | In-memory encrypted-mapping TTL | — | `10m` |
| `PRIVACYMASK_MAX_TEXT_LENGTH` | Max input length in Java chars; larger → `413` | — | `10000` |
| `PRIVACYMASK_PROVIDER_TIMEOUT` | Reactive bound per provider call; breach → `504` | — | `10s` |
| `PRIVACYMASK_RATE_LIMIT_ENABLED` | Token-bucket limiting on `POST /analyze` (`false` bypasses limiting only, never auth) | — | `true` |
| `PRIVACYMASK_RATE_LIMIT_CAPACITY` | Burst tokens (1 consumed per admitted request) | — | `120` |
| `PRIVACYMASK_RATE_LIMIT_REFILL_PER_MINUTE` | Sustained refill, continuous | — | `120` |
| `PRIVACYMASK_OPENAI_API_KEY` | OpenAI credential (backend-only) | `provider: "openai"` | _(empty → safe 502 at request time)_ |
| `PRIVACYMASK_OPENAI_BASE_URL` | Overridable API base | — | `https://api.openai.com` |
| `PRIVACYMASK_OPENAI_MODEL` | Model name | — | `gpt-4o-mini` |
| `PRIVACYMASK_ANTHROPIC_API_KEY` | Anthropic credential (backend-only) | `provider: "anthropic"` | _(empty → safe 502 at request time)_ |
| `PRIVACYMASK_ANTHROPIC_BASE_URL` | Overridable API base | — | `https://api.anthropic.com` |
| `PRIVACYMASK_ANTHROPIC_MODEL` | Model name | — | `claude-sonnet-4-20250514` |
| `PRIVACYMASK_ANTHROPIC_API_VERSION` | Messages API version header | — | `2023-06-01` |
| `PRIVACYMASK_APP_NAME` / `PRIVACYMASK_APP_VERSION` | Reported by `/status` | — | `PrivacyMask` / `0.1.0` |
| `VITE_PRIVACYMASK_API_URL` | Backend base URL baked into the frontend at build time | Frontend calls | `http://localhost:8081` |

## Encryption Key Generation

`PRIVACYMASK_ENCRYPTION_KEY` **must be the Base64 encoding of exactly 32 random bytes (256 bits).** A raw 32-character string is *not* valid (it decodes to 24 bytes) and makes every PII request fail closed with HTTP 500 — clean text keeps working, which is the tell-tale symptom.

PowerShell (using the OS CSPRNG):

```powershell
$b = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
$env:PRIVACYMASK_ENCRYPTION_KEY = [Convert]::ToBase64String($b)
```

Unix:

```bash
export PRIVACYMASK_ENCRYPTION_KEY="$(openssl rand -base64 32)"
```

Rules: never commit the key, never put it in the README, never put it in frontend code, never log it. A valid value is 44 characters ending in `=`.

## Local Development

`application.yml` ships with `server.port: 8080`, but the demonstrated local setup uses **8081** (port 8080 is occupied by Docker Desktop on the dev machine). `application.yml` is intentionally left unchanged; the port is supplied at launch.

Backend (Java 21, Maven 3.9+):

```powershell
cd privacymask-backend
$env:PRIVACYMASK_API_KEY = "test-service-key"
$env:PRIVACYMASK_ENCRYPTION_KEY = "<base64-32-byte-key>"   # see above
$env:PRIVACYMASK_CORS_ALLOWED_ORIGINS = "http://localhost:5173"
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"
```

Or build then run: `mvn clean verify` → `java -jar target/privacymask-backend-0.1.0.jar` (add `--server.port=8081` as needed).

Frontend:

```powershell
cd frontend
npm install
npm run dev      # http://localhost:5173 (strict port)
```

Local addresses: backend API `http://localhost:8081`, dashboard `http://localhost:5173`.

## Curl Examples

Status (public):

```powershell
curl.exe http://localhost:8081/api/v1/privacymask/status
```

Analyze (protected; run from `privacymask-backend/`, where `request-valid.json` holds this exact payload):

```powershell
cd privacymask-backend
curl.exe -i -X POST "http://localhost:8081/api/v1/privacymask/analyze" `
  -H "Content-Type: application/json" `
  -H "X-API-Key: test-service-key" `
  --data-binary "@request-valid.json"
```

```json
{
  "text": "Customer john@example.com needs help with his refund.",
  "provider": "mock"
}
```

Expected: `200 OK`, `processedText` containing `{{EMAIL_001}}`, and `response` with the address re-hydrated plus the mock suffix.

## Error Contract

All errors use the same JSON envelope (`timestamp`, `status`, `error`, `message`, `path`) — never stack traces, paths, keys, PII, or ciphertext.

| Status | Meaning for the client |
|---|---|
| `400` | Invalid request: missing/blank `text`/`provider`, malformed JSON, missing body, or unsupported provider (`Unsupported provider: xyz` — rejected before any pipeline work) |
| `401` | Missing/empty/wrong `X-API-Key`, or no key configured server-side. Returned before rate limiting and all PII work (unauthenticated wrong-method calls also surface as 401, never reach the pipeline) |
| `404` | Unknown route |
| `405` | Wrong HTTP method on a known route (once authenticated) |
| `413` | Input exceeds `PRIVACYMASK_MAX_TEXT_LENGTH`; rejected before detection/encryption/provider work, never logged |
| `415` | Wrong `Content-Type` (must be `application/json`) |
| `429` | Rate limit exhausted: JSON body plus `Retry-After` seconds header; nothing downstream ran |
| `500` | Secure-processing failure (typically encryption/mapping misconfiguration, e.g. a malformed `PRIVACYMASK_ENCRYPTION_KEY`): `"Failed to process request securely."` Nothing partial is returned |
| `502` | Provider failure or missing provider key: `"LLM provider request failed."` Upstream bodies are consumed and discarded; never retried, never replaced with raw input |
| `504` | Provider call exceeded `PRIVACYMASK_PROVIDER_TIMEOUT`: `"LLM provider request timed out."` |

## Providers

One `LlmProvider` abstraction (`complete(LlmRequest) → Mono<LlmResponse>`), resolved per request by `LlmProviderRegistry` (case-insensitive; unknown names → `400`). Providers accept a `SanitizedText` wrapper built **only** from masked pipeline output, so raw text cannot be passed by accident.

- **`mock`** — local and deterministic: echoes the sanitized input plus `" Mock analysis completed."` No network, no credentials. Ideal for development and tests.
- **`openai`** — Responses API (`POST {base-url}/v1/responses`, `{model, input}`), key via `Authorization: Bearer`. Needs `PRIVACYMASK_OPENAI_API_KEY`.
- **`anthropic`** — Messages API (`POST {base-url}/v1/messages`, `{model, max_tokens, messages:[{role:"user", content}]}`), key via `x-api-key` + `anthropic-version` headers. Needs `PRIVACYMASK_ANTHROPIC_API_KEY`.

For real providers: keys stay backend-only (headers only, never bodies/logs/errors/status); missing keys fail at request time with safe `502`s (the app still starts keyless); upstream 4xx/5xx map to safe `502`s, timeouts to `504`s. **No automatic fallback to another provider and no retries — ever.** Deterministic local-HTTP tests prove each provider boundary; live probes (`RealOpenAiIT`/`RealAnthropicIT`) are opt-in only and never run in CI.

## Security Model

- **Authentication before everything:** the Spring Security chain rejects unauthorized callers with JSON `401` before detection, masking, encryption, mapping, or any provider call.
- **Rate limiting before PII work:** exhausted quota → `429` + `Retry-After`; identity is the SHA-256 digest of the API key (raw credential never stored/logged/sent).
- **Request-size validation** before expensive or sensitive work (`413`).
- **PII masking before provider calls**, enforced by type (`SanitizedText`), proven per provider by boundary tests.
- **AES-256-GCM mapping protection** (fresh 12-byte nonce, 128-bit tag, strict 32-byte Base64 key, fail-closed) with request-scoped, TTL-bounded, in-memory storage.
- **Server-side mappings and re-hydration** — atomic (any decryption failure fails the whole request), unknown/expired tokens left as-is.
- **Sanitized errors and metadata-only logging** — `requestId`, provider, counts, type names, timing; never bodies, values, keys, headers, or mappings.
- **Restricted CORS** — off by default; exact origins only; credentials never allowed.
- **Provider credentials never reach the frontend** — the browser calls the backend only, holds the service API key in memory only (no storage/cookies/logs), and performs no PII detection.

**Known demo limitation:** the current `/analyze` response echoes `originalText` and `detections[].value` for development transparency. This is a deliberate, documented demo contract — do not treat it as the final production privacy boundary (a future versioned response should carry only type/start/end/token metadata). Mappings, keys, and provider internals are never exposed regardless.

Threat coverage includes: unauthorized API access (API key), credential leakage (env-only + no logging/reflection), PII processing by unauthorized callers (auth-first ordering), timing side-channels (constant-time compare), credential exfiltration to providers (layer isolation), API abuse (per-client token bucket), quota draining by unauthenticated callers (401s consume nothing), and prompt-injection mapping disclosure (providers never receive the mapping, so there is nothing to disclose).

## Testing

Backend (`mvn clean test`; live-provider probes excluded by name, opt-in only):

```powershell
cd privacymask-backend
mvn clean test
```

Current baseline: **361 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS.**

Frontend (Vitest + Testing Library — memory-only keys, no provider-direct calls, 401/429/413 handling):

```powershell
cd frontend
npm test -- --run     # 14 passed
npm run build         # production build, dist/
```

## Project Structure

```text
PrivacyMask/
├── README.md                      # this file
├── .gitignore                     # secrets, build outputs, local run artifacts
├── privacymask-backend/
│   ├── pom.xml                    # Spring Boot 3.3.x, WebFlux, Security, Validation
│   ├── .env.example               # backend env template (placeholders only)
│   ├── request-valid.json         # local smoke-test payload (example PII)
│   ├── src/main/java/com/privacymask/
│   │   ├── PrivacyMaskApplication.java
│   │   ├── config/ controller/ service/ dto/ model/
│   │   ├── detection/ masking/ encryption/ mapping/ rehydration/
│   │   ├── processing/ llm/ request/ security/ ratelimit/ exception/
│   ├── src/main/resources/application.yml
│   └── src/test/java/com/privacymask/  # 68 test classes (+ test dummy config only)
├── frontend/
│   ├── package.json vite.config.js index.html .env.example
│   └── src/  App.jsx main.jsx index.css components/ services/ test/
└── (no docs/ scripts/ docker/ — intentionally minimal; no Dockerfiles yet)
```

## Troubleshooting

- **Backend unavailable** (`Could not reach…`, `Start the backend on port 8081`): is the backend running? Right port (8081 vs yml default 8080)? Does `VITE_PRIVACYMASK_API_URL` match the backend address (rebuild/reload frontend after changing it)?
- **`401 Authentication failed`**: is `X-API-Key` sent? Does it match server-side `PRIVACYMASK_API_KEY`? Is any key configured server-side at all?
- **`500 Failed to process request securely.`** on PII input while clean text works: `PRIVACYMASK_ENCRYPTION_KEY` is missing or malformed — it must be Base64 of exactly 32 random bytes (a raw 32-char string decodes to 24 bytes and is rejected). Regenerate per [Encryption Key Generation](#encryption-key-generation) and restart.
- **CORS / browser blocked, curl works**: set `PRIVACYMASK_CORS_ALLOWED_ORIGINS` to exactly `http://localhost:5173` (no trailing slash, no `*`) and restart the backend. Never use `*`.
- **`429 Rate limit exceeded`**: wait the `Retry-After` seconds and retry; lower traffic or raise `PRIVACYMASK_RATE_LIMIT_*` for dev.
- **Provider `502`/`504`**: for `openai`/`anthropic`, is the provider key set backend-side? Is the base URL reachable? `mock` needs nothing — use it to isolate gateway vs provider issues.
- Never "fix" any of the above by disabling authentication, rate limiting, encryption, or CORS — each symptom is the security control working as designed.

## Known Limitations

These are verified, deliberate boundaries of the MVP — deferred production-scale features, not bugs:

- **In-memory mapping store** — encrypted token mappings live in a request-scoped, TTL-bounded in-memory store. No PostgreSQL, no persistence across restarts.
- **Single-instance rate limiter** — the token bucket is instance-local. No Redis or shared state; do not run multiple replicas without replacing it.
- **No Kafka, no Docker, no AWS** — intentionally minimal footprint; no Dockerfiles yet.
- **No `PERSON_NAME` NLP detection** — the regex engine covers `EMAIL`, `PHONE`, `CREDIT_CARD` (Luhn-gated), `SSN`, `IP_ADDRESS`, `URL` only.
- **Demo response contract** — `/analyze` still echoes `originalText` and `detections[].value` for development transparency (see [Security Model](#security-model)). A production contract should be versioned and carry only type/start/end/token metadata.
- **No policy engine, no dashboard auth** — the frontend is an unauthenticated local-development playground; the service API key is its only credential.
- **TLS expected at reverse proxy** — the application serves plain HTTP locally.
- **Local/development-oriented configuration** — default ports, TTLs, timeouts, and rate-limit capacities suit local runs; tighten them per environment.

## Production Considerations

This repository is a portfolio MVP, not a deployed production system. Before any production use:

- **Secrets via environment only** — `PRIVACYMASK_API_KEY`, `PRIVACYMASK_ENCRYPTION_KEY`, and provider keys must come from a secret manager/environment, never from committed files. Rotate the encryption key handling with care: previously sealed mappings are in-memory and TTL-bounded, so rotation only affects in-flight requests.
- **Single-instance limits** — rate limiting and the encrypted-mapping store are in-memory and instance-local. Do not run multiple replicas behind a load balancer without replacing them with shared state (e.g. Redis) and re-testing the auth → rate-limit → pipeline ordering.
- **TLS termination** — the app serves plain HTTP; terminate TLS in front of it (reverse proxy / load balancer) so API keys and response bodies are encrypted in transit.
- **Tighten rate limits** — defaults (`120` burst / `120` per minute) are development-oriented; set `PRIVACYMASK_RATE_LIMIT_*` for your traffic profile.
- **CORS allow-list** — configure exactly the origins you serve the frontend from; `*` is dropped by design and credentials are never allowed.
- **Response contract** — the current `/analyze` response echoes `originalText` and `detections[].value` as a documented demo limitation. A production contract should be versioned (e.g. `/api/v2/...`) and carry only type/start/end/token metadata.
- **Observability** — logs are metadata-only by design; aggregate them centrally and alert on `429`/`502`/`504`/`500` rates rather than adding body/PII logging.

## 5-Minute Demo

1. Start the backend on 8081 (with API key + valid Base64 encryption key) and the frontend on 5173.
2. Open the dashboard — the Gateway status card should show Backend UP.
3. In the Playground, enter the API key, keep provider `mock`, and type `Contact john@example.com about card 4111 1111 1111 1111.` (or use a Test-data button).
4. Click **Protect & Analyze**.
5. Point out the masked provider text (`{{EMAIL_001}}`, `{{CREDIT_CARD_002}}`), the detection chips (type + char ranges), and the re-hydrated final response.
6. Explain the punchline: the provider received only the masked text; originals were AES-256-GCM-sealed server-side and restored after the provider replied.

## Delivery History (condensed)

| Phase | Delivered |
|---|---|
| 1 | Reactive WebFlux foundation, versioned REST API, centralized errors, `privacymask.*` config |
| 2 | `POST /analyze` gateway, validation, server-side `requestId`, `mock`-only provider check, secure logging |
| 3 | Regex PII detection engine (6 types, Luhn-gated cards, offsets, overlap resolution) |
| 4 | Tokenization/masking pipeline (`{{TYPE_NNN}}`, dedup, order-preserving) |
| 5 | AES-256-GCM encrypted token mappings, request-scoped TTL store, fail-closed sealing |
| 6 | `LlmProvider` abstraction + `SanitizedText` boundary + `MockLlmProvider` |
| 7 | Atomic server-side response re-hydration |
| 8 | Request lifecycle (size cap → provider check → pipeline → bounded call), 413/502/504 semantics, concurrency isolation |
| 9 | OpenAI provider (Responses API, masked-only, safe 502/504) |
| 10 | Anthropic provider (Messages API, same boundary guarantees) |
| 11 | API-key authentication (`X-API-Key`, constant-time, stateless, public status) |
| 12 | Token-bucket rate limiting (auth → limit → pipeline; `429` + `Retry-After`) |
| 13 | React playground (status card, pipeline diagram, playground, memory-only keys, backend-only calls) + opt-in browser CORS |
| 14 | Production hardening: wildcard-CORS rejection, backend `.env.example`, gitignore hygiene, invalid-key regression test |

Reserved for later (explicitly not built): `PERSON_NAME` NLP detection, policy engine, PostgreSQL persistence, dashboard auth, Redis/Kafka/Docker, and a v2 response contract without raw-PII echo.
#   P r i v a c y M a s k  
 