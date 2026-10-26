import Foundation
import CoreGraphics

/// Document beautification filters.
enum ScanFilter: String, Codable, CaseIterable, Identifiable {
    case original, lighten, enhance, magic, noShadow = "no_shadow", gray, bw, bwSoft = "bw_soft"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .original: return "Nguyên gốc"
        case .lighten: return "Làm sáng"
        case .enhance: return "Tăng cường"
        case .magic: return "Thần kỳ màu"
        case .noShadow: return "Không có bóng"
        case .gray: return "Thang xám"
        case .bw: return "Đen trắng"
        case .bwSoft: return "Bản sao"
        }
    }
}

/// User fine-tuning applied after the filter.
struct Adjustments: Codable, Equatable, Hashable {
    var brightness: Double = 0
    var contrast: Double = 0
    var sharpness: Double = 0
    var strength: Double = 1

    var isNeutral: Bool { brightness == 0 && contrast == 0 && sharpness == 0 }
}

/// Sheet and card formats the crop region can be fitted to (millimetres).
enum PaperPreset: String, Codable, CaseIterable, Identifiable {
    case a4 = "A4", a5 = "A5", a6 = "A6", a3 = "A3", b5 = "B5", letter = "LETTER", legal = "LEGAL"
    case idCard = "ID_CARD", passport = "PASSPORT", businessCard = "BUSINESS_CARD", photo4x6 = "PHOTO_4X6", square = "SQUARE"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .a4: return "A4"
        case .a5: return "A5"
        case .a6: return "A6"
        case .a3: return "A3"
        case .b5: return "B5"
        case .letter: return "Letter"
        case .legal: return "Legal"
        case .idCard: return "Thẻ CCCD"
        case .passport: return "Hộ chiếu"
        case .businessCard: return "Danh thiếp"
        case .photo4x6: return "Ảnh 10×15"
        case .square: return "Vuông"
        }
    }

    /// (width, height) in millimetres; sheets portrait, cards landscape.
    var mm: (Double, Double) {
        switch self {
        case .a4: return (210, 297)
        case .a5: return (148, 210)
        case .a6: return (105, 148)
        case .a3: return (297, 420)
        case .b5: return (176, 250)
        case .letter: return (215.9, 279.4)
        case .legal: return (215.9, 355.6)
        case .idCard: return (85.6, 53.98)
        case .passport: return (125, 88)
        case .businessCard: return (90, 55)
        case .photo4x6: return (102, 152)
        case .square: return (100, 100)
        }
    }

    var isCard: Bool { self == .idCard || self == .passport || self == .businessCard }

    /// Short side over long side.
    var shortOverLong: Double { min(mm.0, mm.1) / max(mm.0, mm.1) }

    static let pointsPerMm = 72.0 / 25.4

    /// Width/height in PDF points when used portrait.
    var widthPt: Double { min(mm.0, mm.1) * PaperPreset.pointsPerMm }
    var heightPt: Double { max(mm.0, mm.1) * PaperPreset.pointsPerMm }

    /// The width/height ratio in the orientation closest to a measured one.
    func ratio(for visible: Double) -> Double { visible <= 1 ? shortOverLong : 1 / shortOverLong }
}

/// Standard sheets recognised from a short/long ratio.
enum PaperKind {
    case aSeries, letter, legal, idCard

    var ratio: Double {
        switch self {
        case .aSeries: return 1 / sqrt(2.0)
        case .letter: return 612.0 / 792.0
        case .legal: return 612.0 / 1008.0
        case .idCard: return 153.01 / 242.65
        }
    }

    static let all: [PaperKind] = [.aSeries, .letter, .legal, .idCard]
    static let snapTolerance = 0.035

    /// The standard sheet whose ratio is closest to `ratio` (w/h, any orientation), if any.
    static func match(_ ratio: Double) -> PaperKind? {
        if ratio <= 0 || ratio.isNaN { return nil }
        let shortOverLong = ratio <= 1 ? ratio : 1 / ratio
        return all.map { ($0, abs(shortOverLong / $0.ratio - 1)) }.filter { $0.1 < snapTolerance }.min { $0.1 < $1.1 }?.0
    }

    static func snap(_ ratio: Double) -> Double {
        guard let kind = match(ratio) else { return ratio }
        return ratio <= 1 ? kind.ratio : 1 / kind.ratio
    }
}

