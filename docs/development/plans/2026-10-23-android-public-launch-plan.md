# Android driver + customer apps: public-launch build plan

Status: **DRAFT, awaiting approval (Wamiq).** No code is written until you approve.
Date: 2026-10-23. Decided with you today: target = public launch, Android only; order = driver app first, then customer app. iOS is a separate plan after Android ships.

Reading this plan: every term is explained the first time it appears. Commands are copy-paste. Anything marked **[YOU]** only you can do (accounts, money, legal, store consoles). Anything marked **[RISK]** is high-risk and needs your explicit OK before we touch it.

---

## 1. Goal and the 60-day target

**Goal:** a woman can install the customer app from the Play Store, book a ride, and be picked up by a verified woman driver, safely, paying online or cash. A verified driver can install the driver app, go online, accept rides, finish them and see earnings.

**Success number (your rule): how many people use it in the first 60 days after launch.** Proposed target, to confirm with you:

| Measure (day 60) | Proposed target | Why this number |
|---|---|---|
| Installed + registered riders | 1,000 | Single city, women-only niche, founder-led outreach |
| Riders with 1+ completed ride | 400 (40% activation) | Installing is not using; completed ride is the real signal |
| Approved drivers | 80 | About 1 driver per 5 active riders keeps wait times short |
| Completed rides per week at day 60 | 600 | Shows repeat use, not just a launch spike |
| Rider 30-day repeat rate | 35% | Retention proves the product, not marketing |

> **Open item for you:** these targets are my proposal, not data. Please confirm or overwrite them in step 12. We measure them in step 12.

## 2. Users and their problem

- **Rider (customer):** a woman who does not feel safe in a normal cab or auto. Needs: trust that the driver is verified, a way to share the trip, quick booking, fair visible price.
- **Driver:** a woman who wants steady rides without a male-dominated platform. Needs: fast onboarding, clear earnings, rides that arrive reliably, and not being drained by a battery-hungry app.
- **Ops (you):** needs to see problems live and act (admin web, already built).

## 3. What already exists (checked in the repo today)

| Part | State |
|---|---|
| Backend (Java/Spring Boot) | Auth, customers, bookings, matching/offers, ride lifecycle, trip share, admin APIs, realtime (WebSocket) built. CI green. Checked-in API contract `docs/api/openapi.json`. |
| Admin web | Driver review, ride oversight, audit log, config editor built. Live map, drivers list, customers, safety screens are stubs. |
| Driver website registration | Built (OTP login, document upload, selfie). |
| Driver Android app | Login, onboarding gate, go-online + location service, offers over WebSocket built. **Ride loop (slice 3) built but never reviewed and never run on an emulator against the real backend.** No ride history, no earnings. |
| Customer Android app | Login, onboarding, book ride (maps, fare estimate, selfie), track booking built. Not run end to end against a real backend recently (to verify in step 0). |
| iOS apps | Skeleton only (home screen). Out of scope here. |
| Payments | **Not built.** Online payment is rejected at booking time. Cash only today. |
| SOS | **Not built.** Deferred in the roadmap to "public launch". We are targeting public launch, so it is now in scope. |
| Real OTP provider | Email OTP sender built. WhatsApp/SMS adapter built but **nothing configured**. |

## 4. Scope

### In scope (public launch gates from the roadmap)
1. Finish and harden driver app: ride loop review, history, earnings, ratings, support, account/documents, push notifications, release build.
2. Finish and harden customer app: ride history, ratings, saved places, trip share, emergency contacts, SOS, payments, receipts, support, release build.
3. Backend work these apps need: payments (provider, webhooks, refunds, receipts), SOS, ratings, earnings, push notifications (FCM), step-up MFA for admin, driver payout records.
4. Admin web: SOS/safety screen, live map, drivers and customers lists (ops cannot run public launch with stubs).
5. Real OTP provider, driver background verification process, Play Store release, monitoring, load test, rollback.

### Out of scope (explicitly, to stop scope creep)
iOS apps, scheduled rides, ride pooling, multi-city, promo/referral engine (marketing uses simple codes later), in-app chat (call-masking only), automated driver payouts to bank (manual weekly payout at launch, see step 5).

## 5. Decisions I need from you before building (blocking)

