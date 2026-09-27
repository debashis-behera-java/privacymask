# PrivacyMask

**PrivacyMask is a real-time PII anonymizer and privacy gateway for AI pipelines.** It sits between your application and external LLM providers: before customer text reaches an external AI, PrivacyMask detects personally identifiable information (PII), replaces it with opaque tokens, and keeps an AES-256-GCM-encrypted mapping so the original values can be restored in the LLM response.

**External providers only ever receive masked text — never raw PII, mappings, or encryption keys.**

## Problem

Applications increasingly send user/customer text to external AI providers. That text can contain email addresses, phone numbers, account identifiers, credit cards, SSNs, and other sensitive information.

Sending raw PII to third-party AI providers creates privacy, compliance, and breach-impact risks.

PrivacyMask creates a security boundary between the application and the external AI provider.

## Solution

PrivacyMask acts as a privacy gateway between your application and external LLM providers.

```text
Client
  |
  v
PrivacyMask Gateway
  |
  +--> Authentication
  |
  +--> Rate Limiting
  |
  +--> Validation
  |
  +--> PII Detection
  |
  +--> Masking / Tokenization
  |
  +--> AES-256-GCM Encrypted Mapping
  |
  +--> LLM Provider
  |      |
  |      +--> Mock
  |      +--> OpenAI
  |      +--> Anthropic
  |
  +--> Response Re-hydration
  |
  v
Client

The critical privacy boundary is:

RAW PII
   |
   v
PrivacyMask
   |
   +----> MASKED TEXT ----> External LLM
   |
   +----> ENCRYPTED MAPPING remains server-side

External providers never receive raw PII, encryption keys, or token mappings.

Key Features
Real-time PII detection and masking.
Opaque replacement tokens such as {{EMAIL_001}}.
AES-256-GCM encrypted token mappings.
Server-side response re-hydration.
Mock provider for local development.
OpenAI provider support.
Anthropic provider support.
Provider boundary accepts masked/sanitized text only.
API-key authentication using X-API-Key.
Constant-time API-key comparison.
Authentication before privacy-sensitive processing.
Token-bucket rate limiting.
429 Too Many Requests with Retry-After.
Request-size validation.
Input validation.
Restricted CORS configuration.
Sanitized error responses.
Metadata-only application logging.
React/Vite playground.
Frontend API key kept in browser memory only.
No provider credentials in the frontend.
Automated backend and frontend tests.
Production-oriented security model.
Technology Stack
Backend
Java 21
Spring Boot 3.3.5
Spring WebFlux
Spring Security
Jakarta Validation
Maven
AES-256-GCM
JUnit / Spring testing infrastructure
Frontend
React 18
Vite
Vitest
Testing Library
JavaScript
Security
API-key authentication
Constant-time credential comparison
AES-256-GCM
Server-side encrypted mappings
Masked provider boundary
Restricted CORS
Rate limiting
Sanitized error envelopes
No plaintext PII logging
Architecture

The processing pipeline is intentionally ordered:

AUTH
  |
  v
RATE LIMIT
  |
  v
VALIDATION
  |
  v
DETECTION
  |
  v
MASKING
  |
  v
ENCRYPTED MAPPING
  |
  v
PROVIDER
  |
  v
RE-HYDRATION
  |
  v
CLIENT

The important security property is:

                  +----------------------+
                  |      PrivacyMask     |
                  |                      |
Raw PII --------->| Detect + Mask        |
                  |        |             |
                  |        v             |
                  | Encrypted Mapping    |
                  |        |             |
                  |        v             |
                  | Masked Provider Text |
                  +--------|-------------+
                           |
                           v
                    External LLM
Data Flow Example

Input:

Customer john@example.com needs help with his refund.

PrivacyMask detects:

EMAIL

The provider receives:

Customer {{EMAIL_001}} needs help with his refund.

The mapping is retained server-side in encrypted form.

Conceptually:

{{EMAIL_001}} -> encrypted("john@example.com")

The provider does not receive:

john@example.com

The provider does not receive:

encryption key

The provider does not receive:

mapping

After the provider responds, PrivacyMask performs server-side re-hydration before returning the response to the authorized caller.

API

Local backend URL:

http://localhost:8081
GET /api/v1/privacymask/status

Public status endpoint.

Example:

curl.exe http://localhost:8081/api/v1/privacymask/status

The status response contains application and configuration-presence information.

Provider credentials themselves are never returned.

POST /api/v1/privacymask/analyze

Protected endpoint.

Authentication:

X-API-Key

Content type:

application/json

Request:

{
  "text": "Customer john@example.com needs help with his refund.",
  "provider": "mock"
}

PowerShell:

cd C:\Users\beher\OneDrive\Desktop\PrivacyMask\privacymask-backend

curl.exe -i `
  -X POST `
  "http://localhost:8081/api/v1/privacymask/analyze" `
  -H "Content-Type: application/json" `
  -H "X-API-Key: test-service-key" `
  --data-binary "@request-valid.json"

