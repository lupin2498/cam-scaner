import UIKit
import CoreImage
import CoreImage.CIFilterBuiltins

/// Turns an original photo plus the page parameters into the finished page image.
enum PageProcessor {
    /// Longest side of a stored page image.
    static let maxPage = 3200
    /// Size previews are rendered at.
    static let previewSize = 1400

    static let context = CIContext(options: [.cacheIntermediates: false])

    // MARK: perspective

    /// Perspective-corrects `image` (upright) with normalized corners. The output ratio is the real
    /// one of the sheet, snapped to a standard size when close; a `paper` format forces its ratio.
    static func warp(_ image: UIImage, corners: Quad?, maxDim: Int = maxPage, paper: PaperPreset? = nil) -> UIImage {
        let upright = image.normalized()
        guard let corners = corners, corners != .full, let cg = upright.cgImage else {
            return upright.scaledDown(maxDim: maxDim)
        }
        let w = cg.width, h = cg.height
        let px = corners.scaled(Double(w), Double(h))
        let estimated = estimateAspectRatio(px, width: w, height: h)
        let ratio = paper?.ratio(for: estimated) ?? PaperKind.snap(estimated)
        let (outW, outH) = correctedSize(px, ratio: ratio, maxDim: maxDim)
        let filter = CIFilter.perspectiveCorrection()
        filter.inputImage = CIImage(cgImage: cg)
        // Core Image puts the origin at the bottom-left corner
        func pt(_ p: Pt) -> CGPoint { CGPoint(x: p.x, y: Double(h) - p.y) }
        filter.topLeft = pt(px.tl)
        filter.topRight = pt(px.tr)
        filter.bottomRight = pt(px.br)
        filter.bottomLeft = pt(px.bl)
        guard let out = filter.outputImage, let corrected = context.createCGImage(out, from: out.extent) else {
            return upright.scaledDown(maxDim: maxDim)
        }
        return UIImage(cgImage: corrected).resized(to: CGSize(width: outW, height: outH))
    }

    // MARK: whole page

    static func render(_ original: UIImage, page: ScanPage, maxDim: Int = maxPage) -> UIImage {
        let warped = warp(original, corners: page.corners, maxDim: maxDim, paper: page.paper)
        let straight = straighten(warped, degrees: page.skew)
        let enhanced = enhance(straight, filter: page.filter, adjustments: page.adjustments)
        let turned = rotate(enhanced, degrees: page.rotation)
        return drawMarks(turned, marks: page.marks, texts: page.texts)
    }

    static func render(_ original: UIImage, draft: DraftPage, maxDim: Int = maxPage, withMarks: Bool = true) -> UIImage {
        let warped = warp(original, corners: draft.corners, maxDim: maxDim, paper: draft.paper)
        let straight = straighten(warped, degrees: draft.skew)
        let enhanced = enhance(straight, filter: draft.filter, adjustments: draft.adjustments)
        let turned = rotate(enhanced, degrees: draft.rotation)
        return withMarks ? drawMarks(turned, marks: draft.marks, texts: draft.texts) : turned
    }

    // MARK: filters

    static func enhance(_ image: UIImage, filter: ScanFilter, adjustments: Adjustments = Adjustments()) -> UIImage {
        if filter == .original && adjustments.isNeutral { return image }
        guard let cg = image.cgImage else { return image }
        let source = CIImage(cgImage: cg)
        var out: CIImage
        switch filter {
        case .original:
            out = source
        case .lighten:
            let e = CIFilter.exposureAdjust()
            e.inputImage = source
            e.ev = 0.6
            out = e.outputImage ?? source
        case .enhance:
            out = document(source, amount: 1.0)
        case .magic:
            let v = CIFilter.vibrance()
            v.inputImage = document(source, amount: 1.2)
            v.amount = 0.7
            let c = CIFilter.colorControls()
            c.inputImage = v.outputImage
            c.saturation = 1.25
            c.contrast = 1.05
            out = c.outputImage ?? source
        case .noShadow:
            out = document(source, amount: 0.7)
        case .gray:
            let c = CIFilter.colorControls()
            c.inputImage = document(source, amount: 1.0)
            c.saturation = 0
            out = c.outputImage ?? source
        case .bw:
            let c = CIFilter.colorControls()
            c.inputImage = document(source, amount: 1.0)
            c.saturation = 0
            let t = CIFilter.colorThresholdOtsu()
            t.inputImage = c.outputImage
            out = t.outputImage ?? source
        case .bwSoft:
            let c = CIFilter.colorControls()
            c.inputImage = document(source, amount: 1.0)
            c.saturation = 0
            let g = CIFilter.gammaAdjust()
            g.inputImage = c.outputImage
            g.power = 1.6
            out = g.outputImage ?? source
        }
        if filter != .bw {
            if adjustments.brightness != 0 || adjustments.contrast != 0 {
                let c = CIFilter.colorControls()
                c.inputImage = out
                c.brightness = Float(adjustments.brightness * 0.3)
                c.contrast = Float(1 + adjustments.contrast * 0.8)
                out = c.outputImage ?? out
            }
            if adjustments.sharpness > 0 {
                let s = CIFilter.sharpenLuminance()
                s.inputImage = out
                s.sharpness = Float(adjustments.sharpness * 1.5)
                out = s.outputImage ?? out
            }
        }
        let strength = min(max(adjustments.strength, 0), 1)
        if strength < 0.999 && filter != .original {
            let d = CIFilter.dissolveTransition()
            d.inputImage = source
            d.targetImage = out
            d.time = Float(strength)
            out = d.outputImage ?? out
        }
        guard let result = context.createCGImage(out, from: source.extent) else { return image }
        return UIImage(cgImage: result)
    }

