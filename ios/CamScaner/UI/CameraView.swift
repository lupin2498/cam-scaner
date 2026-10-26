import SwiftUI
import AVFoundation
import Vision
import CoreMotion
import Combine

/// A code read by the camera or found in a picture.
struct Barcode: Identifiable {
    let payload: String
    let type: String
    var id: String { type + payload }

    /// The payload as a web link, when it is one.
    var link: URL? {
        guard let url = URL(string: payload.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" else { return nil }
        return url
    }

    static func scan(_ image: UIImage) -> Barcode? {
        guard let cg = image.normalized().cgImage else { return nil }
        let request = VNDetectBarcodesRequest()
        try? VNImageRequestHandler(cgImage: cg, orientation: .up, options: [:]).perform([request])
        guard let found = request.results?.first(where: { $0.payloadStringValue != nil }), let payload = found.payloadStringValue else { return nil }
        return Barcode(payload: payload, type: found.symbology.rawValue.replacingOccurrences(of: "VNBarcodeSymbology", with: ""))
    }
}

/// Runs the capture session: preview frames for sheet detection, photos, barcodes, zoom, focus and flash.
final class CameraController: NSObject, ObservableObject {
    enum Flash: CaseIterable {
        case off, on, auto

        var icon: String {
            switch self {
            case .off: return "bolt.slash"
            case .on: return "bolt.fill"
            case .auto: return "bolt.badge.a"
            }
        }

        var mode: AVCaptureDevice.FlashMode {
            switch self {
            case .off: return .off
            case .on: return .on
            case .auto: return .auto
            }
        }

        var next: Flash {
            switch self {
            case .off: return .on
            case .on: return .auto
            case .auto: return .off
            }
        }
    }

    let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "camscaner.camera.session")
    private let videoQueue = DispatchQueue(label: "camscaner.camera.video")
    private var device: AVCaptureDevice?
    private let photoOutput = AVCapturePhotoOutput()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let metadataOutput = AVCaptureMetadataOutput()
    weak var previewLayer: AVCaptureVideoPreviewLayer?

    /// nil while the permission is being asked for.
    @Published var authorized: Bool?
    @Published var available = true
    @Published var running = false
    /// Sheet seen in the preview, normalized to the upright frame.
    @Published var detected: Quad?
    /// Size of the upright preview frame the corners refer to.
    @Published var frameSize = CGSize(width: 3, height: 4)
    /// How long the sheet has been still, 0…1; 1 fires the auto capture.
    @Published var stability: Double = 0
    @Published var zoom: CGFloat = 1
    @Published var flash: Flash = .off
    @Published var barcode: Barcode?
    @Published var processing = false

    var detectionEnabled = true
    var autoCaptureEnabled = true
    var barcodeEnabled = false
    var onAutoCapture: (() -> Void)?
    var onPhoto: ((UIImage) -> Void)?
    var onError: ((String) -> Void)?

    private var configured = false
    private var orientation: AVCaptureVideoOrientation = .portrait
    private var lastDetection = Date.distantPast
    private var lastQuad: Quad?
    private var misses = 0
    private var stableSince: Date?
    private var cooldownUntil = Date.distantPast
    private var barcodePaused = false
    private var observer: NSObjectProtocol?

    static let stillSeconds = 1.1
    static let maxZoom: CGFloat = 8

    deinit {
        if let o = observer { NotificationCenter.default.removeObserver(o) }
    }

    // MARK: lifecycle

