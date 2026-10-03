# Lady Go – iOS (driver app)

Phase 1 skeleton: Swift, SwiftUI, MVVM (ObservableObject + async/await in later phases). Bundle ID `com.ladygo.driver`, iOS 16+.

## Status
**Written but not built.** The authoring machine was Linux with no Swift toolchain or Xcode, so this project has not been compiled. Verify on a Mac or in the macOS CI job.

## Project generation
The Xcode project is generated from `project.yml` with [XcodeGen](https://github.com/yonaskolb/XcodeGen) (keeps the project file out of version control and avoids merge conflicts):

```bash
brew install xcodegen
xcodegen generate
open LadyGoDriver.xcodeproj
```

## Build and test
```bash
xcodebuild -project LadyGoDriver.xcodeproj -scheme LadyGoDriver \
  -destination 'platform=iOS Simulator,name=iPhone 16' test
```

## Configuration
- `API_BASE_URL` build setting feeds Info.plist key `LadyGoAPIBaseURL`. Debug uses `http://localhost:8080`; Release must be overridden by CI/secret config.
- App Transport Security allows only local-network HTTP for development.

## Added later (when first needed)
Networking layer, Keychain token storage, Core Location, MapKit, push notifications.
