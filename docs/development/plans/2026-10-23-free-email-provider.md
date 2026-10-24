# Build plan: a second, free email provider for sign-in codes (until AWS SES is approved)

Status: DRAFT, waiting for owner approval. Nothing in this plan has been built.
Written for: Wamiq (product owner). No coding knowledge assumed.

## 1. Goal, and what "success" means
**Problem.** Drivers sign in with a code sent by email. Amazon SES (our email sender) has not approved us yet, so it can
only email addresses we have verified by hand. Real drivers cannot sign up.

**Goal.** Let any driver receive their sign-in code today, for free, without waiting for AWS, and keep SES as the
sender we switch back to when AWS approves.

**60-day adoption target (to confirm, I have not been given one).** Proposed: *30 drivers complete registration
within 60 days of launch.* This feature supports it by removing the biggest blocker. We will measure two things:
1. Of the people who ask for a code, how many sign in (target: 90% or more).
2. How fast the code arrives (target: under 60 seconds).

## 2. Users and their problem
Women drivers in Srinagar registering on their phone, often on slow mobile data. If the code never arrives, they give
up, and we never see them again. The admin (you) needs registrations to arrive to review them.

## 3. Scope
**In:** one new email sender that speaks plain SMTP, so it works with Resend, Brevo, Gmail or any other provider by
configuration only; a setting that chooses the sender (`ses` or `smtp`); tests; deployment; a rollback.
**Out:** SMS/WhatsApp codes, changing how sign-in works, changing the website, marketing email.

## 4. Provider choice (free limits checked 23 Oct 2026, from each provider's own pricing page where it loaded)
| Provider | Free allowance | Notes |
|---|---|---|
| **Resend (recommended)** | **100 emails/day, 3,000/month, 3 domains** (verified) | Quick sign-up, SMTP and API, domain verified with DNS records. The daily cap equals our busiest pilot day, so it is tight. |
| Brevo (backup) | believed ~300/day, NOT verified (page would not load) | Larger daily limit; new accounts are sometimes reviewed by hand before sending. |
| Gmail (the support address) | about 500/day | Works instantly with an "app password", but mail comes from a Gmail address and Google can suspend automated sending. Last resort only. |
| MilesWeb mailbox | included in your hosting | Free, but shared server reputation, so mail may land in spam; limited sending rate. |

Because the new sender is plain SMTP, switching between these is a settings change, not new code.

## 5. How it will work (user flow, nothing changes for drivers)
Driver types phone and email, then taps "Send code". The code arrives by email, they type it, they are in. The only
visible difference is the "From" address (for example `no-reply@ladygoride.com`) staying the same.

## 6. Technical design
- Add `SmtpEmailSender` next to the existing `SesEmailSender`; both implement the existing `EmailSender` interface.
- New settings: `LADYGO_EMAIL_PROVIDER` (`ses` default, or `smtp`), `LADYGO_SMTP_HOST`, `_PORT`, `_USERNAME`,
  `_PASSWORD`, `_STARTTLS`. The password lives only in `/opt/ladygo/.env` on the server (never in the repo or chat).
- The server refuses to start if `smtp` is chosen but a setting is missing.
- Sending failures are logged (never the code or the password) and return a clean failure, same as SES today.
- Everything else (limits per address/IP, binding an email to an account) stays untouched.

## 7. Security and compliance
- The SMTP password is a secret: stored in the server env file (mode 600), set by you with hidden input, never printed.
- Use TLS (port 587 STARTTLS or 465). The sender refuses plain-text SMTP.
- Codes are never logged. The proxy log already redacts secrets and sensitive query values.
- Personal data: only the email address is sent to the provider. Note it in the privacy policy (you review wording).
- Run `/security-review` after the code is written, before it is deployed.

## 8. Build steps (who does what, and which model)
1. Opus 5.5 (planning, done): this plan.
2. **Sonnet 5.5**: write `SmtpEmailSender`, the provider switch and startup checks. Add a mail library dependency.
3. **Sonnet 5.5**: unit tests with a fake mail server; extend the existing sign-in tests.
4. Haiku 5.5: update docs and the example environment file (mechanical).
5. Opus 5.5: review the diff, run `/code-review` and `/security-review`.

## 9. Test steps
1. All backend tests pass (they must, including the 559 existing ones).
2. **As a real user** (your rule): on the live site, request a code with a verified test address, check the email
   arrives, check the sender name, the time, and sign in. Then try a wrong code, an expired code and a typo email.
3. Check on a phone-width screen and on mobile data.
4. Confirm a failed send shows the same message as today and nothing secret appears in the logs.

## 10. Deploy and rollback
- There is **no staging server**, so the first real test happens on production with your own address. This is flagged
  as a production change.
- Deploy: build the new image, upload it to the server, set the new settings, restart the backend (about 20 seconds
  where sign-in fails), run the real-user test.
- **Rollback (2 minutes):** set `LADYGO_EMAIL_PROVIDER=ses`, restart the backend. SES keeps working for verified addresses.
  Database untouched, no migration needed. A backup is taken before the deploy.

## 11. Launch and marketing
Nothing to announce. The point is that sign-up works. Once it works, invite the first drivers (WhatsApp, in person).

## 12. Measuring the 60-day target
Count weekly: codes requested, sign-ins completed, registrations submitted, registrations approved. I can add these
as a small admin dashboard later (separate task).

## 13. Web Admin tickets (drafted for you to paste; owner Wamiq unless stated; environment: production)
**T1. Create a Resend account and verify ladygoride.com**
Why: provides the free sender. Steps: sign up at resend.com with the support email; Domains → Add domain
`ladygoride.com`; Resend lists DNS records. Acceptance: Resend shows the domain "Verified". Owner: Wamiq.
**T2. Add Resend's DNS records at GoDaddy**
Steps: GoDaddy → DNS → Add record, one per row Resend shows (do not remove existing records). Acceptance: a DNS
lookup shows each record and Resend marks the domain verified. Owner: Wamiq.
**T3. Create an API key and store it on the server**
Steps: Resend → API keys → Create (sending access only); then run the script I will provide, which asks for the key
with hidden input and writes it to `/opt/ladygo/.env`. Acceptance: key exists in the env file, never shown on screen
or in chat. Owner: Wamiq. Risk: high (secret). Never paste the key anywhere.
**T4. Deploy the new sender (HIGH RISK: production change)**
Owner: Claude drafts and runs with your approval. Acceptance: backend healthy, a test code arrives by Resend.
**T5. UAT: real sign-in test.** Lifecycle: Ready for test, In testing/UAT, Test Failed, Ready To Deploy.
Acceptance: code in under 60 seconds, sign-in works, wrong/expired code messages correct. Owner: Wamiq.
**T6. Rollback drill.** Acceptance: switching back to SES and restarting takes under 2 minutes.

## 14. Risks and open questions
- Resend's free cap (100/day) can be hit on a busy day; fall back to Brevo by configuration.
- Mail from a brand-new sending domain may land in spam at first; ask the first drivers to check spam.
- Open question: confirm the 60-day adoption target (section 1).