/// PDF page sizes in points (portrait).
enum PaperSize: String, Codable, CaseIterable, Identifiable {
    case auto = "AUTO", fitImage = "FIT_IMAGE", a4 = "A4", letter = "LETTER", legal = "LEGAL", a3 = "A3", a5 = "A5", b5 = "B5"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .auto: return "Tự động"
        case .fitImage: return "Theo ảnh"
        case .a4: return "A4"
        case .letter: return "Letter"
        case .legal: return "Legal"
        case .a3: return "A3"
        case .a5: return "A5"
        case .b5: return "B5"
        }
    }

    var size: (Double, Double) {
        switch self {
        case .auto, .fitImage: return (0, 0)
        case .a4: return (595.28, 841.89)
        case .letter: return (612, 792)
        case .legal: return (612, 1008)
        case .a3: return (841.89, 1190.55)
        case .a5: return (419.53, 595.28)
        case .b5: return (498.9, 708.66)
        }
    }

    var isFixed: Bool { size.0 > 0 }
}

enum PageOrientation: String, Codable, CaseIterable, Identifiable {
    case auto = "AUTO", portrait = "PORTRAIT", landscape = "LANDSCAPE"
    var id: String { rawValue }
    var label: String {
        switch self {
        case .auto: return "Tự động"
        case .portrait: return "Dọc"
        case .landscape: return "Ngang"
        }
    }
}

enum PageMargin: String, Codable, CaseIterable, Identifiable {
    case none = "NONE", small = "SMALL", medium = "MEDIUM", large = "LARGE"
    var id: String { rawValue }
    var label: String {
        switch self {
        case .none: return "Không"
        case .small: return "Nhỏ"
        case .medium: return "Vừa"
        case .large: return "Lớn"
        }
    }
    var points: Double {
        switch self {
        case .none: return 0
        case .small: return 18
        case .medium: return 36
        case .large: return 54
        }
    }
}

enum PdfQuality: String, Codable, CaseIterable, Identifiable {
    case low = "LOW", medium = "MEDIUM", high = "HIGH", original = "ORIGINAL"
    var id: String { rawValue }
    var label: String {
        switch self {
        case .low: return "Nhẹ"
        case .medium: return "Vừa"
        case .high: return "Cao"
        case .original: return "HD gốc"
        }
    }
    var maxDim: Int {
        switch self {
        case .low: return 1400
        case .medium: return 2000
        case .high: return 2600
        case .original: return 3200
        }
    }
    var jpegQuality: Double {
        switch self {
        case .low: return 0.6
        case .medium: return 0.72
        case .high: return 0.84
        case .original: return 0.93
        }
    }
}

struct PdfOptions: Codable, Equatable {
    var fileName: String
    var paperSize: PaperSize = .auto
    var orientation: PageOrientation = .auto
    var margin: PageMargin = .none
    var quality: PdfQuality = .high
    var ocrLayer = false
    var mergeIdCards = false
    var pageNumbers = false
    var password = ""
    var watermark = ""
    var pagesPerSheet = 1
}

enum SortOrder: String, Codable, CaseIterable, Identifiable {
    case updated = "UPDATED", created = "CREATED", name = "NAME"
    var id: String { rawValue }
    var label: String {
        switch self {
        case .updated: return "Mới sửa gần đây"
        case .created: return "Ngày tạo"
        case .name: return "Tên A–Z"
        }
    }

    func sort(_ docs: [ScanDocument]) -> [ScanDocument] {
        switch self {
        case .updated: return docs.sorted { $0.updatedAt > $1.updatedAt }
        case .created: return docs.sorted { $0.createdAt > $1.createdAt }
        case .name: return docs.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        }
    }
}

enum AppTheme: String, Codable, CaseIterable, Identifiable {
    case system = "SYSTEM", light = "LIGHT", dark = "DARK"
    var id: String { rawValue }
    var label: String {
        switch self {
        case .system: return "Theo hệ thống"
        case .light: return "Sáng"
        case .dark: return "Tối"
        }
    }
}

/// A pen, highlighter or eraser stroke on the finished page; points are x, y pairs normalized to
/// the page, `width` relative to sqrt(page width × height). Strokes placed together share a `group`.
struct MarkStroke: Codable, Equatable, Hashable {
    var points: [Double]
    var color: UInt32
    var width: Double
    var highlighter = false
    var group = 0

    func rotated(_ degrees: Int) -> MarkStroke {
        let turn = ((degrees % 360) + 360) % 360
        if turn == 0 { return self }
        var out: [Double] = []
        out.reserveCapacity(points.count)
        for i in 0..<(points.count / 2) {
            let x = points[2 * i], y = points[2 * i + 1]
            switch turn {
            case 90: out += [1 - y, x]
            case 180: out += [1 - x, 1 - y]
            default: out += [y, 1 - x]
            }
        }
        var s = self
        s.points = out
        return s
    }

