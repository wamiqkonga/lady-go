-- Captain self-declaration v2: the captain alone is responsible for licence, RC and other documents; Lady Go
-- is only the platform. Audience-scoped so riders keep v1 (the captain text makes no sense for a rider).
-- Placeholder wording until legal review (docs/development/plans/2026-10-31-captain-registration-amendments.md).
-- Versions are immutable; this adds a row and never edits v1.
ALTER TABLE declaration_versions
    ADD COLUMN audience varchar(10) NOT NULL DEFAULT 'ALL' CHECK (audience IN ('ALL', 'CUSTOMER', 'DRIVER'));

INSERT INTO declaration_versions (version, audience, text) VALUES
    ('driver-v2', 'DRIVER', 'I declare that all information, documents and photographs I submit to Lady Go are my own, genuine and accurate.

I understand that Lady Go is a platform that connects riders with captains so that I can earn. Lady Go is not my employer and does not own, inspect or guarantee my vehicle or my documents.

I am solely and fully responsible for holding a valid driving licence, vehicle registration certificate (RC), insurance, fitness certificate and any other permit or document the law requires for the vehicle I ride, and for following all traffic and transport laws.

If I carry a fake, tampered, expired or missing licence, RC or other document, or ride without one, I alone am responsible for the consequences, including fines, penalties, accidents, injury, loss or legal action.

Lady Go and its owners are not responsible or liable for any fraud, document tampering, misrepresentation or unlawful act by me, or for any resulting loss or claim, to the extent the law allows.

I understand that a false declaration, or any document found to be fake, can lead to permanent removal from Lady Go.

I accept this declaration freely and I have read and understood it.');
