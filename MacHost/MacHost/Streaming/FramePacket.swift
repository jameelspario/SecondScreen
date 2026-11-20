import Foundation

/// Wire protocol:
/// Mac -> Android: [4-byte Big-Endian length][H.264 Annex-B data]
/// Android -> Mac: [1-byte type][4-byte Big-Endian float x][4-byte Big-Endian float y]
///   type: 0x01 = down, 0x02 = move, 0x03 = up
public enum FramePacket {
    public static func frame(from data: Data) -> Data {
        var length = UInt32(data.count).bigEndian
        var packet = Data(bytes: &length, count: 4)
        packet.append(data)
        return packet
    }
}

public enum TouchEvent: Equatable {
    case down(x: Float, y: Float)
    case move(x: Float, y: Float)
    case up(x: Float, y: Float)

    public static func decode(_ data: Data) -> TouchEvent? {
        guard data.count == 9 else { return nil }
        let type = data[0]
        let x = Float(bitPattern: UInt32(bigEndian: data.subdata(in: 1..<5).withUnsafeBytes { $0.load(as: UInt32.self) }))
        let y = Float(bitPattern: UInt32(bigEndian: data.subdata(in: 5..<9).withUnsafeBytes { $0.load(as: UInt32.self) }))
        switch type {
        case 0x01: return .down(x: x, y: y)
        case 0x02: return .move(x: x, y: y)
        case 0x03: return .up(x: x, y: y)
        default: return nil
        }
    }
}

