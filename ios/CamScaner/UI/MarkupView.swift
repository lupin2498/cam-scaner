import SwiftUI

/// What the markup editor opens for.
enum MarkupMode: String, Identifiable {
    case pen, sign, text
    var id: String { rawValue }

    var title: String {
        switch self {
        case .pen: return "Vẽ và tô"
        case .sign: return "Ký tên"
        case .text: return "Thêm chữ"
        }
    }
}

enum Ink: String, CaseIterable, Identifiable {
    case pen, highlighter, eraser
    var id: String { rawValue }
    var label: String {
        switch self {
        case .pen: return "Bút"
        case .highlighter: return "Bút dạ"
        case .eraser: return "Tẩy"
        }
    }
}

private let penColors: [UInt32] = [0xFF111111, 0xFFD32F2F, 0xFF1565C0, 0xFF2E7D32]
private let highlightColors: [UInt32] = [0xFFFFEB3B, 0xFF69F0AE, 0xFFFF80AB, 0xFF40C4FF]
private let penWidths: [Double] = [0.003, 0.006, 0.012]
private let eraserWidth = 0.03
private let highlighterWidth = 0.03
private let textColors: [UInt32] = [0xFF111111, 0xFFD32F2F, 0xFF1565C0, 0xFFFFFFFF]

/// Draws strokes, signatures and text boxes over the finished page.
struct MarkupView: View {
    @EnvironmentObject private var signatures: SignatureStore
    let image: UIImage
    let mode: MarkupMode
    let onDone: ([MarkStroke], [TextMark]) -> Void
    let onCancel: () -> Void

    @State private var marks: [MarkStroke]
    @State private var texts: [TextMark]
    @State private var ink: Ink = .pen
    @State private var color: UInt32 = penColors[0]
    @State private var highlight: UInt32 = highlightColors[0]
    @State private var width: Double = penWidths[1]
    @State private var current: [Double] = []
    @State private var showPad = false
    @State private var placing: Int?
    @State private var pinchStart: [MarkStroke]?
    @State private var selectedText: Int?
    @State private var editingText: Int?
    @State private var pendingPoint: Pt?
    @State private var dragStartTexts: [TextMark]?
    @State private var moved = false
    /// The text selected when the current touch began.
    @State private var selectedBefore: Int?

