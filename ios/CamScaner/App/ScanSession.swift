import UIKit
import PDFKit
import Combine

/// What the camera is pointed at.
enum CameraMode: String, CaseIterable, Identifiable {
    case document, idCard, book, qr, photo
    var id: String { rawValue }
    var label: String {
        switch self {
        case .document: return "Tài liệu"
        case .idCard: return "Thẻ CCCD"
        case .book: return "Sách"
        case .qr: return "Mã QR"
        case .photo: return "Ảnh"
        }
    }
    var detects: Bool { self == .document || self == .idCard || self == .book }
    var collects: Bool { self == .idCard || self == .book }
}

/// Pages being scanned (camera → crop → filter → save), shared by the screens of the flow.
@MainActor
final class ScanSession: ObservableObject {
    let repository: DocumentRepository
    let settings: Settings
    let workDir: URL

    @Published var pages: [DraftPage] = []
    @Published var currentIndex = 0
    @Published var importing = false
    @Published var saving = false
    @Published var saveProgress: (Int, Int)?
    @Published var cameraMode: CameraMode = .document
    @Published var customName: String?
    @Published var errorMessage: String?
    /// Set when an import or capture is ready for cropping; the flow opens the crop step.
    @Published var cropReady = false
    /// Set when files handed over by another app should open the crop step.
    @Published var pendingImport = false

    private(set) var targetDocId: String?
    private var defaultName = ""
    private var generation = 0

    init(repository: DocumentRepository, settings: Settings) {
        self.repository = repository
        self.settings = settings
        workDir = FileManager.default.temporaryDirectory.appendingPathComponent("scan", isDirectory: true)
        try? FileManager.default.createDirectory(at: workDir, withIntermediateDirectories: true)
    }

    var documentName: String {
        customName ?? targetDocId.flatMap { repository.get($0)?.name } ?? defaultName
    }

    func start(docId: String?) {
        generation += 1
        clear()
        targetDocId = docId
        customName = nil
        cameraMode = .document
        cropReady = false
        defaultName = settings.defaultDocumentName()
    }

    func abandon() {
        generation += 1
        clear()
        cropReady = false
    }

    func clear() {
        for url in Set(pages.map(\.originalURL)) { discard(url) }
        pages = []
        currentIndex = 0
    }

    /// Deletes a photo taken or imported in this session. Photos stored in a document (a page being
    /// edited, or the half of such a page split off as a new one) live elsewhere and are never touched.
    private func discard(_ url: URL) {
        let folder = url.deletingLastPathComponent().resolvingSymlinksInPath().standardizedFileURL.path
        guard folder == workDir.resolvingSymlinksInPath().standardizedFileURL.path else { return }
        try? FileManager.default.removeItem(at: url)
    }

    // MARK: adding photos

    /// Normalizes a photo, detects the sheet and appends one page (two for a book spread).
    private func addPhoto(_ image: UIImage, mode: CameraMode, fromCamera: Bool) async -> Bool {
        let gen = generation
        let autoCrop = settings.autoCrop
        let defaultFilter = settings.defaultFilter
        let cameraPaper = settings.cameraPaper
        let result: [DraftPage]? = await Task.detached(priority: .userInitiated) { [workDir] () -> [DraftPage]? in
            let upright = image.normalized().scaledDown(maxDim: 4096)
            let url = workDir.appendingPathComponent("\(newId()).jpg")
            guard let data = upright.jpegData(compressionQuality: 0.95) else { return nil }
            do { try data.write(to: url, options: .atomic) } catch { return nil }
            let w = Int(upright.pixelSize.width), h = Int(upright.pixelSize.height)
            switch mode {
            case .photo:
                return [DraftPage(originalURL: url, width: w, height: h, detected: nil, corners: nil, filter: .original)]
            case .book:
                let spread = autoCrop ? upright.cgImage.flatMap(DocumentDetector.detect) : nil
                let (left, right) = PaperFit.split(spread)
                return [left, right].map { DraftPage(originalURL: url, width: w, height: h, detected: spread, corners: $0, filter: defaultFilter) }
            default:
                let detected = autoCrop ? upright.cgImage.flatMap(DocumentDetector.detect) : nil
                let paper: PaperPreset? = mode == .idCard ? .idCard : (fromCamera ? cameraPaper : nil)
                let corners = paper.map { PaperFit.fit(detected, paper: $0, width: w, height: h) } ?? detected
                return [DraftPage(originalURL: url, width: w, height: h, detected: detected, corners: corners, filter: defaultFilter, paper: paper)]
            }
        }.value
        guard let drafts = result else { return false }
        guard gen == generation else {
            drafts.first.map { discard($0.originalURL) }
            return false
        }
        pages += drafts
        currentIndex = pages.count - 1
        return true
    }

