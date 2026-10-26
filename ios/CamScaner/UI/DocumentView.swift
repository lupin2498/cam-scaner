import SwiftUI

/// One document: its pages in a grid, reordering, selection and the actions on it.
struct DocumentView: View {
    @EnvironmentObject private var repository: DocumentRepository
    @EnvironmentObject private var session: ScanSession
    let docId: String
    @Binding var flow: ScanFlow?

    @State private var selecting = false
    @State private var selection = Set<String>()
    @State private var renaming = false
    @State private var deleting = false
    @State private var details = false
    @State private var moving = false
    @State private var confirmDeletePages = false
    @State private var transfer: Transfer?
    @State private var namingNew: Transfer?
    @State private var showPhotos = false
    @State private var showExport = false
    @State private var openPage: Int?
    @State private var shareURLs: [URL]?
    @State private var busy: String?
    @State private var message: String?
    @State private var editMode = false
    @Environment(\.dismiss) private var dismiss

    private enum Transfer: Identifiable { case move, copy; var id: Int { self == .move ? 0 : 1 } }

    private var doc: ScanDocument? { repository.get(docId) }

    var body: some View {
        Group {
            if let doc = doc, doc.deletedAt == nil {
                content(doc)
            } else {
                Color.clear.onAppear { dismiss() }
            }
        }
    }

    private func content(_ doc: ScanDocument) -> some View {
        ZStack {
            VStack(spacing: 0) {
                if doc.pages.isEmpty {
                    Spacer()
                    Text("Tài liệu chưa có trang nào").foregroundColor(.secondary)
                    Spacer()
                } else {
                    Text(selecting ? "Chạm để chọn trang" : (editMode ? "Kéo để sắp xếp lại trang" : "Chạm vào trang để xem; Sửa để sắp xếp lại"))
                        .font(.footnote).foregroundColor(.secondary).padding(8)
                    if editMode { reorderList(doc) } else { grid(doc) }
                }
                if !selecting { bottomBar(doc) }
            }
            NavigationLink(destination: ExportView(docId: docId), isActive: $showExport) { EmptyView() }.hidden()
            NavigationLink(
                destination: Group { if let i = openPage { PageView(docId: docId, startIndex: i, flow: $flow) } },
                isActive: Binding(get: { openPage != nil }, set: { if !$0 { openPage = nil } })
            ) { EmptyView() }.hidden()
            if let b = busy { ProgressOverlay(message: b) }
        }
        .navigationTitle(selecting ? "Đã chọn \(selection.count) trang" : doc.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbar(doc) }
        .sheet(isPresented: $renaming) {
            TextInputSheet(title: "Đổi tên tài liệu", placeholder: "Tên tài liệu", text: doc.name) { repository.rename(docId, $0) }
        }
        .sheet(isPresented: $details) { DetailsSheet(doc: doc) }
        .sheet(isPresented: $moving) {
            FolderPickerSheet(current: doc.folderId) { repository.moveToFolder([docId], $0); message = "Đã chuyển vào thư mục" }
        }
        .deferredSheet(isPresented: $showPhotos) { then in
            PhotoPicker(limit: 50) { images in
                guard !images.isEmpty else { return }
                then {
                    session.start(docId: docId)
                    session.importImages(images, into: docId)
                    flow = .imported(targetDocId: docId)
                }
            }
        }
        .sheet(item: $transfer) { kind in
            TargetPickerSheet(exclude: docId, title: kind == .move ? "Chuyển sang tài liệu khác" : "Sao chép sang tài liệu khác") { target in
                movePages(to: target, copy: kind == .copy, newName: "")
            } onNew: { namingNew = kind }
        }
        .sheet(item: $namingNew) { kind in
            TextInputSheet(title: "Tạo tài liệu mới từ trang đã chọn", placeholder: "Tên tài liệu", text: doc.name) { name in
                movePages(to: nil, copy: kind == .copy, newName: name)
            }
        }
        .sheet(item: Binding(get: { shareURLs.map(ShareURLs.init) }, set: { shareURLs = $0?.urls })) { ShareSheet(items: $0.urls) }
        .alert("Xóa tài liệu?", isPresented: $deleting) {
            Button("Xóa", role: .destructive) { repository.delete(docId); dismiss() }
            Button("Hủy", role: .cancel) {}
        } message: { Text("\"\(doc.name)\" sẽ được chuyển vào thùng rác.") }
        .alert("Xóa trang đã chọn?", isPresented: $confirmDeletePages) {
            Button("Xóa", role: .destructive) {
                repository.deletePages(docId, selection)
                selection = []
                selecting = false
            }
            Button("Hủy", role: .cancel) {}
        } message: { Text("Xóa \(selection.count) trang đã chọn?") }
        .toast($message)
    }

