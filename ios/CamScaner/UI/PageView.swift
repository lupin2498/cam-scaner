import SwiftUI

/// Full-screen page viewer with zoom and the page tools.
struct PageView: View {
    @EnvironmentObject private var repository: DocumentRepository
    @EnvironmentObject private var session: ScanSession
    let docId: String
    let startIndex: Int
    @Binding var flow: ScanFlow?

    @State private var index = 0
    @State private var confirmDelete = false
    @State private var showOcr = false
    @State private var busy = false
    @Environment(\.dismiss) private var dismiss

    private var pages: [ScanPage] { repository.get(docId)?.pages ?? [] }

    var body: some View {
        ZStack {
            Color(red: 0.06, green: 0.075, blue: 0.07).ignoresSafeArea()
            if pages.isEmpty {
                Color.clear.onAppear { dismiss() }
            } else {
                VStack(spacing: 0) {
                    TabView(selection: $index) {
                        ForEach(Array(pages.enumerated()), id: \.element.id) { i, page in
                            ZoomableImage(url: repository.pageURL(docId, page.id), version: page.updatedAt).tag(i)
                        }
                    }
                    .tabViewStyle(.page(indexDisplayMode: .never))
                    toolbar
                }
            }
            if busy { ProgressOverlay(message: "Đang xoay trang…") }
            NavigationLink(destination: Group { if let p = current { OcrView(docId: docId, pageId: p.id) } }, isActive: $showOcr) { EmptyView() }.hidden()
        }
        .navigationTitle("\(min(index, max(pages.count - 1, 0)) + 1) / \(pages.count)")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { index = min(startIndex, max(pages.count - 1, 0)) }
        .alert("Xóa trang này?", isPresented: $confirmDelete) {
            Button("Xóa", role: .destructive) {
                if let p = current {
                    repository.deletePages(docId, [p.id])
                    index = min(index, max(pages.count - 1, 0))
                }
            }
            Button("Hủy", role: .cancel) {}
        }
    }

    private var current: ScanPage? { pages.indices.contains(index) ? pages[index] : pages.last }

    private var toolbar: some View {
        HStack {
            tool("Cắt lại", "crop") {
                if let p = current {
                    session.editExisting(docId: docId, pageId: p.id)
                    flow = .edit(docId: docId, pageId: p.id, filterStep: false)
                }
            }
            tool("Bộ lọc", "wand.and.stars") {
                if let p = current {
                    session.editExisting(docId: docId, pageId: p.id)
                    flow = .edit(docId: docId, pageId: p.id, filterStep: true)
                }
            }
            tool("Xoay", "rotate.right") {
                guard let p = current, !busy else { return }
                busy = true
                Task {
                    await repository.updatePage(docId, p.id) { $0.turned(by: 90) }
                    busy = false
                }
            }
            tool("Nhận dạng chữ", "text.viewfinder") { showOcr = true }
            tool("Xóa", "trash") { confirmDelete = true }
        }
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(Color(red: 0.09, green: 0.11, blue: 0.105))
    }

    private func tool(_ title: String, _ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: icon)
                Text(title).font(.caption2)
            }
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
        }
    }
}

/// An image that zooms with a pinch and pans when zoomed; double tap toggles.
struct ZoomableImage: View {
    let url: URL
    let version: Double
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    var body: some View {
        GeometryReader { geo in
            FileImage(url: url, version: version)
                .frame(width: geo.size.width, height: geo.size.height)
                .scaleEffect(scale)
                .offset(offset)
                .gesture(
                    MagnificationGesture()
                        .onChanged { v in scale = min(max(lastScale * v, 1), 5) }
                        .onEnded { _ in
                            lastScale = scale
                            if scale <= 1.01 { offset = .zero; lastOffset = .zero }
                        }
                )
                .simultaneousGesture(
                    DragGesture(minimumDistance: scale > 1.01 ? 0 : 1000)
                        .onChanged { v in
                            let maxX = geo.size.width * (scale - 1) / 2, maxY = geo.size.height * (scale - 1) / 2
                            offset = CGSize(width: min(max(lastOffset.width + v.translation.width, -maxX), maxX),
                                            height: min(max(lastOffset.height + v.translation.height, -maxY), maxY))
                        }
                        .onEnded { _ in lastOffset = offset }
                )
                .onTapGesture(count: 2) {
                    withAnimation {
                        if scale > 1.01 { scale = 1; lastScale = 1; offset = .zero; lastOffset = .zero } else { scale = 2.5; lastScale = 2.5 }
                    }
                }
                .padding(12)
        }
    }
}
