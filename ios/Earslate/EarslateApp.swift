import SwiftUI

@main
struct EarslateApp: App {
    @StateObject private var model = TranslationViewModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
        }
    }
}
