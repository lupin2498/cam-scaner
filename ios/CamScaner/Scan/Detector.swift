import Foundation
import Vision
import CoreImage
import UIKit

/// Finds the paper sheet in a photo or preview frame with Vision's rectangle detector.
enum DocumentDetector {

    private static func request() -> VNDetectRectanglesRequest {
        let request = VNDetectRectanglesRequest()
        request.minimumAspectRatio = 0.3
        request.maximumAspectRatio = 1.0
        request.minimumSize = 0.15
        request.minimumConfidence = 0.5
        request.maximumObservations = 6
        request.quadratureTolerance = 30
        return request
    }

    /// Normalized corners (origin top-left) of the largest sheet in an upright image, or nil.
    static func detect(_ image: CGImage) -> Quad? {
        let r = request()
        try? VNImageRequestHandler(cgImage: image, orientation: .up, options: [:]).perform([r])
        return best(r.results ?? [])
    }

    /// Same for a camera frame; `orientation` turns the frame upright. Corners are in upright coordinates.
    static func detect(pixelBuffer: CVPixelBuffer, orientation: CGImagePropertyOrientation) -> Quad? {
        let r = request()
        try? VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: orientation, options: [:]).perform([r])
        return best(r.results ?? [])
    }

    private static func best(_ observations: [VNRectangleObservation]) -> Quad? {
        let quads = observations.map(quad(from:)).filter { $0.isValid(minAngle: 45, maxAngle: 135) }
        return quads.max { $0.area() < $1.area() }
    }

    private static func quad(from o: VNRectangleObservation) -> Quad {
        // Vision reports normalized points with the origin at the bottom-left corner
        func p(_ v: CGPoint) -> Pt { Pt(Double(v.x), 1 - Double(v.y)).clamped }
        return Quad.ordered([p(o.topLeft), p(o.topRight), p(o.bottomRight), p(o.bottomLeft)])
    }
}
