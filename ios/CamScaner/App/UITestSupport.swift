import UIKit

/// Hooks for the UI tests: `-uiTesting` starts from a fresh install, `-uiTestingSample` imports a
/// generated photo of a document as if it had been shared to the app.
enum UITestSupport {
    static let enabled = ProcessInfo.processInfo.arguments.contains("-uiTesting")
    static var importsSample: Bool { enabled && ProcessInfo.processInfo.arguments.contains("-uiTestingSample") }

    /// Removes documents, signatures and preferences.
    static func reset() {
        if let id = Bundle.main.bundleIdentifier { UserDefaults.standard.removePersistentDomain(forName: id) }
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.removeItem(at: support.appendingPathComponent("documents", isDirectory: true))
        try? FileManager.default.removeItem(at: support.appendingPathComponent("signatures.json"))
    }

    /// A sheet of printed paper lying on a dark table, seen slightly in perspective.
    static func samplePhoto() -> UIImage {
        let size = CGSize(width: 1200, height: 1600)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            UIColor(red: 0.23, green: 0.2, blue: 0.18, alpha: 1).setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            let sheet = UIBezierPath()
            sheet.move(to: CGPoint(x: 230, y: 220))
            sheet.addLine(to: CGPoint(x: 990, y: 250))
            sheet.addLine(to: CGPoint(x: 1040, y: 1390))
            sheet.addLine(to: CGPoint(x: 170, y: 1350))
            sheet.close()
            UIColor(white: 0.96, alpha: 1).setFill()
            sheet.fill()
            let title: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 52), .foregroundColor: UIColor.black]
            ("HOP DONG MAU" as NSString).draw(at: CGPoint(x: 300, y: 320), withAttributes: title)
            let body: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 34), .foregroundColor: UIColor(white: 0.1, alpha: 1)]
            for i in 0..<13 {
                ("Dong \(i + 1): noi dung tai lieu mau" as NSString).draw(at: CGPoint(x: 290, y: 430 + CGFloat(i) * 66), withAttributes: body)
            }
        }
    }
}