| # | Decision | Default I will use if you say nothing |
|---|---|---|
| D-A | Payment provider (Razorpay, Cashfree, Stripe India?) | Razorpay: widest UPI/card support in India. **[YOU]** needs a business account and KYC. |
| D-B | Commission model and driver payout cycle | Platform commission % set in admin config; weekly manual bank payout from admin report |
| D-C | City and launch date | Same city as the pilot plan |
| D-D | OTP channel at launch | WhatsApp primary, SMS fallback (roadmap decision 2026-10-17) |
| D-E | Who handles SOS alerts 24x7? | **[YOU]** SOS without a person answering is worse than no SOS. We need a staffed number or a partner (e.g. security firm) before SOS goes live. |
| D-F | Legal: privacy policy, terms, driver agreement, Play Store data-safety form | **[YOU]** with a lawyer; I draft structure only |

## 6. UI/UX flow (summary; full screens already in `docs/product/driver-android-design.md` and `customer-android-design.md`)

**Driver:** Open, OTP login, onboarding status (documents approved?), Home (offline/online toggle, today's earnings), Offer card (pickup distance, fare, accept/decline with countdown), To pickup (navigate button), Arrived (rider photo check), Enter rider PIN, In trip, Complete (confirm cash or see online paid), Rate rider, History, Earnings (day/week), Profile and documents, Support, Call 112.

**Customer:** Open, OTP login, Home, Enter pickup/drop, Fare estimate, Choose cash/online, Book, Searching, Driver found (photo, vehicle, PIN), Track live, Share trip, SOS, Complete, Pay/receipt, Rate driver, History, Saved places, Emergency contacts, Support.

Design rules: both apps must work with one hand, large touch targets, readable at mobile width, usable on slow networks (offline banner, retry), works on low-end phones (Android 8+ target, check `minSdk` in step 0).

## 7. Data and API design

New backend pieces (each gets a design doc in `docs/api/` first, same rule as every other phase):

- **Payments** (`docs/api/payments.md`): `payments` table (booking, amount, status, provider reference, idempotency key), `refunds`, webhook endpoint with signature check, receipt per ride. State machine: CREATED, AUTHORISED, CAPTURED, FAILED, REFUNDED. **[RISK] money movement.** Every provider call and webhook is idempotent (safe to repeat) and tested.
- **Ratings** (`ratings` table, one per side per ride, 1-5 + optional tag), feeds matching and admin flags.
- **Earnings** (`driver_earnings` ledger: fare, commission, net, payout batch id), read-only for the driver app.
- **SOS** (extends `docs/api/safety.md`): `sos_events` (who, ride, location trail, status OPEN/ACKED/RESOLVED, responder), notifies emergency contacts and ops; admin screen to ack/resolve.
- **Push notifications**: Firebase Cloud Messaging (FCM, Google's free push service) device tokens table; used for offers when app is in background, ride status, SOS ack.
- **Support**: simplest viable = in-app "contact support" (call/WhatsApp number from admin config) + issue form creating a row ops can see. No chat.
- **Step-up MFA** for admin destructive actions (roadmap Launch gate): asks for a fresh authenticator code before config edits or payout actions. **[RISK] admin security.**

Rule: any schema change = a Flyway migration (the versioned database change files in `backend/src/main/resources/db/migration`), tested by the existing Testcontainers tests. After any API change, regenerate the OpenAPI file and the Kotlin API clients so apps and backend cannot drift.

## 8. Security and compliance

- Payments: never store card data (provider hosts the card form; PCI scope stays minimal). Webhook signature verified; amounts re-checked server-side.
- Location data and rider selfies are personal data: retention limits, access logged in the audit log, nothing in server logs (already enforced, commit a29c4a5).
- Android: tokens in encrypted storage (exists), screenshots blocked on sensitive screens (exists), certificate/network security config, ProGuard/R8 shrinking on release, no secrets in the APK.
- Driver background verification actually run on every driver before approval (roadmap hard gate, D5/D10). **[YOU]** pick a verification vendor.
- India: Digital Personal Data Protection Act consent screens, privacy policy URL required by Play Store, motor-vehicle aggregator licensing in your state **[YOU]** check with lawyer.
- Run `/security-review` and the `kayana-*` money skills on payments before merge. Run the existing Semgrep and gitleaks CI jobs.

## 9. Build steps (in order, one small slice at a time, one CI run per slice)

Each step ends with a user-level test (section 10) and a fresh-context code review before it counts as done. Model use per your Rule 2: Opus for design/review, Sonnet for implementation, Haiku for search/format.

| Step | What | Model | Exit check |
|---|---|---|---|
| 0 | **Baseline.** Run backend + infra locally, run both Android apps on an emulator, record what really works. Write findings in `docs/development/baseline-2026-10.md`. | Haiku reads, Opus judges | Written list of real bugs |
| 1 | **Driver app: close slice 3.** Fresh-context review, fix findings, emulator pass of full ride loop against real backend. | Opus review, Sonnet fix | Driver completes a ride end to end |
| 2 | **Backend: ratings + earnings + history endpoints** (design doc first). | Sonnet | API tests + contract updated |
| 3 | **Driver app: history, earnings, ratings, profile/documents, support.** | Sonnet | User-level pass |
| 4 | **FCM push** backend + both apps (offers wake a sleeping driver app). | Sonnet | Offer arrives with app closed |
| 5 | **Payments backend** (design doc, approval, then build). Sandbox mode only. | Opus design, Sonnet build | Idempotency + webhook tests green |
| 6 | **Customer app: payment flow, receipts, history, ratings, saved places, emergency contacts.** | Sonnet | User-level pass |
| 7 | **SOS** backend + customer button + driver button + admin screen. Go-live blocked on D-E. | Opus design, Sonnet build | SOS reaches ops in under 10 s in test |
| 8 | **Admin web:** live map, drivers list, customers list, safety screen, step-up MFA, payout report. | Sonnet | Admin E2E pass |
| 9 | **Hardening:** release build config (R8, signing), crash reporting, analytics events for section 12, accessibility pass, low-end device pass. | Sonnet | Release APK installs and runs |
| 10 | **Load test + security review + OpenAPI drift check + backup/restore drill.** | Opus | No critical findings; restore proven |
| 11 | **Closed beta** on Play Store internal testing track with the pilot cohort. Fix what real people break. | Sonnet | 2 weeks, no crash loop |
| 12 | **Public launch** (staged rollout 10%, 50%, 100%) + marketing + measurement. | n/a | Day-60 dashboard live |

Order logic: driver app first (supply before demand: riders who cannot get a driver uninstall). Payments before the customer polish because the customer flow depends on it. SOS before public launch because the roadmap makes it a hard gate.

## 10. Test steps (your Rule 3: test as a real user after every step)

For each step I will: build the app, open it in the Android emulator, click through the real flow, try the happy path, then mistakes (wrong PIN, wrong OTP, cancel mid-ride, kill the app mid-ride, airplane mode, GPS off, slow network, double-tap pay), check narrow-phone width and a low-end emulator profile, then report exactly what a user would see, including failures with output. Layers underneath: unit tests, backend integration tests (Testcontainers), existing Playwright E2E for web, CI.

Money tests specifically: pay twice (must charge once), webhook arrives twice, webhook arrives before the app returns, refund after complete, provider timeout.

## 11. Deploy and rollback

- **Environments:** local, staging (mirror of prod), production. Everything lands on staging first.
- **Backend deploy:** build Docker image in CI, deploy to staging, run smoke test (login, book, accept, complete), then promote the same image to production. Database migrations only forward; each migration paired with a written rollback note. Nightly Postgres backup to S3 already exists (commit 5928f3b), restore must be drilled in step 10.
- **Backend rollback:** redeploy the previous image tag (kept for 30 days). If a migration was applied, use the written rollback note, or restore from backup as the last resort. **[RISK]** never run an unbounded DELETE/UPDATE by hand in prod.
- **Apps:** Play Store staged rollout (10%, 50%, 100%). Halt the rollout in Play Console if crash rate rises. Backend keeps a minimum-supported-app-version setting so a broken app version can be force-updated.
- **Kill switches in admin config:** disable online payment (fall back to cash), disable new bookings, disable a single app version.

## 12. Launch, marketing, and measuring the 60-day target

- **Pre-launch (4 weeks out):** landing page on the existing website with driver and rider waitlists; WhatsApp community for pilot riders; partnerships with women's colleges, hostels, offices, women-led groups; driver recruitment via women's self-help groups and training institutes. Referral rewards are out of scope for the app, handled manually via admin credits until proven worthwhile.
- **Launch:** staged Play rollout, founder-monitored admin console the first two weeks, press note around "women-only, verified, SOS-backed".
- **Measurement:** app analytics events (install, OTP verified, first booking, first completed ride, repeat ride), plus backend queries for rides/week. Build an **adoption dashboard** (Artifact) fed from the database so you see the section 1 table daily. Use `/schedule` for a weekly adoption report and `/loop` during launch week for daily smoke tests.

## 13. Risks (high-risk list)

| Risk | Mitigation |
|---|---|
| **[RISK]** Money movement bugs (double charge, lost refund) | Idempotency keys, webhook signature, reconciliation report, sandbox first, `kayana-*` + `/security-review` before merge |
| **[RISK]** SOS with nobody answering | Do not enable SOS in prod until D-E is staffed and drilled |
| **[RISK]** Unverified driver on a women-safety platform | Background check hard gate, enforced in the approval screen |
| **[RISK]** Admin config edit without step-up MFA exposed publicly | Step 8 must ship before admin web leaves a developer machine |
| Play Store rejection (data safety, background location, permissions) | Prepare declarations early, use foreground service correctly, submit internal track in step 11 |
| CI minutes budget on the Lady-Go org | One CI run per slice, `[skip ci]` on backup pushes (existing rule) |
| Driver supply too low at launch | Recruit before launch, only open rider signup once N approved drivers exist |

## 14. Web Admin tickets (draft for you to paste; Dev Desk lifecycle: Ready for test, In testing/UAT, Test Failed, Ready To Deploy)

All tickets: environment = **staging first**, then production only after UAT passes. Owner = you unless noted. Claude cannot execute these; paste each into Web Admin.

**T1. Provision payment-provider sandbox and keys**
- Why: step 5 needs sandbox keys; production keys later.
- Steps: 1) Create a business account with the chosen provider (D-A). 2) Complete KYC. 3) Generate **test mode** API key and webhook secret. 4) Give them to the staging backend as environment variables `LADYGO_PAYMENTS_KEY` and `LADYGO_PAYMENTS_WEBHOOK_SECRET` through your secret store (never in chat or git; see `docs/development/ci-secrets.md`).
- Acceptance: staging backend starts with keys set; sandbox test payment succeeds from a curl call.

