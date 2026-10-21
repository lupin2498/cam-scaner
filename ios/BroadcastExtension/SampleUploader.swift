import Foundation
import ReplayKit

private enum Constants {
  static let bufferMaxLength = 10240
  /// Cạnh dài tối đa của ảnh gửi sang ứng dụng (tiết kiệm bộ nhớ/CPU của extension).
  static let maxLongSide: CGFloat = 1280
}

class SampleUploader {
  private static var imageContext = CIContext(options: nil)

  @Atomic private var isReady = false
  private var connection: SocketConnection

  private var dataToSend: Data?
  private var byteIndex = 0

  private let serialQueue: DispatchQueue

  init(connection: SocketConnection) {
    self.connection = connection
    self.serialQueue = DispatchQueue(label: "com.lupin2498.p2pMeet.broadcast.sampleUploader")
    setupConnection()
  }

  @discardableResult func send(sample buffer: CMSampleBuffer) -> Bool {
    guard isReady else {
      return false
    }
    isReady = false
    dataToSend = prepare(sample: buffer)
    byteIndex = 0
    serialQueue.async { [weak self] in
      self?.sendDataChunk()
    }
    return true
  }
}

private extension SampleUploader {
  func setupConnection() {
    connection.didOpen = { [weak self] in
      self?.isReady = true
    }
    connection.streamHasSpaceAvailable = { [weak self] in
      self?.serialQueue.async {
        if let success = self?.sendDataChunk() {
          self?.isReady = !success
        }
      }
    }
  }

  @discardableResult func sendDataChunk() -> Bool {
    guard let dataToSend = dataToSend else {
      return false
    }
    var bytesLeft = dataToSend.count - byteIndex
    var length = bytesLeft > Constants.bufferMaxLength ? Constants.bufferMaxLength : bytesLeft
    length = dataToSend[byteIndex..<(byteIndex + length)].withUnsafeBytes {
      guard let ptr = $0.bindMemory(to: UInt8.self).baseAddress else {
        return 0
      }
      return connection.writeToStream(buffer: ptr, maxLength: length)
    }
    if length > 0 {
      byteIndex += length
      bytesLeft -= length
      if bytesLeft == 0 {
        self.dataToSend = nil
        byteIndex = 0
      }
    } else {
      print("writeBufferToStream failure")
    }
    return true
  }

  func prepare(sample buffer: CMSampleBuffer) -> Data? {
    guard let imageBuffer = CMSampleBufferGetImageBuffer(buffer) else {
      return nil
    }
    CVPixelBufferLockBaseAddress(imageBuffer, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(imageBuffer, .readOnly) }

    let srcWidth = CGFloat(CVPixelBufferGetWidth(imageBuffer))
    let srcHeight = CGFloat(CVPixelBufferGetHeight(imageBuffer))
    let scale = min(1.0, Constants.maxLongSide / max(srcWidth, srcHeight))
    let width = Int((srcWidth * scale).rounded(.down)) & ~1
    let height = Int((srcHeight * scale).rounded(.down)) & ~1
    let orientation = CMGetAttachment(buffer, key: RPVideoSampleOrientationKey as CFString, attachmentModeOut: nil)?.uintValue ?? 0

    let scaleTransform = CGAffineTransform(scaleX: scale, y: scale)
    guard let messageData = jpegData(from: imageBuffer, scale: scaleTransform) else {
      return nil
    }

    let httpResponse = CFHTTPMessageCreateResponse(nil, 200, nil, kCFHTTPVersion1_1).takeRetainedValue()
    CFHTTPMessageSetHeaderFieldValue(httpResponse, "Content-Length" as CFString, String(messageData.count) as CFString)
    CFHTTPMessageSetHeaderFieldValue(httpResponse, "Buffer-Width" as CFString, String(width) as CFString)
    CFHTTPMessageSetHeaderFieldValue(httpResponse, "Buffer-Height" as CFString, String(height) as CFString)
    CFHTTPMessageSetHeaderFieldValue(httpResponse, "Buffer-Orientation" as CFString, String(orientation) as CFString)
    CFHTTPMessageSetBody(httpResponse, messageData as CFData)

    let serializedMessage = CFHTTPMessageCopySerializedMessage(httpResponse)?.takeRetainedValue() as Data?
    return serializedMessage
  }

  func jpegData(from buffer: CVPixelBuffer, scale scaleTransform: CGAffineTransform) -> Data? {
    let image = CIImage(cvPixelBuffer: buffer).transformed(by: scaleTransform)
    guard let colorSpace = image.colorSpace ?? CGColorSpace(name: CGColorSpace.sRGB) else {
      return nil
    }
    let options: [CIImageRepresentationOption: Float] = [
      kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: 0.8
    ]
    return SampleUploader.imageContext.jpegRepresentation(of: image, colorSpace: colorSpace, options: options)
  }
}
