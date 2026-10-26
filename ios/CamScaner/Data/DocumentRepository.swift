import Foundation
import UIKit
import Combine

/// Stores documents as folders: documents/<docId>/doc.json and pages/<pageId>/{original,page,thumb}.jpg,
/// plus documents/folders.json. Deleted documents stay in the trash for `trashDays` days.
@MainActor
final class DocumentRepository: ObservableObject {
    static let trashDays = 30
    private static let thumbSize = 480

    let root: URL
    @Published private(set) var all: [ScanDocument] = []
    @Published private(set) var folders: [Folder] = []
    @Published private(set) var loaded = false

    var documents: [ScanDocument] { all.filter { $0.deletedAt == nil } }
    var trash: [ScanDocument] { all.filter { $0.deletedAt != nil }.sorted { ($0.deletedAt ?? 0) > ($1.deletedAt ?? 0) } }

    private let encoder: JSONEncoder = { let e = JSONEncoder(); e.outputFormatting = [.sortedKeys]; return e }()
    private let decoder = JSONDecoder()

    init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        root = support.appendingPathComponent("documents", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        load()
    }

    // MARK: paths

    func docDir(_ id: String) -> URL { root.appendingPathComponent(id, isDirectory: true) }
    private func pageDir(_ docId: String, _ pageId: String) -> URL { docDir(docId).appendingPathComponent("pages/\(pageId)", isDirectory: true) }
    func originalURL(_ docId: String, _ pageId: String) -> URL { pageDir(docId, pageId).appendingPathComponent("original.jpg") }
    func pageURL(_ docId: String, _ pageId: String) -> URL { pageDir(docId, pageId).appendingPathComponent("page.jpg") }
    func thumbURL(_ docId: String, _ pageId: String) -> URL { pageDir(docId, pageId).appendingPathComponent("thumb.jpg") }

    func get(_ id: String) -> ScanDocument? { all.first { $0.id == id } }

