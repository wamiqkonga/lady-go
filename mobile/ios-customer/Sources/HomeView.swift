import SwiftUI

struct HomeView: View {
    @ObservedObject var viewModel: HomeViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Lady Go")
                .font(.largeTitle.bold())
            Text("Safe, simple rides for women.")
                .font(.title3)
            Text(viewModel.state.phaseNote)
                .font(.footnote)
                .foregroundStyle(.secondary)
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

#Preview {
    HomeView(viewModel: HomeViewModel(apiBaseURL: "http://localhost:8080"))
}
