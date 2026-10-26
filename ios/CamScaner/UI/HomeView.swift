import SwiftUI
import UniformTypeIdentifiers

struct HomeView: View {
    @EnvironmentObject private var repository: DocumentRepository
    @EnvironmentObject private var settings: Settings
    @EnvironmentObject private var session: ScanSession
    @Binding var flow: ScanFlow?
    @Binding var openDocId: String?

    @State private var query = ""
    @State private var folderId: String?
    @State private var selection = Set<String>()
    @State private var selecting = false
    @State private var showPhotos = false
    @State private var showFiles = false
    @State private var fileTypes: [UTType] = [.pdf]
    @State private var newFolder = false
    @State private var renamingFolder: Folder?
    @State private var deletingFolder: Folder?
    @State private var renaming: ScanDocument?
    @State private var deleting: ScanDocument?
    @State private var details: ScanDocument?
    @State private var movingIds: [String]?
    @State private var confirmMerge = false
    @State private var confirmDeleteSelected = false
    @State private var showTrash = false
    @State private var showSettings = false
    @State private var shareURL: URL?
    @State private var busy: String?
    @State private var message: String?

    private var filtered: [ScanDocument] {
        let q = query.trimmingCharacters(in: .whitespaces)
        let inFolder = folderId == nil ? repository.documents : repository.documents.filter { $0.folderId == folderId }
        return settings.sortOrder.sort(q.isEmpty ? inFolder : inFolder.filter { $0.matches(q) })
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            VStack(spacing: 0) {
                if !repository.documents.isEmpty || !repository.folders.isEmpty { folderBar }
                if repository.documents.isEmpty {
                    emptyState
                } else {
                    list
                }
            }
            if !selecting { fabs }
            NavigationLink(destination: TrashView(), isActive: $showTrash) { EmptyView() }.hidden()
            NavigationLink(destination: SettingsView(), isActive: $showSettings) { EmptyView() }.hidden()
            NavigationLink(
                destination: Group { if let id = openDocId { DocumentView(docId: id, flow: $flow) } },
                isActive: Binding(get: { openDocId != nil }, set: { if !$0 { openDocId = nil } })
            ) { EmptyView() }.hidden()
            if let b = busy { ProgressOverlay(message: b) }
        }
        .navigationTitle(selecting ? "Đã chọn \(selection.count)" : "Cam Scaner")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, prompt: "Tìm tài liệu (cả nội dung đã nhận dạng)")
        .toolbar { toolbar }
        .sheet(isPresented: $showPhotos) {
            PhotoPicker(limit: 50) { images in
                guard !images.isEmpty else { return }
                session.start(docId: nil)
                session.importImages(images, into: nil)
                flow = .imported(targetDocId: nil)
            }
        }
        .sheet(isPresented: $showFiles) {
            FilePicker(types: fileTypes) { urls in
                guard !urls.isEmpty else { return }
                session.start(docId: nil)
                session.importFiles(urls, into: nil)
                flow = .imported(targetDocId: nil)
            }
        }
        .sheet(isPresented: $newFolder) {
            TextInputSheet(title: "Thư mục mới", placeholder: "Tên thư mục", text: "") { name in folderId = repository.createFolder(name) }
        }
        .sheet(item: $renamingFolder) { f in
            TextInputSheet(title: "Đổi tên thư mục", placeholder: "Tên thư mục", text: f.name) { repository.renameFolder(f.id, $0) }
        }
        .sheet(item: $renaming) { doc in
            TextInputSheet(title: "Đổi tên tài liệu", placeholder: "Tên tài liệu", text: doc.name) { repository.rename(doc.id, $0) }
        }
        .sheet(item: $details) { doc in DetailsSheet(doc: doc) }
        .sheet(isPresented: Binding(get: { movingIds != nil }, set: { if !$0 { movingIds = nil } })) {
            FolderPickerSheet(current: movingIds?.count == 1 ? repository.get(movingIds![0])?.folderId : nil) { folder in
                if let ids = movingIds { repository.moveToFolder(ids, folder) }
                selection = []
                selecting = false
                message = "Đã chuyển vào thư mục"
            }
        }
        .sheet(item: Binding(get: { shareURL.map(ShareItem.init) }, set: { shareURL = $0?.url })) { item in ShareSheet(items: [item.url]) }
        .alert("Xóa tài liệu?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), presenting: deleting) { doc in
            Button("Xóa", role: .destructive) { repository.delete(doc.id); message = "Đã chuyển vào thùng rác" }
            Button("Hủy", role: .cancel) {}
        } message: { doc in Text("\"\(doc.name)\" sẽ được chuyển vào thùng rác.") }
        .alert("Xóa thư mục?", isPresented: Binding(get: { deletingFolder != nil }, set: { if !$0 { deletingFolder = nil } }), presenting: deletingFolder) { f in
            Button("Xóa", role: .destructive) { if folderId == f.id { folderId = nil }; repository.deleteFolder(f.id) }
            Button("Hủy", role: .cancel) {}
        } message: { f in Text("Các tài liệu trong \"\(f.name)\" được giữ lại ở ngoài thư mục.") }
        .alert("Gộp tài liệu", isPresented: $confirmMerge) {
            Button("Gộp") { mergeSelected() }
            Button("Hủy", role: .cancel) {}
        } message: { Text("Gộp \(selection.count) tài liệu đã chọn thành một? Các trang được nối vào tài liệu đầu tiên theo thứ tự hiển thị.") }
        .alert("Xóa tài liệu?", isPresented: $confirmDeleteSelected) {
            Button("Xóa", role: .destructive) {
                for id in selection { repository.delete(id) }
                message = "Đã chuyển \(selection.count) tài liệu vào thùng rác"
                selection = []
                selecting = false
            }
            Button("Hủy", role: .cancel) {}
        } message: { Text("\(selection.count) tài liệu sẽ được chuyển vào thùng rác.") }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK") {} }
    }

    private var folderBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Chip(text: "Tất cả", selected: folderId == nil) { folderId = nil }
                ForEach(repository.folders) { f in
                    Chip(text: f.name, selected: folderId == f.id) { folderId = f.id }
                        .contextMenu {
                            Button("Đổi tên thư mục") { renamingFolder = f }
                            Button("Xóa thư mục", role: .destructive) { deletingFolder = f }
                        }
                }
                Chip(text: "+ Thư mục mới", selected: false) { newFolder = true }
            }
            .padding(.horizontal)
            .padding(.vertical, 6)
        }
    }

    private var list: some View {
        List {
            ForEach(filtered) { doc in
                DocumentRow(doc: doc, folderName: folderId == nil ? repository.folders.first { $0.id == doc.folderId }?.name : nil,
                            selecting: selecting, selected: selection.contains(doc.id))
                    .contentShape(Rectangle())
                    .onTapGesture {
                        if selecting { toggle(doc.id) } else { openDocId = doc.id }
                    }
                    .onLongPressGesture { selecting = true; toggle(doc.id) }
                    .contextMenu {
                        Button("Xuất PDF nhanh") { quickShare(doc) }
                        Button("Đổi tên") { renaming = doc }
                        Button("Chuyển vào thư mục") { movingIds = [doc.id] }
                        Button("Tạo bản sao") { _ = repository.duplicate(doc.id, name: "\(doc.name) (bản sao)") }
                        Button("Chi tiết") { details = doc }
                        Button("Xóa", role: .destructive) { deleting = doc }
                    }
            }
        }
        .listStyle(.plain)
    }

    private var emptyState: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "doc.text.viewfinder").font(.system(size: 64)).foregroundColor(.accentColor)
            Text("Chưa có tài liệu nào").font(.title3.bold())
            Text("Nhấn nút máy ảnh để quét tài liệu đầu tiên hoặc nhập ảnh, PDF.").multilineTextAlignment(.center).foregroundColor(.secondary)
            Spacer()
        }
        .padding(32)
        .frame(maxWidth: .infinity)
    }

    private var fabs: some View {
        VStack(alignment: .trailing, spacing: 12) {
            Menu {
                Button { showPhotos = true } label: { Label("Nhập ảnh", systemImage: "photo.on.rectangle") }
                Button { fileTypes = [.pdf]; showFiles = true } label: { Label("Nhập PDF", systemImage: "doc.richtext") }
                Button { fileTypes = [.image, .pdf]; showFiles = true } label: { Label("Nhập tệp (ảnh, PDF)", systemImage: "folder") }
            } label: {
                Image(systemName: "square.and.arrow.down").font(.title3).padding(14)
                    .background(Color(UIColor.secondarySystemBackground), in: Circle()).shadow(radius: 3)
            }
            Button {
                session.start(docId: nil)
                flow = .camera(targetDocId: nil)
            } label: {
                Label("Quét", systemImage: "camera.fill").font(.headline).padding(.horizontal, 20).padding(.vertical, 14)
                    .background(Color.accentColor, in: Capsule()).foregroundColor(.white).shadow(radius: 3)
            }
        }
        .padding(20)
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        if selecting {
            ToolbarItem(placement: .navigationBarLeading) {
                Button("Bỏ chọn") { selecting = false; selection = [] }
            }
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                Button { selection = Set(filtered.map(\.id)) } label: { Image(systemName: "checkmark.circle") }
                Button { movingIds = Array(selection) } label: { Image(systemName: "folder") }.disabled(selection.isEmpty)
                Button { confirmMerge = true } label: { Image(systemName: "arrow.triangle.merge") }.disabled(selection.count < 2)
                Button(role: .destructive) { confirmDeleteSelected = true } label: { Image(systemName: "trash") }.disabled(selection.isEmpty)
            }
        } else {
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Picker("Sắp xếp", selection: Binding(get: { settings.sortOrder }, set: { settings.sortOrder = $0 })) {
                        ForEach(SortOrder.allCases) { Text($0.label).tag($0) }
                    }
                    Button { showTrash = true } label: {
                        Label(repository.trash.isEmpty ? "Thùng rác" : "Thùng rác (\(repository.trash.count))", systemImage: "trash")
                    }
                    Button { showSettings = true } label: { Label("Cài đặt", systemImage: "gearshape") }
                    if !repository.documents.isEmpty {
                        Button { selecting = true } label: { Label("Chọn tài liệu", systemImage: "checkmark.circle") }
                    }
                } label: { Image(systemName: "ellipsis.circle") }
            }
        }
    }

    private func toggle(_ id: String) {
        if selection.contains(id) { selection.remove(id) } else { selection.insert(id) }
    }

    private func mergeSelected() {
        let chosen = filtered.filter { selection.contains($0.id) }.map(\.id)
        selection = []
        selecting = false
        guard chosen.count >= 2 else { return }
        repository.merge(target: chosen[0], sources: Array(chosen.dropFirst()))
        message = "Đã gộp tài liệu"
    }

    private func quickShare(_ doc: ScanDocument) {
        guard !doc.pages.isEmpty else { return }
        busy = "Đang tạo PDF…"
        let inputs = doc.pages.map { PdfExporter.Input(page: $0, imageURL: repository.pageURL(doc.id, $0.id)) }
        var options = settings.pdfOptions
        options.fileName = doc.name
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("exports/\(safeFileName(doc.name)).pdf")
        Task {
            do {
                try await PdfExporter.export(name: doc.name, pages: inputs, options: options, to: url) { _ in }
                shareURL = url
            } catch {
                message = "Đã xảy ra lỗi: \(error.localizedDescription)"
            }
            busy = nil
        }
    }
}

