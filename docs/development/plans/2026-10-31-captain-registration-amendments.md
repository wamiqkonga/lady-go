# Captain registration amendments - build plan (DRAFT, awaiting approval)

Date: 2026-10-31. Status: **approved by Wamiq with the recommended defaults; built on branch `feat/captain-registration-two-wheeler`**.

Build notes: the declaration is audience-scoped (`driver-v2`, captains only; riders keep `v1`) instead of one shared `v2`. Insurance, vehicle photo and pollution certificate left the wizard. Android is out of scope.

## 1. Goal and 60-day target

Make captain registration shorter and easier so more captains finish it.

- Goal: a captain with a two-wheeler can register in under 5 minutes from a phone.
- 60-day target: **number to be set by Wamiq** (suggested: at least 60% of captains who start registration finish it, plus N completed captains in 60 days). Measured from the funnel in section 13.

## 2. Users and problem

Women captains who own a two-wheeler. Today they must upload 5 required documents (licence, RC, vehicle photo,
insurance, selfie) and can pick any of 4 vehicle types. Many will drop off at the document steps.

## 3. What changes (from Wamiq's request)

| Step | New content | Required? |
|------|-------------|-----------|
| 1 Vehicle | Vehicle number, RC photo, fitness certificate; vehicle type fixed to **two-wheeler** (no choice shown) | Number and RC photo required; fitness certificate **optional** |
| 2 Driving licence | Licence photo (number and expiry as today) | **Optional** |
| 3 Selfie | Live selfie | Required |
| 4 Self-declaration | New wording (section 5); captain accepts; stored as a record | Required |
| Review | Summary and finish | - |

## 4. Scope

In:
- Website registration wizard (`frontend/website/src/registration`): new step order, optional steps, two-wheeler only.
- Backend: vehicle type, which documents are required for approval, new declaration version `v2`.
- Admin portal: review screen must cope with missing optional documents.
- Save the declaration text in the repo (`docs/legal/captain-self-declaration-driver-v2.md`) AND in the database as an immutable version. Every acceptance is stored per captain (already how `self_declarations` works).

Out (needs a separate decision):
- Android driver app registration screens (same rules should apply there; confirm).
- Changing captains who already registered under the old rules.

## 5. Declaration text (DRAFT - **must be reviewed by a lawyer before launch**)

> I declare that all information, documents and photographs I submit to Lady Go are my own, genuine and accurate.
> I understand that Lady Go is a platform that connects riders with captains so that I can earn. Lady Go is not my
> employer and does not own, inspect or guarantee my vehicle or my documents.
> I am solely and fully responsible for holding a valid driving licence, vehicle registration certificate (RC),
> insurance, fitness certificate and any other permit or document the law requires for the vehicle I ride, and for
> following all traffic and transport laws.
> If I carry a fake, tampered, expired or missing licence, RC or other document, or ride without one, I alone am
> responsible for the consequences, including fines, penalties, accidents, injury, loss or legal action.
> Lady Go and its owners are not responsible or liable for any fraud, document tampering, misrepresentation or
> unlawful act by me, or for any resulting loss or claim, to the extent the law allows.
> I understand that a false declaration, or any document found to be fake, can lead to permanent removal from Lady Go.
> I accept this declaration freely and I have read and understood it.

Wamiq: send the final wording if it differs. "To the extent the law allows" is deliberate.

## 6. Risks and decisions needed BEFORE building (please answer)

HIGH-RISK / business flags:
1. **Safety and brand.** Lady Go is sold to women riders as safe and verified. Letting captains start with no
   licence is a legal and reputational risk (riding without a licence is an offence under the Motor Vehicles Act).
   A declaration shifts blame between the parties but **cannot remove the company's own legal duties or protect it
   from an accident or a police case**. Needs legal review. Option: keep the licence optional to *register*, but
   require it before the captain can go online or take her first ride.
2. **Rider-facing wording.** The site says "Captains verified before they go online". If the licence is optional,
   that claim may become untrue. Decide whether to change it.
3. **Insurance, vehicle photo and pollution certificate** are not in your list. Remove them from the flow, or keep them as optional?
4. **Existing admin approval** requires licence, RC, vehicle photo, insurance and selfie. Admin must be able to approve
   with the new minimum (RC + selfie + declaration), or approval blocks every new captain.
5. **Fitness certificate** is optional in your list. Confirm it is only for captains who have one.
6. "Save this document in our records": I plan (a) a copy in the repo and (b) an immutable DB version plus a stored
   acceptance (captain, time, version) for every captain. Is that what you mean? Do you also want a PDF copy
   downloadable or emailed to the captain?
7. "Customer will be self declaring": I read this as the **captain** accepting. Confirm.
8. Vehicle type: keep the existing `BIKE` value internally and show "Two-wheeler"? (Recommended.)

## 7. UI/UX flow (website)

