import SwiftUI

/// Renders the preview of a page being edited, caching the perspective-corrected base image.
@MainActor
final class PreviewRenderer: ObservableObject {
    @Published var image: UIImage?
    @Published var thumbs: [ScanFilter: UIImage] = [:]
    @Published var rendering = false

    private struct WarpKey: Equatable {
        let url: URL
        let corners: Quad?
        let paper: PaperPreset?
    }

    private var warpKey: WarpKey?
    private var warped: UIImage?
    private var generation = 0

    /// Re-renders the preview shortly after the last change.
    func render(_ page: DraftPage) {
        generation += 1
        let gen = generation
        let key = WarpKey(url: page.originalURL, corners: page.corners, paper: page.paper)
        let cached = warpKey == key ? warped : nil
        rendering = true
        Task {
            try? await Task.sleep(nanoseconds: 50_000_000)
            guard gen == generation else { return }
            let result: (UIImage, UIImage, [ScanFilter: UIImage])? = await Task.detached(priority: .userInitiated) { () -> (UIImage, UIImage, [ScanFilter: UIImage])? in
                let base: UIImage
                if let c = cached {
                    base = c
                } else {
                    guard let original = UIImage(contentsOfFile: page.originalURL.path) else { return nil }
                    base = PageProcessor.warp(original, corners: page.corners, maxDim: PageProcessor.previewSize, paper: page.paper)
                }
                let straight = PageProcessor.straighten(base, degrees: page.skew)
                let enhanced = PageProcessor.enhance(straight, filter: page.filter, adjustments: page.adjustments)
                let turned = PageProcessor.rotate(enhanced, degrees: page.rotation)
                let finished = PageProcessor.drawMarks(turned, marks: page.marks, texts: page.texts)
                var strip: [ScanFilter: UIImage] = [:]
                if cached == nil {
                    let small = base.scaledDown(maxDim: 240)
                    for f in ScanFilter.allCases { strip[f] = PageProcessor.enhance(small, filter: f) }
                }
                return (base, finished, strip)
            }.value
            guard gen == generation else { return }
            rendering = false
            guard let r = result else { return }
            if cached == nil {
                warpKey = key
                warped = r.0
                thumbs = r.2
            }
            image = r.1
        }
    }

    /// The page as it looks without strokes and texts, for the markup editor.
    func plain(_ page: DraftPage) async -> UIImage? {
        let key = WarpKey(url: page.originalURL, corners: page.corners, paper: page.paper)
        let cached = warpKey == key ? warped : nil
        return await Task.detached(priority: .userInitiated) { () -> UIImage? in
            let base: UIImage
            if let c = cached {
                base = c
            } else {
                guard let original = UIImage(contentsOfFile: page.originalURL.path) else { return nil }
                base = PageProcessor.warp(original, corners: page.corners, maxDim: PageProcessor.previewSize, paper: page.paper)
            }
            let straight = PageProcessor.straighten(base, degrees: page.skew)
            let enhanced = PageProcessor.enhance(straight, filter: page.filter, adjustments: page.adjustments)
            return PageProcessor.rotate(enhanced, degrees: page.rotation)
        }.value
    }

    func reset() {
        generation += 1
        warpKey = nil
        warped = nil
        thumbs = [:]
        image = nil
        rendering = false
    }
}

/// Filter step: filters, fine tuning, straightening, rotation, markup, name and save.
struct FilterView: View {
    @EnvironmentObject private var session: ScanSession
    @EnvironmentObject private var settings: Settings
    @EnvironmentObject private var signatures: SignatureStore
    @StateObject private var renderer = PreviewRenderer()
    let onBack: () -> Void
    let onSave: () -> Void

    private enum Panel { case filters, adjust, straighten }
    @State private var panel: Panel = .filters
    @State private var markup: MarkupMode?
    @State private var markupImage: UIImage?
    @State private var renaming = false
    @State private var preparing = false
    @State private var shownPageId: String?

    private var index: Int { min(session.currentIndex, max(session.pages.count - 1, 0)) }
    private var page: DraftPage? { session.pages.indices.contains(index) ? session.pages[index] : nil }