    init(image: UIImage, initialMarks: [MarkStroke], initialTexts: [TextMark], mode: MarkupMode,
         onDone: @escaping ([MarkStroke], [TextMark]) -> Void, onCancel: @escaping () -> Void) {
        self.image = image
        self.mode = mode
        self.onDone = onDone
        self.onCancel = onCancel
        _marks = State(initialValue: initialMarks)
        _texts = State(initialValue: initialTexts)
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button("Hủy", action: onCancel).accessibilityIdentifier("markup.cancel")
                Spacer()
                Text(mode.title).font(.headline)
                Spacer()
                Button("Xong") { onDone(marks, texts) }.font(.headline).accessibilityIdentifier("markup.done")
            }
            .foregroundColor(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            GeometryReader { geo in canvas(in: geo.size) }
                .padding(12)
            if let hint = hint {
                Text(hint).font(.footnote).foregroundColor(.white.opacity(0.85)).padding(.bottom, 4)
            }
            controls
        }
        .background(Color(red: 0.06, green: 0.075, blue: 0.07).ignoresSafeArea())
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showPad) {
            SignaturePad { signature in place(signature) }.environmentObject(signatures)
        }
        .sheet(isPresented: Binding(get: { editingText != nil }, set: { if !$0 { editingText = nil } })) {
            let existing = editingText.flatMap { $0 >= 0 && texts.indices.contains($0) ? texts[$0].text : nil }
            TextInputSheet(title: existing == nil ? "Thêm chữ" : "Sửa chữ", placeholder: "Nội dung", text: existing ?? "", multiline: true) { text in
                commitText(text)
            }
        }
        .onAppear {
            if mode == .sign && signatures.signatures.isEmpty { showPad = true }
        }
    }

    private var hint: String? {
        switch mode {
        case .pen: return nil
        case .sign: return placing != nil ? "Kéo để di chuyển chữ ký, chụm hai ngón để đổi cỡ" : "Chọn chữ ký đã lưu hoặc thêm chữ ký mới"
        case .text: return selectedText == nil ? "Chạm vào trang để thêm chữ; chạm vào chữ để chọn" : "Kéo để di chuyển; chạm lần nữa để sửa nội dung; chạm chỗ trống để bỏ chọn"
        }
    }

    // MARK: canvas

    private struct Layout {
        let size: CGSize
        let origin: CGPoint
        func normalized(_ p: CGPoint) -> Pt { Pt(Double((p.x - origin.x) / size.width), Double((p.y - origin.y) / size.height)).clamped }
    }

    private func layout(in available: CGSize) -> Layout {
        let px = image.pixelSize
        let scale = min(available.width / px.width, available.height / px.height)
        let size = CGSize(width: px.width * scale, height: px.height * scale)
        return Layout(size: size, origin: CGPoint(x: (available.width - size.width) / 2, y: (available.height - size.height) / 2))
    }

    private func canvas(in available: CGSize) -> some View {
        let lay = layout(in: available)
        let drag = DragGesture(minimumDistance: 0)
            .onChanged { v in dragChanged(v, lay: lay) }
            .onEnded { v in dragEnded(v, lay: lay) }
        let magnify = MagnificationGesture()
            .onChanged { v in pinch(v, lay: lay) }
            .onEnded { _ in pinchStart = nil }
        let live: MarkStroke? = current.count >= 2 ? stroke(current) : nil
        return ZStack {
            Image(uiImage: image).resizable().frame(width: lay.size.width, height: lay.size.height)
            MarkCanvas(marks: marks, live: live, texts: texts, placingGroup: placing, selectedText: selectedText)
                .frame(width: lay.size.width, height: lay.size.height)
        }
        .frame(width: available.width, height: available.height)
        .contentShape(Rectangle())
        .accessibilityIdentifier("markup.canvas")
        .gesture(drag)
        .simultaneousGesture(magnify)
    }

    private func stroke(_ points: [Double]) -> MarkStroke {
        switch ink {
        case .pen: return MarkStroke(points: points, color: color, width: width)
        case .highlighter: return MarkStroke(points: points, color: highlight, width: highlighterWidth, highlighter: true)
        case .eraser: return MarkStroke(points: points, color: 0xFFFFFFFF, width: eraserWidth)
        }
    }

    // MARK: gestures

    private func dragChanged(_ v: DragGesture.Value, lay: Layout) {
        let p = lay.normalized(v.location)
        switch mode {
        case .pen:
            current += [p.x, p.y]
        case .sign:
            guard let group = placing else { return }
            if dragStartTexts == nil {
                dragStartTexts = []
                pinchStart = marks
            }
            guard let start = pinchStart else { return }
            let dx = Double(v.translation.width / lay.size.width)
            let dy = Double(v.translation.height / lay.size.height)
            marks = start.map { m in m.group == group ? MarkupView.translated(m, dx: dx, dy: dy) : m }
        case .text:
            if dragStartTexts == nil {
                dragStartTexts = texts
                moved = false
                selectedBefore = selectedText
                selectedText = hitText(lay.normalized(v.startLocation), lay: lay)
            }
            guard let i = selectedText, let start = dragStartTexts, start.indices.contains(i) else { return }
            if abs(v.translation.width) > 4 || abs(v.translation.height) > 4 { moved = true }
            var t = start[i]
            t.cx = min(max(start[i].cx + Double(v.translation.width / lay.size.width), 0), 1)
            t.cy = min(max(start[i].cy + Double(v.translation.height / lay.size.height), 0), 1)
            texts[i] = t
        }
    }

    private func dragEnded(_ v: DragGesture.Value, lay: Layout) {
        switch mode {
        case .pen:
            if current.count >= 2 {
                var s = stroke(current)
                if current.count == 2 { s.points += current }
                marks.append(s)
            }
            current = []
        case .sign:
            dragStartTexts = nil
            pinchStart = nil
        case .text:
            let tapped = !moved && abs(v.translation.width) <= 4 && abs(v.translation.height) <= 4
            if tapped {
                if let i = selectedText {
                    // the first tap selects a text, a second tap on it edits it
                    if selectedBefore == i { editingText = i }
                } else if selectedBefore == nil {
                    // a tap on the empty page adds a text there; with a text selected it only deselects
                    pendingPoint = lay.normalized(v.location)
                    editingText = -1
                }
            }
            dragStartTexts = nil
        }
    }

    private func pinch(_ v: MagnificationGesture.Value, lay: Layout) {
        guard mode == .sign, let group = placing else { return }
        if pinchStart == nil { pinchStart = marks }
        guard let start = pinchStart else { return }
        guard let box = MarkupView.bounds(of: start.filter { $0.group == group }) else { return }
        let cx = Double(box.midX), cy = Double(box.midY)
        let k = Double(min(max(v, 0.3), 3))
        marks = start.map { m in m.group == group ? MarkupView.scaled(m, cx: cx, cy: cy, k: k) : m }
    }

    private func hitText(_ p: Pt, lay: Layout) -> Int? {
        for (i, t) in texts.enumerated().reversed() {
            let box = PageProcessor.textBox(t, width: lay.size.width, height: lay.size.height).insetBy(dx: -12, dy: -12)
            if box.contains(CGPoint(x: p.x * lay.size.width, y: p.y * lay.size.height)) { return i }
        }
        return nil
    }

    // MARK: text

    private func commitText(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        if let i = editingText, i >= 0, texts.indices.contains(i) {
            texts[i].text = trimmed
        } else {
            let at = pendingPoint ?? Pt(0.5, 0.5)
            texts.append(TextMark(text: trimmed, cx: at.x, cy: at.y, size: 0.03, color: color))
            selectedText = texts.count - 1
        }
        editingText = nil
        pendingPoint = nil
    }

    private func editSelected(_ change: (inout TextMark) -> Void) {
        guard let i = selectedText, texts.indices.contains(i) else { return }
        change(&texts[i])
    }

    // MARK: signatures

    private func place(_ s: SavedSignature) {
        let px = image.pixelSize
        let fraction = 0.35
        let boxW = fraction
        let boxH = fraction * Double(px.width) / max(s.aspect, 0.05) / Double(px.height)
        let cx = 0.5, cy = 0.72
        let group = MarkStroke.nextGroup(marks)
        let strokeWidth = s.width * fraction * Double(px.width) / sqrt(Double(px.width * px.height))
        let left = cx - boxW / 2
        let top = cy - boxH / 2
        for stroke in s.strokes where stroke.count >= 4 {
            var pts: [Double] = []
            pts.reserveCapacity(stroke.count)
            var i = 0
            while i + 1 < stroke.count {
                pts.append(left + stroke[i] * boxW)
                pts.append(top + stroke[i + 1] * boxH)
                i += 2
            }
            marks.append(MarkStroke(points: pts, color: s.color, width: strokeWidth, group: group))
        }
        placing = group
    }

    // MARK: controls

    @ViewBuilder
    private var controls: some View {
        switch mode {
        case .pen: penControls
        case .sign: signControls
        case .text: textControls
        }
    }

    private var penControls: some View {
        VStack(spacing: 8) {
            Picker("Dụng cụ", selection: $ink) {
                ForEach(Ink.allCases) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            HStack(spacing: 14) {
                if ink == .pen {
                    ForEach(penColors, id: \.self) { c in colorDot(c, selected: color == c) { color = c } }
                    Divider().frame(height: 24)
                    ForEach(penWidths, id: \.self) { w in
                        Button { width = w } label: {
                            Circle().fill(Color.white).frame(width: 6 + CGFloat(w) * 1000, height: 6 + CGFloat(w) * 1000)
                                .frame(width: 28, height: 28)
                                .background(Circle().stroke(width == w ? Color.brand : Color.clear, lineWidth: 2))
                        }
                    }
                } else if ink == .highlighter {
                    ForEach(highlightColors, id: \.self) { c in colorDot(c, selected: highlight == c) { highlight = c } }
                } else {
                    Text("Tẩy trắng: kéo trên vùng cần xóa").font(.footnote).foregroundColor(.white.opacity(0.8))
                }
                Spacer()
                Button { marks = MarkStroke.undo(marks) } label: { Image(systemName: "arrow.uturn.backward") }.disabled(marks.isEmpty)
                Button { marks = [] } label: { Image(systemName: "trash") }.disabled(marks.isEmpty)
            }
            .foregroundColor(.white)
            .padding(.horizontal, 16)
        }
        .padding(.vertical, 10)
        .background(Color(red: 0.09, green: 0.11, blue: 0.105))
    }

    private var signControls: some View {
        VStack(spacing: 8) {
            if !signatures.signatures.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(signatures.signatures) { s in
                            SignatureThumb(signature: s)
                                .frame(width: 96, height: 44)
                                .background(Color.white, in: RoundedRectangle(cornerRadius: 8))
                                .onTapGesture { place(s) }
                                .contextMenu { Button("Xóa chữ ký đã lưu", role: .destructive) { signatures.remove(s) } }
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
            HStack(spacing: 16) {
                Button { showPad = true } label: { Label("Chữ ký mới", systemImage: "signature") }
                Spacer()
                if placing != nil {
                    Button("Đặt xong") { placing = nil }
                }
                Button { marks = MarkStroke.undo(marks); if let g = placing, !marks.contains(where: { $0.group == g }) { placing = nil } } label: { Image(systemName: "arrow.uturn.backward") }
                    .disabled(marks.isEmpty)
            }
            .foregroundColor(.white)
            .padding(.horizontal, 16)
        }
        .padding(.vertical, 10)
        .background(Color(red: 0.09, green: 0.11, blue: 0.105))
    }

    private var textControls: some View {
        HStack(spacing: 14) {
            Button { pendingPoint = Pt(0.5, 0.5); selectedText = nil; editingText = -1 } label: { Label("Thêm", systemImage: "plus") }
                .accessibilityIdentifier("markup.add")
            if let i = selectedText, texts.indices.contains(i) {
                Divider().frame(height: 24)
                Button { editSelected { $0.size = max(0.012, $0.size / 1.2) } } label: { Image(systemName: "textformat.size.smaller") }
                Button { editSelected { $0.size = min(0.15, $0.size * 1.2) } } label: { Image(systemName: "textformat.size.larger") }
                Button { editSelected { $0.angle = ($0.angle + 90) % 360 } } label: { Image(systemName: "rotate.right") }
                ForEach(textColors, id: \.self) { c in colorDot(c, selected: texts[i].color == c) { editSelected { $0.color = c } } }
                Spacer()
                Button { texts.remove(at: i); selectedText = nil } label: { Image(systemName: "trash") }
            } else {
                Spacer()
            }
        }
        .foregroundColor(.white)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Color(red: 0.09, green: 0.11, blue: 0.105))
    }

    private func colorDot(_ c: UInt32, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Circle().fill(Color(c.cgColor)).frame(width: 24, height: 24)
                .overlay(Circle().stroke(Color.white.opacity(0.6), lineWidth: 1))
                .background(Circle().stroke(selected ? Color.brand : Color.clear, lineWidth: 3).frame(width: 30, height: 30))
        }
    }
}


