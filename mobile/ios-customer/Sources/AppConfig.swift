import Foundation

/// Runtime configuration read from Info.plist (populated from build settings). No hard-coded URLs.
enum AppConfig {
    static var apiBaseURL: String {
        Bundle.main.object(forInfoDictionaryKey: "LadyGoAPIBaseURL") as? String ?? ""
    }
}
