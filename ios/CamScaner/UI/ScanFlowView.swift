import SwiftUI

/// The scan flow shown full screen: camera → crop → filter → saved document.
struct ScanFlowView: View {
    @EnvironmentObject private var session: ScanSession
    let flow: ScanFlow
    let onFinished: (String?) -> Void

    enum Step { case camera, crop, filter }
    @State private var step: Step
    @State private var finished = false

    init(flow: ScanFlow, onFinished: @escaping (String?) -> Void) {
        self.flow = flow
        self.onFinished = onFinished
        switch flow {
        case .camera: _step = State(initialValue: .camera)
        case .imported: _step = State(initialValue: .crop)
        case .edit(_, _, let filterStep): _step = State(initialValue: filterStep ? .filter : .crop)
        }
    }

    private var fromCamera: Bool {
        if case .camera = flow { return true }
        return false
    }

    private var startedAtFilter: Bool {
        if case .edit(_, _, true) = flow { return true }
        return false
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            switch step {
            case .camera:
                CameraView(onDone: { step = .crop }, onCancel: cancel)
            case .crop:
                if session.pages.isEmpty && !session.importing {
                    emptyState
                } else {
                    CropView(canAddPages: fromCamera, onBack: { if fromCamera { step = .camera } else { cancel() } }, onNext: { step = .filter })
                }
            case .filter:
                if session.pages.isEmpty {
                    emptyState
                } else {
                    FilterView(onBack: { if startedAtFilter { cancel() } else { step = .crop } }, onSave: save)
                }
            }
            if session.importing { ProgressOverlay(message: "Đang nhập…") }
            if session.saving, let p = session.saveProgress {
                ProgressOverlay(message: "Đang lưu trang \(p.0)/\(p.1)…", progress: p.1 > 0 ? Double(p.0) / Double(p.1) : nil)
            }
        }
        .preferredColorScheme(.dark)
        .onChange(of: session.cropReady) { ready in
            if ready {
                session.cropReady = false
                if step == .camera { step = .crop }
            }
        }
        .alert(session.errorMessage ?? "", isPresented: Binding(get: { session.errorMessage != nil }, set: { if !$0 { session.errorMessage = nil } })) {
            Button("OK") {}
        }
        .interactiveDismissDisabled()
    }

    private var emptyState: some View {
        VStack(spacing: 16) {
            Image(systemName: "doc.viewfinder").font(.system(size: 48)).foregroundColor(.secondary)
            Text("Không còn trang nào").foregroundColor(.secondary)
            if fromCamera {
                Button("Chụp lại") { step = .camera }.buttonStyle(.borderedProminent)
            }
            Button("Đóng") { cancel() }.buttonStyle(.bordered)
        }
    }

    private func cancel() {
        guard !finished else { return }
        finished = true
        session.abandon()
        onFinished(nil)
    }

    private func save() {
        guard !finished, !session.saving else { return }
        Task {
            do {
                let id = try await session.save()
                finished = true
                onFinished(id)
            } catch {
                session.errorMessage = "Không lưu được: \(error.localizedDescription)"
            }
        }
    }
}