extension MarkupView {
    /// The stroke moved by a normalized offset.
    static func translated(_ m: MarkStroke, dx: Double, dy: Double) -> MarkStroke {
        var s = m
        var pts: [Double] = []
        pts.reserveCapacity(m.points.count)
        var i = 0
        while i + 1 < m.points.count {
            pts.append(m.points[i] + dx)
            pts.append(m.points[i + 1] + dy)
            i += 2
        }
        s.points = pts
        return s
    }

    /// The stroke scaled by `k` about a normalized centre; the line width follows.
    static func scaled(_ m: MarkStroke, cx: Double, cy: Double, k: Double) -> MarkStroke {
        var s = m
        var pts: [Double] = []
        pts.reserveCapacity(m.points.count)
        var i = 0
        while i + 1 < m.points.count {
            pts.append(cx + (m.points[i] - cx) * k)
            pts.append(cy + (m.points[i + 1] - cy) * k)
            i += 2
        }
        s.points = pts
        s.width = m.width * k
        return s
    }

    /// Normalized bounding box of the given strokes.
    static func bounds(of strokes: [MarkStroke]) -> CGRect? {
        var minX = Double.infinity, minY = Double.infinity, maxX = -Double.infinity, maxY = -Double.infinity
        for m in strokes {
            var i = 0
            while i + 1 < m.points.count {
                minX = min(minX, m.points[i]); maxX = max(maxX, m.points[i])
                minY = min(minY, m.points[i + 1]); maxY = max(maxY, m.points[i + 1])
                i += 2
            }
        }
        guard minX <= maxX, minY <= maxY else { return nil }
        return CGRect(x: minX, y: minY, width: maxX - minX, height: maxY - minY)
    }
}

