import CoreBluetooth
import Foundation

/// Wire protocol shared with the Android app. See android/.../Protocol.kt.
enum Proto {
    static let service = CBUUID(string: "7A1D0001-5E3B-4C6A-9F2D-8B4E1C0A9D10")
    static let tx = CBUUID(string: "7A1D0002-5E3B-4C6A-9F2D-8B4E1C0A9D10")
    static let rx = CBUUID(string: "7A1D0003-5E3B-4C6A-9F2D-8B4E1C0A9D10")

    static let magic: UInt8 = 0xA5

    // Phone to watch.
    static let nav: UInt8 = 0x01
    static let icon: UInt8 = 0x02
    static let notif: UInt8 = 0x03
    static let notifRemove: UInt8 = 0x04

    // Watch to phone.
    static let hello: UInt8 = 0x10
    static let action: UInt8 = 0x12

    static func frame(_ type: UInt8, _ payload: Data) -> Data {
        var out = Data([magic, type, UInt8((payload.count >> 8) & 0xFF), UInt8(payload.count & 0xFF)])
        out.append(payload)
        return out
    }
}

/// Reassembles frames from a stream of BLE packets.
final class FrameParser {
    private var buffer = [UInt8]()
    var onFrame: ((UInt8, Data) -> Void)?

    func reset() {
        buffer.removeAll()
    }

    func feed(_ chunk: Data) {
        buffer.append(contentsOf: chunk)
        while true {
            if let start = buffer.firstIndex(of: Proto.magic) {
                if start > 0 { buffer.removeFirst(start) }
            } else {
                buffer.removeAll()
                return
            }
            guard buffer.count >= 4 else { return }
            let length = (Int(buffer[2]) << 8) | Int(buffer[3])
            guard buffer.count >= 4 + length else { return }
            let type = buffer[1]
            let payload = Data(buffer[4..<(4 + length)])
            buffer.removeFirst(4 + length)
            onFrame?(type, payload)
        }
    }
}