    /// Adds a photo taken by the camera; a single shot (or the second side of a card) opens the crop step.
    func addCapture(_ image: UIImage, mode: CameraMode) async {
        let single = !settings.batchMode
        if single && !mode.collects { clear() }
        let added = await addPhoto(image, mode: mode, fromCamera: true)
        guard added, !pages.isEmpty else { return }
        let done: Bool
        switch mode {
        case .idCard: done = pages.filter { $0.existingPageId == nil }.count >= 2
        case .book: done = false
        default: done = single
        }
        if done { cropReady = true }
    }

    /// Imports photos picked by the user.
    func importImages(_ images: [UIImage], into docId: String?) {
        if targetDocId != docId || pages.isEmpty { start(docId: docId) }
        let gen = generation
        importing = true
        Task {
            var imported = 0
            for image in images {
                if gen != generation { break }
                if await addPhoto(image, mode: .document, fromCamera: false) { imported += 1 }
            }
            importing = false
            currentIndex = 0
            if imported < images.count { errorMessage = "Không đọc được \(images.count - imported) ảnh" }
            if gen == generation && !pages.isEmpty { cropReady = true }
        }
    }

    /// Imports files (images or PDFs) from the file system or another app.
    func importFiles(_ urls: [URL], into docId: String?) {
        if targetDocId != docId || pages.isEmpty { start(docId: docId) }
        let gen = generation
        importing = true
        Task {
            var failed = 0
            for url in urls {
                if gen != generation { break }
                let access = url.startAccessingSecurityScopedResource()
                defer { if access { url.stopAccessingSecurityScopedResource() } }
                if url.pathExtension.lowercased() == "pdf" || (try? Data(contentsOf: url, options: .mappedIfSafe))?.prefix(4) == Data("%PDF".utf8) {
                    if await importPdf(url) == 0 { failed += 1 }
                } else if let data = try? Data(contentsOf: url), let image = UIImage(data: data) {
                    if !(await addPhoto(image, mode: .document, fromCamera: false)) { failed += 1 }
                } else {
                    failed += 1
                }
            }
            importing = false
            currentIndex = 0
            if failed > 0 { errorMessage = "Không đọc được \(failed) tệp" }
            if gen == generation && !pages.isEmpty { cropReady = true }
        }
    }

