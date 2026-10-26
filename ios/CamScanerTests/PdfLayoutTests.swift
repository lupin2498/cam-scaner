import XCTest
@testable import CamScaner

final class PdfLayoutTests: XCTestCase {
    func testA4ProportionsFillTheWholeSheet() {
        let sheets = PdfLayout.plan([PageSize(width: 2100, height: 2970, paper: nil)], paper: .auto, orientation: .auto, margin: .none, mergeIdCards: false)
        XCTAssertEqual(sheets.count, 1)
        XCTAssertEqual(sheets[0].width, 595.28, accuracy: 0.01)
        XCTAssertEqual(sheets[0].height, 841.89, accuracy: 0.01)
        XCTAssertEqual(sheets[0].items[0].width, 595.28, accuracy: 0.01)
        XCTAssertEqual(sheets[0].items[0].height, 841.89, accuracy: 0.01)
    }

    func testPageCutToAPresetGetsThatSheet() {
        let sheets = PdfLayout.plan([PageSize(width: 1480, height: 2100, paper: .a5)], paper: .auto, orientation: .auto, margin: .none, mergeIdCards: false)
        XCTAssertEqual(sheets[0].width, 148 * PaperPreset.pointsPerMm, accuracy: 0.01)
        XCTAssertEqual(sheets[0].height, 210 * PaperPreset.pointsPerMm, accuracy: 0.01)
    }

    func testTwoIdCardsShareOneA4AtRealSize() {
        let card = PageSize(width: 1712, height: 1080, paper: .idCard)
        let sheets = PdfLayout.plan([card, card], paper: .auto, orientation: .auto, margin: .none, mergeIdCards: true)
        XCTAssertEqual(sheets.count, 1)
        XCTAssertEqual(sheets[0].items.count, 2)
        XCTAssertEqual(sheets[0].width, 595.28, accuracy: 0.01)
        XCTAssertEqual(sheets[0].items[0].width, 85.6 * PaperPreset.pointsPerMm, accuracy: 0.01)
        XCTAssertEqual(sheets[0].items[0].height, 53.98 * PaperPreset.pointsPerMm, accuracy: 0.01)
        XCTAssertGreaterThan(sheets[0].items[0].y, sheets[0].items[1].y, "the first card is above the second")
        let separate = PdfLayout.plan([card, card], paper: .auto, orientation: .auto, margin: .none, mergeIdCards: false)
        XCTAssertEqual(separate.count, 2)
    }

    func testFixedPaperWithMarginKeepsTheImageInside() {
        let sheets = PdfLayout.plan([PageSize(width: 1000, height: 1000, paper: nil)], paper: .letter, orientation: .portrait, margin: .large, mergeIdCards: false)
        let item = sheets[0].items[0]
        XCTAssertEqual(sheets[0].width, 612)
        XCTAssertEqual(sheets[0].height, 792)
        XCTAssertGreaterThanOrEqual(item.x, 54 - 1e-6)
        XCTAssertGreaterThanOrEqual(item.y, 54 - 1e-6)
        XCTAssertLessThanOrEqual(item.x + item.width, 612 - 54 + 1e-6)
        XCTAssertEqual(item.width, item.height, accuracy: 1e-6)
    }

    func testSeveralPagesPerSheet() {
        let pages = (0..<5).map { _ in PageSize(width: 1000, height: 1400, paper: nil) }
        let four = PdfLayout.plan(pages, paper: .auto, orientation: .auto, margin: .small, mergeIdCards: false, pagesPerSheet: 4)
        XCTAssertEqual(four.count, 2)
        XCTAssertEqual(four[0].items.count, 4)
        XCTAssertEqual(four[1].items.count, 1)
        XCTAssertEqual(Set(four[0].items.map(\.index)), Set(0..<4))
        let two = PdfLayout.plan(pages, paper: .auto, orientation: .auto, margin: .none, mergeIdCards: false, pagesPerSheet: 2)
        XCTAssertEqual(two.count, 3)
        XCTAssertGreaterThan(two[0].width, two[0].height, "two-up sheets are landscape")
        XCTAssertLessThan(two[0].items[0].x, two[0].items[1].x)
        for sheet in four + two {
            for item in sheet.items {
                XCTAssertGreaterThanOrEqual(item.x, -1e-6)
                XCTAssertGreaterThanOrEqual(item.y, -1e-6)
                XCTAssertLessThanOrEqual(item.x + item.width, sheet.width + 1e-6)
                XCTAssertLessThanOrEqual(item.y + item.height, sheet.height + 1e-6)
            }
        }
    }
}
