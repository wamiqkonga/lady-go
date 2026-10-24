# Driver registration UI/UX improvement — build plan (DRAFT, awaiting approval)

Surface: the driver registration website, `frontend/website/src/registration/` (routes `/driver`, `/driver/login`, `/driver/register`). The Android driver app only shows a gate screen that sends drivers here, so the website is where drivers actually register.

Nothing is built yet. Do not build until approved.

## 1. Goal and 60-day target

Goal: more drivers who start registration finish it and reach "Pending review".

Success measure (judged 60 days after launch): **number of drivers who submit a complete application**. Sub-measures:
- Start-to-submit completion rate (today: unknown, not measured at all — step 9 adds measurement).
- Median time from first login to submit.
- Drop-off by step (login, city, vehicle, each document, selfie, declaration).

Target numbers are set by you after we get a baseline in week 1. A guess today would be invented.

## 2. Users and their problem

Who: a woman who wants to drive, probably on a mid-range Android phone, mobile data, in a hurry, maybe first time uploading documents online.

What goes wrong today (found by reading the code, `Dashboard.tsx`, `DocumentUploadSection.tsx`, `Login.tsx`, `registration.css`):
1. **One long page.** City, vehicle, 6 documents, selfie, declaration all stack on one scroll. No sense of "step 3 of 9", no sense of how much is left.
2. **No progress indicator.** The only status is the badge "Not submitted / Pending review". Driver cannot tell what is missing.
3. **Nothing says what to do next.** No "next step" button; the fixed bottom action bar style exists in CSS (`.primary-action`) but is unused.
4. **Status flips to "Pending review" after the first upload**, so a driver with 1 of 8 items done sees "Pending review" and may think they are finished. This is misleading.
5. **Login asks for phone and email at once**, with the phone example `+919900000000` typed by hand. No country-code help, no resend-code button, no countdown, no hint on where to find the code.
6. **Upload is a bare file input.** No "take photo" button, no guidance (what a good licence photo looks like), no example image, no image tips, no upload progress. Error messages from the server are shown raw.
7. **Vehicle form is thin.** Placeholder-only labels (disappear when typing), no colour field in the UI although the form state has it, no inline validation of the registration number format.
8. **Declaration** is a plain paragraph with an "I agree" button; long text, no scroll cue, no checkbox.
9. **Silent failure.** `acceptDeclaration` swallows errors; document list load failures are unhandled. User sees nothing.
10. **No celebration or clear next-step after submit.** No "what happens next, how long review takes".
11. **Accessibility gaps.** Placeholder-as-label, error text not tied to fields, status shown by colour badge only.
12. **No resume hint.** Returning driver lands on the same long page; should land on the first incomplete step.

## 3. Scope

In:
- Step-by-step wizard replacing the single long page (same API, same data).
- Progress bar and "step X of Y", checklist overview, resume at first incomplete step.
- Clearer login: separate phone and email fields with labels, resend code with countdown, auto-advance, paste-friendly 6-digit input.
- Upload step redesign: camera or gallery choice, example image and tips, preview, retake, upload progress, plain-language errors, rejected-document reason shown prominently with a "fix this" action.
- Vehicle step with real labels, vehicle-type cards, inline validation.
- Declaration step with scroll-to-read, checkbox, clear error.
- Review screen ("check everything") and a clear done screen with what happens next.
- Honest status wording (see item 4).
- Mobile first at 360px width, 48px touch targets, light and dark theme, basic accessibility.
- Funnel analytics events (privacy-safe: step names only, no personal data).

Out (not in this change):
- New backend rules, new document types, new API fields (any needed API change is flagged and asked first).
- Android driver app UI (separate plan if wanted).
- Language translations (listed as a possible follow-up; could matter for adoption).
- The marketing page `/driver`.

## 4. UI/UX flow (proposed)

1. `/driver/login`: phone, then email, then "Send code". Code screen: 6 boxes, resend after 30s, "wrong number?" link.
2. Step 1 City.
3. Step 2 Vehicle.
4. Steps 3 to 8 Documents, one per screen: licence, RC, vehicle photo, insurance, fitness, pollution.
5. Step 9 Selfie.
6. Step 10 Declaration.
7. Review all, "Submit application".
8. Done screen: "Application received, review usually takes X" (X is a number you must give us; we will not invent it).
Returning driver: lands on the first incomplete or rejected step, with a checklist menu to jump anywhere.
Bottom fixed primary button ("Continue") on every step; "Back" on top-left; "Save and finish later" always safe because the server already stores each step.

## 5. Data and API design

No new endpoints planned. Uses existing `/api/v1/drivers/me`, `/vehicle`, `/documents`, `/declaration`, `/selfie`, `/api/v1/cities`.
Progress is computed on the client from those responses, the same "server is source of truth" rule as the existing design doc.
Open question for you: should "Submit application" be an explicit action? Today there is none; the backend sets Pending review on first activity. Making it explicit needs a backend change. Options:
- A (default, no backend change): keep current behaviour, change only the wording so the driver is told "Pending review starts when all items are in".
- B: add a real submit endpoint (backend work, separate approval).

