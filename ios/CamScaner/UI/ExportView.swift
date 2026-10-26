import SwiftUI

/// PDF options and the other exports of a document.
struct ExportView: View {
    @EnvironmentObject private var repository: DocumentRepository
    @EnvironmentObject private var settings: Settings
    let docId: String

    @State private var options: PdfOptions
    @State private var result: URL?
    @State private var progress: Double?
    @State private var working: String?
    @State private var share = false
    @State private var save = false
    @State private var message: String?
    @State private var passwordOn = false
    @State private var watermarkOn = false

    init(docId: String) {
        self.docId = docId
        _options = State(initialValue: PdfOptions(fileName: "scan"))
    }

    private var doc: ScanDocument? { repository.get(docId) }

    var body: some View {
        ZStack {
            Form {
                if let r = result { resultSection(r) }
                Section("Tên tệp") { TextField("Tên tệp", text: $options.fileName) }
                Section("Bố cục") {
                    if let doc = doc { LayoutPreview(doc: doc, options: options) }
                    picker("Khổ giấy", PaperSize.allCases, $options.paperSize) { $0.label }
                    if options.paperSize == .auto {
                        Text("Tự nhận khổ A4/Letter/Legal/thẻ theo tỉ lệ trang; trang đã cắt theo khổ nào thì ra đúng khổ đó").font(.footnote).foregroundColor(.secondary)
                    }
                    picker("Hướng trang", PageOrientation.allCases, $options.orientation) { $0.label }
                    picker("Lề trang", PageMargin.allCases, $options.margin) { $0.label }
                    picker("Chất lượng", PdfQuality.allCases, $options.quality) { $0.label }
                    Picker("Số trang trên mỗi tờ", selection: $options.pagesPerSheet) {
                        ForEach([1, 2, 4], id: \.self) { Text("\($0) trang/tờ").tag($0) }
                    }
                    .pickerStyle(.segmented)
                }
                Section("Tùy chọn nâng cao") {
                    ToggleRow(title: "PDF tìm kiếm được (lớp chữ OCR)", subtitle: "Có thể tìm kiếm, bôi đen và sao chép chữ trong PDF", isOn: $options.ocrLayer)
                    ToggleRow(title: "Ghép 2 mặt thẻ (CCCD) vào 1 trang A4", subtitle: "Thẻ in đúng kích thước thật", isOn: $options.mergeIdCards)
                    ToggleRow(title: "Đánh số trang", isOn: $options.pageNumbers)
                    Toggle("Đặt mật khẩu PDF", isOn: $passwordOn.onChange { if !$0 { options.password = "" } })
                    if passwordOn { SecureField("Mật khẩu", text: $options.password) }
                    Toggle("Thêm hình mờ (watermark)", isOn: $watermarkOn.onChange { if !$0 { options.watermark = "" } })
                    if watermarkOn { TextField("Nội dung hình mờ", text: $options.watermark) }
                }
                Section("Xuất dạng khác") {
                    Button { printPdf() } label: { Label("In", systemImage: "printer") }
                    Button { saveToPhotos() } label: { Label("Lưu ảnh vào thư viện", systemImage: "photo.on.rectangle") }
                    Button { exportLongImage() } label: { Label("Ảnh dài", systemImage: "rectangle.portrait.arrowtriangle.2.outward") }
                    Button { exportText() } label: { Label("Văn bản (TXT)", systemImage: "doc.plaintext") }
                        .accessibilityIdentifier("export.txt")
                    Button { exportDocx(withImages: false) } label: { Label("Word (DOCX)", systemImage: "doc.text") }
                    Button { exportDocx(withImages: true) } label: { Label("Word có kèm ảnh trang", systemImage: "doc.richtext") }
                }
                Section {
                    Button { exportPdf() } label: {
                        Label("Tạo PDF", systemImage: "doc.richtext").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("export.create")
                    .disabled((doc?.pages.isEmpty ?? true) || options.fileName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            if let p = progress { ProgressOverlay(message: "Đang tạo PDF… \(Int(p * 100))%", progress: p) }
            if let w = working { ProgressOverlay(message: w) }
        }
        .navigationTitle("Xuất PDF")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            var o = settings.pdfOptions
            o.fileName = doc?.name ?? "scan"
            o.password = ""
            options = o
            passwordOn = false
            watermarkOn = !o.watermark.trimmingCharacters(in: .whitespaces).isEmpty
        }
        .onChange(of: options) { _ in if result?.pathExtension == "pdf" { result = nil } }
        .sheet(isPresented: $share) { if let r = result { ShareSheet(items: [r]) } }
        .sheet(isPresented: $save) { if let r = result { FileExporter(url: r) } }
        .toast($message)
    }

    private func picker<T: Hashable & Identifiable>(_ title: String, _ items: [T], _ selection: Binding<T>, label: @escaping (T) -> String) -> some View {
        Picker(title, selection: selection) {
            ForEach(items) { Text(label($0)).tag($0) }
        }
    }

    private func resultSection(_ url: URL) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Label("Đã tạo tệp (\(formatSize(Int64((try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0))))", systemImage: "checkmark.circle.fill")
                    .foregroundColor(.accentColor)
                    .accessibilityIdentifier("export.done")
                Text(url.lastPathComponent).font(.footnote).foregroundColor(.secondary).accessibilityIdentifier("export.file")
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        Button("Chia sẻ") { share = true }.buttonStyle(.bordered)
                        Button("Lưu vào Tệp") { save = true }.buttonStyle(.bordered)
                        if url.pathExtension == "pdf" { Button("In") { Exporters.print(url, name: options.fileName) }.buttonStyle(.bordered) }
                    }
                }
            }
        }
    }

    private var exportDir: URL { FileManager.default.temporaryDirectory.appendingPathComponent("exports", isDirectory: true) }

    private func exportPdf(then: ((URL) -> Void)? = nil) {
        guard let doc = doc, progress == nil else { return }
        settings.pdfOptions = options
        let inputs = doc.pages.map { PdfExporter.Input(page: $0, imageURL: repository.pageURL(docId, $0.id)) }
        let url = exportDir.appendingPathComponent("\(safeFileName(options.fileName)).pdf")
        progress = 0
        result = nil
        Task {
            do {
                try await PdfExporter.export(name: options.fileName, pages: inputs, options: options, to: url) { p in progress = p }
                result = url
                then?(url)
            } catch {
                message = "Đã xảy ra lỗi: \(error.localizedDescription)"
            }
            progress = nil
        }
    }

    private func printPdf() {
        if let r = result, r.pathExtension == "pdf" { Exporters.print(r, name: options.fileName) } else { exportPdf { Exporters.print($0, name: options.fileName) } }
    }

    private func saveToPhotos() {
        guard let doc = doc else { return }
        working = "Đang lưu ảnh…"
        Task {
            do {
                let n = try await Exporters.saveToPhotos(doc.pages.map { repository.pageURL(docId, $0.id) })
                message = "Đã lưu \(n) ảnh vào thư viện"
            } catch {
                message = error.localizedDescription
            }
            working = nil
        }
    }

    private func exportLongImage() {
        guard let doc = doc else { return }
        working = "Đang ghép ảnh…"
        let pages = doc.pages.map { (repository.pageURL(docId, $0.id), $0.width, $0.height) }
        let url = exportDir.appendingPathComponent("\(safeFileName(options.fileName)).jpg")
        Task.detached {
            do {
                try Exporters.longImage(pages: pages, to: url)
                await MainActor.run { result = url; working = nil }
            } catch {
                await MainActor.run { message = "Đã xảy ra lỗi: \(error.localizedDescription)"; working = nil }
            }
        }
    }

    /// Reads the pages that have no text yet and returns the document with all texts.
    private func ensureText() async -> ScanDocument? {
        guard var doc = doc else { return nil }
        let missing = doc.pages.filter { $0.ocrText == nil }
        for (i, page) in missing.enumerated() {
            working = "Đang nhận dạng chữ trang \(i + 1)/\(missing.count)…"
            if let image = UIImage(contentsOfFile: repository.pageURL(docId, page.id).path) {
                let text = (try? await OCR.recognize(image.scaledDown(maxDim: 2400)))?.text ?? ""
                repository.setOcrText(docId, page.id, text)
            }
        }
        doc = repository.get(docId) ?? doc
        return doc
    }

    private func exportText() {
        working = "Đang xử lý…"
        Task {
            guard let doc = await ensureText() else { working = nil; return }
            let url = exportDir.appendingPathComponent("\(safeFileName(options.fileName)).txt")
            do {
                try Exporters.textFile(Exporters.plainText(doc), to: url)
                result = url
                if doc.pages.allSatisfy({ ($0.ocrText ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) { message = "Không nhận dạng được chữ nào trong tài liệu" }
            } catch {
                message = "Đã xảy ra lỗi: \(error.localizedDescription)"
            }
            working = nil
        }
    }

    private func exportDocx(withImages: Bool) {
        working = "Đang xử lý…"
        Task {
            guard let doc = await ensureText() else { working = nil; return }
            let url = exportDir.appendingPathComponent("\(safeFileName(options.fileName)).docx")
            let pages = doc.pages.map { (text: $0.ocrText ?? "", image: withImages ? repository.pageURL(docId, $0.id) : nil, width: $0.width, height: $0.height) }
            do {
                try Exporters.docx(pages: pages, to: url)
                result = url
            } catch {
                message = "Đã xảy ra lỗi: \(error.localizedDescription)"
            }
            working = nil
        }
    }
}

/// Miniature of the first PDF pages so the effect of paper size and margins is visible.
struct LayoutPreview: View {
    @EnvironmentObject private var repository: DocumentRepository
    let doc: ScanDocument
    let options: PdfOptions

    var body: some View {
        let sheets = PdfLayout.plan(doc.pages.map { PageSize(width: max(1, $0.width), height: max(1, $0.height), paper: $0.paper) },
                                    paper: options.paperSize, orientation: options.orientation, margin: options.margin,
                                    mergeIdCards: options.mergeIdCards, pagesPerSheet: options.pagesPerSheet)
        VStack(alignment: .leading, spacing: 6) {
            Text("\(sheets.count) trang").font(.footnote).foregroundColor(.secondary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .bottom, spacing: 12) {
                    ForEach(Array(sheets.prefix(6).enumerated()), id: \.offset) { _, sheet in
                        let k = 150 / max(sheet.width, sheet.height)
                        ZStack(alignment: .topLeading) {
                            Rectangle().fill(Color.white).shadow(radius: 2)
                            ForEach(sheet.items, id: \.index) { item in
                                if doc.pages.indices.contains(item.index) {
                                    let page = doc.pages[item.index]
                                    FileImage(url: repository.thumbURL(doc.id, page.id), version: page.updatedAt, contentMode: .fill)
                                        .frame(width: item.width * k, height: item.height * k)
                                        .clipped()
                                        .offset(x: item.x * k, y: (sheet.height - item.y - item.height) * k)
                                }
                            }
                        }
                        .frame(width: sheet.width * k, height: sheet.height * k)
                    }
                }
                .padding(.vertical, 6)
            }
        }
    }
}

extension Binding where Value == Bool {
    /// A binding that also runs `handler` with the new value.
    func onChange(_ handler: @escaping (Bool) -> Void) -> Binding<Bool> {
        Binding(get: { wrappedValue }, set: { wrappedValue = $0; handler($0) })
    }
}
