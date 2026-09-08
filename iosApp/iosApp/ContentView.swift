import SwiftUI
import ComposeApp

/// Muestra la UI de Compose Multiplatform. Toda la app vive en el framework `ComposeApp`.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView().ignoresSafeArea()
    }
}
