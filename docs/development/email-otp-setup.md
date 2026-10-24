# Email OTP (MVP 1)

Status: 2026-10-19. For the driver-registration pilot, one-time codes are delivered by **email** instead of
SMS/WhatsApp. Switch: `LADYGO_OTP_CHANNEL=email` (default `sms`, which keeps the existing behaviour and the
`LADYGO_SMS_*` setup in `sms-provider-setup.md`).

## How it behaves
- `POST /auth/otp/request` takes an extra `email`. In email mode it is required and only `purpose=LOGIN` is
  allowed; the 6-digit code is emailed to it (valid `otp.expiry.seconds`, default 5 min). In SMS mode `email`
  is ignored.
- `POST /auth/otp/verify` is unchanged (phone + code). On the first successful verify the email is bound to
  `users.email`.
- **The phone number is not verified in this mode** (`users.phone_verified_at` stays null). Phone is still
  the account identity. To stop someone who merely knows a number from taking over a claimed account, a
  request is silently dropped (same generic response, nothing sent, logged as `otp_email_conflict`) when the
  email differs from the one bound to that phone, or is already bound to another user.
- First claim is still first-come: an attacker could register someone else's phone with their own email
  before the real owner does. Admin document review is the backstop for the pilot; verify phones by call
  before approving drivers.
- Extra send caps: `email.send.max_per_address.daily` (default 20), plus the existing per-phone, per-IP and
  global caps.
- The Android apps still send phone only, so they cannot log in while the channel is `email`.

## Choosing the email provider (`LADYGO_EMAIL_PROVIDER`)
Two senders exist; the channel stays `LADYGO_OTP_CHANNEL=email` for both.

| Provider | Setting | When |
|---|---|---|
| Amazon SES | `LADYGO_EMAIL_PROVIDER=ses` (default) | Once AWS grants production access (the sandbox only reaches verified addresses). |
| Any SMTP relay | `LADYGO_EMAIL_PROVIDER=smtp` | Free stop-gap while SES is pending: Resend (100 emails/day, 3,000/month free), Brevo, Gmail, a hosting mailbox. |

SMTP settings (all required when the provider is `smtp`; startup fails naming any that are missing):
`LADYGO_SMTP_HOST` (e.g. `smtp.resend.com`), `LADYGO_SMTP_PORT` (587 STARTTLS, or 465 implicit TLS), `LADYGO_SMTP_USERNAME`
(Resend uses the literal `resend`), `LADYGO_SMTP_PASSWORD` (Resend: the API key), `LADYGO_EMAIL_FROM`. TLS is mandatory; the
sender never falls back to plain text. Store the password with `scripts/ops/set-smtp-secret.sh` (hidden input, written only
to `/opt/ladygo/.env`), then restart the backend. To switch back, set `LADYGO_EMAIL_PROVIDER=ses` and restart.

Resend setup: add `ladygoride.com` under Domains, add the DNS records it lists at the DNS host, wait for "Verified", then
create an API key with sending access only. The sender address must be on the verified domain (`no-reply@ladygoride.com`).

## Amazon SES setup
1. SES in the same region as the app (default `ap-south-1`, `LADYGO_EMAIL_SES_REGION`).
2. Verify the domain `ladygoride.com` (Easy DKIM CNAMEs, plus SPF/DMARC TXT records, at the DNS host).
3. A new SES account is in the **sandbox**: it only delivers to verified addresses. Verify tester addresses, and
   request production access (Account dashboard) before inviting real drivers.
4. Credentials: an IAM user limited to `ses:SendEmail` on the verified identity; supply
   `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` to the backend (the SDK default provider chain).

| Variable | Value |
|---|---|
| `LADYGO_OTP_CHANNEL` | `email` |
| `LADYGO_EMAIL_FROM` | e.g. `Lady Go <no-reply@ladygoride.com>` (verified identity) |
| `LADYGO_EMAIL_SES_REGION` | `ap-south-1` |
| `LADYGO_SMS_*` | not needed in email mode |
