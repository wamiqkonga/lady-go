-- Per-actor cap on staff invite emails (a SUPER_ADMIN can send at most this many invites per hour).
INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('admin.invite.max.per_hour', '10', 'INTEGER', 'GLOBAL', 'Max staff invite emails one SUPER_ADMIN can send per hour')
ON CONFLICT DO NOTHING;
