import Foundation
import CoreGraphics

/// A point; corners are stored normalized to [0, 1] relative to the upright original image.
struct Pt: Codable, Equatable, Hashable {
    var x: Double
    var y: Double

    init(_ x: Double, _ y: Double) {
        self.x = x
        self.y = y
    }

    static func + (a: Pt, b: Pt) -> Pt { Pt(a.x + b.x, a.y + b.y) }
    static func - (a: Pt, b: Pt) -> Pt { Pt(a.x - b.x, a.y - b.y) }
    static func * (a: Pt, s: Double) -> Pt { Pt(a.x * s, a.y * s) }

    func distance(to o: Pt) -> Double { hypot(x - o.x, y - o.y) }
    var clamped: Pt { Pt(min(max(x, 0), 1), min(max(y, 0), 1)) }
    var cgPoint: CGPoint { CGPoint(x: x, y: y) }
}

/// Four document corners in the order top-left, top-right, bottom-right, bottom-left.
struct Quad: Codable, Equatable, Hashable {
    var tl: Pt
    var tr: Pt
    var br: Pt
    var bl: Pt

    static let full = Quad(tl: Pt(0, 0), tr: Pt(1, 0), br: Pt(1, 1), bl: Pt(0, 1))

    var points: [Pt] { [tl, tr, br, bl] }

    func with(_ index: Int, _ p: Pt) -> Quad {
        var q = self
        switch index {
        case 0: q.tl = p
        case 1: q.tr = p
        case 2: q.br = p
        default: q.bl = p
        }
        return q
    }

    func scaled(_ sx: Double, _ sy: Double) -> Quad {
        Quad(tl: Pt(tl.x * sx, tl.y * sy), tr: Pt(tr.x * sx, tr.y * sy), br: Pt(br.x * sx, br.y * sy), bl: Pt(bl.x * sx, bl.y * sy))
    }

    /// True when the polygon is convex and every interior angle is reasonable.
    func isValid(minAngle: Double = 20, maxAngle: Double = 160) -> Bool {
        let p = points
        var sign = 0
        for i in 0..<4 {
            let a = p[i], b = p[(i + 1) % 4], c = p[(i + 2) % 4]
            let cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if abs(cross) < 1e-12 { return false }
            let s = cross > 0 ? 1 : -1
            if sign == 0 { sign = s } else if s != sign { return false }
        }
        return interiorAngles().allSatisfy { $0 >= minAngle && $0 <= maxAngle }
    }

    func interiorAngles() -> [Double] {
        let p = points
        return (0..<4).map { i in
            let prev = p[(i + 3) % 4], cur = p[i], next = p[(i + 1) % 4]
            let v1 = prev - cur, v2 = next - cur
            let cosv = (v1.x * v2.x + v1.y * v2.y) / (hypot(v1.x, v1.y) * hypot(v2.x, v2.y) + 1e-12)
            return acos(min(max(cosv, -1), 1)) * 180 / .pi
        }
    }

