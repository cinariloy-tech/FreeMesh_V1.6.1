package com.freemesh.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.webkit.WebView;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.UUID;

/**
 * İki tarafı da yapar:
 *  - Merkez (central): yakındaki FreeMesh düğümünü/telefonunu bulup bağlanır (ESP32 veya başka telefon).
 *  - Sunucu (peripheral): başka telefonların bağlanabilmesi için bu telefonu yayınlar.
 * Mesajlar JSON satırı olarak \n ile biter. Tek bir aktif bağlantı üzerinden gönderilir.
 */
public class FreeMeshBle {
    static final UUID SVC  = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID RX   = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID TX   = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private final String myId;

    // Merkez rolü
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rxChar;
    private BluetoothLeScanner scanner;
    private boolean centralReady = false;
    private boolean scanning = false;
    private boolean writing = false;
    private int mtu = 23;
    private final ArrayDeque<byte[]> writeQ = new ArrayDeque<>();
    private final ByteArrayOutputStream inCentral = new ByteArrayOutputStream();

    // Sunucu rolü
    private BluetoothGattServer server;
    private BluetoothGattCharacteristic srvTx;
    private BluetoothDevice subscriber;
    private boolean notifying = false;
    private final ArrayDeque<byte[]> notifyQ = new ArrayDeque<>();
    private final ByteArrayOutputStream inServer = new ByteArrayOutputStream();
    private BluetoothLeAdvertiser advertiser;
    private final AdvertiseCallback advCb = new AdvertiseCallback() {};

    private volatile int lastRssi = -60;
    private boolean closed = false;
    private String lastStatus = "";

    FreeMeshBle(Context c, WebView w) {
        ctx = c;
        web = w;
        BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        myId = loadId(c);
    }

    // Telefonun kalıcı kimliği (ilk açılışta üretilir)
    private static String loadId(Context c) {
        SharedPreferences p = c.getSharedPreferences("freemesh", Context.MODE_PRIVATE);
        String id = p.getString("id", null);
        if (id == null) {
            String hex = "0123456789ABCDEF";
            Random r = new Random();
            StringBuilder sb = new StringBuilder("PH-");
            for (int i = 0; i < 6; i++) sb.append(hex.charAt(r.nextInt(16)));
            id = sb.toString();
            p.edit().putString("id", id).apply();
        }
        return id;
    }

    boolean isConnected() { return centralReady || subscriber != null; }

    private boolean has(String perm) { return ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }

    private boolean hasScanPerms() {
        if (Build.VERSION.SDK_INT >= 31)
            return has(Manifest.permission.BLUETOOTH_SCAN) && has(Manifest.permission.BLUETOOTH_CONNECT);
        return has(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private boolean hasAdvPerms() {
        if (Build.VERSION.SDK_INT >= 31)
            return has(Manifest.permission.BLUETOOTH_ADVERTISE) && has(Manifest.permission.BLUETOOTH_CONNECT);
        return true;
    }

    // ---------- Başlatma ----------
    synchronized void start() {
        if (closed || adapter == null) return;
        if (!adapter.isEnabled()) { status("bt_off"); retryLater(); return; }
        if (!hasScanPerms()) return;
        startServer();
        if (centralReady || scanning || gatt != null) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return;
        ScanFilter f = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SVC)).build();
        ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanning = true;
        status("scanning");
        scanner.startScan(Collections.singletonList(f), s, scanCb);
    }

