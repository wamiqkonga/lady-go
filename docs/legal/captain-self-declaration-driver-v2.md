# Captain self-declaration - version `driver-v2`

- Status: **DRAFT wording - needs legal review before launch** (see docs/development/plans/2026-10-31-captain-registration-amendments.md, Ticket 2).
- Applies to: captains (subject type DRIVER) only. Riders keep version `v1`.
- Where it lives: the `declaration_versions` table (migration `V35__captain_declaration_v2.sql`). A version is immutable.
  If the wording changes, add a NEW version; never edit this one.
- Record of acceptance: each captain's acceptance is a row in `self_declarations` (captain id, version, time).
- This file must match the migration text exactly.

---

I declare that all information, documents and photographs I submit to Lady Go are my own, genuine and accurate.

I understand that Lady Go is a platform that connects riders with captains so that I can earn. Lady Go is not my employer and does not own, inspect or guarantee my vehicle or my documents.

I am solely and fully responsible for holding a valid driving licence, vehicle registration certificate (RC), insurance, fitness certificate and any other permit or document the law requires for the vehicle I ride, and for following all traffic and transport laws.

If I carry a fake, tampered, expired or missing licence, RC or other document, or ride without one, I alone am responsible for the consequences, including fines, penalties, accidents, injury, loss or legal action.

Lady Go and its owners are not responsible or liable for any fraud, document tampering, misrepresentation or unlawful act by me, or for any resulting loss or claim, to the extent the law allows.

I understand that a false declaration, or any document found to be fake, can lead to permanent removal from Lady Go.

I accept this declaration freely and I have read and understood it.
