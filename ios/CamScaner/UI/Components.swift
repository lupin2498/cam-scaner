import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

/// Share sheet for files or text.
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

/// Picks one or several photos from the library. The sheet closes at once; the photos are handed
/// over when loaded (an empty list when the user cancels).
struct PhotoPicker: UIViewControllerRepresentable {
    let limit: Int
    let onPicked: ([UIImage]) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration(photoLibrary: .shared())
        config.filter = .images
        config.selectionLimit = limit
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {
        context.coordinator.parent = self
    }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        var parent: PhotoPicker
        init(_ parent: PhotoPicker) { self.parent = parent }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            // SwiftUI closes its own sheet, so the binding that shows it stays in step
            parent.dismiss()
            let onPicked = parent.onPicked
            guard !results.isEmpty else { return }
            let group = DispatchGroup()
            var images = [UIImage?](repeating: nil, count: results.count)
            for (i, r) in results.enumerated() where r.itemProvider.canLoadObject(ofClass: UIImage.self) {
                group.enter()
                r.itemProvider.loadObject(ofClass: UIImage.self) { object, _ in
                    DispatchQueue.main.async {
                        images[i] = object as? UIImage
                        group.leave()
                    }
                }
            }
            group.notify(queue: .main) { onPicked(images.compactMap { $0 }) }
        }
    }
}

/// Picks files (images and PDFs) from the Files app.
struct FilePicker: UIViewControllerRepresentable {
    let types: [UTType]
    let onPicked: ([URL]) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onPicked: onPicked) }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: true)
        picker.allowsMultipleSelection = true
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPicked: ([URL]) -> Void
        init(onPicked: @escaping ([URL]) -> Void) { self.onPicked = onPicked }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { onPicked(urls) }
    }
}

/// Saves a file to a user-chosen place in Files.
struct FileExporter: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        UIDocumentPickerViewController(forExporting: [url], asCopy: true)
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}
}

/// Blocking progress indicator drawn over the current screen.
struct ProgressOverlay: View {
    let message: String
    var progress: Double? = nil

    var body: some View {
        ZStack {
            Color.black.opacity(0.45).ignoresSafeArea()
            VStack(spacing: 14) {
                HStack(spacing: 14) {
                    ProgressView()
                    Text(message)
                }
                if let p = progress { ProgressView(value: p).frame(width: 220) }
            }
            .padding(24)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        }
    }
}

/// Image loaded from a file and cached by path and version.
struct FileImage: View {
    let url: URL
    let version: Double
    var contentMode: ContentMode = .fit

    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image = image {
                Image(uiImage: image).resizable().aspectRatio(contentMode: contentMode)
            } else {
                Color(UIColor.secondarySystemBackground)
            }
        }
        .task(id: "\(url.path)#\(version)") {
            let path = url.path
            let loaded = await Task.detached(priority: .utility) { UIImage(contentsOfFile: path)?.scaledDown(maxDim: 1600) }.value
            image = loaded
        }
    }
}

/// A row with a title, optional subtitle and a switch.
struct ToggleRow: View {
    let title: String
    var subtitle: String? = nil
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                if let s = subtitle { Text(s).font(.footnote).foregroundColor(.secondary) }
            }
        }
    }
}

/// Horizontal row of selectable chips.
struct ChipRow<T: Hashable>: View {
    let items: [T]
    let label: (T) -> String
    @Binding var selection: T

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(items, id: \.self) { item in
                    Chip(text: label(item), selected: item == selection) { selection = item }
                }
            }
            .padding(.vertical, 4)
        }
    }
}

struct Chip: View {
    let text: String
    let selected: Bool
    var id: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(.subheadline)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(selected ? Color.accentColor : Color(UIColor.secondarySystemFill), in: Capsule())
                .foregroundColor(selected ? .white : .primary)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id ?? "chip.\(text)")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Text input dialog (name, text box...).
struct TextInputSheet: View {
    let title: String
    let placeholder: String
    @State var text: String
    var multiline = false
    let onDone: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            Form {
                if multiline {
                    TextEditor(text: $text).frame(minHeight: 140).accessibilityIdentifier("input.field")
                } else {
                    TextField(placeholder, text: $text).accessibilityIdentifier("input.field")
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Hủy") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Xong") { onDone(text); dismiss() }
                        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .accessibilityIdentifier("input.done")
                }
            }
        }
    }
}

/// Holds back what should follow a sheet (opening the scan flow, another sheet, a message) until
/// the sheet has fully gone, so the next presentation does not collide with the one leaving.
final class AfterSheetQueue: ObservableObject {
    var onScreen = false
    private var pending: [() -> Void] = []

    func run(_ action: @escaping () -> Void) {
        guard onScreen else { return action() }
        pending.append(action)
        // in case the sheet's dismissal is never reported
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in self?.flush() }
    }

    func sheetGone() {
        onScreen = false
        flush()
    }

    private func flush() {
        let actions = pending
        pending = []
        actions.forEach { $0() }
    }
}

struct DeferredSheet<Sheet: View>: ViewModifier {
    @Binding var isPresented: Bool
    let sheet: (_ then: @escaping (@escaping () -> Void) -> Void) -> Sheet
    @StateObject private var queue = AfterSheetQueue()

    func body(content: Content) -> some View {
        content.sheet(isPresented: $isPresented, onDismiss: { queue.sheetGone() }) {
            sheet { action in queue.run(action) }
                .onAppear { queue.onScreen = true }
        }
    }
}

/// Short message shown over the bottom of the screen for a moment; unlike an alert it never
/// competes with sheets being presented or dismissed.
struct Toast: ViewModifier {
    @Binding var message: String?

    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let m = message {
                Text(m)
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
                    .foregroundColor(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Color.black.opacity(0.8), in: Capsule())
                    .padding(.horizontal, 24)
                    .padding(.bottom, 96)
                    .transition(.opacity)
                    .accessibilityIdentifier("toast")
                    .onTapGesture { message = nil }
                    .task(id: m) {
                        try? await Task.sleep(nanoseconds: 2_800_000_000)
                        if message == m { withAnimation { message = nil } }
                    }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: message)
    }
}

extension View {
    /// A sheet whose content can run follow-up actions once it has gone: `then { ... }`.
    func deferredSheet<S: View>(isPresented: Binding<Bool>, @ViewBuilder content: @escaping (_ then: @escaping (@escaping () -> Void) -> Void) -> S) -> some View {
        modifier(DeferredSheet(isPresented: isPresented, sheet: content))
    }

    func toast(_ message: Binding<String?>) -> some View {
        modifier(Toast(message: message))
    }
}

extension Color {
    static let brand = Color(red: 14 / 255, green: 159 / 255, blue: 110 / 255)
}

func formatSize(_ bytes: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
}

func formatDate(_ t: Double) -> String {
    let f = DateFormatter()
    f.dateStyle = .short
    f.timeStyle = .short
    return f.string(from: Date(timeIntervalSince1970: t))
}

func safeFileName(_ name: String) -> String {
    let bad = CharacterSet(charactersIn: "\\/:*?\"<>|\n\r\t")
    let cleaned = name.components(separatedBy: bad).joined(separator: "_").trimmingCharacters(in: .whitespaces)
    return String((cleaned.isEmpty ? "scan" : cleaned).prefix(80))
}
