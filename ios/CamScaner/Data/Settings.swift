import Foundation
import Combine
import SwiftUI

/// User preferences backed by UserDefaults.
final class Settings: ObservableObject {
    private let defaults = UserDefaults.standard

    @Published var defaultFilter: ScanFilter { didSet { defaults.set(defaultFilter.rawValue, forKey: "default_filter") } }
    @Published var autoCapture: Bool { didSet { defaults.set(autoCapture, forKey: "auto_capture") } }
    @Published var autoCrop: Bool { didSet { defaults.set(autoCrop, forKey: "auto_crop") } }
    @Published var showGrid: Bool { didSet { defaults.set(showGrid, forKey: "grid") } }
    @Published var batchMode: Bool { didSet { defaults.set(batchMode, forKey: "batch_mode") } }
    @Published var cameraPaper: PaperPreset? { didSet { defaults.set(cameraPaper?.rawValue, forKey: "camera_paper") } }
    @Published var sortOrder: SortOrder { didSet { defaults.set(sortOrder.rawValue, forKey: "sort_order") } }
    @Published var theme: AppTheme { didSet { defaults.set(theme.rawValue, forKey: "theme") } }
    @Published var appLock: Bool { didSet { defaults.set(appLock, forKey: "app_lock") } }
    @Published var namePrefix: String { didSet { defaults.set(namePrefix, forKey: "name_prefix") } }
    @Published var pdfOptions: PdfOptions {
        didSet {
            var saved = pdfOptions
            saved.password = ""
            if let data = try? JSONEncoder().encode(saved) { defaults.set(data, forKey: "pdf_options") }
        }
    }

    init() {
        defaultFilter = ScanFilter(rawValue: defaults.string(forKey: "default_filter") ?? "") ?? .enhance
        autoCapture = defaults.object(forKey: "auto_capture") as? Bool ?? true
        autoCrop = defaults.object(forKey: "auto_crop") as? Bool ?? true
        showGrid = defaults.bool(forKey: "grid")
        batchMode = defaults.bool(forKey: "batch_mode")
        cameraPaper = PaperPreset(rawValue: defaults.string(forKey: "camera_paper") ?? "")
        sortOrder = SortOrder(rawValue: defaults.string(forKey: "sort_order") ?? "") ?? .updated
        theme = AppTheme(rawValue: defaults.string(forKey: "theme") ?? "") ?? .system
        appLock = defaults.bool(forKey: "app_lock")
        namePrefix = defaults.string(forKey: "name_prefix") ?? ""
        if let data = defaults.data(forKey: "pdf_options"), let o = try? JSONDecoder().decode(PdfOptions.self, from: data) {
            pdfOptions = o
        } else {
            pdfOptions = PdfOptions(fileName: "scan")
        }
    }

    var colorScheme: ColorScheme? {
        switch theme {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }

    /// "Cam Scaner 25-10-2026 18.38" style default name.
    func defaultDocumentName() -> String {
        let f = DateFormatter()
        f.dateFormat = "dd-MM-yyyy HH.mm"
        let prefix = namePrefix.trimmingCharacters(in: .whitespaces)
        return "\(prefix.isEmpty ? "Cam Scaner" : prefix) \(f.string(from: Date()))"
    }
}

/// Signatures kept for reuse, newest first.
final class SignatureStore: ObservableObject {
    @Published private(set) var signatures: [SavedSignature] = []
    private let url: URL

    init(directory: URL) {
        url = directory.appendingPathComponent("signatures.json")
        if let data = try? Data(contentsOf: url), let list = try? JSONDecoder().decode([SavedSignature].self, from: data) {
            signatures = list
        }
    }

    func add(_ s: SavedSignature) {
        signatures = ([s] + signatures.filter { $0.strokes != s.strokes }).prefix(6).map { $0 }
        persist()
    }

    func remove(_ s: SavedSignature) {
        signatures.removeAll { $0.id == s.id }
        persist()
    }

    func removeAll() {
        signatures = []
        persist()
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(signatures) { try? data.write(to: url, options: .atomic) }
    }
}