    func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            authorized = true
            run()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { ok in
                DispatchQueue.main.async {
                    self.authorized = ok
                    if ok { self.run() }
                }
            }
        default:
            authorized = false
        }
    }

    func stop() {
        sessionQueue.async {
            if self.session.isRunning { self.session.stopRunning() }
            DispatchQueue.main.async { self.running = false }
        }
    }

    private func run() {
        sessionQueue.async {
            if !self.configured { self.configure() }
            let ok = self.device != nil
            if ok && !self.session.isRunning { self.session.startRunning() }
            DispatchQueue.main.async {
                self.available = ok
                self.running = self.session.isRunning
            }
        }
    }

    private func configure() {
        configured = true
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = .photo
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) ?? AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: camera), session.canAddInput(input) else { return }
        session.addInput(input)
        device = camera
        if (try? camera.lockForConfiguration()) != nil {
            if camera.isFocusModeSupported(.continuousAutoFocus) { camera.focusMode = .continuousAutoFocus }
            if camera.isExposureModeSupported(.continuousAutoExposure) { camera.exposureMode = .continuousAutoExposure }
            camera.isSubjectAreaChangeMonitoringEnabled = true
            camera.unlockForConfiguration()
        }
        observer = NotificationCenter.default.addObserver(forName: .AVCaptureDeviceSubjectAreaDidChange, object: camera, queue: nil) { [weak self] _ in
            self?.resetFocus()
        }
        if session.canAddOutput(photoOutput) {
            session.addOutput(photoOutput)
            if #available(iOS 16.0, *) {
                if let best = camera.activeFormat.supportedMaxPhotoDimensions.last { photoOutput.maxPhotoDimensions = best }
            } else {
                photoOutput.isHighResolutionCaptureEnabled = true
            }
        }
        videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(self, queue: videoQueue)
        if session.canAddOutput(videoOutput) { session.addOutput(videoOutput) }
        if session.canAddOutput(metadataOutput) {
            session.addOutput(metadataOutput)
            metadataOutput.setMetadataObjectsDelegate(self, queue: videoQueue)
            let wanted: [AVMetadataObject.ObjectType] = [.qr, .ean13, .ean8, .upce, .code39, .code93, .code128, .itf14, .pdf417, .dataMatrix, .aztec]
            metadataOutput.metadataObjectTypes = wanted.filter { metadataOutput.availableMetadataObjectTypes.contains($0) }
        }
        applyOrientation()
    }

    /// Called by the preview when the interface turns; frames and photos follow the screen.
    func updateOrientation(_ o: AVCaptureVideoOrientation) {
        guard o != orientation else { return }
        orientation = o
        sessionQueue.async { self.applyOrientation() }
    }

    private func applyOrientation() {
        for output in [photoOutput as AVCaptureOutput, videoOutput] {
            if let c = output.connection(with: .video), c.isVideoOrientationSupported, c.videoOrientation != orientation {
                c.videoOrientation = orientation
            }
        }
    }

    // MARK: controls

    func capture() {
        guard !processing else { return }
        processing = true
        let flashMode = flash.mode
        sessionQueue.async {
            guard self.session.isRunning else {
                DispatchQueue.main.async { self.processing = false }
                return
            }
            let settings: AVCapturePhotoSettings
            if self.photoOutput.availablePhotoCodecTypes.contains(.jpeg) {
                settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
            } else {
                settings = AVCapturePhotoSettings()
            }
            if self.photoOutput.supportedFlashModes.contains(flashMode) { settings.flashMode = flashMode }
            if #available(iOS 16.0, *) {
                settings.maxPhotoDimensions = self.photoOutput.maxPhotoDimensions
            } else {
                settings.isHighResolutionPhotoEnabled = self.photoOutput.isHighResolutionCaptureEnabled
            }
            self.photoOutput.capturePhoto(with: settings, delegate: self)
        }
    }

    func setZoom(_ factor: CGFloat) {
        guard let d = device else { return }
        let z = min(max(factor, 1), min(d.activeFormat.videoMaxZoomFactor, CameraController.maxZoom))
        zoom = z
        sessionQueue.async {
            if (try? d.lockForConfiguration()) != nil {
                d.videoZoomFactor = z
                d.unlockForConfiguration()
            }
        }
    }

    /// Focuses and meters at a point of the preview layer.
    func focus(atLayerPoint point: CGPoint) {
        guard let d = device, let layer = previewLayer else { return }
        let devicePoint = layer.captureDevicePointConverted(fromLayerPoint: point)
        sessionQueue.async {
            guard (try? d.lockForConfiguration()) != nil else { return }
            if d.isFocusPointOfInterestSupported && d.isFocusModeSupported(.autoFocus) {
                d.focusPointOfInterest = devicePoint
                d.focusMode = .autoFocus
            }
            if d.isExposurePointOfInterestSupported && d.isExposureModeSupported(.continuousAutoExposure) {
                d.exposurePointOfInterest = devicePoint
                d.exposureMode = .continuousAutoExposure
            }
            d.unlockForConfiguration()
        }
    }

    private func resetFocus() {
        guard let d = device else { return }
        sessionQueue.async {
            guard (try? d.lockForConfiguration()) != nil else { return }
            let centre = CGPoint(x: 0.5, y: 0.5)
            if d.isFocusPointOfInterestSupported && d.isFocusModeSupported(.continuousAutoFocus) {
                d.focusPointOfInterest = centre
                d.focusMode = .continuousAutoFocus
            }
            if d.isExposurePointOfInterestSupported && d.isExposureModeSupported(.continuousAutoExposure) {
                d.exposurePointOfInterest = centre
                d.exposureMode = .continuousAutoExposure
            }
            d.unlockForConfiguration()
        }
    }

    func resumeBarcodes() {
        videoQueue.async { self.barcodePaused = false }
    }

    private func resetStability() {
        lastQuad = nil
        stableSince = nil
    }
}