    func size(of docId: String) -> Int64 {
        guard let e = FileManager.default.enumerator(at: docDir(docId), includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        var total: Int64 = 0
        for case let url as URL in e {
            total += Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        return total
    }

    // MARK: loading

    private func load() {
        let fm = FileManager.default
        var docs: [ScanDocument] = []
        if let items = try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) {
            for dir in items where (try? dir.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true {
                if let data = try? Data(contentsOf: dir.appendingPathComponent("doc.json")), let doc = try? decoder.decode(ScanDocument.self, from: data) {
                    docs.append(doc)
                }
            }
        }
        let limit = Date().timeIntervalSince1970 - Double(DocumentRepository.trashDays) * 86_400
        for doc in docs where (doc.deletedAt ?? .infinity) < limit {
            try? fm.removeItem(at: docDir(doc.id))
        }
        docs.removeAll { ($0.deletedAt ?? .infinity) < limit }
        all = docs.sorted { $0.updatedAt > $1.updatedAt }
        if let data = try? Data(contentsOf: root.appendingPathComponent("folders.json")), let list = try? decoder.decode([Folder].self, from: data) {
            folders = list
        }
        loaded = true
    }

    private func persist(_ doc: ScanDocument) {
        let dir = docDir(doc.id)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        if let data = try? encoder.encode(doc) { try? data.write(to: dir.appendingPathComponent("doc.json"), options: .atomic) }
        all = (all.filter { $0.id != doc.id } + [doc]).sorted { $0.updatedAt > $1.updatedAt }
    }

    private func persistFolders() {
        if let data = try? encoder.encode(folders) { try? data.write(to: root.appendingPathComponent("folders.json"), options: .atomic) }
    }

    // MARK: saving pages

    /// Renders and stores `drafts` into `docId` (a new document named `newName` when nil).
    func saveDrafts(docId: String?, newName: String, drafts: [DraftPage], rename: String? = nil, folderId: String? = nil,
                    progress: @escaping (Int, Int) -> Void = { _, _ in }) async throws -> String {
        let id = docId ?? newId()
        let isNew = docId == nil
        let now = Date().timeIntervalSince1970
        var rendered: [ScanPage] = []
        for (index, draft) in drafts.enumerated() {
            let pageId = draft.existingPageId ?? newId()
            let original = originalURL(id, pageId)
            if draft.originalURL.standardizedFileURL != original.standardizedFileURL {
                try FileManager.default.createDirectory(at: original.deletingLastPathComponent(), withIntermediateDirectories: true)
                if FileManager.default.fileExists(atPath: original.path) { try FileManager.default.removeItem(at: original) }
                try FileManager.default.copyItem(at: draft.originalURL, to: original)
            }
            var page = ScanPage(id: pageId, corners: draft.corners, rotation: draft.rotation, filter: draft.filter, adjustments: draft.adjustments,
                                skew: draft.skew, marks: draft.marks, texts: draft.texts, paper: draft.paper)
            page = try await renderPage(id, page)
            rendered.append(page)
            progress(index + 1, drafts.count)
        }
        if let existing = get(id) {
            let byId = Dictionary(uniqueKeysWithValues: rendered.map { ($0.id, $0) })
            let kept = existing.pages.map { byId[$0.id] ?? $0 }
            let added = rendered.filter { r in !existing.pages.contains { $0.id == r.id } }
            var doc = existing
            doc.pages = kept + added
            doc.updatedAt = now
            if let rename = rename?.trimmingCharacters(in: .whitespaces), !rename.isEmpty { doc.name = rename }
            persist(doc)
        } else {
            _ = isNew
            persist(ScanDocument(id: id, name: newName, createdAt: now, updatedAt: now, pages: rendered, folderId: folderId))
        }
        return id
    }

    /// Renders the page image and thumbnail from the stored original.
    private func renderPage(_ docId: String, _ page: ScanPage) async throws -> ScanPage {
        let original = originalURL(docId, page.id)
        let pageURL = pageURL(docId, page.id)
        let thumbURL = thumbURL(docId, page.id)
        let input = page
        let thumbSize = DocumentRepository.thumbSize
        let result: (Int, Int) = try await Task.detached(priority: .userInitiated) {
            guard let image = UIImage(contentsOfFile: original.path) else { throw CocoaError(.fileReadCorruptFile) }
            let rendered = PageProcessor.render(image, page: input)
            guard let jpeg = rendered.jpegData(compressionQuality: 0.92) else { throw CocoaError(.fileWriteUnknown) }
            try jpeg.write(to: pageURL, options: .atomic)
            let thumb = rendered.scaledDown(maxDim: thumbSize)
            try thumb.jpegData(compressionQuality: 0.82)?.write(to: thumbURL, options: .atomic)
            return (Int(rendered.size.width * rendered.scale), Int(rendered.size.height * rendered.scale))
        }.value
        var out = page
        out.width = result.0
        out.height = result.1
        out.updatedAt = Date().timeIntervalSince1970
        out.ocrText = nil
        return out
    }

    func updatePage(_ docId: String, _ pageId: String, _ transform: (ScanPage) -> ScanPage) async {
        guard let doc = get(docId), let current = doc.pages.first(where: { $0.id == pageId }) else { return }
        guard let page = try? await renderPage(docId, transform(current)) else { return }
        guard var latest = get(docId) else { return }
        latest.pages = latest.pages.map { $0.id == pageId ? page : $0 }
        latest.updatedAt = Date().timeIntervalSince1970
        persist(latest)
    }

    func setOcrText(_ docId: String, _ pageId: String, _ text: String) {
        guard var doc = get(docId) else { return }
        doc.pages = doc.pages.map { var p = $0; if p.id == pageId { p.ocrText = text }; return p }
        persist(doc)
    }

    func deletePages(_ docId: String, _ ids: Set<String>) {
        guard var doc = get(docId) else { return }
        for id in ids { try? FileManager.default.removeItem(at: pageDir(docId, id)) }
        doc.pages.removeAll { ids.contains($0.id) }
        doc.updatedAt = Date().timeIntervalSince1970
        persist(doc)
    }

    func reorder(_ docId: String, _ pageIds: [String]) {
        guard var doc = get(docId) else { return }
        let byId = Dictionary(uniqueKeysWithValues: doc.pages.map { ($0.id, $0) })
        doc.pages = pageIds.compactMap { byId[$0] } + doc.pages.filter { !pageIds.contains($0.id) }
        doc.updatedAt = Date().timeIntervalSince1970
        persist(doc)
    }

    func rename(_ docId: String, _ name: String) {
        guard var doc = get(docId) else { return }
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if !trimmed.isEmpty { doc.name = trimmed }
        doc.updatedAt = Date().timeIntervalSince1970
        persist(doc)
    }

    // MARK: trash

    func delete(_ docId: String) {
        guard var doc = get(docId) else { return }
        doc.deletedAt = Date().timeIntervalSince1970
        persist(doc)
    }

    func restore(_ docId: String) {
        guard var doc = get(docId) else { return }
        doc.deletedAt = nil
        if let f = doc.folderId, !folders.contains(where: { $0.id == f }) { doc.folderId = nil }
        doc.updatedAt = Date().timeIntervalSince1970
        persist(doc)
    }

    func deleteForever(_ docId: String) {
        try? FileManager.default.removeItem(at: docDir(docId))
        all.removeAll { $0.id == docId }
    }

    func emptyTrash() {
        for doc in trash { try? FileManager.default.removeItem(at: docDir(doc.id)) }
        all.removeAll { $0.deletedAt != nil }
    }

    // MARK: merging and moving pages

    func merge(target targetId: String, sources: [String]) {
        guard var target = get(targetId) else { return }
        for sourceId in sources where sourceId != targetId {
            guard let source = get(sourceId) else { continue }
            for page in source.pages { movePageDir(from: sourceId, pageId: page.id, to: targetId) }
            target.pages += source.pages
            try? FileManager.default.removeItem(at: docDir(sourceId))
            all.removeAll { $0.id == sourceId }
        }
        target.updatedAt = Date().timeIntervalSince1970
        persist(target)
    }

    /// Moves (or copies) pages into `toId`, or into a new document named `newName` when nil. Returns the receiving id.
    @discardableResult
    func movePages(from fromId: String, pageIds: [String], to toId: String?, copy: Bool, newName: String = "") -> String? {
        guard let from = get(fromId) else { return nil }
        let moving = pageIds.compactMap { id in from.pages.first { $0.id == id } }
        if moving.isEmpty { return nil }
        let now = Date().timeIntervalSince1970
        var target = toId.flatMap { get($0) } ??
            ScanDocument(id: newId(), name: newName.isEmpty ? from.name : newName, createdAt: now, updatedAt: now, pages: [], folderId: from.folderId)
        var received: [ScanPage] = []
        for page in moving {
            if copy {
                var p = page
                p.id = newId()
                p.updatedAt = now
                try? FileManager.default.createDirectory(at: pageDir(target.id, p.id).deletingLastPathComponent(), withIntermediateDirectories: true)
                try? FileManager.default.copyItem(at: pageDir(fromId, page.id), to: pageDir(target.id, p.id))
                received.append(p)
            } else {
                movePageDir(from: fromId, pageId: page.id, to: target.id)
                received.append(page)
            }
        }
        if !copy && fromId != target.id {
            var source = from
            source.pages.removeAll { pageIds.contains($0.id) }
            source.updatedAt = now
            persist(source)
        }
        if fromId == target.id, let current = get(target.id) { target = current }
        target.pages += received
        target.updatedAt = now
        persist(target)
        return target.id
    }

    func duplicate(_ docId: String, name: String) -> String? {
        guard let doc = get(docId) else { return nil }
        let now = Date().timeIntervalSince1970
        let id = newId()
        let pages: [ScanPage] = doc.pages.map { page in
            var p = page
            p.id = newId()
            p.updatedAt = now
            try? FileManager.default.createDirectory(at: pageDir(id, p.id).deletingLastPathComponent(), withIntermediateDirectories: true)
            try? FileManager.default.copyItem(at: pageDir(docId, page.id), to: pageDir(id, p.id))
            return p
        }
        persist(ScanDocument(id: id, name: name, createdAt: now, updatedAt: now, pages: pages, folderId: doc.folderId))
        return id
    }

    private func movePageDir(from fromId: String, pageId: String, to toId: String) {
        let src = pageDir(fromId, pageId), dst = pageDir(toId, pageId)
        try? FileManager.default.createDirectory(at: dst.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? FileManager.default.removeItem(at: dst)
        try? FileManager.default.moveItem(at: src, to: dst)
    }

    // MARK: folders

    @discardableResult
    func createFolder(_ name: String) -> String {
        let folder = Folder(id: newId(), name: name.trimmingCharacters(in: .whitespaces).isEmpty ? "Thư mục" : name.trimmingCharacters(in: .whitespaces), createdAt: Date().timeIntervalSince1970)
        folders.append(folder)
        persistFolders()
        return folder.id
    }

    func renameFolder(_ id: String, _ name: String) {
        folders = folders.map { var f = $0; if f.id == id, !name.trimmingCharacters(in: .whitespaces).isEmpty { f.name = name.trimmingCharacters(in: .whitespaces) }; return f }
        persistFolders()
    }

    func deleteFolder(_ id: String) {
        folders.removeAll { $0.id == id }
        persistFolders()
        for doc in all where doc.folderId == id {
            var d = doc
            d.folderId = nil
            persist(d)
        }
    }

    func moveToFolder(_ ids: [String], _ folderId: String?) {
        for id in ids {
            guard var doc = get(id) else { continue }
            doc.folderId = folderId
            doc.updatedAt = Date().timeIntervalSince1970
            persist(doc)
        }
    }

    // MARK: backup

    func backupData() -> Data {
        let zip = ZipWriter()
        if let e = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey]) {
            for case let url as URL in e where (try? url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true {
                let rel = url.path.replacingOccurrences(of: root.path + "/", with: "")
                if let data = try? Data(contentsOf: url) { zip.add(rel, data) }
            }
        }
        return zip.finish()
    }

    /// Restores a backup; returns how many documents it held, or -1 when it was not a backup.
    func restoreBackup(_ data: Data) -> Int {
        guard let entries = try? ZipReader.entries(data) else { return -1 }
        var docs = 0
        for entry in entries {
            let parts = entry.name.split(separator: "/").map(String.init)
            guard !parts.contains(".."), !parts.isEmpty else { continue }
            let url = root.appendingPathComponent(entry.name)
            if entry.name == "folders.json" {
                if let incoming = try? decoder.decode([Folder].self, from: entry.data) {
                    let known = Set(folders.map(\.id))
                    folders += incoming.filter { !known.contains($0.id) }
                    persistFolders()
                }
                continue
            }
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? entry.data.write(to: url, options: .atomic)
            if url.lastPathComponent == "doc.json" { docs += 1 }
        }
        if docs == 0 { return -1 }
        load()
        return docs
    }
}

extension UIImage {
    /// The image scaled so its longest side is at most `maxDim` pixels.
    func scaledDown(maxDim: Int) -> UIImage {
        let w = size.width * scale, h = size.height * scale
        let longSide = max(w, h)
        if longSide <= CGFloat(maxDim) { return self }
        let s = CGFloat(maxDim) / longSide
        let target = CGSize(width: max(1, (w * s).rounded()), height: max(1, (h * s).rounded()))
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            draw(in: CGRect(origin: .zero, size: target))
        }
    }

    /// The image redrawn upright at scale 1 (EXIF orientation applied).
    func normalized() -> UIImage {
        if imageOrientation == .up && scale == 1 { return self }
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in draw(in: CGRect(origin: .zero, size: size)) }
    }
}
