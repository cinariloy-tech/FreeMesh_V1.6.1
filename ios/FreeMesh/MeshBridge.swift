import Foundation
import CoreBluetooth
import WebKit

// Hem merkez (yakındaki düğüm/telefonu bulup bağlanır) hem sunucu (başka telefon bu telefona bağlanır) rolü.
// Android'deki FreeMeshBle.java'nın iOS karşılığı. Mesajlar JSON satırı, \n ile biter.
final class MeshBridge: NSObject, WKScriptMessageHandler,
                        CBCentralManagerDelegate, CBPeripheralDelegate, CBPeripheralManagerDelegate {
    static let shared = MeshBridge()

    static let svc = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    static let rxUUID = CBUUID(string: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    static let txUUID = CBUUID(string: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    // Web tarafında window.FMBridge olarak görünür
    static let injectedJS = """
    window.FMBridge = {
      send: function (s) { window.webkit.messageHandlers.fm.postMessage(s); },
      connected: function () { return window.__fmReady === true; }
    };
    """

    weak var webView: WKWebView?
    private var central: CBCentralManager!
    private var pm: CBPeripheralManager!
    private var serverReady = false

    // Merkez rolü
    private var peripheral: CBPeripheral?
    private var rxChar: CBCharacteristic?
    private var centralReady = false
    private var pendingCentral: [Data] = []
    private var inCentral = Data()

    // Sunucu rolü
    private var subscriber: CBCentral?
    private var txChar: CBMutableCharacteristic?
    private var pendingNotify: [Data] = []
    private var inServer = Data()

    private var lastRssi: Int = -60
    private let myId: String

    private override init() {
        let key = "freemesh.id"
        let d = UserDefaults.standard
        if let saved = d.string(forKey: key) {
            myId = saved
        } else {
            let hex = Array("0123456789ABCDEF")
            let id = "PH-" + String((0..<6).map { _ in hex.randomElement()! })
            d.set(id, forKey: key)
            myId = id
        }
        super.init()
        central = CBCentralManager(delegate: self, queue: nil)
        pm = CBPeripheralManager(delegate: self, queue: nil)
    }

    // MARK: Web -> telefon -> karşı taraf
    func userContentController(_ userContentController: WKUserContentController,
                               didReceive message: WKScriptMessage) {
        guard let s = message.body as? String else { return }
        send(s)
    }

    private func withFrom(_ json: String) -> String {
        guard let idx = json.lastIndex(of: "}") else { return json }
        return String(json[..<idx]) + ",\"from\":\"\(myId)\"}"
    }

    private func send(_ json: String) {
        let bytes = Array((withFrom(json) + "\n").utf8)
        if centralReady, let p = peripheral, let rx = rxChar {
            let size = max(20, p.maximumWriteValueLength(for: .withoutResponse))
            var i = 0
            while i < bytes.count {
                let end = min(i + size, bytes.count)
                pendingCentral.append(Data(bytes[i..<end]))
                i = end
            }
            flushCentral(p, rx)
        } else if subscriber != nil {
            var i = 0
            while i < bytes.count {
                let end = min(i + 20, bytes.count)
                pendingNotify.append(Data(bytes[i..<end]))
                i = end
            }
            flushNotify()
        }
    }

    private func flushCentral(_ p: CBPeripheral, _ rx: CBCharacteristic) {
        while !pendingCentral.isEmpty && p.canSendWriteWithoutResponse {
            p.writeValue(pendingCentral.removeFirst(), for: rx, type: .withoutResponse)
        }
    }

    private func flushNotify() {
        guard let sub = subscriber, let tx = txChar else { return }
        while let next = pendingNotify.first {
            if pm.updateValue(next, for: tx, onSubscribedCentrals: [sub]) {
                pendingNotify.removeFirst()
            } else {
                break
            }
        }
    }

    // MARK: Karşı taraf -> telefon -> web
    private func consume(_ data: Data, server: Bool) {
        if server { inServer.append(data) } else { inCentral.append(data) }
        var buf = server ? inServer : inCentral
        while let idx = buf.firstIndex(of: 0x0A) {
            let lineData = buf.subdata(in: buf.startIndex..<idx)
            buf.removeSubrange(buf.startIndex...idx)
            let line = String(decoding: lineData, as: UTF8.self)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !line.isEmpty { deliver(line) }
        }
        if server { inServer = buf } else { inCentral = buf }
    }

    private func deliver(_ raw: String) {
        var line = raw
        let prefix = "{\"t\":\"tx\","
        if line.hasPrefix(prefix) {
            line = "{\"t\":\"rx\",\"rssi\":\(lastRssi),\"hop\":1," + String(line.dropFirst(prefix.count))
        }
        guard let enc = try? JSONSerialization.data(withJSONObject: [line]),
              let arr = String(data: enc, encoding: .utf8) else { return }
        let literal = String(arr.dropFirst().dropLast())
        js("window.onMeshRx && window.onMeshRx(\(literal))")
    }

    private func updateReady() {
        let ready = centralReady || subscriber != nil
        js("window.__fmReady = \(ready ? "true" : "false")")
        setStatus(ready ? "connected" : "disconnected")
    }

    private func setStatus(_ s: String) {
        js("window.onBleState && window.onBleState('\(s)')")
    }

    private func js(_ script: String) {
        webView?.evaluateJavaScript(script, completionHandler: nil)
    }

    // MARK: Merkez rolü
    func centralManagerDidUpdateState(_ c: CBCentralManager) {
        switch c.state {
        case .poweredOn: startScan()
        case .poweredOff: setStatus("bt_off")
        default: break
        }
    }

    private func startScan() {
        guard central.state == .poweredOn, peripheral == nil, !centralReady else { return }
        setStatus("scanning")
        central.scanForPeripherals(withServices: [MeshBridge.svc], options: nil)
    }

    func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        central.stopScan()
        lastRssi = RSSI.intValue
        peripheral = p
        p.delegate = self
        setStatus("connecting")
        central.connect(p, options: nil)
    }

    func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        p.discoverServices([MeshBridge.svc])
    }

    func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        resetCentral()
    }

    func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        resetCentral()
    }

    private func resetCentral() {
        centralReady = false
        rxChar = nil
        peripheral = nil
        pendingCentral.removeAll()
        inCentral = Data()
        updateReady()
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in self?.startScan() }
    }

    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let s = p.services?.first(where: { $0.uuid == MeshBridge.svc }) else {
            central.cancelPeripheralConnection(p)
            return
        }
        p.discoverCharacteristics([MeshBridge.rxUUID, MeshBridge.txUUID], for: s)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        guard let chars = s.characteristics else { return }
        rxChar = chars.first { $0.uuid == MeshBridge.rxUUID }
        if let tx = chars.first(where: { $0.uuid == MeshBridge.txUUID }) {
            p.setNotifyValue(true, for: tx)
        } else {
            central.cancelPeripheralConnection(p)
        }
    }

    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor ch: CBCharacteristic, error: Error?) {
        guard ch.uuid == MeshBridge.txUUID, ch.isNotifying else { return }
        centralReady = true
        updateReady()
        p.readRSSI()
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor ch: CBCharacteristic, error: Error?) {
        if ch.uuid == MeshBridge.txUUID, let d = ch.value { consume(d, server: false) }
    }

    func peripheral(_ p: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {
        if error == nil { lastRssi = RSSI.intValue }
        if centralReady {
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak p] in p?.readRSSI() }
        }
    }

    func peripheralIsReady(toSendWriteWithoutResponse p: CBPeripheral) {
        if let rx = rxChar { flushCentral(p, rx) }
    }

    // MARK: Sunucu rolü
    func peripheralManagerDidUpdateState(_ p: CBPeripheralManager) {
        guard p.state == .poweredOn, !serverReady else { return }
        serverReady = true
        let rx = CBMutableCharacteristic(type: MeshBridge.rxUUID,
                                         properties: [.write, .writeWithoutResponse],
                                         value: nil, permissions: [.writeable])
        let tx = CBMutableCharacteristic(type: MeshBridge.txUUID,
                                         properties: [.notify], value: nil, permissions: [.readable])
        txChar = tx
        let service = CBMutableService(type: MeshBridge.svc, primary: true)
        service.characteristics = [rx, tx]
        pm.add(service)
        pm.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [MeshBridge.svc],
                             CBAdvertisementDataLocalNameKey: "FreeMesh"])
    }

    func peripheralManager(_ p: CBPeripheralManager, central: CBCentral, didSubscribeTo ch: CBCharacteristic) {
        guard ch.uuid == MeshBridge.txUUID else { return }
        subscriber = central
        updateReady()
    }

    func peripheralManager(_ p: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom ch: CBCharacteristic) {
        guard subscriber?.identifier == central.identifier else { return }
        subscriber = nil
        pendingNotify.removeAll()
        inServer = Data()
        updateReady()
    }

    func peripheralManager(_ p: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for r in requests {
            if r.characteristic.uuid == MeshBridge.rxUUID, let v = r.value {
                consume(v, server: true)
            }
            if r.characteristic.uuid == MeshBridge.rxUUID {
                p.respond(to: r, withResult: .success)
            }
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers p: CBPeripheralManager) {
        flushNotify()
    }
}