/// Strokes, the stroke being drawn, text boxes and the selection outlines over the page.
struct MarkCanvas: View {
    let marks: [MarkStroke]
    let live: MarkStroke?
    let texts: [TextMark]
    let placingGroup: Int?
    let selectedText: Int?

    var body: some View {
        Canvas { ctx, size in
            for m in marks { MarkCanvas.draw(m, in: ctx, size: size) }
            if let l = live { MarkCanvas.draw(l, in: ctx, size: size) }
            if let group = placingGroup, let box = MarkupView.bounds(of: marks.filter { $0.group == group }) {
                let rect = CGRect(x: box.minX * size.width - 8, y: box.minY * size.height - 8,
                                  width: box.width * size.width + 16, height: box.height * size.height + 16)
                MarkCanvas.dashed(rect, in: ctx)
            }
            ctx.withCGContext { cg in
                for t in texts { PageProcessor.drawText(t, in: cg, width: size.width, height: size.height) }
            }
            if let i = selectedText, texts.indices.contains(i) {
                let rect = PageProcessor.textBox(texts[i], width: size.width, height: size.height).insetBy(dx: -6, dy: -6)
                MarkCanvas.dashed(rect, in: ctx)
            }
        }
    }

    private static func draw(_ m: MarkStroke, in ctx: GraphicsContext, size: CGSize) {
        guard m.points.count >= 2 else { return }
        let path = Path(PageProcessor.strokePath(m.points, width: size.width, height: size.height))
        let unit: CGFloat = sqrt(size.width * size.height)
        let lineWidth: CGFloat = max(1, CGFloat(m.width) * unit)
        let color = Color(m.color.cgColor)
        if m.highlighter {
            var hctx = ctx
            hctx.blendMode = .multiply
            hctx.opacity = 110.0 / 255.0
            hctx.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: lineWidth, lineCap: .square, lineJoin: .round))
        } else {
            ctx.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
        }
    }

    private static func dashed(_ rect: CGRect, in ctx: GraphicsContext) {
        ctx.stroke(Path(roundedRect: rect, cornerRadius: 6), with: .color(Color.brand), style: StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
    }
}

