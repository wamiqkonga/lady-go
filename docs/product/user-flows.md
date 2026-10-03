# Lady Go — User Flows (Customer and Driver)

Status: Draft v0.1 · Phase 0

## 1. Booking state machine

```text
REQUESTED -> SEARCHING_DRIVER -> DRIVER_ASSIGNED -> DRIVER_ACCEPTED
          -> DRIVER_ARRIVING -> DRIVER_ARRIVED -> RIDE_STARTED
          -> RIDE_COMPLETED -> PAYMENT_PENDING -> COMPLETED

Any state before RIDE_STARTED -> CANCELLED
SEARCHING_DRIVER -> CANCELLED (no driver found / customer cancel)
DRIVER_ASSIGNED -> SEARCHING_DRIVER (driver rejected or timed out)
DRIVER_ACCEPTED / DRIVER_ARRIVING / DRIVER_ARRIVED -> SEARCHING_DRIVER (driver cancels; re-match)
```

Rules:
- Transitions are validated in one `BookingStateMachine` service; controllers never set status directly.
- Each transition writes a `booking_events` row (who, from, to, reason, time) which powers the admin timeline and audit trail.
- After RIDE_STARTED a customer cannot cancel; they can only end the ride via the support/safety path.
- PAYMENT_PENDING moves to COMPLETED only after server-confirmed payment, or cash confirmation by the driver.

## 2. Customer flows

### 2.1 Registration and login
1. Enter phone number -> backend sends OTP (rate limited, expiry 5 min, max attempts).
2. Enter OTP -> backend verifies -> issues access + refresh token.
3. New user completes name (and optional photo, emergency contact).
4. Tokens stored in Keystore (Android) / Keychain (iOS).
5. Logout revokes refresh token. Forgot/reset account is the same OTP flow (account recovery by phone).

### 2.2 Book a ride
1. Open app, grant location permission (or search a pickup manually).
2. Choose pickup and destination.
3. App calls fare estimate: distance, duration, fare per ride type.
4. Select ride type and payment method, confirm.
5. Booking created (REQUESTED -> SEARCHING_DRIVER). Customer sees searching screen with cancel option.
6. Driver assigned and accepted: customer sees driver name, photo, rating, vehicle, plate, ETA, and Ride PIN.
7. Live tracking of driver via WebSocket; push notification on arrival.
8. At pickup, driver enters the PIN; ride starts.
9. Live ride tracking, share-trip link and SOS available throughout.
10. Ride completes, final fare shown, payment, rating, receipt.

### 2.3 Cancel
- Before assignment: free cancel.
- After assignment: configurable grace window, then fee per cancellation policy.
- Reason required; event recorded.

### 2.4 No driver found
- After `ride.max.search.time` the booking goes to CANCELLED with reason NO_DRIVER_FOUND; customer is notified and can retry.

### 2.5 Safety flows
- **SOS:** long-press or confirm button -> incident created with current location and ride context -> admin safety queue alerted -> emergency contact notified (SMS with trip link).
- **Share trip:** generates a revocable, expiring tokenised link showing live location and driver/vehicle details only; expires shortly after ride end.
- **Report issue:** after or during ride, free text + category -> support queue.

### 2.6 Profile
View/edit profile, photo, emergency contact, saved locations (home/work), ride history, preferences. Phone/email change requires OTP re-verification.

## 3. Driver flows

### 3.1 Onboarding
1. Register with phone + OTP.
2. Enter personal information, upload photo, government ID, driving licence.
3. Add vehicle (type, make, model, colour, capacity, registration number) and upload registration documents.
4. Submit for verification (status PENDING_REVIEW).
5. Admin approves or rejects (with reason, can request re-upload). Driver is notified.
6. Only APPROVED drivers with an approved vehicle can go online.

### 3.2 Availability
- OFFLINE -> ONLINE: app starts sending location (adaptive interval).
- ONLINE -> BUSY: when an offer is outstanding or the driver pauses.
- ONLINE -> ON_RIDE: after accepting a ride.
- ONLINE/BUSY -> OFFLINE: driver goes offline; location sending stops.
- SUSPENDED: set by admin; forces OFFLINE, blocks going online.

### 3.3 Ride lifecycle
1. Receive ride request (pickup, distance, estimated fare) with countdown (`driver.response.timeout`).
2. Accept or reject. Timeout counts as a rejection; matching moves to the next candidate.
3. Navigate to pickup (external navigation hand-off), driver may cancel with reason (tracked).
4. Mark arrived. Customer is notified.
5. Enter customer PIN -> ride started.
6. Complete ride at destination -> final fare computed server-side.
7. Cash: driver confirms cash received. Online: waits for server-confirmed payment.
8. Driver rates the customer (post-MVP optional); earnings updated.

### 3.4 Earnings and history
Daily/weekly earnings, ride history, per-ride breakdown (fare, service fee, net).

## 4. Edge cases to design for
- Driver app loses connectivity mid-ride: server keeps ride state; app resumes from server on reconnect.
- Two requests racing for one driver: assignment uses an atomic claim (Redis lock/DB constraint).
- Customer app killed during ride: ride continues; state restored on relaunch.
- Driver suspended during a ride: ride completes under admin supervision; no new offers.
- Duplicate payment webhooks: idempotent processing.
- Fare disputes: admin may adjust the fare with an audited reason.