    var body: some View {
        VStack(spacing: 0) {
            header
            if session.pages.count > 1 { pager }
            ZStack {
                if let image = renderer.image {
                    Image(uiImage: image).resizable().aspectRatio(contentMode: .fit)
                        .shadow(radius: 6)
                        .padding(12)
                } else {
                    ProgressView().tint(.white)
                }
                if renderer.rendering && renderer.image != nil {
                    ProgressView().tint(.white).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing).padding(20)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            if let page = page {
                panelView(page)
                toolbar(page)
            }
        }
        .background(Color(red: 0.06, green: 0.075, blue: 0.07).ignoresSafeArea())
        .onAppear {
            if let p = page {
                shownPageId = p.id
                renderer.render(p)
            }
        }
        .onChange(of: page) { p in
            guard let p = p else { return }
            if p.id != shownPageId {
                shownPageId = p.id
                renderer.reset()
            }
            renderer.render(p)
        }
        .sheet(isPresented: $renaming) {
            TextInputSheet(title: "Tên tài liệu", placeholder: "Tên tài liệu", text: session.documentName) { session.customName = $0 }
        }
        .fullScreenCover(item: $markup) { mode in
            if let image = markupImage, let p = page {
                MarkupView(image: image, initialMarks: p.marks, initialTexts: p.texts, mode: mode) { marks, texts in
                    session.update(index) { d in
                        var q = d
                        q.marks = marks
                        q.texts = texts
                        return q
                    }
                    markup = nil
                } onCancel: { markup = nil }
                .environmentObject(signatures)
            }
        }
        .overlay { if preparing { ProgressOverlay(message: "Đang chuẩn bị…") } }
    }

    // MARK: header and pager

    private var header: some View {
        HStack(spacing: 12) {
            Button(action: onBack) { Image(systemName: "chevron.left").font(.title3) }
            Button { renaming = true } label: {
                HStack(spacing: 4) {
                    Text(session.documentName).font(.subheadline.bold()).lineLimit(1)
                    Image(systemName: "pencil").font(.caption)
                }
                .foregroundColor(.white)
            }
            .frame(maxWidth: .infinity)
            Button(action: onSave) {
                Text("Lưu").font(.headline).padding(.horizontal, 18).padding(.vertical, 8)
                    .background(Color.brand, in: Capsule()).foregroundColor(.white)
            }
            .disabled(session.saving || session.pages.isEmpty)
            .accessibilityIdentifier("filter.save")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    private var pager: some View {
        HStack(spacing: 14) {
            Button { session.currentIndex = max(0, index - 1) } label: { Image(systemName: "chevron.left") }.disabled(index == 0)
            Text("Trang \(index + 1)/\(session.pages.count)").font(.subheadline).monospacedDigit()
            Button { session.currentIndex = min(session.pages.count - 1, index + 1) } label: { Image(systemName: "chevron.right") }
                .disabled(index >= session.pages.count - 1)
            Spacer()
            Button("Áp dụng cho mọi trang") { applyToAll() }.font(.subheadline)
        }
        .foregroundColor(.white)
        .padding(.horizontal, 16)
        .padding(.bottom, 4)
    }

    // MARK: panels

    @ViewBuilder
    private func panelView(_ page: DraftPage) -> some View {
        switch panel {
        case .filters: filterStrip(page)
        case .adjust: adjustPanel(page)
        case .straighten: straightenPanel(page)
        }
    }

    private func filterStrip(_ page: DraftPage) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(ScanFilter.allCases) { f in
                    Button { set(page) { $0.filter = f } } label: {
                        VStack(spacing: 4) {
                            Group {
                                if let t = renderer.thumbs[f] {
                                    Image(uiImage: t).resizable().aspectRatio(contentMode: .fill)
                                } else {
                                    Color.white.opacity(0.1)
                                }
                            }
                            .frame(width: 64, height: 80)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(page.filter == f ? Color.brand : Color.clear, lineWidth: 3))
                            Text(f.label).font(.caption2).foregroundColor(page.filter == f ? .brand : .white).lineLimit(1)
                        }
                        .frame(width: 72)
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(f.label)
                    .accessibilityAddTraits(page.filter == f ? AccessibilityTraits([.isButton, .isSelected]) : AccessibilityTraits.isButton)
                    .accessibilityIdentifier("filter.\(f.rawValue)")
                }
            }
            .padding(.horizontal, 12)
        }
        .padding(.vertical, 8)
    }