Example response:

{
  "requestId": "...",
  "originalText": "Customer john@example.com needs help with his refund.",
  "processedText": "Customer {{EMAIL_001}} needs help with his refund.",
  "provider": "mock",
  "status": "ANALYZED",
  "detections": [
    {
      "type": "EMAIL",
      "value": "john@example.com",
      "start": 9,
      "end": 25
    }
  ],
  "response": "Customer john@example.com needs help with his refund. Mock analysis completed."
}
Current Privacy Contract

The current MVP intentionally returns:

originalText

and:

detections[].value

for development/demo transparency.

This is a documented MVP limitation.

It is not required for the provider security boundary.

The provider still receives only masked text.

The frontend does not require the raw detection values. The frontend can display:

PII type
token
character range

without receiving the original PII value.

A future version can introduce a versioned response contract that removes unnecessary raw-PII echo without silently breaking the current API.

For the current MVP, consumers should treat the API response as potentially sensitive and should not indiscriminately log or persist it.

Security Model
Authentication

Protected analysis requests require:

X-API-Key

API keys are compared using a constant-time comparison mechanism.

Missing or incorrect API keys are rejected before privacy-sensitive processing.

Expected responses:

Missing API key -> 401
Wrong API key   -> 401
Valid API key   -> processing continues
Rate Limiting

/analyze uses token-bucket rate limiting.

When the configured limit is exhausted:

HTTP 429 Too Many Requests

The response includes:

Retry-After

Unauthenticated requests do not consume an authenticated caller's quota.

The current implementation is in-memory and therefore intended for a single-instance MVP deployment.

PII Detection

The current MVP performs rule/regex-based detection.

Examples include supported sensitive values such as:

john@example.com

which becomes:

{{EMAIL_001}}

Detection metadata includes:

type
value
start
end

The current implementation does not provide full NLP-based person-name detection.

PERSON_NAME NLP detection is reserved for future scope.

Masking

Detected PII is replaced with opaque tokens.

Example:

Customer john@example.com needs help.

becomes:

Customer {{EMAIL_001}} needs help.

The token does not contain the original value.

Encryption

Token mappings use:

AES/GCM/NoPadding

with:

256-bit encryption key
fresh nonce
authenticated encryption tag
strict key validation

The configured encryption key must be Base64 that decodes to exactly:

32 bytes

which is:

256 bits

PowerShell key generation:

$b = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
[Convert]::ToBase64String($b)

Never commit the generated key.

Never place the real key inside source code.

Provider Boundary

This is the most important security property of PrivacyMask.

The provider receives:

Sanitized / masked text

The provider does NOT receive:

Raw PII

The provider does NOT receive:

Encryption keys

The provider does NOT receive:

Encrypted mappings

The provider boundary is enforced server-side.

The frontend never communicates directly with OpenAI or Anthropic.

Provider Support
Mock

The mock provider is intended for:

local development
automated tests
demonstrations
smoke testing

Example:

Customer {{EMAIL_001}} needs help with his refund.

The mock provider returns a deterministic analysis response.

OpenAI

The OpenAI provider receives masked text only.

OpenAI credentials remain backend-side.

The frontend never receives the OpenAI API key.

Anthropic

The Anthropic provider receives masked text only.

Anthropic credentials remain backend-side.

The frontend never receives the Anthropic API key.

Logging Security

Application logging is intentionally metadata-oriented.

