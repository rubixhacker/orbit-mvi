import UIKit
import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.mainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        TabView {
            ComposeView()
                    .ignoresSafeArea(.keyboard) // Compose has own keyboard handler
                    .tabItem {
                        Label("Compose", systemImage: "star")
                    }

            PostListView()
                .tabItem {
                    Label("SwiftUI", systemImage: "list.bullet")
                }
        }
    }
}



