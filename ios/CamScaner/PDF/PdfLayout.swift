import Foundation

/// Where an image goes on a PDF page, in points with the origin at the bottom-left corner.
struct Placement {
    let index: Int
    let x: Double
    let y: Double
    let width: Double
    let height: Double
}

struct Sheet {
    let width: Double
    let height: Double
    let items: [Placement]
}

/// A page image to lay out: its pixel size and the paper format it was cut to, if known.
struct PageSize {
    let width: Int
    let height: Int
    let paper: PaperPreset?
    var ratio: Double { Double(width) / Double(height) }
}

/// Computes PDF pages for a list of page images.
enum PdfLayout {
    private static let a4W = 595.28
    private static let a4H = 841.89
    private static let cardGap = 36.0
    private static let idCardWidthPt = 242.65
    private static let idCardHeightPt = 153.01

    static func plan(_ pages: [PageSize], paper: PaperSize, orientation: PageOrientation, margin: PageMargin, mergeIdCards: Bool, pagesPerSheet: Int = 1) -> [Sheet] {
        if pagesPerSheet > 1 { return nUp(pages, paper: paper, orientation: orientation, margin: margin, perSheet: pagesPerSheet) }
        var sheets: [Sheet] = []
        var i = 0
        while i < pages.count {
            let page = pages[i]
            if paper == .auto, let card = cardSize(page) {
                if mergeIdCards, i + 1 < pages.count, let next = cardSize(pages[i + 1]) {
                    sheets.append(cardSheet([(i, card), (i + 1, next)]))
                    i += 2
                    continue
                }
                sheets.append(cardSheet([(i, card)]))
                i += 1
                continue
            }
            sheets.append(singleSheet(i, page, paper: paper, orientation: orientation, margin: margin))
            i += 1
        }
        return sheets
    }

    private static func cardSize(_ page: PageSize) -> (Double, Double)? {
        let landscape = page.ratio >= 1
        let short: Double, long: Double
        if let preset = page.paper, preset.isCard {
            short = preset.widthPt
            long = preset.heightPt
        } else if page.paper == nil, PaperKind.match(page.ratio) == .idCard {
            short = idCardHeightPt
            long = idCardWidthPt
        } else {
            return nil
        }
        return landscape ? (long, short) : (short, long)
    }

    private static func singleSheet(_ index: Int, _ page: PageSize, paper: PaperSize, orientation: PageOrientation, margin: PageMargin) -> Sheet {
        let ratio = page.ratio
        let m = margin.points
        var exactFit = false
        var baseW: Double, baseH: Double
        if paper.isFixed {
            (baseW, baseH) = paper.size
        } else if paper == .auto, let preset = page.paper, !preset.isCard {
            exactFit = true
            baseW = preset.widthPt
            baseH = preset.heightPt
        } else if paper == .auto, let kind = PaperKind.match(ratio), let std = standard(kind) {
            exactFit = true
            (baseW, baseH) = std.size
        } else {
            (baseW, baseH) = fitImagePage(ratio, margin: m)
        }
        let landscape: Bool
        switch orientation {
        case .auto: landscape = ratio > 1
        case .portrait: landscape = false
        case .landscape: landscape = true
        }
        let pageW = landscape ? max(baseW, baseH) : min(baseW, baseH)
        let pageH = landscape ? min(baseW, baseH) : max(baseW, baseH)
        let boxW = pageW - 2 * m, boxH = pageH - 2 * m
        let boxRatio = boxW / boxH
        let fill = exactFit && m == 0 && orientation == .auto && abs(ratio / boxRatio - 1) < PaperKind.snapTolerance
        let iw: Double, ih: Double
        if fill {
            iw = boxW; ih = boxH
        } else if ratio > boxRatio {
            iw = boxW; ih = boxW / ratio
        } else {
            iw = boxH * ratio; ih = boxH
        }
        return Sheet(width: pageW, height: pageH, items: [Placement(index: index, x: (pageW - iw) / 2, y: (pageH - ih) / 2, width: iw, height: ih)])
    }

    private static func standard(_ kind: PaperKind) -> PaperSize? {
        switch kind {
        case .aSeries: return .a4
        case .letter: return .letter
        case .legal: return .legal
        case .idCard: return nil
        }
    }

    private static func fitImagePage(_ ratio: Double, margin: Double) -> (Double, Double) {
        let contentLong = a4H - 2 * margin
        let (cw, ch) = ratio >= 1 ? (contentLong, contentLong / ratio) : (contentLong * ratio, contentLong)
        return (cw + 2 * margin, ch + 2 * margin)
    }

    private static func cardSheet(_ cards: [(Int, (Double, Double))]) -> Sheet {
        var items: [Placement] = []
        let totalH = cards.map { $0.1.1 }.reduce(0, +) + cardGap * Double(cards.count - 1)
        var top = (a4H - totalH) / 2
        for (index, size) in cards {
            let (cw, ch) = size
            items.append(Placement(index: index, x: (a4W - cw) / 2, y: a4H - top - ch, width: cw, height: ch))
            top += ch + cardGap
        }
        return Sheet(width: a4W, height: a4H, items: items)
    }

    private static func nUp(_ pages: [PageSize], paper: PaperSize, orientation: PageOrientation, margin: PageMargin, perSheet: Int) -> [Sheet] {
        let base = paper.isFixed ? paper : .a4
        let m = margin.points
        let gap = max(m, 10)
        let landscape: Bool
        switch orientation {
        case .auto: landscape = perSheet == 2
        case .portrait: landscape = false
        case .landscape: landscape = true
        }
        let pageW = landscape ? max(base.size.0, base.size.1) : min(base.size.0, base.size.1)
        let pageH = landscape ? min(base.size.0, base.size.1) : max(base.size.0, base.size.1)
        let (cols, rows): (Int, Int) = perSheet == 2 ? (landscape ? (2, 1) : (1, 2)) : (2, 2)
        let cellW = (pageW - 2 * m - gap * Double(cols - 1)) / Double(cols)
        let cellH = (pageH - 2 * m - gap * Double(rows - 1)) / Double(rows)
        var sheets: [Sheet] = []
        var start = 0
        while start < pages.count {
            let end = min(start + cols * rows, pages.count)
            var items: [Placement] = []
            for (k, index) in (start..<end).enumerated() {
                let ratio = pages[index].ratio
                let col = k % cols, row = k / cols
                let (iw, ih) = ratio > cellW / cellH ? (cellW, cellW / ratio) : (cellH * ratio, cellH)
                let x = m + Double(col) * (cellW + gap) + (cellW - iw) / 2
                let top = m + Double(row) * (cellH + gap) + (cellH - ih) / 2
                items.append(Placement(index: index, x: x, y: pageH - top - ih, width: iw, height: ih))
            }
            sheets.append(Sheet(width: pageW, height: pageH, items: items))
            start = end
        }
        return sheets
    }
}
