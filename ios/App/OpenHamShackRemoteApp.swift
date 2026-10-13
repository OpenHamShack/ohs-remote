import SwiftUI

@main
struct OpenHamShackRemoteApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(.dark)
                #if DEBUG
                .task { DebugLaunch.orient() }
                #endif
        }
        .onChange(of: scenePhase) { _, phase in
            model.scenePhase(phase)
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let session = model.session {
            BoxView(session: session)
                .id(session.box.id)
        } else {
            BoxListView()
        }
    }
}
