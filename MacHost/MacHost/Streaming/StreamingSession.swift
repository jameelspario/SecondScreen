import Foundation
import CoreGraphics
import CoreMedia
import QuartzCore
import Combine
import os

private let logger = Logger(subsystem: "com.secondscreen.machost", category: "StreamingSession")

@MainActor
public final class StreamingSession: ObservableObject {
    @Published public private(set) var isStreaming: Bool = false
    @Published public private(set) var isClientConnected: Bool = false
    @Published public private(set) var clientEndpoint: String?
    @Published public private(set) var framesSent: Int = 0
    @Published public var errorMessage: String?

    public let virtualDisplayService = VirtualDisplayService()

    private var encoder: VideoEncoder?
    private var captureSession: ScreenCaptureSession?
    private var server: StreamingServer?
    private let touchHandler = TouchInputHandler()

    public init() {}

    public func startSession(
        width: Int = 1920,
        height: Int = 1080,
        refreshRate: Double = 60.0,
        hiDPI: Bool = false,
        port: UInt16 = 7070
    ) async {
        await stopSession()
        errorMessage = nil

        do {
            // 1. Create or ensure Virtual Display
            let displayID = try virtualDisplayService.createVirtualDisplay(
                width: width,
                height: height,
                refreshRate: refreshRate,
                hiDPI: hiDPI
            )

            // 2. Start TCP Streaming Server
            let server = StreamingServer()
            server.onClientConnected = { [weak self] endpoint in
                Task { @MainActor in
                    self?.isClientConnected = true
                    self?.clientEndpoint = endpoint
                    self?.encoder?.forceNextKeyFrame = true
                    if let lastBuffer = self?.captureSession?.lastPixelBuffer {
                        self?.encoder?.encode(
                            pixelBuffer: lastBuffer,
                            presentationTime: CMTime(seconds: CACurrentMediaTime(), preferredTimescale: 600)
                        )
                    }
                }
            }
            server.onClientDisconnected = { [weak self] in
                Task { @MainActor in
                    self?.isClientConnected = false
                    self?.clientEndpoint = nil
                }
            }
            server.onTouchEvent = { [weak self] event in
                Task { @MainActor in
                    self?.touchHandler.handleTouch(event, in: displayID)
                }
            }
            try server.start(port: port)
            self.server = server

            // 3. Setup Hardware Video Encoder
            let encoder = VideoEncoder(width: width, height: height, bitrate: 8_000_000)
            encoder.onEncodedData = { [weak self] data in
                self?.server?.send(data: data)
                Task { @MainActor in
                    self?.framesSent += 1
                }
            }
            self.encoder = encoder

            // 4. Start ScreenCaptureKit Capture
            let capture = ScreenCaptureSession()
            capture.onFrame = { [weak self] pixelBuffer, time in
                self?.encoder?.encode(pixelBuffer: pixelBuffer, presentationTime: time)
            }
            try await capture.start(displayID: displayID)
            self.captureSession = capture

            self.isStreaming = true
            logger.info("Streaming session started successfully on display \(displayID)")
        } catch {
            logger.error("Failed to start streaming session: \(error.localizedDescription)")
            self.errorMessage = error.localizedDescription
            await stopSession()
        }
    }

    public func stopSession() async {
        await captureSession?.stop()
        captureSession = nil
        encoder = nil
        server?.stop()
        server = nil
        virtualDisplayService.destroyVirtualDisplay()

        isStreaming = false
        isClientConnected = false
        clientEndpoint = nil
        framesSent = 0
    }
}