1. Phone + email, code (unchanged).
2. Step 1 "Your two-wheeler": vehicle number, RC photo, fitness certificate marked "Optional - skip if you don't have one". No vehicle-type picker; shown as fixed "Two-wheeler".
3. Step 2 "Driving licence (optional)": a clear Skip button; helper text that it can be added later.
4. Step 3 "Selfie".
5. Step 4 "Self-declaration": full text in a scrollable box, one checkbox "I have read and agree", then Continue.
6. Review screen: shows done and skipped items; optional ones show "Skipped - you can add later".
Mobile width first. Plum theme, light mode (as in PR 81).

## 8. Data and API design

- `declaration_versions`: new Flyway migration inserts `v2` with the section 5 text. `v1` stays untouched (immutable).
- The current declaration version moves to `v2`. Existing `v1` acceptances stay on record.
- Vehicle: accept only two-wheeler at registration (validated server-side, not just in the UI).
- Documents: required-for-approval set becomes RC + selfie (+ declaration). LICENSE and FITNESS_CERTIFICATE (and possibly INSURANCE) become optional. Change in `AdminVerificationService` and its "missing" list. Admin UI shows "not provided".
- OpenAPI doc and generated clients regenerated (CI enforces this - PR 81 just failed on it).
- No new secrets, no money movement.

## 9. Security and compliance

- Documents stay private (existing upload rules: type and size checks, private storage).
- Declaration acceptance is stored with captain id, version and timestamp.
- Declaration wording needs legal sign-off (existing note in `docs/api/driver-onboarding.md` section 9).
- Run `/security-review` before merge (identity documents involved).

## 10. Build steps (after approval)

| # | Step | Model | Why |
|---|------|-------|-----|
| 1 | Settle the open points in section 6 with Wamiq (superpowers `brainstorming`) | Opus 5.5 | decisions |
| 2 | Backend: migration `v2`, two-wheeler validation, optional-document rules, tests first | Sonnet 5.5 | feature + tests |
| 3 | OpenAPI and regenerate clients (website, admin) | Haiku 5.5 | mechanical |
| 4 | Website wizard: new steps, skip, fixed vehicle type, declaration step | Sonnet 5.5 | feature + tests |
| 5 | Admin portal: "not provided" display, approval rule | Sonnet 5.5 | feature |
| 6 | Run as a user (desktop and mobile width): happy path, skip optional, bad inputs, resume later | Opus 5.5 | Rule 3 |
| 7 | `/code-review` and `/security-review` | Opus 5.5 | before merge |

Step 2 (backend) and step 4 (website) can run as parallel sessions once the API shape is fixed.

## 11. Test steps (as a user)

- Register with only RC photo + selfie + declaration: succeeds, shows pending review.
- Register with all optional items: succeeds.
- Continue without vehicle number, RC photo, selfie or declaration: blocked with a clear message.
- Submit a non-two-wheeler vehicle through the API: rejected.
- Close the browser midway and return: resumes at the right step.
- 375px width: no horizontal scroll, buttons reachable.
- Admin opens the application: sees "not provided" for skipped items and can approve.
- Declaration v2 stored for the captain; v1 captains unaffected.

## 12. Deploy and rollback

- Staging first. The Flyway migration is additive (one new row), so rollback = revert the app and set the current version back to `v1`.
- No production data deletes or updates.

## 13. Launch, marketing and measurement

- Update the captain page copy: "Register in 5 minutes - only your RC and a selfie to start".
- Weekly funnel: opened registration, code verified, step 1 done, selfie done, declaration accepted, submitted.
- Use `/schedule` for a weekly adoption report during the 60 days.

## 14. Web Admin tickets (drafted for Wamiq to paste; staging first)

**Ticket 1 - Approve captains with the reduced document set**
- Why: new registrations only provide RC, selfie and declaration; the review queue must not block them.
- Steps: (1) On staging open Admin > Verification queue. (2) Open a captain who skipped the licence. (3) Confirm "Licence: not provided" shows. (4) Approve.
- Acceptance: approval works with RC + selfie + declaration; skipped items show "not provided".
- Owner: Wamiq. Environment: staging, then production. Lifecycle: Ready for test, In testing/UAT, Test Failed, Ready To Deploy.

**Ticket 2 - Publish declaration v2 after legal review**
- Why: legal sign-off is needed on the wording before the new text is live.
- Steps: (1) Send section 5 to the lawyer. (2) Record the approved text in `docs/legal/captain-self-declaration-driver-v2.md`. (3) Confirm the v2 row exists in the staging DB. (4) Confirm a test captain sees v2.
- Acceptance: lawyer approval recorded; staging shows v2.
- Owner: Wamiq. Environment: staging first.

**Ticket 3 - Rider-facing "verified" wording**
- Why: the licence is optional, so "verified captains" may be untrue.
- Steps: decide the wording; update the Home, About and Safety copy.
- Acceptance: no claim on the site that the company cannot support.
- Owner: Wamiq (product). Environment: staging first.
