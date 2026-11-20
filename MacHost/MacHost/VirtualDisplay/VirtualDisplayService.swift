import Foundation
import CoreGraphics
import Combine
import os

private let logger = Logger(subsystem: "com.secondscreen.machost", category: "VirtualDisplayService")

@MainActor
public final class VirtualDisplayService: ObservableObject {
    @Published public private(set) var activeDisplayID: CGDirectDisplayID?
    @Published public private(set) var isDisplayActive: Bool = false

    private var wrapper: VirtualDisplayWrapper?

    public init() {}

    @discardableResult
    public func createVirtualDisplay(
        name: String = "SecondScreen Display",
        width: Int = 1920,
        height: Int = 1080,
        refreshRate: Double = 60.0,
        hiDPI: Bool = false
    ) throws -> CGDirectDisplayID {
        destroyVirtualDisplay()

        guard let createdWrapper = VirtualDisplayWrapper.create(
            withName: name,
            vendorID: 0x1234,
            productID: 0x5678,
            serialNumber: 1,
            sizeInMillimeters: CGSize(width: 527, height: 296),
            maxPixelsWide: 8192,
            maxPixelsHigh: 8192,
            terminationQueue: .main,
            terminationHandler: { [weak self] in
                Task { @MainActor in
                    self?.handleTermination()
                }
            }
        ), createdWrapper.displayID != 0 else {
            throw DisplayError.creationFailed("Could not allocate CGVirtualDisplay. Verify system permissions.")
        }

        let applied = createdWrapper.applyWidth(
            UInt(width),
            height: UInt(height),
            refreshRate: refreshRate,
            hiDPI: hiDPI
        )

        guard applied else {
            createdWrapper.invalidate()
            throw DisplayError.configurationFailed("Failed to apply initial resolution \(width)x\(height)")
        }

        self.wrapper = createdWrapper
        self.activeDisplayID = createdWrapper.displayID
        self.isDisplayActive = true

        logger.info("Created virtual display: \(name) (\(width)x\(height)) -> ID \(createdWrapper.displayID)")
        return createdWrapper.displayID
    }

    public func reconfigure(width: Int, height: Int, refreshRate: Double = 60.0, hiDPI: Bool = false) -> Bool {
        guard let wrapper else { return false }
        return wrapper.applyWidth(UInt(width), height: UInt(height), refreshRate: refreshRate, hiDPI: hiDPI)
    }

    public func destroyVirtualDisplay() {
        if let wrapper {
            wrapper.invalidate()
            self.wrapper = nil
        }
        self.activeDisplayID = nil
        self.isDisplayActive = false
    }

    private func handleTermination() {
        logger.warning("Virtual display was terminated by OS")
        destroyVirtualDisplay()
    }

    public enum DisplayError: LocalizedError {
        case creationFailed(String)
        case configurationFailed(String)

        public var errorDescription: String? {
            switch self {
            case .creationFailed(let msg), .configurationFailed(let msg): return msg
            }
        }
    }
}