    /// Core Image's document enhancer: whitens the paper and sharpens the ink.
    private static func document(_ image: CIImage, amount: Float) -> CIImage {
        let f = CIFilter.documentEnhancer()
        f.inputImage = image
        f.amount = amount
        return f.outputImage ?? image
    }

    // MARK: geometry

    static func rotate(_ image: UIImage, degrees: Int) -> UIImage {
        let d = ((degrees % 360) + 360) % 360
        if d == 0 { return image }
        let w = image.size.width * image.scale, h = image.size.height * image.scale
        let size = d % 180 == 0 ? CGSize(width: w, height: h) : CGSize(width: h, height: w)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            ctx.cgContext.translateBy(x: size.width / 2, y: size.height / 2)
            ctx.cgContext.rotate(by: CGFloat(d) * .pi / 180)
            image.draw(in: CGRect(x: -w / 2, y: -h / 2, width: w, height: h))
        }
    }

    /// Rotates by a small clockwise angle, keeping the size; uncovered corners are paper white.
    static func straighten(_ image: UIImage, degrees: Double) -> UIImage {
        if abs(degrees) < 0.01 { return image }
        let w = image.size.width * image.scale, h = image.size.height * image.scale
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: format).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
            ctx.cgContext.translateBy(x: w / 2, y: h / 2)
            ctx.cgContext.rotate(by: CGFloat(degrees) * .pi / 180)
            image.draw(in: CGRect(x: -w / 2, y: -h / 2, width: w, height: h))
        }
    }

    // MARK: marks

    /// Draws strokes and text boxes onto the finished page.
    static func drawMarks(_ image: UIImage, marks: [MarkStroke], texts: [TextMark]) -> UIImage {
        if marks.isEmpty && texts.isEmpty { return image }
        let w = image.size.width * image.scale, h = image.size.height * image.scale
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: format).image { ctx in
            image.draw(in: CGRect(x: 0, y: 0, width: w, height: h))
            let cg = ctx.cgContext
            for t in texts { drawText(t, in: cg, width: w, height: h) }
            let unit = sqrt(w * h)
            for m in marks {
                guard m.points.count >= 2 else { continue }
                cg.saveGState()
                cg.setLineCap(.round)
                cg.setLineJoin(.round)
                cg.setLineWidth(max(1, CGFloat(m.width) * unit))
                var color = UIColor(cgColor: m.color.cgColor)
                if m.highlighter {
                    color = color.withAlphaComponent(110 / 255)
                    cg.setBlendMode(.multiply)
                    cg.setLineCap(.square)
                }
                cg.setStrokeColor(color.cgColor)
                cg.addPath(strokePath(m.points, width: w, height: h))
                cg.strokePath()
                cg.restoreGState()
            }
        }
    }

    /// A smooth path through normalized points, scaled to `width` × `height`.
    static func strokePath(_ points: [Double], width: CGFloat, height: CGFloat) -> CGPath {
        let path = CGMutablePath()
        let n = points.count / 2
        guard n > 0 else { return path }
        var px = CGFloat(points[0]) * width, py = CGFloat(points[1]) * height
        path.move(to: CGPoint(x: px, y: py))
        if n == 1 {
            path.addLine(to: CGPoint(x: px + 0.1, y: py))
            return path
        }
        for i in 1..<n {
            let x = CGFloat(points[2 * i]) * width, y = CGFloat(points[2 * i + 1]) * height
            path.addQuadCurve(to: CGPoint(x: (px + x) / 2, y: (py + y) / 2), control: CGPoint(x: px, y: py))
            px = x
            py = y
        }
        path.addLine(to: CGPoint(x: px, y: py))
        return path
    }

    static func textFont(_ mark: TextMark, width: CGFloat, height: CGFloat) -> UIFont {
        UIFont.systemFont(ofSize: CGFloat(mark.size) * sqrt(width * height))
    }

    /// Normalized box of a text mark on a page of the given pixel size (page axes, before the angle).
    static func textBox(_ mark: TextMark, width: CGFloat, height: CGFloat) -> CGRect {
        let font = textFont(mark, width: width, height: height)
        let lines = mark.lines
        let widest = lines.map { ($0 as NSString).size(withAttributes: [.font: font]).width }.max() ?? 0
        let total = font.pointSize * TextMark.lineHeight * CGFloat(lines.count)
        let (bw, bh) = mark.angle % 180 == 0 ? (widest, total) : (total, widest)
        return CGRect(x: CGFloat(mark.cx) * width - bw / 2, y: CGFloat(mark.cy) * height - bh / 2, width: bw, height: bh)
    }

    static func drawText(_ mark: TextMark, in cg: CGContext, width: CGFloat, height: CGFloat) {
        let font = textFont(mark, width: width, height: height)
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor(cgColor: mark.color.cgColor)]
        let lineHeight = font.pointSize * TextMark.lineHeight
        let lines = mark.lines
        let total = lineHeight * CGFloat(lines.count)
        cg.saveGState()
        cg.translateBy(x: CGFloat(mark.cx) * width, y: CGFloat(mark.cy) * height)
        cg.rotate(by: CGFloat(mark.angle) * .pi / 180)
        UIGraphicsPushContext(cg)
        var y = -total / 2
        for line in lines {
            let size = (line as NSString).size(withAttributes: attrs)
            (line as NSString).draw(at: CGPoint(x: -size.width / 2, y: y), withAttributes: attrs)
            y += lineHeight
        }
        UIGraphicsPopContext()
        cg.restoreGState()
    }
}

extension UIImage {
    func resized(to target: CGSize) -> UIImage {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            draw(in: CGRect(origin: .zero, size: target))
        }
    }

    /// Pixel size regardless of scale.
    var pixelSize: CGSize { CGSize(width: size.width * scale, height: size.height * scale) }
}
