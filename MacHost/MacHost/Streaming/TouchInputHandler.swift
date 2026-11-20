import Foundation
import CoreGraphics

/// Injects touch events from the Android device into the macOS Virtual Display as mouse events.
public final class TouchInputHandler {
    public init() {}

    public func handleTouch(_ event: TouchEvent, in displayID: CGDirectDisplayID) {
        let bounds = CGDisplayBounds(displayID)

        let (nx, ny, mouseType): (Float, Float, CGEventType) = {
            switch event {
            case .down(let x, let y):
                return (x, y, .leftMouseDown)
            case .move(let x, let y):
                return (x, y, .leftMouseDragged)
            case .up(let x, let y):
                return (x, y, .leftMouseUp)
            }
        }()

        let clampedX = Double(min(max(nx, 0.0), 1.0))
        let clampedY = Double(min(max(ny, 0.0), 1.0))

        let targetPoint = CGPoint(
            x: bounds.origin.x + (clampedX * bounds.width),
            y: bounds.origin.y + (clampedY * bounds.height)
        )

        guard let cgEvent = CGEvent(
            mouseEventSource: nil,
            mouseType: mouseType,
            mouseCursorPosition: targetPoint,
            mouseButton: .left
        ) else { return }

        cgEvent.post(tap: .cghidEventTap)
    }
}