## 6. Security and compliance

- Keep token in memory only, no personal data in URLs, no `dangerouslySetInnerHTML`, reviewer notes stay as plain text.
- Production CSP check must still pass (`check-production-csp.mjs` pattern): no new third-party scripts. Analytics must be first-party or a CSP-approved host; I will not add a third-party analytics tool without your OK.
- Camera access only via the user's tap; keep the file-input fallback.
- Documents are personal data: no image bytes in storage, no logging of numbers.
- Risk flags: none involve money movement, DELETE or UPDATE statements, or hardcoded credentials. Production change is front-end only.

## 7. Build steps (order, with model per Rule 2)

Each step ends with a real-browser run (Rule 3).
1. Wizard shell: step state, progress bar, bottom action bar, resume logic. Sonnet 5.5 (feature code).
2. Login redesign with resend and countdown. Sonnet 5.5.
3. Vehicle step and city step. Sonnet 5.5.
4. Document step component: camera or gallery, tips, preview, progress, errors. Sonnet 5.5.
5. Selfie and declaration steps. Sonnet 5.5.
6. Review and done screens, status wording fix. Sonnet 5.5.
7. Accessibility and dark mode pass, 360px check. Sonnet 5.5.
8. Update component tests and Playwright E2E (`driver-registration-flow.spec.ts`). Sonnet 5.5.
9. Funnel events. Sonnet 5.5.
10. Plan review, architecture decisions, final review. Opus 5.5 (brain).
Read-only searching and renames: Haiku 5.5.

## 8. Test steps

- Unit: component tests per step (vitest), including rejected document and failed upload.
- E2E: Playwright happy path plus wrong file type, oversize file, rejected-then-resubmit, refresh mid-way and resume, back button.
- By hand as a user, in the browser: phone width 360px and 390px, slow network, dark mode, camera denied, wrong OTP, expired OTP, resend.
- Run `/code-review` before merge (Rule 4). Superpowers `verification-before-completion` before saying done.

## 9. Deploy and rollback

- Merge to `develop`, deploy to staging first, run the E2E workflow `driver-registration-e2e.yml`.
- Gate the new wizard behind a flag `VITE_REG_WIZARD` (build-time) so old page stays available.
- Rollback: set the flag off and redeploy the website (minutes). No data migration, so rollback is safe.

## 10. Launch and marketing

- Soft launch to a small group of real or test drivers first, watch the funnel for 3 to 7 days.
- Fix the biggest drop-off, then promote on the `/driver` page, WhatsApp and partner channels (your call on channels).
- Add a short "What you need" checklist on `/driver` (licence, RC, insurance, 10 minutes) so drivers arrive prepared.

## 11. Measuring the 60-day target

- Funnel events: `reg_login_started`, `reg_otp_verified`, `reg_step_viewed:<step>`, `reg_step_completed:<step>`, `reg_submitted`.
- Weekly report of starts, submits, drop-off per step. Suggest `/schedule` for a weekly adoption report and an artifact dashboard (Rule 4).
- Baseline in week 1, target set with you.

## 12. Web Admin tickets (drafted for you to paste)

Follow the Dev Desk lifecycle: Ready for test, In testing/UAT, Test Failed, Ready To Deploy. Environment: staging first.

**Ticket A: Driver registration wizard, staging**
- Why: drivers drop off on one long page with no progress.
- Steps: (1) Deploy website build with `VITE_REG_WIZARD=true` to staging. (2) Open staging `/driver/login` on a phone. (3) Register a test driver through all steps. (4) Try wrong file, big file, refresh mid-way.
- Acceptance: every step shows progress; refresh resumes at the right step; rejected document shows reason and can be resubmitted; done screen shown; no console errors.
- Owner: Wamiq (UAT), Claude (build). Environment: staging.

**Ticket B: Funnel analytics events**
- Why: need the 60-day number.
- Steps: confirm the analytics destination (decision needed from you), enable on staging, run one test registration, check events arrive.
- Acceptance: each event above appears once per action; no phone, email or document number inside any event.
- Owner: Wamiq. Environment: staging.

**Ticket C: Production release**
- Why: ship to drivers.
- Steps: after Ticket A and B pass UAT, move to Ready To Deploy, deploy website to production with the flag on, smoke test with one real login.
- Acceptance: login, one full test registration, flag-off rollback tested once on staging first.
- Owner: Wamiq. Environment: production, after staging passes.

## 13. Decisions needed from you

1. Confirm the target is the website flow (not the Android gate screen).
2. Submit behaviour: option A (wording only) or B (new backend submit).
3. Review time promise to show on the done screen (a real number).
4. Analytics destination that fits the CSP.
5. Hindi or other languages now or later.
