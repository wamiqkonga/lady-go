# Push notifications (FCM) setup

Status: built 2026-10-31. Delivery to real phones is **not yet verified**: it needs a Firebase project, which is created by
the project owner (see section 1). Until then the apps build and run with push switched off.

## What is built

| Part | Where |
|---|---|
| Device registration | `POST /api/v1/notifications/devices`, `DELETE /api/v1/notifications/devices` (rider and driver tokens; the app is taken from the caller's role) |
| Sender SPI | `notifications/push`: `LoggingPushSender` (dev/test), `FcmPushSender` (FCM HTTP v1), `DisabledPushSender` |
| Triggers | `RidePushListener`, after each ride transition commits, off the request thread |
| Apps | `push/` package in both Android apps: token registration, notification channel `ride_updates`, foreground display |

Who is told what:

| Transition | Recipient | Event type |
|---|---|---|
| Driver assigned (an offer) | driver | `RIDE_OFFER` |
| Driver accepted | rider | `DRIVER_ACCEPTED` |
| Driver arrived | rider | `DRIVER_ARRIVED` |
| Ride completed | rider | `RATE_RIDE` |
| Cancelled by driver | rider | `RIDE_CANCELLED_BY_DRIVER` |
| Cancelled by rider (a driver was assigned) | driver | `RIDE_CANCELLED_BY_RIDER` |
| No driver found | rider | `NO_DRIVER_FOUND` |

Privacy: titles and bodies are generic (they show on a locked screen). They never contain an address, name, PIN or fare. The data
payload holds only the booking id and event type. Delivery logs (`notification_logs`) keep the event type and outcome, never the
message or the token.

## 1. One-time Firebase setup (project owner)

1. Create a Firebase project and add two Android apps: `com.ladygo.customer` and `com.ladygo.driver` (add the debug and release
   SHA-1 fingerprints for each).
2. Download each app's `google-services.json` and put it at `mobile/android-customer/app/google-services.json` and
   `mobile/android-driver/app/google-services.json`. **They are gitignored; never commit them.** Use CI secrets for release builds.
3. Create a service account with the *Firebase Cloud Messaging API (V1)* role and download its JSON key.

## 2. Backend configuration (staging and prod)

| Variable | Value |
|---|---|
| `LADYGO_PUSH_PROVIDER` | `fcm` (default in staging/prod is `disabled`; `logging` is refused there) |
| `LADYGO_FCM_PROJECT_ID` | the Firebase project id |
| `LADYGO_FCM_SERVICE_ACCOUNT_JSON` | the full service-account key JSON, from the secret store only |

Startup fails if `fcm` is chosen without the last two.

## 3. Check it works

1. Build both apps with their `google-services.json` in place, sign in, and accept the notification prompt.
2. Confirm a row in `device_tokens` for the user.
3. Book a ride and have a driver accept it: the rider's phone shows "Your driver is on the way", also with the app closed.
4. Cancel as the rider after a driver accepted: the driver's phone shows "The rider cancelled".

## Known limits

- A forced sign-out (refresh token rejected) cannot unregister the phone, because there is no valid session. The token is removed
  when FCM reports it dead, or moves to whoever signs in on that phone next.
- Pushes are best effort. The apps still poll while open, so a missed push never blocks a ride.
- iOS is not covered (the iOS apps are skeletons).
