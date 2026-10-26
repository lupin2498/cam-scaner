import Foundation
import Vision
import UIKit

/// A recognised line; `box` is normalized with the origin at the top-left corner.
struct OcrLine {
    let text: String
    let box: CGRect
}

struct OcrResult {
    let text: String
    let lines: [OcrLine]
}

/// On-device text recognition with Vision (Vietnamese on iOS 16 and later, Latin otherwise).
enum OCR {
    static func recognize(_ image: UIImage) async throws -> OcrResult {
        guard let cg = image.normalized().cgImage else { return OcrResult(text: "", lines: []) }
        return try await recognize(cg)
    }

    static func recognize(_ cg: CGImage) async throws -> OcrResult {
        try await Task.detached(priority: .userInitiated) {
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            if #available(iOS 16.0, *) {
                request.revision = VNRecognizeTextRequestRevision3
                let supported = (try? request.supportedRecognitionLanguages()) ?? []
                request.recognitionLanguages = ["vi-VN", "en-US"].filter { supported.contains($0) }
            } else {
                request.recognitionLanguages = ["en-US"]
            }
            try VNImageRequestHandler(cgImage: cg, orientation: .up, options: [:]).perform([request])
            let observations = (request.results ?? []).sorted { a, b in
                // top to bottom, then left to right (Vision's origin is at the bottom)
                let dy = b.boundingBox.midY - a.boundingBox.midY
                if abs(dy) > 0.01 { return dy > 0 }
                return a.boundingBox.minX < b.boundingBox.minX
            }
            var lines: [OcrLine] = []
            for o in observations {
                guard let candidate = o.topCandidates(1).first else { continue }
                let b = o.boundingBox
                lines.append(OcrLine(text: candidate.string, box: CGRect(x: b.minX, y: 1 - b.maxY, width: b.width, height: b.height)))
            }
            return OcrResult(text: lines.map(\.text).joined(separator: "\n"), lines: lines)
        }.value
    }
}