extension CameraController: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard detectionEnabled else {
            if lastQuad != nil {
                resetStability()
                DispatchQueue.main.async {
                    self.detected = nil
                    self.stability = 0
                }
            }
            return
        }
        let now = Date()
        guard now.timeIntervalSince(lastDetection) > 0.1, let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        lastDetection = now
        let w = CVPixelBufferGetWidth(buffer), h = CVPixelBufferGetHeight(buffer)
        var quad = DocumentDetector.detect(pixelBuffer: buffer, orientation: .up)
        if quad == nil {
            // keep the last outline for a couple of frames so it does not flicker
            misses += 1
            if misses <= 2, let last = lastQuad { quad = last }
        } else {
            misses = 0
        }
        var progress = 0.0
        if let q = quad, let last = lastQuad, q.maxDistance(to: last) < 0.03, q.area() > 0.12 {
            let since = stableSince ?? now
            stableSince = since
            progress = min(1, now.timeIntervalSince(since) / CameraController.stillSeconds)
        } else {
            stableSince = nil
        }
        lastQuad = quad
        let fire = progress >= 1 && autoCaptureEnabled && now > cooldownUntil && !processing
        if fire {
            cooldownUntil = now.addingTimeInterval(3)
            stableSince = nil
            progress = 0
        }
        DispatchQueue.main.async {
            self.detected = quad
            self.frameSize = CGSize(width: w, height: h)
            self.stability = progress
            if fire { self.onAutoCapture?() }
        }
    }
}

extension CameraController: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        if let error = error {
            DispatchQueue.main.async {
                self.processing = false
                self.onError?(error.localizedDescription)
            }
            return
        }
        guard let data = photo.fileDataRepresentation(), let image = UIImage(data: data) else {
            DispatchQueue.main.async {
                self.processing = false
                self.onError?("Không đọc được ảnh chụp")
            }
            return
        }
        DispatchQueue.main.async {
            self.processing = false
            self.onPhoto?(image)
        }
    }
}

extension CameraController: AVCaptureMetadataOutputObjectsDelegate {
    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard barcodeEnabled, !barcodePaused else { return }
        guard let code = metadataObjects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject }).first(where: { $0.stringValue != nil }),
              let value = code.stringValue else { return }
        barcodePaused = true
        let type = code.type.rawValue.split(separator: ".").last.map(String.init) ?? code.type.rawValue
        DispatchQueue.main.async { self.barcode = Barcode(payload: value, type: type) }
    }
}

