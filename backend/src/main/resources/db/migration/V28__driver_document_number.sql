-- V28: driver_documents.document_number - closes a gap against the already-approved Phase 5 design
-- (docs/api/driver-onboarding.md section 0: "driving licence (photo + number + expiry)"). expires_at
-- already existed (V16) but nothing ever set it; this adds the missing number field alongside it.

ALTER TABLE driver_documents ADD COLUMN document_number varchar(100);