**T2. Configure WhatsApp OTP (and SMS fallback)**
- Why: real users cannot log in without it.
- Steps: Meta business verification, approved authentication template, set `LADYGO_SMS_*` variables per `docs/development/sms-provider-setup.md`; SMS needs TRAI DLT registration.
- Acceptance: a real phone receives an OTP in under 30 s on staging.

**T3. Firebase project for push notifications**
- Steps: create Firebase project, add both Android package IDs, download `google-services.json` for each app (kept out of git if it contains restricted keys), create a service account key for the backend, store as secret.
- Acceptance: test push from Firebase console arrives on an emulator.

**T4. Play Console setup**
- Steps: create developer account (one-time fee), create two apps (customer, driver), create the **internal testing** track, create an upload key and Play App Signing, prepare privacy policy URL, data-safety form, background-location declaration for the driver app.
- Acceptance: a test AAB (Android app bundle) installs from the internal track.

**T5. SOS operations setup (blocks SOS go-live)**
- Steps: decide responder (D-E), get a staffed phone number, write the SOS runbook (who answers, what to do, when to call police), schedule a drill.
- Acceptance: a staged SOS drill is answered within 60 s by the responder.

**T6. Driver background-verification vendor**
- Steps: choose vendor, contract, define the admin step "verification passed" before approval.
- Acceptance: one test driver cannot be approved without a verification record.