struct ShareItem: Identifiable {
    let url: URL
    var id: String { url.path }
}

struct DocumentRow: View {
    @EnvironmentObject private var repository: DocumentRepository
    let doc: ScanDocument
    let folderName: String?
    let selecting: Bool
    let selected: Bool

    var body: some View {
        HStack(spacing: 12) {
            if selecting {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle").foregroundColor(.accentColor)
            }
            Group {
                if let first = doc.pages.first {
                    FileImage(url: repository.thumbURL(doc.id, first.id), version: first.updatedAt, contentMode: .fill)
                } else {
                    Image(systemName: "doc").foregroundColor(.secondary)
                }
            }
            .frame(width: 56, height: 74)
            .background(Color(UIColor.secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 4) {
                Text(doc.name).font(.headline).lineLimit(1)
                Text("\(doc.pages.count) trang · \(formatDate(doc.updatedAt))\(folderName.map { " · \($0)" } ?? "")")
                    .font(.footnote).foregroundColor(.secondary).lineLimit(1)
            }
            Spacer()
        }
        .padding(.vertical, 4)
    }
}

struct DetailsSheet: View {
    @EnvironmentObject private var repository: DocumentRepository
    let doc: ScanDocument
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            List {
                Text("Số trang: \(doc.pages.count)")
                Text("Tạo lúc: \(formatDate(doc.createdAt))")
                Text("Sửa lúc: \(formatDate(doc.updatedAt))")
                Text("Dung lượng: \(formatSize(repository.size(of: doc.id)))")
                Text("Thư mục: \(repository.folders.first { $0.id == doc.folderId }?.name ?? "Không thư mục")")
                Text("Đã nhận dạng chữ: \(doc.pages.filter { !($0.ocrText ?? "").isEmpty }.count)/\(doc.pages.count) trang")
            }
            .navigationTitle(doc.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Đóng") { dismiss() } } }
        }
    }
}

/// Picks a folder (or none) for documents; can create a new one.
struct FolderPickerSheet: View {
    @EnvironmentObject private var repository: DocumentRepository
    let current: String?
    let onPick: (String?) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var naming = false

    var body: some View {
        NavigationView {
            List {
                row("Không thư mục", selected: current == nil) { onPick(nil) }
                ForEach(repository.folders) { f in row(f.name, selected: current == f.id) { onPick(f.id) } }
                Button("Thư mục mới…") { naming = true }
            }
            .navigationTitle("Chuyển vào thư mục")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Hủy") { dismiss() } } }
            .sheet(isPresented: $naming) {
                TextInputSheet(title: "Thư mục mới", placeholder: "Tên thư mục", text: "") { name in
                    onPick(repository.createFolder(name))
                    dismiss()
                }
            }
        }
    }

    private func row(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button { action(); dismiss() } label: {
            HStack {
                Text(title).foregroundColor(.primary)
                Spacer()
                if selected { Image(systemName: "checkmark").foregroundColor(.accentColor) }
            }
        }
    }
}
