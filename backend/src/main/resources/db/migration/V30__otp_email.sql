-- MVP 1 email-OTP channel (ladygo.otp.channel=email): the code is delivered to this address and, on a
-- successful LOGIN verify, the address is bound to users.email. NULL for phone/SMS-channel requests.
ALTER TABLE otp_requests ADD COLUMN email varchar(254);
