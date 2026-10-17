# Lady Go – Android (customer app)

Phase 1 skeleton: Kotlin, Jetpack Compose, MVVM (ViewModel + StateFlow). Application ID `com.ladygo.customer`.

## Status
Slice 1 built: OTP login (`intendedRole=CUSTOMER`), onboarding gate (name + declaration), placeholder Home.
Booking and ride-in-progress follow - see `docs/product/customer-android-design.md`. Generated API client
lives in `app/openapi-client/` (regenerate with `gradle :app:openApiGenerateClient`; CI drift-checks it).

## Build
Requirements: JDK 17+, Android SDK (API 36), Gradle 8.13+ (or generate a wrapper: `gradle wrapper`).

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
gradle :app:lintDebug
```

## Configuration
- Debug builds call the backend at `http://10.0.2.2:8080` (emulator host alias).
- Release builds read `LADYGO_API_BASE_URL` from Gradle properties (CI secret); never commit real values.

## Dependencies added later
CameraX (ride selfie), STOMP client (live ride status), Google Maps/Places only if the platform `Geocoder` proves
inadequate for the pilot area.
