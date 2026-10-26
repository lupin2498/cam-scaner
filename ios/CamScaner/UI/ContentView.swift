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
        }
        .onChange(of: session.pendingImport) { pending in
            // files handed over by another app open the crop step
            if pending && flow == nil {
                session.pendingImport = false
                flow = .imported(targetDocId: session.targetDocId)
            }
        }
    }
}