Logs may contain:

requestId
provider
request path
HTTP status
detection count
detection type

Logs should not contain:

raw request bodies
raw PII
API keys
provider credentials
encryption keys
plaintext mappings
ciphertext mappings
Error Handling

PrivacyMask uses sanitized error responses.

Example:

{
  "timestamp": "...",
  "status": 401,
  "error": "Unauthorized",
  "message": "...",
  "path": "/api/v1/privacymask/analyze"
}

Internal stack traces and sensitive implementation details are not exposed to API consumers.

CORS

CORS is restricted to explicitly configured origins.

Example:

$env:PRIVACYMASK_CORS_ALLOWED_ORIGINS="http://localhost:5173"

Wildcard CORS is not accepted as a production-safe configuration.

Credentials are not enabled for arbitrary origins.

Environment Variables

Backend configuration includes:

PRIVACYMASK_APP_NAME
PRIVACYMASK_APP_VERSION
PRIVACYMASK_ENCRYPTION_KEY
PRIVACYMASK_MAPPING_TTL
PRIVACYMASK_MAX_TEXT_LENGTH
PRIVACYMASK_PROVIDER_TIMEOUT
PRIVACYMASK_API_KEY
PRIVACYMASK_CORS_ALLOWED_ORIGINS
PRIVACYMASK_RATE_LIMIT_ENABLED
PRIVACYMASK_RATE_LIMIT_CAPACITY
PRIVACYMASK_RATE_LIMIT_REFILL_PER_MINUTE
PRIVACYMASK_OPENAI_API_KEY
PRIVACYMASK_OPENAI_BASE_URL
PRIVACYMASK_OPENAI_MODEL
PRIVACYMASK_ANTHROPIC_API_KEY
PRIVACYMASK_ANTHROPIC_BASE_URL
PRIVACYMASK_ANTHROPIC_MODEL
PRIVACYMASK_ANTHROPIC_API_VERSION

Frontend:

VITE_PRIVACYMASK_API_URL

Use the provided .env.example files as templates.

Never commit:

.env

or real credentials.

Local Development
Prerequisites

Install:

Java 21
Maven
Node.js
npm

The current MVP does not require:

PostgreSQL
Redis
Kafka
Docker
Start Backend

Open PowerShell:

cd C:\Users\beher\OneDrive\Desktop\PrivacyMask\privacymask-backend

Set the development API key:

$env:PRIVACYMASK_API_KEY="test-service-key"

Generate a fresh development encryption key:

$b = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
$env:PRIVACYMASK_ENCRYPTION_KEY=[Convert]::ToBase64String($b)

Set CORS:

$env:PRIVACYMASK_CORS_ALLOWED_ORIGINS="http://localhost:5173"

Start:

mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"

Backend:

http://localhost:8081
Start Frontend

Open another PowerShell window:

cd C:\Users\beher\OneDrive\Desktop\PrivacyMask\frontend

Install dependencies:

npm install

Start:

npm run dev

Frontend:

http://localhost:5173
Frontend Security

The frontend:

communicates only with the PrivacyMask backend;
does not call OpenAI directly;
does not call Anthropic directly;
contains no provider credentials;
contains no encryption keys;
performs no PII detection;
keeps the entered API key in memory;
does not intentionally store the API key in localStorage;
does not intentionally store the API key in sessionStorage;
does not use cookies for the API key;
provides a clear operation for removing the in-memory key.

The frontend result UI displays masked tokens and detection metadata.

It does not need to display raw detection values.

Testing
Backend

Run:

cd privacymask-backend
mvn clean test

Verified release baseline:

Tests run: 361
Failures: 0
Errors: 0
Skipped: 0

BUILD SUCCESS

Backend coverage includes tests for:

authentication
missing API key
wrong API key
valid API key
authentication isolation
rate limiting
validation
malformed JSON
oversized requests
provider selection
provider boundary
masking
detection
encryption
invalid encryption configuration
response re-hydration
CORS
error handling
security regressions
Frontend Tests

Run:

cd frontend
npm test -- --run

Verified result:

14 passed
0 failed
Frontend Production Build

Run:

npm run build

Verified:

PASS
37 modules