/// Small drawing of a saved signature.
struct SignatureThumb: View {
    let signature: SavedSignature

    var body: some View {
        Canvas { ctx, size in
            let aspect = CGFloat(max(signature.aspect, 0.05))
            var w: CGFloat = size.width - 8
            var h: CGFloat = w / aspect
            if h > size.height - 8 {
                h = size.height - 8
                w = h * aspect
            }
            let ox: CGFloat = (size.width - w) / 2
            let oy: CGFloat = (size.height - h) / 2
            let lineWidth: CGFloat = max(1, CGFloat(signature.width) * w)
            let color = Color(signature.color.cgColor)
            for s in signature.strokes where s.count >= 4 {
                var path = Path()
                var i = 0
                while i + 1 < s.count {
                    let p = CGPoint(x: ox + CGFloat(s[i]) * w, y: oy + CGFloat(s[i + 1]) * h)
                    if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
                    i += 2
                }
                ctx.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
            }
        }
    }
}

/// White pad to draw a signature on; the result is normalized to its bounding box.
struct SignaturePad: View {
    @EnvironmentObject private var signatures: SignatureStore
    let onDone: (SavedSignature) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var strokes: [[CGPoint]] = []
    @State private var current: [CGPoint] = []
    @State private var color: UInt32 = 0xFF1A237E
    @State private var save = true

