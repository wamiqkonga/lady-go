# SMS provider setup (OTP delivery)

Status: adapter built. **Pilot channel decided 2026-10-17: WhatsApp (Meta Cloud API) instead of SMS** -
see "WhatsApp (pilot channel)" below; no DLT needed on that path. The SMS sections further down stay
valid for a later SMS fallback at public launch. The class names say "Sms" but the adapter is a generic
HTTP JSON sender and carries either channel.

## WhatsApp (pilot channel)

No code beyond the generic adapter: dot-path response parsing (`messages.0.id`, `error.code`) and
optional sender/DLT ids make it fit Meta's WhatsApp Cloud API through configuration alone.

Operational prerequisites (founder, start now - verification takes days): a Meta Business account with
business verification, a WhatsApp Business phone number (not one already used in the consumer app), a
System User access token (permanent, `whatsapp_business_messaging` scope), and an approved
**Authentication**-category message template (e.g. `ladygo_otp`, language `en`) with the copy-code button.
Authentication templates are the cheapest category and need no free-text approval of the OTP wording.

| Variable | Value |
|---|---|
| `LADYGO_SMS_BASE_URL` | `https://graph.facebook.com/v21.0/<PHONE_NUMBER_ID>/messages` |
| `LADYGO_SMS_AUTH_HEADER_NAME` | `Authorization` |
| `LADYGO_SMS_AUTH_HEADER_VALUE` | `Bearer <system user token>` - secret |
| `LADYGO_SMS_SENDER_ID`, `_DLT_TEMPLATE_ID`, `_DLT_ENTITY_ID` | leave unset (blank) |
| `LADYGO_SMS_RESPONSE_MESSAGE_ID_FIELD` | `messages.0.id` |
| `LADYGO_SMS_RESPONSE_ERROR_CODE_FIELD` | `error.code` |
| `LADYGO_SMS_BODY_TEMPLATE` | see below |

Body template (Meta's authentication-template shape; `{{to}}` must be digits with country code - Meta
accepts the E.164 `+` prefix too):

```json
{"messaging_product":"whatsapp","to":"{{to}}","type":"template",
 "template":{"name":"ladygo_otp","language":{"code":"en"},
  "components":[
   {"type":"body","parameters":[{"type":"text","text":"{{var:code}}"}]},
   {"type":"button","sub_type":"url","index":"0","parameters":[{"type":"text","text":"{{var:code}}"}]}]}}
```

Notes:
- Meta returns HTTP 200 with a `messages[0].id` once it *accepts* the message; actual delivery (user not
  on WhatsApp, blocked, etc.) is reported later by webhook, which this adapter does not consume. A rider
  or driver without WhatsApp on the registered number gets no code - acceptable for a known pilot cohort,
  and an SMS fallback (second sender behind the same interface) is a public-launch item.
- Retry policy (5xx/timeout only, never 4xx) and the circuit breaker apply unchanged. Meta rate-limit
  errors arrive as 4xx (`130429`), so they are not retried; the per-number/IP/global send caps below still
  bound volume.
- Verify the template name, language and component layout against the approved template in Meta's
  dashboard before first send - a mismatch is a 4xx (`132000`/`132001`) and the circuit breaker will not
  open on a single one, but every login will fail until corrected.
- Not yet exercised against the live API (no credentials yet); adapter behaviour is covered by stub-server
  tests with Meta-shaped responses.

## Architecture

`SmsSender` (`backend/src/main/java/com/ladygo/notifications/sms/SmsSender.java`) is the only interface
anything in the backend uses to send an SMS: `send(toE164, templateKey, variables)`. Two implementations:

- `LoggingSmsSender` - dev/test only (`@Profile({"dev","test"})`). Logs the rendered template to the
  console instead of contacting a carrier. Structurally impossible to activate in staging/prod (profile
  annotation), double-checked at boot by `SmsSenderStartupGuard`.
- `HttpSmsSender` - the real, generic adapter (`@Profile("!dev & !test")`). Every provider-specific
  detail - endpoint, auth header, request body shape, sender id, DLT ids - comes from configuration
  below, never from code. Choosing a provider means filling in env vars, not writing a new class.

`SmsSenderStartupGuard` refuses to start staging/prod if `LoggingSmsSender` is somehow still active, or
if `HttpSmsSender`'s required configuration is missing - same defence-in-depth pattern as
`EligibilityPolicy`/`DevOnlyFlagsStartupGuard`.

