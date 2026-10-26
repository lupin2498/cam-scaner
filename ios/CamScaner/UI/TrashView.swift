import SwiftUI

/// Deleted documents: restore them or remove them for good.
struct TrashView: View {
    @EnvironmentObject private var repository: DocumentRepository
    @State private var deleting: ScanDocument?
    @State private var emptying = false

    var body: some View {
        Group {
            if repository.trash.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "trash").font(.largeTitle).foregroundColor(.secondary)
                    Text("Thùng rác trống").foregroundColor(.secondary)
                }
            } else {
                List {
                    Section(footer: Text("Tài liệu trong thùng rác tự xóa sau \(DocumentRepository.trashDays) ngày")) {
                        ForEach(repository.trash) { doc in
                            HStack(spacing: 12) {
                                if let first = doc.pages.first {
                                    FileImage(url: repository.thumbURL(doc.id, first.id), version: first.updatedAt, contentMode: .fill)
                                        .frame(width: 48, height: 64).clipShape(RoundedRectangle(cornerRadius: 6))
                                }
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(doc.name).font(.headline).lineLimit(1)
                                    Text("\(doc.pages.count) trang · Đã xóa \(formatDate(doc.deletedAt ?? 0))").font(.footnote).foregroundColor(.secondary)
                                    HStack {
                                        Button("Khôi phục") { repository.restore(doc.id) }.buttonStyle(.bordered)
                                        Button("Xóa vĩnh viễn", role: .destructive) { deleting = doc }.buttonStyle(.borderless)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Thùng rác")
        .toolbar {
            if !repository.trash.isEmpty {
                Button(role: .destructive) { emptying = true } label: { Image(systemName: "trash.slash") }
            }
        }
        .alert("Xóa vĩnh viễn?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), presenting: deleting) { doc in
            Button("Xóa", role: .destructive) { repository.deleteForever(doc.id) }
            Button("Hủy", role: .cancel) {}
        } message: { doc in Text("\"\(doc.name)\" sẽ bị xóa vĩnh viễn, không thể khôi phục.") }
        .alert("Dọn sạch thùng rác?", isPresented: $emptying) {
            Button("Xóa tất cả", role: .destructive) { repository.emptyTrash() }
            Button("Hủy", role: .cancel) {}
        }
    }
}