    private let lineWidth: CGFloat = 3

    var body: some View {
        NavigationView {
            VStack(spacing: 12) {
                Text("Ký vào khung bên dưới").font(.footnote).foregroundColor(.secondary)
                GeometryReader { geo in
                    ZStack {
                        Color.white
                        Canvas { ctx, _ in
                            for s in strokes + (current.isEmpty ? [] : [current]) where !s.isEmpty {
                                var path = Path()
                                path.move(to: s[0])
                                for p in s.dropFirst() { path.addLine(to: p) }
                                if s.count == 1 { path.addLine(to: CGPoint(x: s[0].x + 0.5, y: s[0].y)) }
                                ctx.stroke(path, with: .color(Color(color.cgColor)), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
                            }
                        }
                        Path { p in
                            p.move(to: CGPoint(x: 24, y: geo.size.height * 0.75))
                            p.addLine(to: CGPoint(x: geo.size.width - 24, y: geo.size.height * 0.75))
                        }
                        .stroke(Color.gray.opacity(0.4), style: StrokeStyle(lineWidth: 1, dash: [6, 4]))
                    }
                    .contentShape(Rectangle())
                    .accessibilityIdentifier("signature.pad")
                    .gesture(
                        DragGesture(minimumDistance: 0)
                            .onChanged { v in current.append(v.location) }
                            .onEnded { _ in
                                strokes.append(current)
                                current = []
                            }
                    )
                }
                .frame(height: 220)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.gray.opacity(0.4)))
                .padding(.horizontal, 16)
                HStack(spacing: 16) {
                    ForEach([UInt32(0xFF1A237E), 0xFF111111, 0xFFC62828], id: \.self) { c in
                        Button { color = c } label: {
                            Circle().fill(Color(c.cgColor)).frame(width: 26, height: 26)
                                .overlay(Circle().stroke(color == c ? Color.accentColor : Color.clear, lineWidth: 3).frame(width: 32, height: 32))
                        }
                    }
                    Spacer()
                    Button { if !strokes.isEmpty { strokes.removeLast() } } label: { Image(systemName: "arrow.uturn.backward") }.disabled(strokes.isEmpty)
                    Button { strokes = [] } label: { Image(systemName: "trash") }.disabled(strokes.isEmpty)
                }
                .padding(.horizontal, 20)
                Toggle("Lưu chữ ký để dùng lại", isOn: $save).padding(.horizontal, 20)
                Spacer()
            }
            .padding(.top, 12)
            .navigationTitle("Chữ ký")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Hủy") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Dùng") { finish() }.disabled(strokes.allSatisfy { $0.isEmpty }).accessibilityIdentifier("signature.use")
                }
            }
        }
    }

    private func finish() {
        let pts = strokes.flatMap { $0 }
        guard let minX = pts.map(\.x).min(), let maxX = pts.map(\.x).max(), let minY = pts.map(\.y).min(), let maxY = pts.map(\.y).max() else { return }
        let pad = lineWidth
        let x0 = minX - pad, y0 = minY - pad
        let w = max(maxX - minX + 2 * pad, 1), h = max(maxY - minY + 2 * pad, 1)
        var normalized: [[Double]] = []
        for s in strokes where !s.isEmpty {
            var flat: [Double] = []
            flat.reserveCapacity(s.count * 2)
            for p in s {
                flat.append(Double((p.x - x0) / w))
                flat.append(Double((p.y - y0) / h))
            }
            normalized.append(flat)
        }
        let signature = SavedSignature(strokes: normalized, aspect: Double(w / h), width: Double(lineWidth / w), color: color)
        if save { signatures.add(signature) }
        dismiss()
        onDone(signature)
    }
}
