import SwiftUI
#if canImport(Translation)
import Translation
#endif

/// Recognised text of a page, editable, with copy and share (and the system translator on iOS 17.4+).
struct OcrView: View {
    @EnvironmentObject private var repository: DocumentRepository
    let docId: String
    let pageId: String

    @State private var text = ""
    @State private var running = true
    @State private var error: String?
    @State private var share = false
    @State private var translate = false

    var body: some View {
        Group {
            if running {
                VStack(spacing: 12) {
                    ProgressView()
                    Text("Đang nhận dạng chữ…")
                }
            } else if let e = error {
                Text("Đã xảy ra lỗi: \(e)").padding()
            } else if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text("Không tìm thấy chữ trong trang này").foregroundColor(.secondary)
            } else {
                VStack {
                    TextEditor(text: $text).padding(8).background(Color(UIColor.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
                    HStack(spacing: 12) {
                        Button {
                            UIPasteboard.general.string = text
                        } label: { Label("Sao chép", systemImage: "doc.on.doc").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
                        Button { share = true } label: { Label("Chia sẻ", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
                    }
                }
                .padding()
            }
        }
        .navigationTitle("Nhận dạng văn bản (OCR)")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !running && !text.isEmpty && TranslationModifier.available {
                Button { translate = true } label: { Image(systemName: "character.book.closed") }
            }
        }
        .sheet(isPresented: $share) { ShareSheet(items: [text]) }
        .modifier(TranslationModifier(isPresented: $translate, text: text))
        .task { await recognize() }
    }

    private func recognize() async {
        if let stored = repository.get(docId)?.pages.first(where: { $0.id == pageId })?.ocrText {
            text = stored
            running = false
            return
        }
        guard let image = UIImage(contentsOfFile: repository.pageURL(docId, pageId).path) else {
            error = "Không đọc được ảnh trang"
            running = false
            return
        }
        do {
            text = try await OCR.recognize(image.scaledDown(maxDim: 2400)).text
            repository.setOcrText(docId, pageId, text)
        } catch {
            self.error = error.localizedDescription
        }
        running = false
    }
}

/// Apple's translation sheet where it exists (iOS 17.4+); nothing otherwise.
struct TranslationModifier: ViewModifier {
    @Binding var isPresented: Bool
    let text: String

    static var available: Bool {
        #if canImport(Translation)
        if #available(iOS 17.4, *) { return true }
        #endif
        return false
    }

    @ViewBuilder
    func body(content: Content) -> some View {
        #if canImport(Translation)
        if #available(iOS 17.4, *) {
            content.translationPresentation(isPresented: $isPresented, text: text)
        } else {
            content
        }
        #else
        content
        #endif
    }
}