The generated dist/ directory is ignored by Git.

Manual Smoke Test

Create:

privacymask-backend/request-valid.json

with:

{
  "text": "Customer john@example.com needs help with his refund.",
  "provider": "mock"
}

Check status:

curl.exe http://localhost:8081/api/v1/privacymask/status

Expected:

HTTP 200

Analyze:

curl.exe -i `
  -X POST `
  "http://localhost:8081/api/v1/privacymask/analyze" `
  -H "Content-Type: application/json" `
  -H "X-API-Key: test-service-key" `
  --data-binary "@request-valid.json"

Expected:

HTTP/1.1 200 OK

Expected masked text:

Customer {{EMAIL_001}} needs help with his refund.

Expected re-hydrated response:

Customer john@example.com needs help with his refund. Mock analysis completed.
Negative Security Tests
Test	Expected Result
No API key	401
Wrong API key	401
Invalid JSON	400
Unsupported provider	400
Oversized request	413
Provider failure	502
Encryption failure with PII	500
Rate limit exhausted	429

Security tests verify that the system does not expose:

encryption keys
provider credentials
stack traces
ciphertext
internal mappings
provider-side raw PII
Repository Hygiene

The repository ignores local/generated files such as:

target/
node_modules/
dist/
.env
logs
temporary request files
temporary encryption-key files

Before committing:

git status
git diff --check

Never commit:

real API keys
real encryption keys
provider credentials
customer data
production secrets
GitHub

Repository:

https://github.com/debashis-behera-java/PrivacyMask

Suggested GitHub description:

Privacy gateway for LLM apps: PII masking, AES-256-GCM mappings and secure response re-hydration.

Suggested topics:

java
spring-boot
spring-webflux
spring-security
react
privacy
pii
data-masking
llm
ai-security
cybersecurity
aes-gcm
rest-api
Production Considerations

The current implementation is an MVP and should not be treated as a finished multi-instance enterprise deployment.

Before production use, consider:

Replacing the in-memory mapping store with a secure distributed store.
Replacing the in-memory rate limiter with distributed rate limiting.
Using production secret management.
Using TLS through the deployment/reverse-proxy layer.
Defining data retention and deletion policies.
Adding privacy-safe audit/observability controls.
Strengthening PII detection for the application's domain.
Introducing a versioned response contract with minimal raw-PII exposure.
Adding container/orchestration deployment if required.
Performing a formal threat model and security review before handling real customer data.
Known Limitations

The following are known and intentionally deferred:

In-memory mapping store.
Single-instance in-memory rate limiter.
Regex/rule-based PII detection.
No full PERSON_NAME NLP detection.
No PostgreSQL persistence.
No Redis.
No Kafka.
No Docker deployment.
No distributed mapping storage.
No distributed rate limiting.
No policy engine.
No authenticated administrative dashboard.
TLS is expected at the deployment/reverse-proxy layer.
Development-oriented default configuration.
Current MVP response includes originalText.
Current MVP response includes detections[].value.
Versioned response contract without unnecessary raw-PII echo is deferred.

These are documented scope limitations rather than hidden defects.

Privacy Contract Decision

The current MVP deliberately preserves the existing API response contract.

The current response contains:

requestId
originalText
processedText
provider
status
detections
response

The security boundary remains:

Client
  |
  v
PrivacyMask
  |
  +--> Raw PII detected
  |
  +--> Raw PII masked
  |
  +--> Mapping encrypted
  |
  +--> Only masked text sent to provider
  |
  +--> Provider response re-hydrated server-side
  |
  v
Authorized client

The current raw-PII echo is a documented MVP/demo behavior.

It is not required for the provider privacy boundary.

A future API version can remove unnecessary raw-PII fields without breaking existing clients.

Project Structure
PrivacyMask/
|
├── privacymask-backend/
|   |
|   ├── src/
|   |   ├── main/
|   |   |   ├── java/
|   |   |   └── resources/
|   |   |
|   |   └── test/
|   |
|   ├── pom.xml
|   └── .env.example
|
├── frontend/
|   |
|   ├── src/
|   ├── package.json
|   ├── package-lock.json
|   ├── vite.config.js
|   └── .env.example
|
├── README.md
├── LICENSE
└── .gitignore
5-Minute Demo
1. Start backend
cd C:\Users\beher\OneDrive\Desktop\PrivacyMask\privacymask-backend

$env:PRIVACYMASK_API_KEY="test-service-key"

$b = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
$env:PRIVACYMASK_ENCRYPTION_KEY=[Convert]::ToBase64String($b)

$env:PRIVACYMASK_CORS_ALLOWED_ORIGINS="http://localhost:5173"

mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"
2. Start frontend
cd C:\Users\beher\OneDrive\Desktop\PrivacyMask\frontend
npm run dev
3. Open
http://localhost:5173
4. Use synthetic test data
Customer john@example.com needs help with his refund.
5. Select
Mock
6. Run analysis

The provider-facing text should be:

Customer {{EMAIL_001}} needs help with his refund.
7. Verify

The UI should show:

gateway status
masked token
detection metadata
provider
request ID
re-hydrated response

Use synthetic data for demonstrations.

Do not paste real customer information into a development environment.

Troubleshooting
401 Unauthorized

Verify:

X-API-Key

matches:

PRIVACYMASK_API_KEY
400 Invalid Request

Check:

JSON syntax
Content-Type
required text
required provider
413 Payload Too Large

The configured maximum text length was exceeded.

429 Too Many Requests

The rate limit has been exhausted.

Wait for the value specified by:

Retry-After
500 Failed to Process Request Securely

If the request contains PII, verify:

PRIVACYMASK_ENCRYPTION_KEY

is valid Base64 representing exactly 32 random bytes.

CORS Error

Set:

$env:PRIVACYMASK_CORS_ALLOWED_ORIGINS="http://localhost:5173"

Restart the backend.

Frontend Cannot Reach Backend

Verify the backend is running:

http://localhost:8081

and the frontend API configuration points to the correct backend URL.

Project Status
MVP Status: COMPLETE

PrivacyMask MVP is complete and release-ready within its documented scope.

Verified release baseline:

Backend tests       : 361 passed
Backend failures    : 0
Backend errors      : 0
Backend skipped     : 0

Frontend tests      : 14 passed
Frontend failures   : 0

Frontend build      : PASS

/status             : 200 PASS
/analyze            : 200 PASS

PII masking         : PASS
Encrypted mapping   : PASS
Provider boundary   : PASS
Response rehydrate  : PASS
Authentication      : PASS
Rate limiting       : PASS
CORS restrictions   : PASS
Release Verification

The project has been verified for:

Authentication
        |
        v
Rate Limiting
        |
        v
Request Validation
        |
        v
PII Detection
        |
        v
PII Masking
        |
        v
Encrypted Mapping
        |
        v
Provider Boundary
        |
        v
Response Re-hydration

The most important guarantee is:

RAW PII
   |
   X
External LLM

Instead:

RAW PII
   |
   v
PrivacyMask
   |
   v
MASKED TEXT
   |
   v
External LLM
Future Roadmap

Potential future versions may include:

PERSON_NAME NLP detection.
More PII types.
PostgreSQL-backed mapping storage.
Redis-based distributed rate limiting.
Distributed deployment.
Docker support.
Kubernetes deployment.
Policy engine.
Administrative dashboard.
Dashboard authentication.
Audit logging with privacy controls.
Advanced provider routing.
Versioned API.
Safer response contract without unnecessary raw-PII echo.
Enterprise secret management.
Production observability.
Multi-tenant isolation.
Reserved for Later

Explicitly not built in the current MVP:

PERSON_NAME NLP detection
policy engine
PostgreSQL persistence
dashboard authentication
Redis
Kafka
Docker deployment
distributed rate limiting
distributed mapping storage
versioned v2 response contract without raw-PII echo
License

MIT License.

See:

LICENSE
Author

Debashis Behera

PrivacyMask is a portfolio project demonstrating:

Java backend engineering
Spring Boot
Spring WebFlux
Spring Security
React frontend development
REST API design
PII detection
Data masking
AES-256-GCM encryption
API-key authentication
Rate limiting
Secure LLM integration
Provider-boundary security
Response re-hydration
Automated testing
Production-oriented security design
