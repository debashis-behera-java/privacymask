# PrivacyMask

**PrivacyMask is a real-time PII anonymizer and privacy gateway for AI pipelines.** It sits between your application and external LLM providers: before customer text reaches an external AI, PrivacyMask detects personally identifiable information (PII), replaces it with opaque tokens, and keeps an AES-256-GCM-encrypted mapping so the original values can be restored in the LLM response. **External providers only ever receive masked text — never raw PII, mappings, or keys.**

## Problem

Applications increasingly send user/customer text to external LLM providers. That text often contains PII such as email addresses, phone numbers, account IDs, SSNs, credit cards, IP addresses, and URLs.

Sending raw PII to third-party AI providers creates privacy, compliance, and breach-impact risks.

PrivacyMask provides a security boundary between your application and external AI providers.

## Solution

PrivacyMask acts as a privacy gateway/proxy in front of an LLM provider:

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
  +--> Request Validation
  |
  +--> PII Detection
  |
  +--> Tokenization / Masking
  |
  +--> AES-256-GCM Encrypted Mapping
  |
  +--> LLM Provider
  |       |
  |       +--> Mock
  |       +--> OpenAI
  |       +--> Anthropic
  |
  +--> Response Re-hydration
  |
  v
Client
