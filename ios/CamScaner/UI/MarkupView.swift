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
                Button("Hủy", action: onCancel)
                Spacer()
                Text(mode.title).font(.headline)
                Spacer()
                Button("Xong") { onDone(marks, texts) }.font(.headline)
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
            SignaturePad { signature in place(signature) }
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
        case .text: return selectedText == nil ? "Chạm vào trang để thêm chữ; chạm vào chữ để chọn" : "Kéo để di chuyển; chạm lần nữa để sửa nội dung"
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
        return ZStack {
            Image(uiImage: image).resizable().frame(width: lay.size.width, height: lay.size.height)
            Canvas { ctx, size in
                var all = marks
                if current.count >= 2 { all.append(stroke(current)) }
                for m in all where m.points.count >= 2 {
                    let path = Path(PageProcessor.strokePath(m.points, width: size.width, height: size.height))
                    let lineWidth = max(1, CGFloat(m.width) * sqrt(size.width * size.height))
                    if m.highlighter {
                        var hctx = ctx
                        hctx.blendMode = .multiply
                        hctx.opacity = 110.0 / 255.0
                        hctx.stroke(path, with: .color(Color(m.color.cgColor)), style: StrokeStyle(lineWidth: lineWidth, lineCap: .square, lineJoin: .round))
                    } else {
                        ctx.stroke(path, with: .color(Color(m.color.cgColor)), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
                    }
                }
                if let group = placing {
                    let pts = marks.filter { $0.group == group }.flatMap { m in (0..<(m.points.count / 2)).map { CGPoint(x: m.points[2 * $0] * size.width, y: m.points[2 * $0 + 1] * size.height) } }
                    if let minX = pts.map(\.x).min(), let maxX = pts.map(\.x).max(), let minY = pts.map(\.y).min(), let maxY = pts.map(\.y).max() {
                        let box = CGRect(x: minX - 8, y: minY - 8, width: maxX - minX + 16, height: maxY - minY + 16)
                        ctx.stroke(Path(roundedRect: box, cornerRadius: 6), with: .color(.brand), style: StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
                    }
                }
                ctx.withCGContext { cg in
                    for t in texts { PageProcessor.drawText(t, in: cg, width: size.width, height: size.height) }
                }
                if let i = selectedText, texts.indices.contains(i) {
                    let box = PageProcessor.textBox(texts[i], width: size.width, height: size.height).insetBy(dx: -6, dy: -6)
                    ctx.stroke(Path(roundedRect: box, cornerRadius: 4), with: .color(.brand), style: StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
                }
            }
            .frame(width: lay.size.width, height: lay.size.height)
        }
        .frame(width: available.width, height: available.height)
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { v in dragChanged(v, lay: lay) }
                .onEnded { v in dragEnded(v, lay: lay) }
        )
        .simultaneousGesture(
            MagnificationGesture()
                .onChanged { v in pinch(v, lay: lay) }
                .onEnded { _ in pinchStart = nil }
        )
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
            let dx = Double(v.translation.width / lay.size.width), dy = Double(v.translation.height / lay.size.height)
            marks = start.map { m in
                guard m.group == group else { return m }
                var s = m
                s.points = (0..<(m.points.count / 2)).flatMap { [m.points[2 * $0] + dx, m.points[2 * $0 + 1] + dy] }
                return s
            }
        case .text:
            if dragStartTexts == nil {
                dragStartTexts = texts
                moved = false
                let start = lay.normalized(v.startLocation)
                selectedText = hitText(start, lay: lay)
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
            let tapped = abs(v.translation.width) <= 4 && abs(v.translation.height) <= 4
            if tapped {
                if let i = selectedText, dragStartTexts?.indices.contains(i) == true, !moved {
                    // second tap on the selected text edits it
                    if hitText(lay.normalized(v.startLocation), lay: lay) == i, texts[i] == dragStartTexts?[i] { editingText = i }
                } else if selectedText == nil {
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
        let own = start.filter { $0.group == group }
        let xs = own.flatMap { m in (0..<(m.points.count / 2)).map { m.points[2 * $0] } }
        let ys = own.flatMap { m in (0..<(m.points.count / 2)).map { m.points[2 * $0 + 1] } }
        guard let minX = xs.min(), let maxX = xs.max(), let minY = ys.min(), let maxY = ys.max() else { return }
        let cx = (minX + maxX) / 2, cy = (minY + maxY) / 2
        let k = Double(min(max(v, 0.3), 3))
        marks = start.map { m in
            guard m.group == group else { return m }
            var s = m
            s.points = (0..<(m.points.count / 2)).flatMap { [cx + (m.points[2 * $0] - cx) * k, cy + (m.points[2 * $0 + 1] - cy) * k] }
            s.width = m.width * k
            return s
        }
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
        for stroke in s.strokes where stroke.count >= 4 {
            let pts = (0..<(stroke.count / 2)).flatMap { [cx - boxW / 2 + stroke[2 * $0] * boxW, cy - boxH / 2 + stroke[2 * $0 + 1] * boxH] }
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

/// Small drawing of a saved signature.
struct SignatureThumb: View {
    let signature: SavedSignature

    var body: some View {
        Canvas { ctx, size in
            let aspect = max(signature.aspect, 0.05)
            var w = size.width - 8, h = w / aspect
            if h > size.height - 8 {
                h = size.height - 8
                w = h * aspect
            }
            let ox = (size.width - w) / 2, oy = (size.height - h) / 2
            for s in signature.strokes where s.count >= 4 {
                var path = Path()
                path.move(to: CGPoint(x: ox + s[0] * w, y: oy + s[1] * h))
                for i in 1..<(s.count / 2) { path.addLine(to: CGPoint(x: ox + s[2 * i] * w, y: oy + s[2 * i + 1] * h)) }
                ctx.stroke(path, with: .color(Color(signature.color.cgColor)), style: StrokeStyle(lineWidth: max(1, signature.width * w), lineCap: .round, lineJoin: .round))
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
                    Button("Dùng") { finish() }.disabled(strokes.allSatisfy { $0.isEmpty })
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
        let normalized: [[Double]] = strokes.filter { !$0.isEmpty }.map { s in
            s.flatMap { [Double(($0.x - x0) / w), Double(($0.y - y0) / h)] }
        }
        let signature = SavedSignature(strokes: normalized, aspect: Double(w / h), width: Double(lineWidth / w), color: color)
        if save { signatures.add(signature) }
        dismiss()
        onDone(signature)
    }
}
