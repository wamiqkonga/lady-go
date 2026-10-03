import SwiftUI

@main
struct LadyGoCustomerApp: App {
    var body: some Scene {
        WindowGroup {
            HomeView(viewModel: HomeViewModel())
        }
    }
}
