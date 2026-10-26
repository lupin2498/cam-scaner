import UIKit
import Photos

/// Exports besides PDF: gallery, long image, text and Word files, printing.
enum Exporters {

    /// Saves every page image into the photo library (needs add-only access). Returns how many were saved.
    static func saveToPhotos(_ urls: [URL]) async throws -> Int {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { throw ExportError.denied }
        var saved = 0
        for url in urls where FileManager.default.fileExists(atPath: url.path) {
            try await PHPhotoLibrary.shared().performChanges {
                PHAssetChangeRequest.creationRequestForAssetFromImage(atFileURL: url)
            }
            saved += 1
        }
        return saved
    }

    enum ExportError: LocalizedError {
        case denied
        var errorDescription: String? { "Không có quyền ghi vào thư viện ảnh" }
    }

    /// All pages stacked into one tall JPEG.
    static func longImage(pages: [(URL, Int, Int)], to url: URL) throws {
        let usable = pages.filter { $0.1 > 0 && $0.2 > 0 }
        guard !usable.isEmpty else { throw CocoaError(.fileNoSuchFile) }
        let gapFraction = 0.015
        let heightPerWidth = usable.map { Double($0.2) / Double($0.1) }.reduce(0, +) + gapFraction * Double(usable.count - 1)
        var width = min(1600, usable.map(\.1).min() ?? 1600)
        if Double(width * width) * heightPerWidth > 36_000_000 { width = Int(sqrt(36_000_000 / heightPerWidth)) }
        width = max(width, 320)
        let gap = Int((Double(width) * gapFraction).rounded())
        let heights = usable.map { max(1, Int((Double(width) * Double($0.2) / Double($0.1)).rounded())) }
        let total = heights.reduce(0, +) + gap * (usable.count - 1)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        let image = UIGraphicsImageRenderer(size: CGSize(width: width, height: total), format: format).image { ctx in
            UIColor(red: 236 / 255, green: 239 / 255, blue: 238 / 255, alpha: 1).setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: width, height: total))
            var y = 0
            for (i, page) in usable.enumerated() {
                if let img = UIImage(contentsOfFile: page.0.path) {
                    img.draw(in: CGRect(x: 0, y: y, width: width, height: heights[i]))
                }
                y += heights[i] + gap
            }
        }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try image.jpegData(compressionQuality: 0.88)?.write(to: url, options: .atomic)
    }

    static func textFile(_ text: String, to url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try text.data(using: .utf8)?.write(to: url, options: .atomic)
    }

    /// The texts of all pages, separated by a page marker line.
    static func plainText(_ doc: ScanDocument) -> String {
        doc.pages.enumerated().map { i, p in "— Trang \(i + 1) —\n\((p.ocrText ?? "").trimmingCharacters(in: .whitespacesAndNewlines))" }.joined(separator: "\n\n")
    }

    /// Writes a minimal Word file (.docx) with the text of every page and, optionally, the page images.
    static func docx(pages: [(text: String, image: URL?, width: Int, height: Int)], to url: URL) throws {
        let zip = ZipWriter()
        let images = pages.enumerated().compactMap { i, p -> (Int, Data)? in
            guard let u = p.image, let data = try? Data(contentsOf: u) else { return nil }
            return (i, data)
        }
        zip.add("[Content_Types].xml", Data(contentTypes(withImages: !images.isEmpty).utf8))
        zip.add("_rels/.rels", Data(rootRels.utf8))
        zip.add("word/_rels/document.xml.rels", Data(documentRels(images.map(\.0)).utf8))
        zip.add("word/document.xml", Data(document(pages).utf8))
        for (index, data) in images { zip.add("word/media/image\(index).jpg", data) }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try zip.finish().write(to: url, options: .atomic)
    }

    private static func contentTypes(withImages: Bool) -> String {
        var s = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        s += "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
        s += "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
        s += "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
        if withImages { s += "<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>" }
        s += "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
        return s + "</Types>"
    }

    private static let rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
        "</Relationships>"

    private static func documentRels(_ indices: [Int]) -> String {
        var s = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        for i in indices {
            s += "<Relationship Id=\"rIdImg\(i)\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image\(i).jpg\"/>"
        }
        return s + "</Relationships>"
    }

    private static func document(_ pages: [(text: String, image: URL?, width: Int, height: Int)]) -> String {
        var s = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        s += "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        s += "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
        s += "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
        s += "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
        s += "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
        for (index, page) in pages.enumerated() {
            if index > 0 { s += "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>" }
            if let image = page.image, FileManager.default.fileExists(atPath: image.path) {
                s += imageParagraph(index, width: page.width, height: page.height)
            }
            let lines = page.text.trimmingCharacters(in: .whitespacesAndNewlines).components(separatedBy: "\n")
            if lines.allSatisfy({ $0.trimmingCharacters(in: .whitespaces).isEmpty }) {
                s += "<w:p/>"
            } else {
                for line in lines { s += "<w:p><w:r><w:t xml:space=\"preserve\">\(escape(line))</w:t></w:r></w:p>" }
            }
        }
        s += "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>"
        return s + "</w:body></w:document>"
    }

    private static func imageParagraph(_ index: Int, width: Int, height: Int) -> String {
        let w = Double(max(width, 1)), h = Double(max(height, 1))
        let scale = min(6_120_000 / w, 9_000_000 / h)
        let cx = Int(w * scale), cy = Int(h * scale)
        let id = index + 1
        return "<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"\(cx)\" cy=\"\(cy)\"/><wp:docPr id=\"\(id)\" name=\"Trang \(id)\"/>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"\(id)\" name=\"image\(index).jpg\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"rIdImg\(index)\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"\(cx)\" cy=\"\(cy)\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
    }

    static func escape(_ s: String) -> String {
        var out = ""
        out.reserveCapacity(s.count)
        for ch in s.unicodeScalars {
            switch ch {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            default:
                if ch.value < 0x20 && ch != "\t" { continue }
                out.unicodeScalars.append(ch)
            }
        }
        return out
    }

    /// Hands a PDF to the system print dialog.
    @MainActor
    static func print(_ url: URL, name: String) {
        guard UIPrintInteractionController.isPrintingAvailable, let data = try? Data(contentsOf: url) else { return }
        let info = UIPrintInfo(dictionary: nil)
        info.jobName = name
        info.outputType = .general
        let controller = UIPrintInteractionController.shared
        controller.printInfo = info
        controller.printingItem = data
        controller.present(animated: true, completionHandler: nil)
    }
}