    @SuppressLint("MissingPermission")
    private synchronized void startServer() {
        if (server != null || !hasAdvPerms()) return;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        server = bm.openGattServer(ctx, serverCb);
        if (server == null) return;

        BluetoothGattService svc = new BluetoothGattService(SVC, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic rx = new BluetoothGattCharacteristic(RX,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        srvTx = new BluetoothGattCharacteristic(TX,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ);
        srvTx.addDescriptor(new BluetoothGattDescriptor(CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        svc.addCharacteristic(rx);
        svc.addCharacteristic(srvTx);
        server.addService(svc);

        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser != null) {
            AdvertiseSettings st = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(new ParcelUuid(SVC))
                    .build();
            advertiser.startAdvertising(st, data, advCb);
        }
    }

    // ---------- Sunucu rolü: başka telefon bağlanır ----------
    private final BluetoothGattServerCallback serverCb = new BluetoothGattServerCallback() {
        @Override public void onConnectionStateChange(BluetoothDevice d, int st, int newState) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED && d.equals(subscriber)) {
                dropSubscriber();
            }
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice d, int reqId,
                BluetoothGattCharacteristic ch, boolean prep, boolean respNeeded, int offset, byte[] value) {
            if (RX.equals(ch.getUuid())) onBytes(value, inServer);
            if (respNeeded && server != null) server.sendResponse(d, reqId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice d, int reqId, BluetoothGattDescriptor desc,
                boolean prep, boolean respNeeded, int offset, byte[] value) {
            if (CCCD.equals(desc.getUuid())) {
                boolean on = value != null && value.length > 0 && (value[0] & 0x01) != 0;
                if (on) subscriber = d;
                else if (d.equals(subscriber)) dropSubscriber();
                updateReady();
            }
            if (respNeeded && server != null) server.sendResponse(d, reqId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }

        @Override public void onNotificationSent(BluetoothDevice d, int st) {
            synchronized (FreeMeshBle.this) { notifying = false; }
            flushNotify();
        }
    };

    private synchronized void dropSubscriber() {
        subscriber = null;
        notifyQ.clear();
        notifying = false;
        inServer.reset();
        updateReady();
    }

    // ---------- Merkez rolü: yakındaki cihazı bul ve bağlan ----------
    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (scanning && scanner != null && hasScanPerms()) scanner.stopScan(scanCb);
        scanning = false;
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            if (gatt != null || centralReady) return;
            stopScan();
            connect(r.getDevice());
        }
        @Override public void onScanFailed(int errorCode) {
            scanning = false;
            retryLater();
        }
    };

    @SuppressLint("MissingPermission")
    private void connect(BluetoothDevice d) {
        status("connecting");
        gatt = d.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.requestMtu(247)) g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cleanup(g);
            }
        }

        @SuppressLint("MissingPermission")
        @Override public void onMtuChanged(BluetoothGatt g, int newMtu, int st) {
            mtu = newMtu;
            g.discoverServices();
        }

        @SuppressLint("MissingPermission")
        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            BluetoothGattService svc = g.getService(SVC);
            if (svc == null) { g.disconnect(); return; }
            rxChar = svc.getCharacteristic(RX);
            BluetoothGattCharacteristic tx = svc.getCharacteristic(TX);
            if (rxChar == null || tx == null) { g.disconnect(); return; }
            g.setCharacteristicNotification(tx, true);
            BluetoothGattDescriptor d = tx.getDescriptor(CCCD);
            if (d != null) {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(d);
            } else {
                markReady();
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
            markReady();
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            onBytes(ch.getValue(), inCentral);
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int st) {
            writing = false;
            pumpWrites();
        }

        @Override public void onReadRemoteRssi(BluetoothGatt g, int rssi, int st) {
            if (st == BluetoothGatt.GATT_SUCCESS) lastRssi = rssi;
        }
    };

    private void markReady() {
        centralReady = true;
        updateReady();
        ui.removeCallbacks(rssiTick);
        ui.postDelayed(rssiTick, 3000);
    }

    private final Runnable rssiTick = new Runnable() {
        @SuppressLint("MissingPermission")
        @Override public void run() {
            if (gatt != null && centralReady) gatt.readRemoteRssi();
            ui.postDelayed(this, 3000);
        }
    };

    @SuppressLint("MissingPermission")
    private synchronized void cleanup(BluetoothGatt g) {
        centralReady = false;
        rxChar = null;
        writing = false;
        writeQ.clear();
        inCentral.reset();
        ui.removeCallbacks(rssiTick);
        try { g.close(); } catch (Exception ignored) { }
        gatt = null;
        updateReady();
        retryLater();
    }

    private void retryLater() {
        if (!closed) ui.postDelayed(this::start, 3000);
    }

    // ---------- Gelen veri ----------
    private void onBytes(byte[] v, ByteArrayOutputStream buf) {
        if (v == null) return;
        for (byte b : v) {
            if (b == '\n') {
                String line = new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
                buf.reset();
                if (!line.isEmpty()) deliver(line);
            } else {
                buf.write(b);
            }
        }
    }

    // Diğer telefondan gelen "tx" satırını web'in beklediği "rx" biçimine çevirir
    private void deliver(String line) {
        if (line.startsWith("{\"t\":\"tx\",")) {
            line = "{\"t\":\"rx\",\"rssi\":" + lastRssi + ",\"hop\":1," + line.substring(10);
        }
        js("window.onMeshRx && window.onMeshRx(" + JSONObject.quote(line) + ")");
    }

    // ---------- Giden veri ----------
    private String withFrom(String json) {
        int i = json.lastIndexOf('}');
        if (i < 0) return json;
        return json.substring(0, i) + ",\"from\":\"" + myId + "\"}";
    }

    synchronized void send(String json) {
        String line = withFrom(json);
        byte[] b = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (centralReady && rxChar != null) {
            int cs = Math.max(20, mtu - 3);
            for (int i = 0; i < b.length; i += cs)
                writeQ.add(Arrays.copyOfRange(b, i, Math.min(b.length, i + cs)));
            pumpWrites();
        } else if (subscriber != null && srvTx != null) {
            for (int i = 0; i < b.length; i += 20)
                notifyQ.add(Arrays.copyOfRange(b, i, Math.min(b.length, i + 20)));
            flushNotify();
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void pumpWrites() {
        if (writing || gatt == null || rxChar == null) return;
        byte[] c = writeQ.poll();
        if (c == null) return;
        writing = true;
        rxChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        rxChar.setValue(c);
        if (!gatt.writeCharacteristic(rxChar)) writing = false;
    }

    @SuppressLint("MissingPermission")
    private synchronized void flushNotify() {
        if (notifying || subscriber == null || srvTx == null || server == null) return;
        byte[] c = notifyQ.poll();
        if (c == null) return;
        notifying = true;
        srvTx.setValue(c);
        if (!server.notifyCharacteristicChanged(subscriber, srvTx, false)) notifying = false;
    }

    // ---------- Web ile iletişim ----------
    private void updateReady() {
        boolean ready = centralReady || subscriber != null;
        js("window.__fmReady = " + ready);
        status(ready ? "connected" : "disconnected");
    }

    private void status(String s) {
        if (s.equals(lastStatus)) return;
        lastStatus = s;
        js("window.onBleState && window.onBleState('" + s + "')");
    }

    private void js(String s) {
        ui.post(() -> web.evaluateJavaScript(s, null));
    }

    @SuppressLint("MissingPermission")
    void close() {
        closed = true;
        stopScan();
        ui.removeCallbacks(rssiTick);
        if (gatt != null) { try { gatt.close(); } catch (Exception ignored) { } gatt = null; }
        if (advertiser != null && hasAdvPerms()) { try { advertiser.stopAdvertising(advCb); } catch (Exception ignored) { } }
        if (server != null) { try { server.close(); } catch (Exception ignored) { } server = null; }
        centralReady = false;
    }
}
