import XCTest
@testable import CamScaner

final class GeometryTests: XCTestCase {
    private let sheet = Quad(tl: Pt(0.15, 0.10), tr: Pt(0.85, 0.12), br: Pt(0.90, 0.95), bl: Pt(0.10, 0.93))

    /// True when `p` lies inside the convex quad `q` (with a small tolerance).
    private func inside(_ q: Quad, _ p: Pt) -> Bool {
        let pts = q.points
        var sign = 0
        for i in 0..<4 {
            let a = pts[i], b = pts[(i + 1) % 4]
            let cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
            if abs(cross) < 1e-9 { continue }
            let s = cross > 0 ? 1 : -1
            if sign == 0 { sign = s } else if s != sign { return false }
        }
        return true
    }

    func testHomographyMapsTheUnitSquareOntoTheQuad() {
        let h = Homography.squareTo(sheet)
        for (uv, expected) in [((0.0, 0.0), sheet.tl), ((1.0, 0.0), sheet.tr), ((1.0, 1.0), sheet.br), ((0.0, 1.0), sheet.bl)] {
            let p = h.map(uv.0, uv.1)
            XCTAssertEqual(p.x, expected.x, accuracy: 1e-9)
            XCTAssertEqual(p.y, expected.y, accuracy: 1e-9)
        }
        // the centre of the square lands on the intersection of the diagonals
        let c = h.map(0.5, 0.5)
        XCTAssertTrue(inside(sheet, c))
    }

    func testPaperFitStaysInsideTheSheetAndTakesThePaperRatio() {
        let w = 1200, h = 1600
        for paper in PaperPreset.allCases {
            let fitted = PaperFit.fit(sheet, paper: paper, width: w, height: h)
            XCTAssertTrue(fitted.isValid(), "\(paper) fit is not a valid quad")
            for p in fitted.points { XCTAssertTrue(inside(sheet, p), "\(paper): corner \(p) outside the sheet") }
            let visible = estimateAspectRatio(sheet.scaled(Double(w), Double(h)), width: w, height: h)
            let target = paper.ratio(for: visible)
            let measured = estimateAspectRatio(fitted.scaled(Double(w), Double(h)), width: w, height: h)
            XCTAssertEqual(measured, target, accuracy: 0.12, "\(paper): ratio \(measured) instead of \(target)")
        }
    }

    func testPaperFitOfAWholePhotoIsCentred() {
        let fitted = PaperFit.fit(nil, paper: .square, width: 1000, height: 2000)
        XCTAssertEqual(fitted.tl.x, 0, accuracy: 1e-9)
        XCTAssertEqual(fitted.tr.x, 1, accuracy: 1e-9)
        XCTAssertEqual((fitted.tl.y + fitted.bl.y) / 2, 0.5, accuracy: 1e-9)
        XCTAssertEqual(fitted.bl.y - fitted.tl.y, 0.5, accuracy: 1e-9)
    }

    func testSplitHalvesMeetInTheMiddle() {
        let (left, right) = PaperFit.split(sheet)
        XCTAssertEqual(left.tr.x, right.tl.x, accuracy: 1e-9)
        XCTAssertEqual(left.br.x, right.bl.x, accuracy: 1e-9)
        XCTAssertEqual(left.tl.x, sheet.tl.x, accuracy: 1e-9)
        XCTAssertEqual(right.tr.x, sheet.tr.x, accuracy: 1e-9)
        XCTAssertTrue(left.isValid() && right.isValid())
        let (top, bottom) = PaperFit.split(nil)
        XCTAssertEqual(top.tr.x, 0.5, accuracy: 1e-9)
        XCTAssertEqual(bottom.tl.x, 0.5, accuracy: 1e-9)
    }

    func testGuideIsCentredWithThePaperProportions() {
        let g = PaperFit.guide(.a4, viewWidth: 400, viewHeight: 800)
        let w = (g.tr.x - g.tl.x) * 400, h = (g.bl.y - g.tl.y) * 800
        XCTAssertEqual(w / h, 210 / 297, accuracy: 1e-6)
        XCTAssertEqual((g.tl.x + g.tr.x) / 2, 0.5, accuracy: 1e-9)
        XCTAssertEqual((g.tl.y + g.bl.y) / 2, 0.5, accuracy: 1e-9)
        // cards are shown landscape on a portrait preview
        let card = PaperFit.guide(.idCard, viewWidth: 400, viewHeight: 800)
        XCTAssertGreaterThan((card.tr.x - card.tl.x) * 400, (card.bl.y - card.tl.y) * 800)
    }

    func testQuadRotation() {
        let q = Quad(tl: Pt(0.1, 0.2), tr: Pt(0.9, 0.2), br: Pt(0.9, 0.8), bl: Pt(0.1, 0.8))
        let r = q.rotated(90)
        XCTAssertEqual(r.tl.x, 0.8, accuracy: 1e-9)
        XCTAssertEqual(r.tl.y, 0.1, accuracy: 1e-9)
        XCTAssertEqual(q.rotated(90).rotated(270), q)
        XCTAssertEqual(q.rotated(180).rotated(180), q)
    }

    func testAspectRatioOfAFrontalRectangle() {
        let rect = Quad(tl: Pt(300, 400), tr: Pt(900, 400), br: Pt(900, 1200), bl: Pt(300, 1200))
        XCTAssertEqual(estimateAspectRatio(rect, width: 1200, height: 1600), 0.75, accuracy: 1e-6)
        let (w, h) = correctedSize(rect, ratio: 0.75, maxDim: 400)
        XCTAssertEqual(w, 300)
        XCTAssertEqual(h, 400)
    }

    func testStandardSheetsAreRecognised() {
        XCTAssertEqual(PaperKind.match(210 / 297.0), .aSeries)
        XCTAssertEqual(PaperKind.match(792 / 612.0), .letter)
        XCTAssertEqual(PaperKind.match(85.6 / 53.98), .idCard)
        XCTAssertNil(PaperKind.match(1.0))
        XCTAssertEqual(PaperKind.snap(0.70), 1 / sqrt(2.0), accuracy: 1e-9)
        XCTAssertEqual(PaperKind.snap(0.5), 0.5)
    }
}