    /// Renders each PDF page as an image page (uncut, unfiltered). Returns how many were added.
    private func importPdf(_ url: URL) async -> Int {
        let gen = generation
        guard let pdf = PDFDocument(url: url) else { return 0 }
        if pdf.isLocked { errorMessage = "PDF bị khóa mật khẩu"; return 0 }
        var added = 0
        for i in 0..<min(pdf.pageCount, 200) {
            if gen != generation { break }
            guard let page = pdf.page(at: i) else { continue }
            let bounds = page.bounds(for: .mediaBox)
            let scale = 2480 / max(bounds.width, bounds.height)
            let size = CGSize(width: max(1, (bounds.width * scale).rounded()), height: max(1, (bounds.height * scale).rounded()))
            let image: UIImage = await Task.detached(priority: .userInitiated) {
                let format = UIGraphicsImageRendererFormat.default()
                format.scale = 1
                return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
                    UIColor.white.setFill()
                    ctx.fill(CGRect(origin: .zero, size: size))
                    ctx.cgContext.translateBy(x: 0, y: size.height)
                    ctx.cgContext.scaleBy(x: scale, y: -scale)
                    page.draw(with: .mediaBox, to: ctx.cgContext)
                }
            }.value
            if await addPhoto(image, mode: .photo, fromCamera: false) { added += 1 }
        }
        return added
    }

    // MARK: editing

    func editExisting(docId: String, pageId: String) {
        start(docId: docId)
        guard let page = repository.get(docId)?.pages.first(where: { $0.id == pageId }) else { return }
        let url = repository.originalURL(docId, pageId)
        var w = page.width, h = page.height
        if let image = UIImage(contentsOfFile: url.path) {
            w = Int(image.pixelSize.width)
            h = Int(image.pixelSize.height)
        }
        pages = [DraftPage(id: pageId, originalURL: url, width: w, height: h, detected: page.corners, corners: page.corners, rotation: page.rotation,
                           filter: page.filter, adjustments: page.adjustments, skew: page.skew, marks: page.marks, texts: page.texts,
                           paper: page.paper, existingPageId: pageId)]
        currentIndex = 0
    }

    func update(_ index: Int, _ transform: (DraftPage) -> DraftPage) {
        guard pages.indices.contains(index) else { return }
        pages[index] = transform(pages[index])
    }

    func rotate(_ index: Int, by delta: Int) { update(index) { $0.turned(by: delta) } }

    /// Fits the outline to a paper format inside the detected sheet; nil frees it again.
    func fitPaper(_ index: Int, _ paper: PaperPreset?) {
        update(index) { p in
            var q = p
            if let paper = paper {
                let own = p.paper == nil && p.corners != nil && p.corners != p.detected && p.corners != .full
                let base = own ? p.corners : p.detected
                q.corners = PaperFit.fit(base, paper: paper, width: p.width, height: p.height)
                q.paper = paper
            } else {
                q.corners = p.detected
                q.paper = nil
            }
            return q
        }
    }

    /// Finds the sheet again (or for the first time) on a page.
    func autoDetect(_ index: Int) async {
        guard pages.indices.contains(index) else { return }
        let page = pages[index]
        var detected = page.detected
        if detected == nil {
            let path = page.originalURL.path
            detected = await Task.detached { () -> Quad? in
                UIImage(contentsOfFile: path)?.normalized().cgImage.flatMap(DocumentDetector.detect)
            }.value
        }
        guard let now = pages.firstIndex(where: { $0.id == page.id }) else { return }
        update(now) { p in
            var q = p
            q.detected = detected
            q.corners = p.paper.map { PaperFit.fit(detected, paper: $0, width: p.width, height: p.height) } ?? detected
            return q
        }
    }

    /// Splits a photographed spread into its left and right halves as shown.
    func split(_ index: Int) {
        guard pages.indices.contains(index) else { return }
        let p = pages[index]
        let (a, b) = PaperFit.split(p.corners ?? p.detected)
        let (first, second) = a.rotated(p.rotation).tl.x <= b.rotated(p.rotation).tl.x ? (a, b) : (b, a)
        var left = p
        left.corners = first
        left.marks = []
        left.texts = []
        var right = p
        right.id = newId()
        right.corners = second
        right.marks = []
        right.texts = []
        right.existingPageId = nil
        pages[index] = left
        pages.insert(right, at: index + 1)
        currentIndex = index
    }

    func remove(_ index: Int) {
        guard pages.indices.contains(index) else { return }
        let removed = pages.remove(at: index)
        let shared = pages.contains { $0.originalURL == removed.originalURL }
        if !shared { discard(removed.originalURL) }
        currentIndex = min(currentIndex, max(0, pages.count - 1))
    }

    /// Renders all pages into the target document. Returns the document id.
    func save() async throws -> String {
        saving = true
        saveProgress = (0, pages.count)
        defer {
            saving = false
            saveProgress = nil
        }
        let drafts = pages
        let id = try await repository.saveDrafts(docId: targetDocId, newName: documentName, drafts: drafts, rename: customName) { done, total in
            self.saveProgress = (done, total)
        }
        clear()
        return id
    }
}
