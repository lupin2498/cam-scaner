import SwiftUI
import LocalAuthentication

/// Shared state of the app: storage, settings and the scan in progress.
@MainActor
final class AppState: ObservableObject {
    let repository = DocumentRepository()
    let settings = Settings()
    let signatures: SignatureStore
    let session: ScanSession

    @Published var locked = false
    private var prompting = false

    init() {
        signatures = SignatureStore(directory: repository.root.deletingLastPathComponent())
        session = ScanSession(repository: repository, settings: settings)
        locked = settings.appLock
    }

    /// Asks for Face ID, Touch ID or the passcode; lets the user in when none is set up.
    func unlock() {
        guard !prompting else { return }
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            locked = false
            return
        }
        prompting = true
        context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Mở khóa Cam Scaner") { ok, _ in
            DispatchQueue.main.async {
                self.prompting = false
                if ok { self.locked = false }
            }
        }
    }

    static func lockAvailable() -> Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }
}

@main
struct CamScanerApp: App {
    @StateObject private var state = AppState()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ZStack {
                ContentView()
                if state.locked {
                    LockView { state.unlock() }
                        .transition(.opacity)
                }
            }
            .environmentObject(state)
            .environmentObject(state.repository)
            .environmentObject(state.settings)
            .environmentObject(state.session)
            .environmentObject(state.signatures)
            .preferredColorScheme(state.settings.colorScheme)
            .tint(Color.brand)
            .onChange(of: scenePhase) { phase in
                if phase == .background && state.settings.appLock { state.locked = true }
                if phase == .active && state.locked { state.unlock() }
            }
            .onOpenURL { url in
                state.session.importFiles([url], into: nil)
            }
        }
    }
}

/// Covers the app while it is locked.
struct LockView: View {
    let onUnlock: () -> Void

    var body: some View {
        ZStack {
            Color(UIColor.systemBackground).ignoresSafeArea()
            VStack(spacing: 20) {
                Image(systemName: "lock.fill").font(.system(size: 48)).foregroundColor(.accentColor)
                Text("Cam Scaner đang khóa").font(.title2.bold())
                Button("Mở khóa", action: onUnlock).buttonStyle(.borderedProminent)
            }
        }
    }
}