/// UIKit view hosting the preview layer; keeps the layer and the capture turned with the interface.
final class CameraPreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    var onOrientation: ((AVCaptureVideoOrientation) -> Void)?

    override func layoutSubviews() {
        super.layoutSubviews()
        let o: AVCaptureVideoOrientation
        switch window?.windowScene?.interfaceOrientation {
        case .landscapeLeft: o = .landscapeLeft
        case .landscapeRight: o = .landscapeRight
        case .portraitUpsideDown: o = .portraitUpsideDown
        default: o = .portrait
        }
        if let c = previewLayer.connection, c.isVideoOrientationSupported, c.videoOrientation != o { c.videoOrientation = o }
        onOrientation?(o)
    }
}

struct CameraPreview: UIViewRepresentable {
    let controller: CameraController

    func makeUIView(context: Context) -> CameraPreviewView {
        let view = CameraPreviewView()
        view.previewLayer.session = controller.session
        view.previewLayer.videoGravity = .resizeAspectFill
        view.onOrientation = { [weak controller] o in controller?.updateOrientation(o) }
        controller.previewLayer = view.previewLayer
        return view
    }

    func updateUIView(_ uiView: CameraPreviewView, context: Context) {}
}

/// Polygon through the given points.
struct QuadShape: Shape {
    let points: [CGPoint]

    func path(in rect: CGRect) -> Path {
        var p = Path()
        guard let first = points.first else { return p }
        p.move(to: first)
        for pt in points.dropFirst() { p.addLine(to: pt) }
        p.closeSubpath()
        return p
    }
}

/// Rule-of-thirds grid.
struct GridLines: View {
    var body: some View {
        GeometryReader { geo in
            Path { p in
                for i in 1...2 {
                    let x = geo.size.width * CGFloat(i) / 3, y = geo.size.height * CGFloat(i) / 3
                    p.move(to: CGPoint(x: x, y: 0)); p.addLine(to: CGPoint(x: x, y: geo.size.height))
                    p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: geo.size.width, y: y))
                }
            }
            .stroke(Color.white.opacity(0.35), lineWidth: 1)
        }
        .allowsHitTesting(false)
    }
}

/// Bubble level from the device motion; shown while the phone is held over the table.
final class LevelModel: ObservableObject {
    private let manager = CMMotionManager()
    @Published var gravity: CMAcceleration?

    func start() {
        guard manager.isDeviceMotionAvailable else { return }
        manager.deviceMotionUpdateInterval = 1 / 20
        manager.startDeviceMotionUpdates(to: .main) { [weak self] motion, _ in
            if let g = motion?.gravity { self?.gravity = g }
        }
    }

    func stop() { manager.stopDeviceMotionUpdates() }
}

struct LevelIndicator: View {
    @StateObject private var model = LevelModel()

    var body: some View {
        ZStack {
            if let g = model.gravity, abs(g.z) > 0.6 {
                let tilt = hypot(g.x, g.y)
                let level = tilt < 0.045
                Circle().stroke(Color.white.opacity(0.7), lineWidth: 1.5).frame(width: 46, height: 46)
                Circle().stroke(level ? Color.green : Color.white.opacity(0.9), lineWidth: 2).frame(width: 18, height: 18)
                Circle().fill(level ? Color.green : Color.white)
                    .frame(width: 12, height: 12)
                    .offset(x: CGFloat(-g.x) * 70, y: CGFloat(g.y) * 70)
                    .animation(.linear(duration: 0.05), value: tilt)
            }
        }
        .frame(width: 50, height: 50)
        .onAppear(perform: model.start)
        .onDisappear(perform: model.stop)
        .allowsHitTesting(false)
    }
}

/// Camera screen: modes, paper guide, sheet outline, auto capture, zoom, focus, barcodes.
struct CameraView: View {
    @EnvironmentObject private var session: ScanSession
    @EnvironmentObject private var settings: Settings
    @StateObject private var camera = CameraController()
    let onDone: () -> Void
    let onCancel: () -> Void

    @State private var shutterFlash = false
    @State private var focusPoint: CGPoint?
    @State private var pinchStart: CGFloat = 1
    @State private var pickingImage = false
    @State private var message: String?

