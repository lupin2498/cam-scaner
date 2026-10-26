import UIKit
import PDFKit
import CoreText

/// Builds the PDF: page sizing, compression, optional invisible OCR text, watermark, page numbers, password.
enum PdfExporter {
    struct Input {
        let page: ScanPage
        let imageURL: URL
    }

    static func export(name: String, pages: [Input], options: PdfOptions, to url: URL, progress: @escaping (Double) -> Void) async throws {
        let sheets = PdfLayout.plan(
            pages.map { PageSize(width: max(1, $0.page.width), height: max(1, $0.page.height), paper: $0.page.paper) },
            paper: options.paperSize, orientation: options.orientation, margin: options.margin,
            mergeIdCards: options.mergeIdCards, pagesPerSheet: options.pagesPerSheet
        )
        // text is read before drawing so the renderer block stays synchronous
        var texts: [Int: OcrResult] = [:]
        if options.ocrLayer {
            for (i, input) in pages.enumerated() {
                if let image = UIImage(contentsOfFile: input.imageURL.path) {
                    texts[i] = try? await OCR.recognize(image.scaledDown(maxDim: 2400))
                }
            }
        }
        let data = try await Task.detached(priority: .userInitiated) { () -> Data in
            let format = UIGraphicsPDFRendererFormat()
            format.documentInfo = [kCGPDFContextTitle as String: name, kCGPDFContextCreator as String: "Cam Scaner"]
            let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 595.28, height: 841.89), format: format)
            return renderer.pdfData { ctx in
                for (sheetIndex, sheet) in sheets.enumerated() {
                    ctx.beginPage(withBounds: CGRect(x: 0, y: 0, width: sheet.width, height: sheet.height), pageInfo: [:])
                    let cg = ctx.cgContext
                    for item in sheet.items {
                        let input = pages[item.index]
                        guard let image = UIImage(contentsOfFile: input.imageURL.path) else { continue }
                        let scaled = image.scaledDown(maxDim: options.quality.maxDim)
                        // a JPEG-backed image is embedded as JPEG instead of being re-encoded losslessly
                        let drawn = scaled.jpegData(compressionQuality: options.quality.jpegQuality).flatMap(UIImage.init(data:)) ?? scaled
                        let rect = CGRect(x: item.x, y: sheet.height - item.y - item.height, width: item.width, height: item.height)
                        drawn.draw(in: rect)
                        if let text = texts[item.index] { drawInvisible(text, in: rect, cg: cg) }
                    }
                    if !options.watermark.trimmingCharacters(in: .whitespaces).isEmpty { drawWatermark(options.watermark, sheet: sheet, cg: cg) }
                    if options.pageNumbers { drawPageNumber(sheetIndex + 1, total: sheets.count, sheet: sheet, margin: options.margin) }
                    DispatchQueue.main.async { progress(Double(sheetIndex + 1) / Double(sheets.count)) }
                }
            }
        }.value
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if options.password.isEmpty {
            try data.write(to: url, options: .atomic)
        } else {
            guard let doc = PDFDocument(data: data) else { throw CocoaError(.fileWriteUnknown) }
            let ok = doc.write(to: url, withOptions: [.userPasswordOption: options.password, .ownerPasswordOption: options.password])
            if !ok { throw CocoaError(.fileWriteUnknown) }
        }
    }

    /// Invisible text over each recognised line so the PDF can be searched and copied from.
    private static func drawInvisible(_ result: OcrResult, in rect: CGRect, cg: CGContext) {
        cg.saveGState()
        cg.setTextDrawingMode(.invisible)
        for line in result.lines where !line.text.trimmingCharacters(in: .whitespaces).isEmpty {
            let boxW = line.box.width * rect.width
            let boxH = line.box.height * rect.height
            guard boxW > 1, boxH > 1 else { continue }
            let fontSize = boxH * 0.85
            let attr = NSAttributedString(string: line.text, attributes: [.font: UIFont.systemFont(ofSize: fontSize)])
            let ctLine = CTLineCreateWithAttributedString(attr)
            let width = CGFloat(CTLineGetTypographicBounds(ctLine, nil, nil, nil))
            guard width > 0 else { continue }
            let x = rect.minX + line.box.minX * rect.width
            let baseline = rect.minY + line.box.maxY * rect.height - boxH * 0.18
            cg.saveGState()
            // UIKit's flipped context: flip the text back and stretch it to the width of the box
            cg.textMatrix = CGAffineTransform(scaleX: boxW / width, y: -1)
            cg.textPosition = CGPoint(x: x, y: baseline)
            CTLineDraw(ctLine, cg)
            cg.restoreGState()
        }
        cg.restoreGState()
    }

    private static func drawWatermark(_ text: String, sheet: Sheet, cg: CGContext) {
        var size = min(sheet.width, sheet.height) / 9
        var attrs: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: size), .foregroundColor: UIColor(white: 0.35, alpha: 0.16)]
        var width = (text as NSString).size(withAttributes: attrs).width
        let diagonal = hypot(sheet.width, sheet.height) * 0.75
        if width > diagonal {
            size *= diagonal / width
            attrs[.font] = UIFont.boldSystemFont(ofSize: size)
            width = (text as NSString).size(withAttributes: attrs).width
        }
        cg.saveGState()
        cg.translateBy(x: sheet.width / 2, y: sheet.height / 2)
        cg.rotate(by: -atan2(sheet.height, sheet.width))
        (text as NSString).draw(at: CGPoint(x: -width / 2, y: -size / 2), withAttributes: attrs)
        cg.restoreGState()
    }

    private static func drawPageNumber(_ number: Int, total: Int, sheet: Sheet, margin: PageMargin) {
        let text = "\(number) / \(total)"
        let attrs: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 9), .foregroundColor: UIColor(white: 0.45, alpha: 1)]
        let size = (text as NSString).size(withAttributes: attrs)
        let y = sheet.height - max(10, margin.points / 2 - 4.5) - size.height
        (text as NSString).draw(at: CGPoint(x: (sheet.width - size.width) / 2, y: y), withAttributes: attrs)
    }
}
