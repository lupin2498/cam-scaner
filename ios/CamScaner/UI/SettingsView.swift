import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @EnvironmentObject private var settings: Settings
    @EnvironmentObject private var repository: DocumentRepository
    @EnvironmentObject private var signatures: SignatureStore
    @State private var exportURL: URL?
    @State private var restoring = false
    @State private var busy: String?
    @State private var message: String?

    var body: some View {
        ZStack {
            Form {
                Section("Bộ lọc mặc định cho ảnh quét") {
                    Picker("Bộ lọc", selection: $settings.defaultFilter) {
                        ForEach(ScanFilter.allCases) { Text($0.label).tag($0) }
                    }
                }
                Section("Quét") {
                    ToggleRow(title: "Tự động cắt mép giấy", subtitle: "Tắt để luôn giữ toàn bộ ảnh", isOn: $settings.autoCrop)
                    ToggleRow(title: "Tự động chụp khi nhận diện được tài liệu", isOn: $settings.autoCapture)
                    ToggleRow(title: "Hiện lưới khi chụp", isOn: $settings.showGrid)
                }
                Section("Giao diện") {
                    Picker("Giao diện", selection: $settings.theme) {
                        ForEach(AppTheme.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("settings.theme")
                }
                Section("Tiền tố tên tài liệu mới") {
                    TextField("Ví dụ: Hóa đơn (mặc định: Cam Scaner)", text: $settings.namePrefix)
                }
                Section("Bảo mật") {
                    Toggle(isOn: Binding(get: { settings.appLock }, set: { on in
                        if on && !AppState.lockAvailable() { message = "Máy chưa cài khóa màn hình hoặc Face ID/Touch ID" } else { settings.appLock = on }
                    })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Khóa ứng dụng")
                            Text("Mở ứng dụng bằng Face ID, Touch ID hoặc mật mã").font(.footnote).foregroundColor(.secondary)
                        }
                    }
                }
                Section(header: Text("Sao lưu & khôi phục"), footer: Text("Toàn bộ tài liệu, ảnh gốc và thư mục trong một tệp .zip")) {
                    Button("Sao lưu") { backup() }
                    Button("Khôi phục") { restoring = true }
                }
                Section {
                    HStack {
                        Text("Chữ ký đã lưu: \(signatures.signatures.count)")
                        Spacer()
                        Button("Xóa chữ ký đã lưu") { signatures.removeAll() }.disabled(signatures.signatures.isEmpty)
                    }
                }
                Section {
                    Text("Phiên bản \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0") · Không quảng cáo · Không hình mờ")
                        .font(.footnote).foregroundColor(.secondary)
                }
            }
            if let b = busy { ProgressOverlay(message: b) }
        }
        .navigationTitle("Cài đặt")
        .sheet(item: Binding(get: { exportURL.map(ShareItem.init) }, set: { exportURL = $0?.url })) { FileExporter(url: $0.url) }
        .deferredSheet(isPresented: $restoring) { then in
            FilePicker(types: [.zip, .data]) { urls in
                guard let url = urls.first else { return }
                then { restore(url) }
            }
        }
        .toast($message)
    }

    private func restore(_ url: URL) {
        busy = "Đang khôi phục…"
        Task {
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            // mapped, so a large backup is not read into memory at once
            if let data = try? Data(contentsOf: url, options: .alwaysMapped) {
                let count = repository.restoreBackup(data)
                message = count < 0 ? "Tệp không phải bản sao lưu của Cam Scaner" : "Đã khôi phục \(count) tài liệu"
            } else {
                message = "Không đọc được tệp"
            }
            busy = nil
        }
    }

    private func backup() {
        busy = "Đang sao lưu…"
        Task {
            let data = repository.backupData()
            let f = DateFormatter()
            f.dateFormat = "yyyyMMdd-HHmm"
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("CamScaner-\(f.string(from: Date())).zip")
            try? data.write(to: url, options: .atomic)
            busy = nil
            exportURL = url
        }
    }
}