    static func undo(_ marks: [MarkStroke]) -> [MarkStroke] {
        guard let last = marks.last else { return marks }
        if last.group == 0 { return Array(marks.dropLast()) }
        var m = marks
        while let l = m.last, l.group == last.group { m.removeLast() }
        return m
    }

    static func nextGroup(_ marks: [MarkStroke]) -> Int { (marks.map(\.group).max() ?? 0) + 1 }
}

/// A text box typed onto the page: centre normalized, font size relative to sqrt(w × h), angle a multiple of 90.
struct TextMark: Codable, Equatable, Hashable {
    var text: String
    var cx: Double
    var cy: Double
    var size: Double = 0.03
    var color: UInt32 = 0xFF111111
    var angle = 0

    static let lineHeight = 1.25

    var lines: [String] { text.components(separatedBy: "\n") }

    func rotated(_ degrees: Int) -> TextMark {
        let turn = ((degrees % 360) + 360) % 360
        if turn == 0 { return self }
        var t = self
        switch turn {
        case 90: t.cx = 1 - cy; t.cy = cx
        case 180: t.cx = 1 - cx; t.cy = 1 - cy
        default: t.cx = cy; t.cy = 1 - cx
        }
        t.angle = (angle + turn) % 360
        return t
    }
}

/// One page of a document: the original photo plus everything applied to it.
struct ScanPage: Codable, Equatable, Identifiable {
    var id: String
    var corners: Quad?
    var rotation = 0
    var filter: ScanFilter = .enhance
    var adjustments = Adjustments()
    /// Clockwise straightening in degrees.
    var skew: Double = 0
    var marks: [MarkStroke] = []
    var texts: [TextMark] = []
    var paper: PaperPreset?
    var ocrText: String?
    var width = 0
    var height = 0
    var updatedAt: Double = Date().timeIntervalSince1970

    /// Turns the page and everything drawn on it.
    func turned(by delta: Int) -> ScanPage {
        var p = self
        p.rotation = ((rotation + delta) % 360 + 360) % 360
        p.marks = marks.map { $0.rotated(delta) }
        p.texts = texts.map { $0.rotated(delta) }
        return p
    }
}

struct ScanDocument: Codable, Equatable, Identifiable {
    var id: String
    var name: String
    var createdAt: Double
    var updatedAt: Double
    var pages: [ScanPage]
    var folderId: String?
    var deletedAt: Double?

    func matches(_ query: String) -> Bool {
        name.localizedCaseInsensitiveContains(query) || pages.contains { $0.ocrText?.localizedCaseInsensitiveContains(query) == true }
    }
}

struct Folder: Codable, Equatable, Identifiable {
    var id: String
    var name: String
    var createdAt: Double
}

/// A signature kept for reuse: strokes normalized to their box, aspect = width / height.
struct SavedSignature: Codable, Equatable, Identifiable {
    var id = UUID().uuidString
    var strokes: [[Double]]
    var aspect: Double
    var width: Double
    var color: UInt32
}

func newId() -> String { String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16)) }

/// A page being scanned or edited, before it is saved into a document.
struct DraftPage: Identifiable, Equatable {
    var id: String = newId()
    /// Upright original photo (JPEG) in the scan folder.
    var originalURL: URL
    var width: Int
    var height: Int
    var detected: Quad?
    var corners: Quad?
    var rotation = 0
    var filter: ScanFilter = .enhance
    var adjustments = Adjustments()
    var skew: Double = 0
    var marks: [MarkStroke] = []
    var texts: [TextMark] = []
    var paper: PaperPreset?
    /// Set when re-editing a page that already belongs to a document.
    var existingPageId: String?

    func turned(by delta: Int) -> DraftPage {
        var p = self
        p.rotation = ((rotation + delta) % 360 + 360) % 360
        p.marks = marks.map { $0.rotated(delta) }
        p.texts = texts.map { $0.rotated(delta) }
        return p
    }
}

extension UInt32 {
    /// 0xAARRGGBB as a CGColor.
    var cgColor: CGColor {
        CGColor(red: CGFloat((self >> 16) & 0xFF) / 255, green: CGFloat((self >> 8) & 0xFF) / 255, blue: CGFloat(self & 0xFF) / 255, alpha: CGFloat((self >> 24) & 0xFF) / 255)
    }
}
