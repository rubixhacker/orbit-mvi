import SwiftUI
import ComposeApp
import KMPNativeCoroutinesAsync

struct PostListView: View {
    let viewModel = ViewModelHelper.shared.postListViewModel
    @State var state: PostListState?

    var body: some View {
        VStack {
            if let state = state {
                // Simple demonstration of state usage
                Text("State: \(state)")
            } else {
                Text("Loading...")
            }
        }
        .task {
            let observable = viewModel.observe()
            do {
                for try await state in observable.stateFlowNative {
                    self.state = state
                }
            } catch {
                print("Error: \(error)")
            }
        }
    }
}
