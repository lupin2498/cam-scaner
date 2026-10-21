import CallKit
import Flutter
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    if let registrar = engineBridge.pluginRegistry.registrar(forPlugin: "P2PMeetNative") {
      P2PMeetNative.shared.attach(messenger: registrar.messenger())
    }
  }
}

/// Kênh native cho P2P Meet trên iOS:
/// - Phát hiện cuộc gọi khác (điện thoại, FaceTime, Zalo/Messenger qua CallKit).
/// - Chuyển tiếp thông báo bắt đầu/dừng phát màn hình từ Broadcast Extension.
final class P2PMeetNative: NSObject {
  static let shared = P2PMeetNative()

  private var channel: FlutterMethodChannel?
  private let callObserver = CXCallObserver()
  private var observingDarwin = false

  func attach(messenger: FlutterBinaryMessenger) {
    let channel = FlutterMethodChannel(name: "p2pmeet/native", binaryMessenger: messenger)
    channel.setMethodCallHandler { [weak self] call, result in
      self?.handle(call, result: result)
    }
    self.channel = channel
    observeBroadcastNotifications()
  }

  private func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "isOtherCallActive":
      result(callObserver.calls.contains { !$0.hasEnded })
    case "requestPermissions":
      result(["camera", "microphone"])
    case "startCallService", "setAudioManagement":
      result(true)
    case "stopCallService":
      result(true)
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  private func observeBroadcastNotifications() {
    guard !observingDarwin else { return }
    observingDarwin = true
    let center = CFNotificationCenterGetDarwinNotifyCenter()
    let observer = UnsafeRawPointer(Unmanaged.passUnretained(self).toOpaque())
    for name in ["iOS_BroadcastStarted", "iOS_BroadcastStopped"] {
      CFNotificationCenterAddObserver(
        center,
        observer,
        { _, observer, name, _, _ in
          guard let observer = observer, let name = name else { return }
          let me = Unmanaged<P2PMeetNative>.fromOpaque(observer).takeUnretainedValue()
          let event = (name.rawValue as String) == "iOS_BroadcastStarted" ? "broadcastStarted" : "broadcastStopped"
          DispatchQueue.main.async {
            me.channel?.invokeMethod(event, arguments: nil)
          }
        },
        name as CFString,
        nil,
        .deliverImmediately
      )
    }
  }
}