    /// Shoelace area in the units of the points.
    func area() -> Double {
        let p = points
        var s = 0.0
        for i in 0..<4 {
            let a = p[i], b = p[(i + 1) % 4]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2
    }

    func maxDistance(to o: Quad) -> Double {
        zip(points, o.points).map { $0.distance(to: $1) }.max() ?? 0
    }

    /// Orders four arbitrary points clockwise starting from the top-left one.
    static func ordered(_ pts: [Pt]) -> Quad {
        let cx = pts.map(\.x).reduce(0, +) / 4
        let cy = pts.map(\.y).reduce(0, +) / 4
        let sorted = pts.sorted { atan2($0.y - cy, $0.x - cx) < atan2($1.y - cy, $1.x - cx) }
        var start = 0
        for i in 1..<4 where sorted[i].x + sorted[i].y < sorted[start].x + sorted[start].y { start = i }
        let r = (0..<4).map { sorted[(start + $0) % 4] }
        return Quad(tl: r[0], tr: r[1], br: r[2], bl: r[3])
    }

    /// These normalized corners as seen on the photo turned clockwise by `degrees` (a multiple of 90).
    func rotated(_ degrees: Int) -> Quad {
        let turn = ((degrees % 360) + 360) % 360
        func map(_ p: Pt) -> Pt {
            switch turn {
            case 90: return Pt(1 - p.y, p.x)
            case 180: return Pt(1 - p.x, 1 - p.y)
            case 270: return Pt(p.y, 1 - p.x)
            default: return p
            }
        }
        return Quad(tl: map(tl), tr: map(tr), br: map(br), bl: map(bl))
    }
}

// MARK: - Real aspect ratio of a photographed rectangle (Zhang & He, 2007)

private let minFocal = 0.25
private let maxFocal = 5.0
private let nominalFocal = 0.8
private let minConvergenceDeg = 1.0

private func cross3(_ a: [Double], _ b: [Double]) -> [Double] {
    [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
}

private func dot3(_ a: [Double], _ b: [Double]) -> Double { a[0] * b[0] + a[1] * b[1] + a[2] * b[2] }

private func angleBetween(_ a1: Pt, _ a2: Pt, _ b1: Pt, _ b2: Pt) -> Double {
    let u = a2 - a1, v = b2 - b1
    let norm = hypot(u.x, u.y) * hypot(v.x, v.y)
    if norm <= 0 { return 0 }
    let c = min(abs(u.x * v.x + u.y * v.y) / norm, 1)
    return acos(c) * 180 / .pi
}

/// Estimates the real width/height ratio of a rectangle seen in perspective; `c` is in pixels of an
/// image of `width` × `height`. Falls back to the visible ratio when the estimate is unstable.
func estimateAspectRatio(_ c: Quad, width: Int, height: Int) -> Double {
    let visW = max(c.tl.distance(to: c.tr), c.bl.distance(to: c.br))
    let visH = max(c.tl.distance(to: c.bl), c.tr.distance(to: c.br))
    if visW <= 0 || visH <= 0 { return 1 }
    let visible = visW / visH
    let u0 = Double(width) / 2, v0 = Double(height) / 2
    let m1 = [c.tl.x, c.tl.y, 1.0], m2 = [c.tr.x, c.tr.y, 1.0], m3 = [c.bl.x, c.bl.y, 1.0], m4 = [c.br.x, c.br.y, 1.0]
    let d2 = dot3(cross3(m2, m4), m3)
    let d3 = dot3(cross3(m3, m4), m2)
    if abs(d2) < 1e-9 || abs(d3) < 1e-9 { return visible }
    let k2 = dot3(cross3(m1, m4), m3) / d2
    let k3 = dot3(cross3(m1, m4), m2) / d3
    let n2 = (0..<3).map { k2 * m2[$0] - m1[$0] }
    let n3 = (0..<3).map { k3 * m3[$0] - m1[$0] }
    let longSide = Double(max(width, height))
    var f = Double.nan
    if abs(n2[2]) > 1e-6 && abs(n3[2]) > 1e-6 {
        let f2 = -((n2[0] * n3[0] - (n2[0] * n3[2] + n2[2] * n3[0]) * u0 + n2[2] * n3[2] * u0 * u0) +
            (n2[1] * n3[1] - (n2[1] * n3[2] + n2[2] * n3[1]) * v0 + n2[2] * n3[2] * v0 * v0)) / (n2[2] * n3[2])
        if f2 > 0 { f = sqrt(f2) }
    }
    let convergence = min(angleBetween(c.tl, c.tr, c.bl, c.br), angleBetween(c.tl, c.bl, c.tr, c.br))
    let plausible = !f.isNaN && f >= minFocal * longSide && f <= maxFocal * longSide
    if !plausible || convergence < minConvergenceDeg { f = nominalFocal * longSide }
    func quad(_ n: [Double]) -> Double {
        let x = (n[0] - u0 * n[2]) / f
        let y = (n[1] - v0 * n[2]) / f
        return x * x + y * y + n[2] * n[2]
    }
    let q3 = quad(n3)
    let ratio = q3 > 0 ? sqrt(quad(n2) / q3) : Double.nan
    if ratio.isNaN || ratio < 0.2 || ratio > 5 || abs(ratio / visible - 1) > 0.6 { return visible }
    return ratio
}

/// Output size in pixels for a perspective-corrected page with the given real ratio.
func correctedSize(_ c: Quad, ratio: Double, maxDim: Int) -> (Int, Int) {
    let visW = max(c.tl.distance(to: c.tr), c.bl.distance(to: c.br))
    let visH = max(c.tl.distance(to: c.bl), c.tr.distance(to: c.br))
    var w: Double, h: Double
    if ratio < visW / visH {
        w = visW; h = w / ratio
    } else {
        h = visH; w = h * ratio
    }
    let s = min(1, Double(maxDim) / max(w, h))
    w *= s; h *= s
    return (max(1, Int(w.rounded())), max(1, Int(h.rounded())))
}

// MARK: - Homography and paper fitting

/// Projective map of the unit square onto a quadrilateral (Heckbert): (0,0) ↦ tl, (1,0) ↦ tr, (1,1) ↦ br, (0,1) ↦ bl.
struct Homography {
    let a, b, c, d, e, f, g, h: Double

    func map(_ u: Double, _ v: Double) -> Pt {
        let den = g * u + h * v + 1
        return Pt((a * u + b * v + c) / den, (d * u + e * v + f) / den)
    }

    func quad(_ u0: Double, _ v0: Double, _ u1: Double, _ v1: Double) -> Quad {
        Quad(tl: map(u0, v0), tr: map(u1, v0), br: map(u1, v1), bl: map(u0, v1))
    }

    static func squareTo(_ q: Quad) -> Homography {
        let (x0, y0) = (q.tl.x, q.tl.y), (x1, y1) = (q.tr.x, q.tr.y), (x2, y2) = (q.br.x, q.br.y), (x3, y3) = (q.bl.x, q.bl.y)
        let sx = x0 - x1 + x2 - x3, sy = y0 - y1 + y2 - y3
        if abs(sx) < 1e-9 && abs(sy) < 1e-9 {
            return Homography(a: x1 - x0, b: x3 - x0, c: x0, d: y1 - y0, e: y3 - y0, f: y0, g: 0, h: 0)
        }
        let dx1 = x1 - x2, dx2 = x3 - x2, dy1 = y1 - y2, dy2 = y3 - y2
        let det = dx1 * dy2 - dx2 * dy1
        if abs(det) < 1e-12 {
            return Homography(a: x1 - x0, b: x3 - x0, c: x0, d: y1 - y0, e: y3 - y0, f: y0, g: 0, h: 0)
        }
        let g = (sx * dy2 - dx2 * sy) / det
        let h = (dx1 * sy - sx * dy1) / det
        return Homography(a: x1 - x0 + g * x1, b: x3 - x0 + h * x3, c: x0, d: y1 - y0 + g * y1, e: y3 - y0 + h * y3, f: y0, g: g, h: h)
    }
}

enum PaperFit {
    /// The largest region with the proportions of `paper` inside `base` (the detected sheet, or the
    /// whole photo when nil), centred on it and seen in the same perspective.
    static func fit(_ base: Quad?, paper: PaperPreset, width: Int, height: Int) -> Quad {
        let quad = base ?? .full
        let px = quad.scaled(Double(width), Double(height))
        let visible = estimateAspectRatio(px, width: width, height: height)
        let target = paper.ratio(for: visible)
        let k = target / visible
        let hom = Homography.squareTo(quad)
        if k <= 1 {
            return hom.quad(0.5 - k / 2, 0, 0.5 + k / 2, 1)
        } else {
            let hh = 1 / k
            return hom.quad(0, 0.5 - hh / 2, 1, 0.5 + hh / 2)
        }
    }

    /// The two pages of an open book photographed as one spread: left and right halves.
    static func split(_ spread: Quad?) -> (Quad, Quad) {
        let h = Homography.squareTo(spread ?? .full)
        return (h.quad(0, 0, 0.5, 1), h.quad(0.5, 0, 1, 1))
    }

    /// Normalized frame with the proportions of `paper` centred in a preview of the given size.
    static func guide(_ paper: PaperPreset, viewWidth: Double, viewHeight: Double, fill: Double = 0.86) -> Quad {
        let portraitView = viewHeight >= viewWidth
        let landscapeFormat = paper.isCard
        let ratio = portraitView != landscapeFormat ? paper.shortOverLong : 1 / paper.shortOverLong
        var w = viewWidth * fill
        var h = w / ratio
        if h > viewHeight * fill {
            h = viewHeight * fill
            w = h * ratio
        }
        let left = (viewWidth - w) / 2 / viewWidth, top = (viewHeight - h) / 2 / viewHeight
        let right = left + w / viewWidth, bottom = top + h / viewHeight
        return Quad(tl: Pt(left, top), tr: Pt(right, top), br: Pt(right, bottom), bl: Pt(left, bottom))
    }
}
