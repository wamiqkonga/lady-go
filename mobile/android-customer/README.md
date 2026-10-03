# Lady Go – Android (customer app)

Phase 1 skeleton: Kotlin, Jetpack Compose, MVVM (ViewModel + StateFlow). Application ID `com.ladygo.customer`.

## Status
**Written but not built.** The authoring machine had no Android SDK and blocked access to Google/Maven repositories, so this project has not been compiled. Library versions in `gradle/libs.versions.toml` are starting points; bump to current stable on the first build.

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

## Dependencies added later (each when first needed)
Hilt, Retrofit + Kotlin Serialization, Room, Google Maps SDK / Maps Compose, Firebase Cloud Messaging.
