-- V27: SMS send cost/abuse caps (provider-agnostic SmsSender adapter, docs/development/sms-provider-setup.md).
-- Separate from otp.resend.max.per.hour/otp.resend.cooldown.seconds (how often ONE number can ASK for
-- a code) - these bound actual SMS *send volume*, per number, per IP, and globally per day.

INSERT INTO configuration (key, value, value_type, scope, description) VALUES
    ('sms.send.max_per_number.daily', '20', 'INTEGER', 'GLOBAL',
        'Max OTP SMS sends to one phone number per rolling 24h, independent of the resend cooldown'),
    ('sms.send.max_per_ip.daily', '50', 'INTEGER', 'GLOBAL',
        'Max OTP SMS sends attributable to one client IP per rolling 24h'),
    ('sms.send.max_global.daily', '5000', 'INTEGER', 'GLOBAL',
        'Daily cost cap across every OTP SMS send; hitting it blocks further sends and logs an alert');