## Environment variables

All `LADYGO_SMS_*`. Secrets (`LADYGO_SMS_AUTH_HEADER_VALUE`) must never be committed - env/secret
manager only, same rule as every other `LADYGO_*` secret in this repo.

| Variable | Required in staging/prod | Meaning |
|---|---|---|
| `LADYGO_SMS_BASE_URL` | yes | Provider's send endpoint (full URL) |
| `LADYGO_SMS_AUTH_HEADER_NAME` | yes | HTTP header carrying the credential (default `Authorization`) |
| `LADYGO_SMS_AUTH_HEADER_VALUE` | yes | The credential itself - secret |
| `LADYGO_SMS_SENDER_ID` | no (SMS only) | Registered SMS sender id/header the provider requires |
| `LADYGO_SMS_DLT_TEMPLATE_ID` | no (SMS only) | India DLT template id for the OTP message content |
| `LADYGO_SMS_DLT_ENTITY_ID` | no (SMS only) | India DLT principal entity id |
| `LADYGO_SMS_BODY_TEMPLATE` | yes | The provider's request body - see "Body template" below |
| `LADYGO_SMS_RESPONSE_MESSAGE_ID_FIELD` | no (default `messageId`) | JSON field (dot path) in a 2xx response holding the provider's message id |
| `LADYGO_SMS_RESPONSE_ERROR_CODE_FIELD` | no (default `errorCode`) | JSON field (dot path) in a non-2xx response holding a provider error code |
| `LADYGO_SMS_CONNECT_TIMEOUT_MS` | no (default `3000`) | Connect timeout |
| `LADYGO_SMS_READ_TIMEOUT_MS` | no (default `5000`) | Read/response timeout |
| `LADYGO_SMS_MAX_RETRIES` | no (default `2`) | Additional attempts after the first, only on a 5xx or timeout/IO error - never on a 4xx |
| `LADYGO_SMS_RETRY_BASE_DELAY_MS` | no (default `500`) | Base delay for exponential backoff between retries |
| `LADYGO_SMS_CIRCUIT_BREAKER_FAILURE_THRESHOLD` | no (default `5`) | Consecutive failures before the breaker opens (fail-fast, no HTTP attempt) |
| `LADYGO_SMS_CIRCUIT_BREAKER_COOLDOWN_MS` | no (default `30000`) | How long the breaker stays open before one probe attempt is allowed through |

In staging/prod (`application-staging.yml`/`application-prod.yml`) the required ones have no default -
Spring fails to start the whole application if one is missing, before `SmsSenderStartupGuard` even runs
as a second check. In dev/test these are blank by default and never read, because `HttpSmsSender` isn't
even wired as a bean there (`LoggingSmsSender` is).

## Body template

`LADYGO_SMS_BODY_TEMPLATE` is a string, rendered by simple placeholder substitution - no JSON library
involvement on the way in, so it can match whatever shape the chosen provider's API actually wants.
Placeholders:

- `{{to}}` - the destination number (E.164)
- `{{senderId}}`, `{{dltTemplateId}}`, `{{dltEntityId}}` - from the config above
- `{{var:KEY}}` for each entry in the caller's variables map - for the OTP flow specifically, that's
  `{{var:code}}` (the 6-digit code) and `{{var:validForMinutes}}`

Every substituted value is JSON-string-escaped (quotes, backslashes, newlines) before insertion, so this
assumes the body template itself is JSON-shaped. Example (MSG91-flavoured, illustrative only - this repo
does not integrate with MSG91 or any other provider):

```json
{
  "sender": "{{senderId}}",
  "route": "4",
  "DLT_TE_ID": "{{dltTemplateId}}",
  "mobiles": "{{to}}",
  "VAR1": "{{var:code}}"
}
```

## Reliability

- **Bounded retry, backoff, outcome-dependent**: a 4xx response means the request itself was wrong
  (bad number, bad template id, bad auth) - retrying it would just repeat the same failure while
  burning the retry budget. Only a 5xx response or a timeout/IO error is retried, up to
  `LADYGO_SMS_MAX_RETRIES` additional attempts, with exponential backoff
  (`LADYGO_SMS_RETRY_BASE_DELAY_MS * 2^attempt`).