    private func grid(_ doc: ScanDocument) -> some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 108), spacing: 12)], spacing: 12) {
                ForEach(Array(doc.pages.enumerated()), id: \.element.id) { index, page in
                    ZStack(alignment: .bottomTrailing) {
                        FileImage(url: repository.thumbURL(docId, page.id), version: page.updatedAt)
                            .frame(maxWidth: .infinity)
                            .aspectRatio(0.72, contentMode: .fit)
                            .background(Color.white)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(selection.contains(page.id) ? Color.accentColor : Color.black.opacity(0.08), lineWidth: selection.contains(page.id) ? 3 : 1))
                        Text("\(index + 1)").font(.caption.bold()).foregroundColor(.white).padding(.horizontal, 8).padding(.vertical, 2)
                            .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 6))
                        if selecting {
                            Image(systemName: selection.contains(page.id) ? "checkmark.circle.fill" : "circle")
                                .font(.title2).foregroundColor(.accentColor).padding(6)
                                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                        }
                    }
                    .onTapGesture {
                        if selecting { toggle(page.id) } else { openPage = index }
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("Trang \(index + 1)")
                    .accessibilityAddTraits(.isButton)
                    .accessibilityIdentifier("doc.page.\(index)")
                }
            }
            .padding(12)
        }
    }

    /// Reordering uses a list with drag handles, the reliable way on iOS.
    private func reorderList(_ doc: ScanDocument) -> some View {
        List {
            ForEach(doc.pages) { page in
                HStack {
                    FileImage(url: repository.thumbURL(docId, page.id), version: page.updatedAt)
                        .frame(width: 48, height: 64).clipShape(RoundedRectangle(cornerRadius: 6))
                    Text("Trang \((doc.pages.firstIndex { $0.id == page.id } ?? 0) + 1)")
                }
            }
            .onMove { from, to in
                var ids = doc.pages.map(\.id)
                ids.move(fromOffsets: from, toOffset: to)
                repository.reorder(docId, ids)
            }
            .onDelete { offsets in
                repository.deletePages(docId, Set(offsets.map { doc.pages[$0].id }))
            }
        }
        .environment(\.editMode, .constant(.active))
    }

    private func bottomBar(_ doc: ScanDocument) -> some View {
        HStack {
            Spacer()
            bottomAction("Chụp thêm", "camera", id: "doc.addCamera") {
                session.start(docId: docId)
                flow = .camera(targetDocId: docId)
            }
            Spacer()
            bottomAction("Thêm ảnh", "photo.on.rectangle", id: "doc.addPhotos") { showPhotos = true }
            Spacer()
            bottomAction("Xuất PDF", "doc.richtext", highlighted: true, id: "doc.export") { showExport = true }
            Spacer()
        }
        .padding(.vertical, 8)
        .background(.bar)
    }

    private func bottomAction(_ title: String, _ icon: String, highlighted: Bool = false, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon)
                Text(title).font(.caption)
            }
            .foregroundColor(highlighted ? .accentColor : .primary)
        }
        .accessibilityLabel(title)
        .accessibilityIdentifier(id)
    }

    @ToolbarContentBuilder
    private func toolbar(_ doc: ScanDocument) -> some ToolbarContent {
        ToolbarItem(placement: .navigationBarLeading) {
            if selecting { Button("Bỏ chọn") { selecting = false; selection = [] } }
        }
        ToolbarItemGroup(placement: .navigationBarTrailing) {
            if selecting {
                Button { selection = Set(doc.pages.map(\.id)) } label: { Image(systemName: "checkmark.circle") }
                Menu {
                    Button { rotateSelected(doc) } label: { Label("Xoay trang đã chọn", systemImage: "rotate.right") }
                    Button { shareURLs = doc.pages.filter { selection.contains($0.id) }.map { repository.pageURL(docId, $0.id) } } label: { Label("Chia sẻ ảnh JPG", systemImage: "square.and.arrow.up") }
                    Button { transfer = .move } label: { Label("Chuyển sang tài liệu khác", systemImage: "folder") }
                    Button { transfer = .copy } label: { Label("Sao chép sang tài liệu khác", systemImage: "doc.on.doc") }
                    Button(role: .destructive) { confirmDeletePages = true } label: { Label("Xóa trang đã chọn", systemImage: "trash") }
                } label: { Image(systemName: "ellipsis.circle") }.disabled(selection.isEmpty)
            } else {
                Button(editMode ? "Xong" : "Sửa") { editMode.toggle() }
                Menu {
                    Button { renaming = true } label: { Label("Đổi tên", systemImage: "pencil") }
                    Button { selecting = true; editMode = false } label: { Label("Chọn trang", systemImage: "checkmark.circle") }
                    Button { recognizeAll(doc) } label: { Label("Nhận dạng chữ mọi trang", systemImage: "text.viewfinder") }
                    Button { shareURLs = doc.pages.map { repository.pageURL(docId, $0.id) } } label: { Label("Chia sẻ ảnh JPG", systemImage: "square.and.arrow.up") }
                    Button { moving = true } label: { Label("Chuyển vào thư mục", systemImage: "folder") }
                    Button { _ = repository.duplicate(docId, name: "\(doc.name) (bản sao)"); message = "Đã tạo bản sao" } label: { Label("Tạo bản sao", systemImage: "doc.on.doc") }
                    Button { details = true } label: { Label("Chi tiết", systemImage: "info.circle") }
                    Button(role: .destructive) { deleting = true } label: { Label("Xóa tài liệu", systemImage: "trash") }
                } label: { Image(systemName: "ellipsis.circle") }
            }
        }
    }

    private func toggle(_ id: String) {
        if selection.contains(id) { selection.remove(id) } else { selection.insert(id) }
    }

    private func rotateSelected(_ doc: ScanDocument) {
        let ids = doc.pages.map(\.id).filter { selection.contains($0) }
        busy = "Đang xoay trang…"
        Task {
            for id in ids { await repository.updatePage(docId, id) { $0.turned(by: 90) } }
            busy = nil
        }
    }

    private func movePages(to target: String?, copy: Bool, newName: String) {
        guard let doc = doc else { return }
        let ids = doc.pages.map(\.id).filter { selection.contains($0) }
        selection = []
        selecting = false
        guard !ids.isEmpty else { return }
        repository.movePages(from: docId, pageIds: ids, to: target, copy: copy, newName: newName)
        message = copy ? "Đã sao chép \(ids.count) trang" : "Đã chuyển \(ids.count) trang"
    }

    private func recognizeAll(_ doc: ScanDocument) {
        let pages = doc.pages.sorted { ($0.ocrText == nil ? 0 : 1) < ($1.ocrText == nil ? 0 : 1) }
        Task {
            for (i, page) in pages.enumerated() {
                busy = "Đang nhận dạng chữ trang \(i + 1)/\(pages.count)…"
                if let image = UIImage(contentsOfFile: repository.pageURL(docId, page.id).path) {
                    let text = (try? await OCR.recognize(image.scaledDown(maxDim: 2400)))?.text ?? ""
                    repository.setOcrText(docId, page.id, text)
                }
            }
            busy = nil
            message = "Đã nhận dạng chữ, có thể tìm theo nội dung"
        }
    }
}

struct ShareURLs: Identifiable {
    let urls: [URL]
    var id: String { urls.map(\.path).joined() }
}

/// Lists the other documents as targets for moved or copied pages, or offers a new one.
struct TargetPickerSheet: View {
    @EnvironmentObject private var repository: DocumentRepository
    let exclude: String
    let title: String
    let onPick: (String) -> Void
    let onNew: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            List {
                Button("Tài liệu mới…") { dismiss(); onNew() }
                ForEach(repository.documents.filter { $0.id != exclude }) { d in
                    Button {
                        dismiss()
                        onPick(d.id)
                    } label: {
                        VStack(alignment: .leading) {
                            Text(d.name).foregroundColor(.primary).lineLimit(1)
                            Text("\(d.pages.count) trang").font(.footnote).foregroundColor(.secondary)
                        }
                    }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Hủy") { dismiss() } } }
        }
    }
}
