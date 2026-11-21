import CoreBluetooth
import Foundation

/// BLE central that finds the phone by service UUID, subscribes to TX and
/// keeps reconnecting. All callbacks run on the main queue.
final class BLEManager: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    @Published var status = "Đang khởi động"
    @Published var connected = false
    /// How many times an established link was lost, and why the last one went.
    @Published var drops = 0
    @Published var lastDrop = ""

    var onFrame: ((UInt8, Data) -> Void)?
    var onConnected: (() -> Void)?

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var rxCharacteristic: CBCharacteristic?
    private let parser = FrameParser()

    override init() {
        super.init()
        parser.onFrame = { [weak self] type, payload in
            self?.onFrame?(type, payload)
        }
        central = CBCentralManager(delegate: self, queue: .main)
    }

    /// Call when the app comes to the foreground: restarts discovery if we are not connected.
    func ensureConnecting() {
        guard central.state == .poweredOn else { return }
        if connected, peripheral?.state != .connected {
            // watchOS tore the link down while the app was suspended and the
            // disconnect callback never arrived. Do not trust the stale flag.
            noteDrop("Hệ thống ngắt khi app ngủ")
            dropLink()
        }
        guard !connected else { return }
        if let known = peripheral, known.state == .disconnected {
            central.connect(known, options: nil)
        }
        if !central.isScanning {
            central.scanForPeripherals(withServices: [Proto.service], options: nil)
        }
        if status != "Đang kết nối" {
            status = "Đang tìm điện thoại"
        }
    }

    func send(_ type: UInt8, json: [String: Any]) {
        guard connected, let peripheral = peripheral, let characteristic = rxCharacteristic,
              let payload = try? JSONSerialization.data(withJSONObject: json) else { return }
        let writeType: CBCharacteristicWriteType =
            characteristic.properties.contains(.write) ? .withResponse : .withoutResponse
        peripheral.writeValue(Proto.frame(type, payload), for: characteristic, type: writeType)
    }

    private func noteDrop(_ reason: String) {
        drops += 1
        lastDrop = reason
    }

    private func dropLink() {
        connected = false
        rxCharacteristic = nil
        parser.reset()
    }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            ensureConnecting()
        case .poweredOff:
            dropLink()
            status = "Bluetooth đang tắt"
        case .unauthorized:
            dropLink()
            status = "Chưa cấp quyền Bluetooth"
        case .unsupported:
            status = "Không hỗ trợ Bluetooth LE"
        default:
            dropLink()
            status = "Bluetooth chưa sẵn sàng"
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard !connected else { return }
        if let old = self.peripheral, old.identifier != peripheral.identifier {
            // The phone rotates its BLE address, so it can show up as a new peripheral.
            central.cancelPeripheralConnection(old)
        }
        if self.peripheral?.identifier == peripheral.identifier,
           self.peripheral?.state == .connecting || self.peripheral?.state == .connected {
            return
        }
        self.peripheral = peripheral
        peripheral.delegate = self
        status = "Đang kết nối"
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard peripheral.identifier == self.peripheral?.identifier else { return }
        central.stopScan()
        status = "Đang thiết lập"
        peripheral.discoverServices([Proto.service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard peripheral.identifier == self.peripheral?.identifier else { return }
        dropLink()
        self.peripheral = nil
        status = "Kết nối lỗi, thử lại"
        ensureConnecting()
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        guard peripheral.identifier == self.peripheral?.identifier else { return }
        if connected {
            noteDrop(error?.localizedDescription ?? "Ngắt chủ động")
        }
        dropLink()
        status = "Mất kết nối"
        ensureConnecting()
    }

    // MARK: CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard peripheral.identifier == self.peripheral?.identifier else { return }
        guard error == nil,
              let service = peripheral.services?.first(where: { $0.uuid == Proto.service }) else {
            // Without the service the link is useless. Drop it so the
            // disconnect handler starts a clean attempt instead of hanging here.
            status = "Không thấy dịch vụ, thử lại"
            central.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.discoverCharacteristics([Proto.tx, Proto.rx], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        guard let characteristics = service.characteristics else { return }
        for characteristic in characteristics {
            if characteristic.uuid == Proto.rx {
                rxCharacteristic = characteristic
            }
        }
        for characteristic in characteristics where characteristic.uuid == Proto.tx {
            peripheral.setNotifyValue(true, for: characteristic)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard characteristic.uuid == Proto.tx else { return }
        if let error = error {
            status = "Lỗi đăng ký: \(error.localizedDescription)"
            return
        }
        if characteristic.isNotifying {
            parser.reset()
            connected = true
            status = "Đã kết nối"
            onConnected?()
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard characteristic.uuid == Proto.tx, let value = characteristic.value else { return }
        parser.feed(value)
    }
}