    private func adjustPanel(_ page: DraftPage) -> some View {
        VStack(spacing: 6) {
            slider("Độ sáng", value: page.adjustments.brightness, range: -1...1) { v in set(page) { $0.adjustments.brightness = v } }
            slider("Tương phản", value: page.adjustments.contrast, range: -1...1) { v in set(page) { $0.adjustments.contrast = v } }
            slider("Độ nét", value: page.adjustments.sharpness, range: 0...1) { v in set(page) { $0.adjustments.sharpness = v } }
            slider("Độ mạnh bộ lọc", value: page.adjustments.strength, range: 0...1) { v in set(page) { $0.adjustments.strength = v } }
            Button("Đặt lại") { set(page) { $0.adjustments = Adjustments() } }.font(.subheadline).foregroundColor(.white)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private func straightenPanel(_ page: DraftPage) -> some View {
        VStack(spacing: 6) {
            HStack {
                Text("Làm thẳng").font(.subheadline).foregroundColor(.white)
                Spacer()
                Text(String(format: "%.1f°", page.skew)).font(.subheadline).monospacedDigit().foregroundColor(.white)
                Button("0°") { set(page) { $0.skew = 0 } }.font(.subheadline).foregroundColor(.brand)
            }
            Slider(value: Binding(get: { page.skew }, set: { v in set(page) { $0.skew = (v * 10).rounded() / 10 } }), in: -10...10)
                .tint(.brand)
            HStack(spacing: 24) {
                Button { session.rotate(index, by: -90) } label: { Label("Xoay trái", systemImage: "rotate.left") }
                Button { session.rotate(index, by: 90) } label: { Label("Xoay phải", systemImage: "rotate.right") }
            }
            .font(.subheadline).foregroundColor(.white)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private func slider(_ title: String, value: Double, range: ClosedRange<Double>, onChange: @escaping (Double) -> Void) -> some View {
        HStack(spacing: 10) {
            Text(title).font(.caption).foregroundColor(.white).frame(width: 96, alignment: .leading)
            Slider(value: Binding(get: { value }, set: onChange), in: range).tint(.brand)
        }
    }

    // MARK: toolbar

    private func toolbar(_ page: DraftPage) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                tool("Bộ lọc", "wand.and.stars", on: panel == .filters, id: "filter.panel.filters") { panel = .filters }
                tool("Tinh chỉnh", "slider.horizontal.3", on: panel == .adjust, id: "filter.panel.adjust") { panel = .adjust }
                tool("Làm thẳng", "level", on: panel == .straighten, id: "filter.panel.straighten") { panel = .straighten }
                tool("Xoay", "rotate.right", on: false, id: "filter.rotate") { session.rotate(index, by: 90) }
                tool("Vẽ", "pencil.tip", on: false, id: "filter.pen") { openMarkup(.pen, page) }
                tool("Ký tên", "signature", on: false, id: "filter.sign") { openMarkup(.sign, page) }
                tool("Thêm chữ", "textformat", on: false, id: "filter.text") { openMarkup(.text, page) }
                tool("Xóa trang", "trash", on: false, id: "filter.delete") { session.remove(index) }
            }
            .padding(.horizontal, 8)
        }
        .padding(.vertical, 8)
        .background(Color(red: 0.09, green: 0.11, blue: 0.105))
    }

    private func tool(_ title: String, _ icon: String, on: Bool, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: icon).font(.system(size: 18))
                Text(title).font(.caption2)
            }
            .foregroundColor(on ? .brand : .white)
            .frame(width: 72)
        }
        .accessibilityLabel(title)
        .accessibilityIdentifier(id)
    }

    // MARK: actions

    private func set(_ page: DraftPage, _ change: (inout DraftPage) -> Void) {
        guard let i = session.pages.firstIndex(where: { $0.id == page.id }) else { return }
        session.update(i) { p in
            var q = p
            change(&q)
            return q
        }
    }

    private func applyToAll() {
        guard let page = page else { return }
        for i in session.pages.indices {
            session.update(i) { p in
                var q = p
                q.filter = page.filter
                q.adjustments = page.adjustments
                return q
            }
        }
    }

    private func openMarkup(_ mode: MarkupMode, _ page: DraftPage) {
        guard !preparing else { return }
        preparing = true
        Task {
            let image = await renderer.plain(page)
            preparing = false
            guard let image = image else { return }
            markupImage = image
            markup = mode
        }
    }
}
