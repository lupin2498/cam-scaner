import SwiftUI

/// Where the scan flow starts, and for what.
enum ScanFlow: Identifiable, Equatable {
    case camera(targetDocId: String?)
    case imported(targetDocId: String?)
    case edit(docId: String, pageId: String, filterStep: Bool)

    var id: String {
        switch self {
        case .camera(let t): return "camera-\(t ?? "")"
        case .imported(let t): return "imported-\(t ?? "")"
        case .edit(let d, let p, let f): return "edit-\(d)-\(p)-\(f)"
        }
    }
}

struct ContentView: View {
    @EnvironmentObject private var session: ScanSession
    @EnvironmentObject private var repository: DocumentRepository
    @EnvironmentObject private var settings: Settings
    @EnvironmentObject private var signatures: SignatureStore
    @State private var flow: ScanFlow?
    @State private var openDocId: String?

    var body: some View {
        NavigationView {
            HomeView(flow: $flow, openDocId: $openDocId)
        }
        .navigationViewStyle(.stack)
        .fullScreenCover(item: $flow) { f in
            ScanFlowView(flow: f) { savedId in
                flow = nil
                // editing a page returns to where it was opened; a new scan opens its document
                if case .edit = f { return }
                if let id = savedId { openDocId = id }
            }
            .environmentObject(session)
            .environmentObject(repository)
            .environmentObject(settings)
            .environmentObject(signatures)
        }
        .onChange(of: session.pendingImport) { pending in
            // files handed over by another app open the crop step; an import started from a screen
            // of the app has already opened its flow
            guard pending else { return }
            session.pendingImport = false
            if flow == nil { flow = .imported(targetDocId: session.targetDocId) }
        }
    }
}
