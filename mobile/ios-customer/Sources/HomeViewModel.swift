import Foundation

struct HomeState: Equatable {
    var apiBaseURL: String
    var phaseNote: String = "Skeleton build. Features arrive in later development phases."
}

/// Placeholder MVVM view model. Real screens (auth, booking, ...) follow the roadmap.
@MainActor
final class HomeViewModel: ObservableObject {
    @Published private(set) var state: HomeState

    init(apiBaseURL: String = AppConfig.apiBaseURL) {
        self.state = HomeState(apiBaseURL: apiBaseURL)
    }
}
