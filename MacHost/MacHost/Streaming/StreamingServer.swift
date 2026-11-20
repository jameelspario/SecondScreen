import Foundation
import Network
import os

private let logger = Logger(subsystem: "com.secondscreen.machost", category: "StreamingServer")

/// TCP server on port 7070 broadcasting via Bonjour as `_secondscreen._tcp`.
public final class StreamingServer {
    public var onTouchEvent: ((TouchEvent) -> Void)?
    public var onClientConnected: ((String) -> Void)?
    public var onClientDisconnected: (() -> Void)?

    private var listener: NWListener?
    private var connection: NWConnection?
    private let queue = DispatchQueue(label: "com.secondscreen.streaming.server", qos: .userInteractive)

    public init() {}

    public func start(port: UInt16 = 7070) throws {
        stop()

        let params = NWParameters.tcp
        params.includePeerToPeer = true

        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            throw ServerError.invalidPort
        }

        let listener = try NWListener(using: params, on: nwPort)
        listener.service = NWListener.Service(name: "SecondScreen Mac", type: "_secondscreen._tcp")

        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                logger.info("StreamingServer listening on port \(port)")
            case .failed(let error):
                logger.error("StreamingServer failed: \(error.localizedDescription, privacy: .public)")
            default:
                break
            }
        }

        listener.newConnectionHandler = { [weak self] newConn in
            self?.accept(newConn)
        }

        listener.start(queue: queue)
        self.listener = listener
    }

    public func stop() {
        connection?.cancel()
        connection = nil
        listener?.cancel()
        listener = nil
    }

    public func send(data: Data) {
        guard let connection else { return }
        let packet = FramePacket.frame(from: data)
        connection.send(content: packet, completion: .contentProcessed { error in
            if let error {
                logger.debug("Send error: \(error.localizedDescription, privacy: .public)")
            }
        })
    }

    private func accept(_ newConn: NWConnection) {
        if let existing = connection {
            existing.cancel()
            DispatchQueue.main.async { self.onClientDisconnected?() }
        }

        self.connection = newConn
        let endpointDesc = String(describing: newConn.endpoint)

        newConn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                logger.info("Client connected: \(endpointDesc, privacy: .public)")
                DispatchQueue.main.async { self?.onClientConnected?(endpointDesc) }
                self?.receiveTouchLoop(on: newConn)
            case .failed(let error):
                logger.info("Client connection failed: \(error.localizedDescription, privacy: .public)")
                DispatchQueue.main.async { self?.onClientDisconnected?() }
                if self?.connection === newConn { self?.connection = nil }
            case .cancelled:
                DispatchQueue.main.async { self?.onClientDisconnected?() }
                if self?.connection === newConn { self?.connection = nil }
            default:
                break
            }
        }

        newConn.start(queue: queue)
    }

    private func receiveTouchLoop(on conn: NWConnection) {
        conn.receive(minimumIncompleteLength: 9, maximumLength: 9) { [weak self] data, _, isDone, error in
            if let data, let event = TouchEvent.decode(data) {
                DispatchQueue.main.async { self?.onTouchEvent?(event) }
            }
            if error != nil || isDone { return }
            self?.receiveTouchLoop(on: conn)
        }
    }

    public enum ServerError: LocalizedError {
        case invalidPort
        public var errorDescription: String? {
            switch self {
            case .invalidPort: return "Invalid server port"
            }
        }
    }
}