**T7. Production infrastructure and edge headers**
- Steps: provision prod host(s), TLS certificate, the `frame-ancestors` CSP header at the proxy (`docs/security/admin-web-csp.md`), backup bucket, monitoring/alerts, then restore drill.
- **[RISK] prod change.** Acceptance: staging mirrors prod; restore drill documented.

**T8. Legal documents**
- Steps: privacy policy, terms of use, driver agreement, DPDP consent text, aggregator licence check, Play Store listing text.
- Acceptance: public URLs exist and are linked from both apps and the website.

**T9. Commission and pricing config in staging admin**
- Steps: after step 8 ships, set commission %, base fare, per-km, minimum fare in the admin config screen with step-up MFA.
- Acceptance: fare estimate in the customer app matches your price sheet.

## 15. What I need from you to start

Approve this plan (or tell me what to change), answer D-A to D-F (or accept defaults), and say "go". Then I begin at **step 0 (baseline)**, which writes no feature code and shows us honestly what works today.

### Suggested Claude features, at the right moment
- `brainstorming` skill now, before step 5 payments design (cheap, prevents a bad money design).
- Parallel sessions later: after step 2, driver-app work and payments backend touch different files and can run at once, at the cost of extra CI minutes.
- `/code-review` and `/security-review` before every merge; `kayana-*` money skills on steps 5 and 8.
- `/schedule` weekly adoption report and `/loop` for launch week smoke tests.
- Adoption dashboard Artifact at step 12.
