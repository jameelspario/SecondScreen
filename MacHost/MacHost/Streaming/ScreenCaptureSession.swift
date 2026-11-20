import ScreenCaptureKit
import CoreMedia
import CoreGraphics
import Foundation
import AppKit
import os

private let logger = Logger(subsystem: "com.secondscreen.machost", category: "ScreenCaptureSession")

public final class ScreenCaptureSession: NSObject, SCStreamOutput, SCStreamDelegate {
    public var onFrame: ((CVPixelBuffer, CMTime) -> Void)?
    public private(set) var lastPixelBuffer: CVPixelBuffer?

    private var stream: SCStream?

    public override init() { super.init() }

    public static func hasScreenRecordingPermission() -> Bool {
        return CGPreflightScreenCaptureAccess()
    }

    public static func requestScreenRecordingPermission() {
        _ = CGRequestScreenCaptureAccess()
    }

    public static func openSystemSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture") {
            NSWorkspace.shared.open(url)
        }
    }

    public func start(displayID: CGDirectDisplayID) async throws {
        // Attempt shareable content retrieval directly — ScreenCaptureKit is the source of truth
        let content: SCShareableContent
        do {
            content = try await SCShareableContent.current
        } catch {
            Self.requestScreenRecordingPermission()
            throw CaptureError.permissionDenied
        }

        guard let scDisplay = content.displays.first(where: { $0.displayID == displayID }) else {
            throw CaptureError.displayNotFound(displayID)
        }

        let filter = SCContentFilter(display: scDisplay, excludingWindows: [])

        let config = SCStreamConfiguration()
        let bounds = CGDisplayBounds(displayID)
        config.width = Int(bounds.width)
        config.height = Int(bounds.height)
        config.minimumFrameInterval = CMTime(value: 1, timescale: 30)
        config.pixelFormat = kCVPixelFormatType_32BGRA
        config.colorSpaceName = CGColorSpace.sRGB
        config.showsCursor = false

        let newStream = SCStream(filter: filter, configuration: config, delegate: self)
        try newStream.addStreamOutput(self, type: .screen, sampleHandlerQueue: .global(qos: .userInteractive))
        try await newStream.startCapture()
        self.stream = newStream
        logger.info("ScreenCaptureSession started for display \(displayID) (\(Int(bounds.width))x\(Int(bounds.height)))")
    }

    public func stop() async {
        guard let stream else { return }
        try? await stream.stopCapture()
        self.stream = nil
        logger.info("ScreenCaptureSession stopped")
    }

    // MARK: – SCStreamOutput
    public func stream(_ stream: SCStream,
                       didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
                       of type: SCStreamOutputType) {
        guard type == .screen,
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        self.lastPixelBuffer = pixelBuffer
        let presentationTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        onFrame?(pixelBuffer, presentationTime)
    }

    // MARK: – SCStreamDelegate
    public func stream(_ stream: SCStream, didStopWithError error: Error) {
        logger.error("SCStream stopped with error: \(error.localizedDescription, privacy: .public)")
    }

    public enum CaptureError: LocalizedError {
        case permissionDenied
        case displayNotFound(CGDirectDisplayID)

        public var errorDescription: String? {
            switch self {
            case .permissionDenied:
                return "Screen Recording permission required. Please enable 'MacHost' in System Settings -> Privacy & Security -> Screen & System Audio Recording."
            case .displayNotFound(let id):
                return "Could not find display ID \(id) in ScreenCaptureKit"
            }
        }
    }
}
