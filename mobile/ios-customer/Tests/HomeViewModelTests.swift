import XCTest
@testable import LadyGoCustomer

@MainActor
final class HomeViewModelTests: XCTestCase {
    func testExposesConfiguredAPIBaseURL() {
        let viewModel = HomeViewModel(apiBaseURL: "https://api.test.example")
        XCTAssertEqual(viewModel.state.apiBaseURL, "https://api.test.example")
    }

    func testSkeletonNoteIsPresent() {
        let viewModel = HomeViewModel(apiBaseURL: "http://localhost")
        XCTAssertFalse(viewModel.state.phaseNote.isEmpty)
    }
}
