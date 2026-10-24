-- V33: support contact numbers shown in the driver app Help card
-- (docs/product/driver-android-slice4-design.md, Q1, ticket T12). Deliberately seeded EMPTY: an empty
-- value means "not configured" and GET /api/v1/support/contact then returns null for that field. The owner
-- sets the real numbers through Admin config; we never guess one. Additive; rollback = delete the rows.

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('support.phone', '', 'STRING', 'GLOBAL',
        'Support phone number shown to drivers and riders, digits with an optional leading + (8-15 digits). Empty = not configured'),
    ('support.whatsapp', '', 'STRING', 'GLOBAL',
        'Support WhatsApp number shown to drivers and riders, digits with an optional leading + (8-15 digits). Empty = not configured');
