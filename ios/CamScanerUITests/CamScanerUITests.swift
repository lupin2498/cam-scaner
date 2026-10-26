import XCTest

/// Drives the app on the simulator. Each test starts from a fresh install (`-uiTesting`); the
/// simulator has no camera, so pages come from a generated photo imported at launch.
final class CamScanerUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-uiTesting"]
        // permission prompts (camera, photos) are accepted when they get in the way
        addUIInterruptionMonitor(withDescription: "System alert") { alert in
            for title in ["Allow", "OK", "Allow Full Access", "Cho phép"] where alert.buttons[title].exists {
                alert.buttons[title].tap()
                return true
            }
            return false
        }
    }

    @discardableResult
    private func element(_ id: String, timeout: TimeInterval = 30, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        let e = app.descendants(matching: .any)[id].firstMatch
        XCTAssertTrue(e.waitForExistence(timeout: timeout), "\(id) did not appear", file: file, line: line)
        return e
    }

    /// Scrolls the screen up until the element is on it (rows of long lists are only created when shown).
    @discardableResult
    private func scrolledTo(_ id: String, swipes: Int = 8, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        let e = app.descendants(matching: .any)[id].firstMatch
        var n = 0
        while !(e.exists && e.isHittable) && n < swipes {
            app.swipeUp()
            n += 1
        }
        XCTAssertTrue(e.exists && e.isHittable, "\(id) not found after scrolling", file: file, line: line)
        return e
    }

    private func waitGone(_ e: XCUIElement, timeout: TimeInterval = 20, file: StaticString = #filePath, line: UInt = #line) {
        let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: e)
        XCTAssertEqual(XCTWaiter().wait(for: [gone], timeout: timeout), .completed, "element still there", file: file, line: line)
    }

    private func shot(_ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }

    /// Imports the generated photo, fits the outline to A4, lightens it and saves: the new document opens.
    private func scanSample() {
        app.launchArguments.append("-uiTestingSample")
        app.launch()
        element("crop.next", timeout: 60)
        element("crop.editor")
        shot("crop")
        element("paper.A4").tap()
        element("crop.next").tap()
        element("filter.save", timeout: 30)
        element("filter.lighten").tap()
        shot("filter")
        element("filter.save").tap()
        element("doc.export", timeout: 60)
        element("doc.page.0")
        shot("document")
    }

    private func backToHome() {
        while !app.descendants(matching: .any)["home.scan"].exists {
            let back = app.navigationBars.buttons.element(boundBy: 0)
            guard back.exists else { break }
            back.tap()
        }
        element("home.scan")
    }

    func testScanSaveAndExport() {
        scanSample()
        element("doc.export").tap()
        element("export.create").tap()
        element("export.done", timeout: 60)
        shot("export")
        // text recognition runs for the text export
        scrolledTo("export.txt").tap()
        let file = element("export.file", timeout: 120)
        let txt = expectation(for: NSPredicate(format: "label ENDSWITH '.txt'"), evaluatedWith: file)
        XCTAssertEqual(XCTWaiter().wait(for: [txt], timeout: 120), .completed)
        backToHome()
        XCTAssertEqual(app.descendants(matching: .any).matching(identifier: "doc.row").count, 1)
        shot("home")
    }

    func testPageToolsTextAndRecognition() {
        scanSample()
        element("doc.page.0").tap()
        element("page.filter").tap()

        // a text box typed onto the page
        element("filter.panel.filters").swipeLeft()
        element("filter.text").tap()
        element("markup.add").tap()
        let field = element("input.field")
        field.tap()
        field.typeText("DA THANH TOAN")
        element("input.done").tap()
        shot("markup_text")
        element("markup.done").tap()
        element("filter.save").tap()

        // back on the page: recognise its text
        element("page.ocr", timeout: 60).tap()
        let text = element("ocr.text", timeout: 120)
        let found = expectation(for: NSPredicate(format: "value CONTAINS[c] 'HOP DONG'"), evaluatedWith: text)
        XCTAssertEqual(XCTWaiter().wait(for: [found], timeout: 60), .completed, "recognised text: \(String(describing: text.value))")
        shot("ocr")
    }

    func testFoldersSelectionTrashAndSearch() {
        scanSample()
        backToHome()

        // a new folder is selected and empty
        element("home.newFolder").tap()
        let name = element("input.field")
        name.tap()
        name.typeText("Cong viec")
        element("input.done").tap()
        element("folder.Cong viec")
        waitGone(app.descendants(matching: .any).matching(identifier: "doc.row").firstMatch)
        element("folder.all").tap()
        element("doc.row")

        // search finds nothing for an unknown word, the document again when cleared
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.tap()
        search.typeText("zzzz")
        waitGone(app.descendants(matching: .any).matching(identifier: "doc.row").firstMatch)
        app.buttons["Cancel"].firstMatch.tap()
        element("doc.row")

        // select the document, delete it, bring it back from the trash
        element("home.more").tap()
        app.buttons["Chọn tài liệu"].tap()
        element("doc.row").tap()
        element("home.deleteSelected").tap()
        app.alerts.buttons["Xóa"].tap()
        waitGone(app.descendants(matching: .any).matching(identifier: "doc.row").firstMatch)
        element("home.more").tap()
        app.buttons["Thùng rác (1)"].tap()
        let restore = app.buttons["Khôi phục"]
        XCTAssertTrue(restore.waitForExistence(timeout: 10))
        shot("trash")
        restore.tap()
        XCTAssertTrue(app.staticTexts["Thùng rác trống"].waitForExistence(timeout: 10))
        backToHome()
        element("doc.row")

        // settings: dark theme
        element("home.more").tap()
        app.buttons["Cài đặt"].tap()
        element("settings.theme")
        app.buttons["Tối"].tap()
        shot("settings_dark")
    }

    func testCameraScreenOpensAndCloses() {
        app.launch()
        element("home.scan").tap()
        // the simulator has no camera: the screen says so and closes again
        element("camera.close", timeout: 30)
        app.tap()
        shot("camera")
        element("camera.close").tap()
        element("home.scan")
    }
}