- **Circuit breaker**: `SimpleCircuitBreaker` tracks consecutive failures; once it opens, later calls
  fail immediately with no HTTP attempt at all until the cooldown passes, then one probe attempt decides
  whether it closes again. This is what actually protects request threads from a saturated/dead
  provider - bounded retries alone do not, since every request would still block through its own
  retry sequence's timeouts.
- **OTP send never reveals whether a number is registered**: `POST /api/v1/auth/otp/request` always
  returns the same generic message ("If this number is valid, a code was sent.") regardless of whether
  the phone belongs to an existing user - `OtpService.requestOtp` generates and sends a code
  unconditionally, with no branch on user existence.
- **Send caps** (DB-backed config, editable from the admin config screen, V27 migration): independent
  per-number (`sms.send.max_per_number.daily`, default 20), per-IP (`sms.send.max_per_ip.daily`, default
  50) and global (`sms.send.max_global.daily`, default 5000) daily caps - separate from the existing
  per-number/purpose resend cooldown (`otp.resend.cooldown.seconds`/`otp.resend.max.per.hour`), which
  bounds how often one number can *ask* for a code, not actual send volume. Hitting the global cap logs
  an `ERROR`-level, grep-able line (`operation=sms_global_daily_cap_exceeded alert=true`) - there is no
  external alerting system wired up yet, so this is the interim signal ops needs to notice an outage or
  an abuse pattern.
- **Logging**: `HttpSmsSender` never logs the rendered request body (it contains the OTP code) or a full
  phone number - only a last-4-digit suffix, the template key, and the outcome/error code.
  `LoggingSmsSender` is the deliberate dev/test-only exception (there's no real carrier there to protect
  secrets from).

## DLT checklist (India)

Required before any real send will succeed, independent of which provider is chosen:

1. **Principal entity registration** on the DLT portal (one of the telecom-operator-run portals, e.g.
   Airtel/Jio/Vodafone-Idea's DLT registration site) - produces the entity id for
   `LADYGO_SMS_DLT_ENTITY_ID`.
2. **Sender header registration** - the 6-character alphanumeric sender id (e.g. `LADYGO`) for
   `LADYGO_SMS_SENDER_ID`, registered against the entity above.
3. **Content template registration and approval** - the exact OTP message text (with `{#var#}`-style
   placeholders per the DLT portal's own syntax) must be submitted and approved before it can be sent;
   produces the template id for `LADYGO_SMS_DLT_TEMPLATE_ID`. A template approved for one message
   wording cannot be reused for different wording - a new template needs its own registration/approval
   cycle.
4. **One template id per distinct OTP message**, not shared across different flows - if a future
   message (e.g. a different purpose than login/phone-verify) needs different wording, it needs its own
   registered template and its own `templateKey` in code, each potentially pointing at a different
   template id (this adapter only models one template id globally today - a multi-template version
   would extend `SmsSenderProperties` to map `templateKey -> dltTemplateId`, not change `SmsSender`'s
   own interface).
5. **Provider-side DLT linkage** - most aggregators require the entity/sender/template ids to also be
   registered or confirmed on the aggregator's own dashboard before they'll accept a send referencing
   them, in addition to the telecom-operator DLT portal registration itself.

## Provider-selection checklist

Not a recommendation - what to check before picking one, so it fits this adapter without code changes:

- [ ] Plain HTTPS request/response API (not SDK-only) - this adapter is a generic HTTP client
- [ ] Synchronous send endpoint that returns a message id on success and a machine-readable error code
      on failure in the response body (set `LADYGO_SMS_RESPONSE_MESSAGE_ID_FIELD`/
      `LADYGO_SMS_RESPONSE_ERROR_CODE_FIELD` to match)
- [ ] Header-based or static-token auth (not a signed-request scheme needing per-request computation -
      that would need adapter code changes, not just configuration)
- [ ] India DLT support built into the provider's own send API (template id + entity id as request
      fields, matching the checklist above)
- [ ] Documented rate limits and realistic latency, to size `LADYGO_SMS_READ_TIMEOUT_MS`,
      `LADYGO_SMS_MAX_RETRIES` and the circuit breaker thresholds sanely
- [ ] Pricing model compatible with the `sms.send.max_global.daily` cost cap being a meaningful control
      (i.e. cost is genuinely linear per SMS, not bucketed in a way that makes the cap meaningless)
- [ ] Delivery status webhook (nice to have, not required by this adapter today - `SmsDeliveryResult` is
      synchronous accept/reject only, no delivery-receipt tracking yet)
