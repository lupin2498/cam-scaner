import XCTest
@testable import CamScaner

final class ModelTests: XCTestCase {
    func testTextMarkTurnsWithThePage() {
        let t = TextMark(text: "Hi", cx: 0.2, cy: 0.3)
        let r = t.rotated(90)
        XCTAssertEqual(r.cx, 0.7, accuracy: 1e-9)
        XCTAssertEqual(r.cy, 0.2, accuracy: 1e-9)
        XCTAssertEqual(r.angle, 90)
        var back = t
        for _ in 0..<4 { back = back.rotated(90) }
        XCTAssertEqual(back.cx, t.cx, accuracy: 1e-9)
        XCTAssertEqual(back.cy, t.cy, accuracy: 1e-9)
        XCTAssertEqual(back.angle, 0)
    }

    func testStrokeRotationKeepsPointCount() {
        let s = MarkStroke(points: [0.1, 0.2, 0.5, 0.5], color: 0xFF000000, width: 0.01)
        let r = s.rotated(90)
        XCTAssertEqual(r.points.count, 4)
        XCTAssertEqual(r.points[0], 0.8, accuracy: 1e-9)
        XCTAssertEqual(r.points[1], 0.1, accuracy: 1e-9)
    }

    func testUndoRemovesAWholeGroup() {
        let marks = [
            MarkStroke(points: [0, 0, 1, 1], color: 0, width: 0.01),
            MarkStroke(points: [0, 0, 1, 1], color: 0, width: 0.01, group: 3),
            MarkStroke(points: [0, 1, 1, 0], color: 0, width: 0.01, group: 3),
        ]
        XCTAssertEqual(MarkStroke.undo(marks).count, 1)
        XCTAssertEqual(MarkStroke.undo(Array(marks.prefix(1))).count, 0)
        XCTAssertEqual(MarkStroke.nextGroup(marks), 4)
    }

    func testPageTurnRotatesMarksAndTexts() {
        var page = ScanPage(id: "p")
        page.marks = [MarkStroke(points: [0.1, 0.2, 0.3, 0.4], color: 0, width: 0.01)]
        page.texts = [TextMark(text: "a", cx: 0.1, cy: 0.2)]
        let turned = page.turned(by: 90)
        XCTAssertEqual(turned.rotation, 90)
        XCTAssertEqual(turned.texts[0].angle, 90)
        XCTAssertEqual(turned.marks[0].points[0], 0.8, accuracy: 1e-9)
        XCTAssertEqual(page.turned(by: -90).rotation, 270)
    }

    func testScanPageSurvivesJson() throws {
        var page = ScanPage(id: "abc", corners: Quad(tl: Pt(0.1, 0.1), tr: Pt(0.9, 0.1), br: Pt(0.9, 0.9), bl: Pt(0.1, 0.9)))
        page.paper = .idCard
        page.texts = [TextMark(text: "x", cx: 0.5, cy: 0.5, size: 0.04, color: 0xFFFF0000, angle: 90)]
        page.marks = [MarkStroke(points: [0, 0, 1, 1], color: 0xFF112233, width: 0.006, highlighter: true, group: 2)]
        page.ocrText = "hello"
        let data = try JSONEncoder().encode(page)
        let decoded = try JSONDecoder().decode(ScanPage.self, from: data)
        XCTAssertEqual(decoded, page)
    }

    func testDocumentSearchLooksIntoRecognisedText() {
        var page = ScanPage(id: "p")
        page.ocrText = "Hop dong thue nha"
        let doc = ScanDocument(id: "d", name: "Tai lieu", createdAt: 0, updatedAt: 0, pages: [page])
        XCTAssertTrue(doc.matches("thue nha"))
        XCTAssertTrue(doc.matches("tai lieu"))
        XCTAssertFalse(doc.matches("hoa don"))
    }

    func testSortOrders() {
        let a = ScanDocument(id: "a", name: "Bao cao", createdAt: 1, updatedAt: 5, pages: [])
        let b = ScanDocument(id: "b", name: "An", createdAt: 2, updatedAt: 3, pages: [])
        XCTAssertEqual(SortOrder.updated.sort([b, a]).map(\.id), ["a", "b"])
        XCTAssertEqual(SortOrder.created.sort([a, b]).map(\.id), ["b", "a"])
        XCTAssertEqual(SortOrder.name.sort([a, b]).map(\.id), ["b", "a"])
    }
}

final class ZipTests: XCTestCase {
    func testWriterAndReaderRoundTrip() throws {
        let zip = ZipWriter()
        zip.add("doc.json", Data("{\"a\":1}".utf8))
        zip.add("pages/p1/page.jpg", Data([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10]))
        zip.add("empty.txt", Data())
        let data = zip.finish()
        let entries = try ZipReader.entries(data)
        XCTAssertEqual(entries.map(\.name), ["doc.json", "pages/p1/page.jpg", "empty.txt"])
        XCTAssertEqual(String(decoding: entries[0].data, as: UTF8.self), "{\"a\":1}")
        XCTAssertEqual(entries[1].data, Data([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10]))
        XCTAssertEqual(entries[2].data.count, 0)
    }

    func testGarbageIsRejected() {
        XCTAssertThrowsError(try ZipReader.entries(Data(repeating: 7, count: 100)))
        XCTAssertThrowsError(try ZipReader.entries(Data()))
    }

    func testDocxIsAValidPackage() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("test-\(UUID().uuidString).docx")
        try Exporters.docx(pages: [(text: "Dong 1\nDong 2 <&>", image: nil, width: 100, height: 140)], to: url)
        let entries = try ZipReader.entries(try Data(contentsOf: url))
        let names = Set(entries.map(\.name))
        XCTAssertTrue(names.contains("[Content_Types].xml"))
        XCTAssertTrue(names.contains("word/document.xml"))
        let document = String(decoding: entries.first { $0.name == "word/document.xml" }!.data, as: UTF8.self)
        XCTAssertTrue(document.contains("Dong 2 &lt;&amp;&gt;"))
        try? FileManager.default.removeItem(at: url)
    }
}
