import SwiftUI

/// Crop step: the sheet outline with draggable corners and edges, paper formats, rotation, splitting.
struct CropView: View {
    @EnvironmentObject private var session: ScanSession
    @EnvironmentObject private var settings: Settings
    let canAddPages: Bool
    let onBack: () -> Void
    let onNext: () -> Void

    @State private var image: UIImage?
    @State private var loadedFor: URL?
    @State private var handle: Int?
    @State private var dragStart: Quad?
    @State private var fingerAt: CGPoint?
    @State private var detecting = false

    private static let cornerRadius: CGFloat = 13
    private static let grabDistance: CGFloat = 40

    private var index: Int { min(session.currentIndex, max(session.pages.count - 1, 0)) }
    private var page: DraftPage? { session.pages.indices.contains(index) ? session.pages[index] : nil }

    var body: some View {
        VStack(spacing: 0) {
            header
            if let page = page {
                GeometryReader { geo in editor(page, in: geo.size) }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                if !(page.corners ?? .full).isValid() {
                    Text("Vùng chọn không hợp lệ: kéo các góc để tạo hình tứ giác lồi").font(.footnote).foregroundColor(.red).padding(.bottom, 4)
                }
                paperRow(page)
                tools(page)
                Button(action: onNext) {
                    Text("Tiếp").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 12)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!(page.corners ?? .full).isValid())
                .accessibilityIdentifier("crop.next")
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
            } else {
                Spacer()
                Text("Đang nhập…").foregroundColor(.secondary)
                Spacer()
            }
        }
        .background(Color(red: 0.06, green: 0.075, blue: 0.07).ignoresSafeArea())
        .task(id: page?.originalURL) { await load() }
    }

    // MARK: header

    /// Back to the camera adds pages in batch mode; a single shot is taken again.
    private var backTitle: String { canAddPages ? (settings.batchMode ? "Chụp thêm" : "Chụp lại") : "Hủy" }

    private var header: some View {
        HStack {
            Button(action: onBack) {
                Label(backTitle, systemImage: canAddPages ? "camera" : "xmark").labelStyle(.titleAndIcon)
            }
            .accessibilityIdentifier("crop.back")
            Spacer()
            if session.pages.count > 1 {
                HStack(spacing: 10) {
                    Button { session.currentIndex = max(0, index - 1) } label: { Image(systemName: "chevron.left") }.disabled(index == 0)
                    Text("Trang \(index + 1)/\(session.pages.count)").font(.subheadline.bold()).monospacedDigit()
                    Button { session.currentIndex = min(session.pages.count - 1, index + 1) } label: { Image(systemName: "chevron.right") }
                        .disabled(index >= session.pages.count - 1)
                }
            } else {
                Text("Cắt mép").font(.headline)
            }
            Spacer()
            Label(backTitle, systemImage: canAddPages ? "camera" : "xmark").labelStyle(.titleAndIcon).hidden()
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
    }

    // MARK: editor

    private struct Layout {
        let rotation: Int
        let unrotated: CGSize
        let shown: CGSize
        let origin: CGPoint

        func point(_ p: Pt) -> CGPoint { CGPoint(x: origin.x + CGFloat(p.x) * shown.width, y: origin.y + CGFloat(p.y) * shown.height) }

        func normalized(_ p: CGPoint) -> Pt {
            Pt(Double((p.x - origin.x) / shown.width), Double((p.y - origin.y) / shown.height)).clamped
        }
    }

    private func layout(_ page: DraftPage, in size: CGSize) -> Layout {
        let w = CGFloat(max(page.width, 1)), h = CGFloat(max(page.height, 1))
        let turned = page.rotation % 180 != 0
        let sw = turned ? h : w, sh = turned ? w : h
        let scale = min(size.width / sw, size.height / sh)
        let shown = CGSize(width: sw * scale, height: sh * scale)
        return Layout(rotation: page.rotation, unrotated: CGSize(width: w * scale, height: h * scale), shown: shown,
                      origin: CGPoint(x: (size.width - shown.width) / 2, y: (size.height - shown.height) / 2))
    }

    private func editor(_ page: DraftPage, in size: CGSize) -> some View {
        let lay = layout(page, in: size)
        let quad = (page.corners ?? .full).rotated(page.rotation)
        let pts = quad.points.map(lay.point)
        let valid = quad.isValid()
        let mids = (0..<4).map { i in CGPoint(x: (pts[i].x + pts[(i + 1) % 4].x) / 2, y: (pts[i].y + pts[(i + 1) % 4].y) / 2) }
        return ZStack {
            if let image = image {
                Image(uiImage: image).resizable()
                    .frame(width: lay.unrotated.width, height: lay.unrotated.height)
                    .rotationEffect(.degrees(Double(page.rotation)))
                    .position(x: size.width / 2, y: size.height / 2)
            } else {
                ProgressView().tint(.white)
            }
            // dim everything outside the outline
            Path { p in
                p.addRect(CGRect(origin: lay.origin, size: lay.shown))
                p.addLines(pts)
                p.closeSubpath()
            }
            .fill(Color.black.opacity(0.45), style: FillStyle(eoFill: true))
            QuadShape(points: pts).stroke(valid ? Color.brand : Color.red, lineWidth: 2)
            ForEach(0..<4, id: \.self) { i in
                Circle().fill(Color.white.opacity(0.9)).frame(width: 14, height: 14)
                    .overlay(Circle().stroke(valid ? Color.brand : Color.red, lineWidth: 2))
                    .position(mids[i])
                Circle().fill(Color.white).frame(width: CropView.cornerRadius * 2, height: CropView.cornerRadius * 2)
                    .overlay(Circle().stroke(valid ? Color.brand : Color.red, lineWidth: 3))
                    .shadow(radius: 2)
                    .position(pts[i])
            }
            if let h = handle, h < 4, let finger = fingerAt, let image = image {
                loupe(point: quad.points[h], lay: lay, image: image, finger: finger, size: size)
            }
        }
        .frame(width: size.width, height: size.height)
        .contentShape(Rectangle())
        .accessibilityIdentifier("crop.editor")
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { v in drag(v, page: page, lay: lay) }
                .onEnded { _ in
                    handle = nil
                    dragStart = nil
                    fingerAt = nil
                }
        )
    }

    /// Magnified view of the area under the dragged corner, shown away from the finger.
    private func loupe(point: Pt, lay: Layout, image: UIImage, finger: CGPoint, size: CGSize) -> some View {
        let zoom: CGFloat = 2.4
        let diameter: CGFloat = 120
        let shownZ = CGSize(width: lay.shown.width * zoom, height: lay.shown.height * zoom)
        let offset = CGSize(width: shownZ.width * (0.5 - CGFloat(point.x)), height: shownZ.height * (0.5 - CGFloat(point.y)))
        let left = finger.x > size.width / 2
        return ZStack {
            Image(uiImage: image).resizable()
                .frame(width: lay.unrotated.width * zoom, height: lay.unrotated.height * zoom)
                .rotationEffect(.degrees(Double(lay.rotation)))
                .offset(offset)
            Path { p in
                p.move(to: CGPoint(x: diameter / 2 - 14, y: diameter / 2)); p.addLine(to: CGPoint(x: diameter / 2 + 14, y: diameter / 2))
                p.move(to: CGPoint(x: diameter / 2, y: diameter / 2 - 14)); p.addLine(to: CGPoint(x: diameter / 2, y: diameter / 2 + 14))
            }
            .stroke(Color.brand, lineWidth: 1.5)
        }
        .frame(width: diameter, height: diameter)
        .clipShape(Circle())
        .overlay(Circle().stroke(Color.white, lineWidth: 2))
        .shadow(radius: 4)
        .position(x: left ? diameter / 2 + 8 : size.width - diameter / 2 - 8, y: diameter / 2 + 8)
        .allowsHitTesting(false)
    }

    private func drag(_ v: DragGesture.Value, page: DraftPage, lay: Layout) {
        let shown = (page.corners ?? .full).rotated(page.rotation)
        if handle == nil {
            let pts = shown.points.map(lay.point)
            var best: (Int, CGFloat)?
            for (i, p) in pts.enumerated() {
                let d = hypot(p.x - v.startLocation.x, p.y - v.startLocation.y)
                if d < CropView.grabDistance && (best == nil || d < best!.1) { best = (i, d) }
            }
            if best == nil {
                for i in 0..<4 {
                    let m = CGPoint(x: (pts[i].x + pts[(i + 1) % 4].x) / 2, y: (pts[i].y + pts[(i + 1) % 4].y) / 2)
                    let d = hypot(m.x - v.startLocation.x, m.y - v.startLocation.y)
                    if d < CropView.grabDistance && (best == nil || d < best!.1) { best = (4 + i, d) }
                }
            }
            handle = best?.0 ?? -1
            dragStart = shown
        }
        guard let h = handle, h >= 0, let start = dragStart else { return }
        fingerAt = v.location
        var updated = shown
        if h < 4 {
            updated = shown.with(h, lay.normalized(v.location))
        } else {
            let i = h - 4, j = (i + 1) % 4
            let dx = Double(v.translation.width / lay.shown.width), dy = Double(v.translation.height / lay.shown.height)
            updated = start.with(i, (start.points[i] + Pt(dx, dy)).clamped).with(j, (start.points[j] + Pt(dx, dy)).clamped)
        }
        let back = (360 - page.rotation % 360) % 360
        let corners = updated.rotated(back)
        session.update(index) { p in
            var q = p
            q.corners = corners
            return q
        }
    }

    // MARK: paper formats and tools

    private func paperRow(_ page: DraftPage) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                DarkChip(text: "Tự do", selected: page.paper == nil) { session.fitPaper(index, nil) }
                    .accessibilityIdentifier("paper.free")
                ForEach(PaperPreset.allCases) { preset in
                    DarkChip(text: preset.label, selected: page.paper == preset) { session.fitPaper(index, preset) }
                        .accessibilityIdentifier("paper.\(preset.rawValue)")
                }
            }
            .padding(.horizontal, 12)
        }
        .padding(.vertical, 6)
    }

    private func tools(_ page: DraftPage) -> some View {
        HStack {
            tool("Xoay trái", "rotate.left") { session.rotate(index, by: -90) }
            tool("Xoay phải", "rotate.right") { session.rotate(index, by: 90) }
            tool("Toàn bộ", "rectangle.dashed") { fit(page) }
            tool("Tự nhận", "viewfinder") {
                guard !detecting else { return }
                detecting = true
                Task {
                    await session.autoDetect(index)
                    detecting = false
                }
            }
            tool("Chia đôi", "rectangle.split.2x1") { session.split(index) }
            tool("Xóa", "trash") { session.remove(index) }
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 4)
    }

    private func fit(_ page: DraftPage) {
        session.update(index) { p in
            var q = p
            q.corners = .full
            q.paper = nil
            return q
        }
    }

    private func tool(_ title: String, _ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: icon).font(.system(size: 18))
                Text(title).font(.caption2)
            }
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
        }
    }

    private func load() async {
        guard let url = page?.originalURL else {
            image = nil
            loadedFor = nil
            return
        }
        if loadedFor == url, image != nil { return }
        // never show the previous page's photo under this page's outline
        image = nil
        let path = url.path
        let loaded = await Task.detached(priority: .userInitiated) { UIImage(contentsOfFile: path)?.normalized().scaledDown(maxDim: 1800) }.value
        image = loaded
        loadedFor = url
    }
}

/// Chip for the dark scan screens.
struct DarkChip: View {
    let text: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(.subheadline)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(selected ? Color.brand : Color.white.opacity(0.12), in: Capsule())
                .foregroundColor(.white)
        }
        .buttonStyle(.plain)
    }
}