    private var mode: CameraMode { session.cameraMode }
    private var newPages: Int { session.pages.filter { $0.existingPageId == nil }.count }

    private var guidePaper: PaperPreset? {
        switch mode {
        case .document: return settings.cameraPaper
        case .idCard: return .idCard
        default: return nil
        }
    }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black
                if camera.authorized == false {
                    permissionDenied
                } else if !camera.available {
                    Text("Không tìm thấy máy ảnh").foregroundColor(.white)
                } else {
                    CameraPreview(controller: camera)
                    overlay(geo.size)
                        .contentShape(Rectangle())
                        .gesture(
                            MagnificationGesture()
                                .onChanged { v in camera.setZoom(pinchStart * v) }
                                .onEnded { _ in pinchStart = camera.zoom }
                        )
                        .simultaneousGesture(
                            DragGesture(minimumDistance: 0)
                                .onEnded { v in
                                    guard abs(v.translation.width) < 8, abs(v.translation.height) < 8 else { return }
                                    focusPoint = v.location
                                    camera.focus(atLayerPoint: v.location)
                                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { focusPoint = nil }
                                }
                        )
                }
                VStack(spacing: 0) {
                    topBar.padding(.top, geo.safeAreaInsets.top + 6)
                    Spacer()
                    bottomControls.padding(.bottom, geo.safeAreaInsets.bottom + 8)
                }
                if camera.processing { ProgressView().tint(.white).scaleEffect(1.4) }
            }
        }
        .ignoresSafeArea()
        .onAppear {
            camera.onPhoto = { image in
                let m = session.cameraMode
                Task { await session.addCapture(image, mode: m) }
            }
            camera.onAutoCapture = { shoot() }
            camera.onError = { message = $0 }
            apply()
            camera.start()
        }
        .onDisappear { camera.stop() }
        .onChange(of: session.cameraMode) { _ in apply() }
        .onChange(of: settings.autoCapture) { _ in apply() }
        .sheet(item: $camera.barcode, onDismiss: { camera.resumeBarcodes() }) { code in BarcodeSheet(code: code) }
        .deferredSheet(isPresented: $pickingImage) { then in
            PhotoPicker(limit: 1) { images in
                guard let image = images.first else { return }
                let code = Barcode.scan(image)
                // the result sheet opens once the photo picker has gone
                then {
                    if let code = code { camera.barcode = code } else { message = "Không tìm thấy mã QR hay mã vạch trong ảnh" }
                }
            }
        }
        .toast($message)
    }

    private func apply() {
        camera.detectionEnabled = mode.detects
        camera.autoCaptureEnabled = settings.autoCapture && mode.detects
        camera.barcodeEnabled = mode == .qr
    }

    // MARK: overlay

    private func viewPoint(_ p: Pt, in size: CGSize) -> CGPoint {
        let frame = camera.frameSize
        let s = max(size.width / frame.width, size.height / frame.height)
        let w = frame.width * s, h = frame.height * s
        return CGPoint(x: (size.width - w) / 2 + CGFloat(p.x) * w, y: (size.height - h) / 2 + CGFloat(p.y) * h)
    }

    private func overlay(_ size: CGSize) -> some View {
        ZStack {
            if settings.showGrid && mode != .qr { GridLines() }
            if let paper = guidePaper {
                let g = PaperFit.guide(paper, viewWidth: size.width, viewHeight: size.height)
                QuadShape(points: g.points.map { CGPoint(x: $0.x * size.width, y: $0.y * size.height) })
                    .stroke(Color.white.opacity(0.85), style: StrokeStyle(lineWidth: 1.5, dash: [10, 7]))
                Text(paper.label).font(.caption.bold()).foregroundColor(.white).padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Color.black.opacity(0.45), in: Capsule())
                    .position(x: size.width / 2, y: g.tl.y * size.height - 16)
            }
            if mode == .qr {
                RoundedRectangle(cornerRadius: 14)
                    .stroke(Color.white.opacity(0.9), lineWidth: 2)
                    .frame(width: min(size.width, size.height) * 0.62, height: min(size.width, size.height) * 0.62)
            }
            if let q = camera.detected, mode.detects {
                let pts = q.points.map { viewPoint($0, in: size) }
                QuadShape(points: pts).fill(Color.brand.opacity(0.18))
                QuadShape(points: pts).stroke(Color.brand, lineWidth: 2.5)
            }
            if let f = focusPoint {
                RoundedRectangle(cornerRadius: 4).stroke(Color.yellow, lineWidth: 1.5).frame(width: 72, height: 72).position(f)
            }
            if shutterFlash { Color.white.opacity(0.75) }
            if mode.detects {
                LevelIndicator().position(x: size.width - 44, y: size.height * 0.42)
            }
        }
        .animation(.easeOut(duration: 0.1), value: camera.detected)
    }

    // MARK: bars

    private var topBar: some View {
        HStack(spacing: 6) {
            iconButton("xmark", action: onCancel)
                .accessibilityLabel("Đóng")
                .accessibilityIdentifier("camera.close")
            Spacer()
            if mode == .qr {
                Button { pickingImage = true } label: {
                    Label("Quét từ ảnh", systemImage: "photo").font(.subheadline).padding(.horizontal, 12).padding(.vertical, 8)
                        .background(Color.black.opacity(0.45), in: Capsule()).foregroundColor(.white)
                }
            } else {
                iconButton(camera.flash.icon) { camera.flash = camera.flash.next }
                if mode.detects {
                    iconButton(settings.autoCapture ? "a.circle.fill" : "a.circle") { settings.autoCapture.toggle() }
                }
                if mode != .idCard && mode != .book {
                    iconButton(settings.batchMode ? "square.stack.3d.up.fill" : "square.stack.3d.up") { settings.batchMode.toggle() }
                }
                iconButton(settings.showGrid ? "grid.circle.fill" : "grid.circle") { settings.showGrid.toggle() }
                if mode == .document { paperMenu }
            }
        }
        .padding(.horizontal, 12)
    }

    private var paperMenu: some View {
        Menu {
            Button { settings.cameraPaper = nil } label: {
                if settings.cameraPaper == nil { Label("Tự do (theo mép giấy)", systemImage: "checkmark") } else { Text("Tự do (theo mép giấy)") }
            }
            ForEach(PaperPreset.allCases) { p in
                Button { settings.cameraPaper = p } label: {
                    if settings.cameraPaper == p { Label(p.label, systemImage: "checkmark") } else { Text(p.label) }
                }
            }
        } label: {
            HStack(spacing: 4) {
                Image(systemName: "doc.plaintext")
                Text(settings.cameraPaper?.label ?? "Khổ").font(.caption)
            }
            .foregroundColor(.white).padding(.horizontal, 10).padding(.vertical, 8)
            .background(Color.black.opacity(0.45), in: Capsule())
        }
    }

    private func iconButton(_ name: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: name).font(.system(size: 17, weight: .medium)).foregroundColor(.white)
                .frame(width: 38, height: 38).background(Color.black.opacity(0.45), in: Circle())
        }
    }

    private var hint: String? {
        switch mode {
        case .idCard: return newPages == 1 ? "Lật thẻ và chụp mặt sau" : "Chụp mặt trước rồi mặt sau; hai mặt được ghép lên một trang A4 khi xuất PDF"
        case .book: return "Chụp cả hai trang sách đang mở; ảnh tự tách thành hai trang"
        case .qr: return "Đưa mã QR hoặc mã vạch vào khung hình"
        case .photo: return "Ảnh được giữ nguyên, không cắt và không lọc"
        case .document: return settings.autoCapture && camera.detected != nil ? "Giữ yên máy để tự chụp" : nil
        }
    }

    private var bottomControls: some View {
        VStack(spacing: 10) {
            if let h = hint {
                Text(h).font(.footnote).foregroundColor(.white).padding(.horizontal, 12).padding(.vertical, 6)
                    .background(Color.black.opacity(0.45), in: Capsule()).multilineTextAlignment(.center)
            }
            if camera.zoom > 1.05 {
                Text(String(format: "%.1f×", camera.zoom)).font(.caption.bold()).foregroundColor(.white)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(CameraMode.allCases) { m in
                        Button { session.cameraMode = m } label: {
                            Text(m.label).font(.subheadline.weight(m == mode ? .bold : .regular))
                                .foregroundColor(m == mode ? .yellow : .white)
                                .padding(.horizontal, 12).padding(.vertical, 6)
                        }
                    }
                }
                .padding(.horizontal, 12)
            }
            HStack {
                Group {
                    if mode == .qr {
                        Color.clear
                    } else if let last = session.pages.last {
                        Button(action: onDone) {
                            ZStack(alignment: .topTrailing) {
                                FileImage(url: last.originalURL, version: 0, contentMode: .fill)
                                    .frame(width: 52, height: 52).clipShape(RoundedRectangle(cornerRadius: 8))
                                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.white, lineWidth: 1.5))
                                Text("\(session.pages.count)").font(.caption2.bold()).foregroundColor(.white).padding(5)
                                    .background(Color.brand, in: Circle()).offset(x: 8, y: -8)
                            }
                        }
                    } else {
                        Color.clear
                    }
                }
                .frame(width: 64, height: 64)
                Spacer()
                if mode == .qr {
                    Image(systemName: "qrcode.viewfinder").font(.system(size: 44)).foregroundColor(.white.opacity(0.8))
                } else {
                    Button(action: shoot) {
                        ZStack {
                            Circle().stroke(Color.white, lineWidth: 4).frame(width: 76, height: 76)
                            if camera.stability > 0 {
                                Circle().trim(from: 0, to: camera.stability).stroke(Color.brand, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                                    .rotationEffect(.degrees(-90)).frame(width: 76, height: 76)
                            }
                            Circle().fill(Color.white).frame(width: 62, height: 62)
                        }
                    }
                    .disabled(camera.processing || !camera.running)
                }
                Spacer()
                Group {
                    if mode != .qr && !session.pages.isEmpty {
                        Button(action: onDone) {
                            Image(systemName: "checkmark").font(.title3.bold()).foregroundColor(.black)
                                .frame(width: 52, height: 52).background(Color.white, in: Circle())
                        }
                    } else {
                        Color.clear
                    }
                }
                .frame(width: 64, height: 64)
            }
            .padding(.horizontal, 24)
        }
    }

    private var permissionDenied: some View {
        VStack(spacing: 16) {
            Image(systemName: "camera.fill").font(.system(size: 44)).foregroundColor(.white)
            Text("Cần quyền máy ảnh để quét tài liệu").foregroundColor(.white).multilineTextAlignment(.center)
            Button("Mở Cài đặt") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(32)
    }

    private func shoot() {
        guard !camera.processing, camera.running else { return }
        shutterFlash = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) { shutterFlash = false }
        camera.capture()
    }
}

/// What a scanned code holds, with copy, open and share.
struct BarcodeSheet: View {
    let code: Barcode
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var share = false

    var body: some View {
        NavigationView {
            List {
                Section(header: Text(code.type.isEmpty ? "Nội dung" : code.type)) {
                    Text(code.payload).textSelection(.enabled)
                }
                Section {
                    Button { UIPasteboard.general.string = code.payload } label: { Label("Sao chép", systemImage: "doc.on.doc") }
                    if let link = code.link {
                        Button { openURL(link) } label: { Label("Mở liên kết", systemImage: "safari") }
                    }
                    Button { share = true } label: { Label("Chia sẻ", systemImage: "square.and.arrow.up") }
                }
            }
            .navigationTitle("Kết quả quét mã")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Quét tiếp") { dismiss() } } }
            .sheet(isPresented: $share) { ShareSheet(items: [code.payload]) }
        }
    }
}
