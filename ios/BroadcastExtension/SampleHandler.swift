import OSLog
import ReplayKit

private let broadcastLogger = OSLog(subsystem: "com.lupin2498.p2pMeet.BroadcastExtension", category: "Broadcast")

private enum Constants {
  /// Phải trùng với RTCAppGroupIdentifier trong Info.plist của Runner.
  static let appGroupIdentifier = "group.com.lupin2498.p2pMeet"
}

/// Nhận khung hình màn hình từ ReplayKit và gửi sang ứng dụng P2P Meet qua Unix socket
/// trong App Group (định dạng mà flutter_webrtc FlutterSocketConnectionFrameReader đọc).
class SampleHandler: RPBroadcastSampleHandler {
  private var clientConnection: SocketConnection?
  private var uploader: SampleUploader?
  private var frameCount = 0

  var socketFilePath: String {
    let sharedContainer = FileManager.default.containerURL(
      forSecurityApplicationGroupIdentifier: Constants.appGroupIdentifier)
    return sharedContainer?.appendingPathComponent("rtc_SSFD").path ?? ""
  }

  override init() {
    super.init()
    if let connection = SocketConnection(filePath: socketFilePath) {
      clientConnection = connection
      setupConnection()
      uploader = SampleUploader(connection: connection)
    }
    os_log(.debug, log: broadcastLogger, "socket: %{public}s", socketFilePath)
  }

  override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
    frameCount = 0
    DarwinNotificationCenter.shared.postNotification(.broadcastStarted)
    openConnection()
  }

  override func broadcastPaused() {}

  override func broadcastResumed() {}

  override func broadcastFinished() {
    DarwinNotificationCenter.shared.postNotification(.broadcastStopped)
    clientConnection?.close()
  }

  override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleBufferType: RPSampleBufferType) {
    switch sampleBufferType {
    case .video:
      // Giảm tải: gửi 1/2 số khung (khoảng 15 fps) để nằm trong giới hạn bộ nhớ của extension.
      frameCount += 1
      if frameCount % 2 == 0 {
        uploader?.send(sample: sampleBuffer)
      }
    default:
      break
    }
  }
}

private extension SampleHandler {
  func setupConnection() {
    clientConnection?.didClose = { [weak self] error in
      os_log(.debug, log: broadcastLogger, "client connection did close %{public}s", String(describing: error))
      if let error = error {
        self?.finishBroadcastWithError(error)
      } else {
        let screenSharingStopped = 10001
        let customError = NSError(
          domain: RPRecordingErrorDomain,
          code: screenSharingStopped,
          userInfo: [NSLocalizedDescriptionKey: "Đã dừng chia sẻ màn hình"])
        self?.finishBroadcastWithError(customError)
      }
    }
  }

  func openConnection() {
    let queue = DispatchQueue(label: "broadcast.connectTimer")
    let timer = DispatchSource.makeTimerSource(queue: queue)
    timer.schedule(deadline: .now(), repeating: .milliseconds(100), leeway: .milliseconds(500))
    timer.setEventHandler { [weak self] in
      guard self?.clientConnection?.open() == true else {
        return
      }
      timer.cancel()
    }
    timer.resume()
  }
}
