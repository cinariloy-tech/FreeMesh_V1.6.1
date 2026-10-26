// Free Mesh düğüm yazılımı — ESP32-WROOM-32U
// Arduino IDE: Board = "ESP32 Dev Module", Arduino ESP32 core 3.x
// Telefon ile BLE (Nordic UART benzeri) konuşur, düğümler arası ESP-NOW ile flood-relay yapar.
// Telefon JSON satırı yollar (\n ile biter), düğüm JSON satırı geri bildirir.

#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <string.h>

#define WIFI_CHANNEL 1
#define MAX_TTL 5
#define BODY_MAX 180
#define HELLO_MS 5000
#define SEEN_N 48

#define SVC_UUID "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define RX_UUID  "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  // telefon -> düğüm (write)
#define TX_UUID  "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  // düğüm -> telefon (notify)

enum : uint8_t { T_HELLO = 1, T_MSG = 2, T_CALL_REQ = 3, T_CALL_ACC = 4, T_CALL_END = 5, T_PTT = 6 };
static const uint8_t MAGIC = 0xF3;

struct __attribute__((packed)) Pkt {
  uint8_t magic;
  uint8_t type;
  uint32_t id;
  uint8_t ttl;
  uint8_t src[6];
  uint8_t dst[6];
  uint8_t len;
  char body[BODY_MAX];
};

static uint8_t BCAST[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
static uint8_t myMac[6];
static String devName;
static uint32_t seen[SEEN_N];
static uint8_t seenIdx = 0;
static BLECharacteristic* txChar = nullptr;
static volatile bool bleConnected = false;
static String rxBuf;
static uint32_t lastHello = 0;

String macStr(const uint8_t* m) {
  char b[18];
  sprintf(b, "%02X:%02X:%02X:%02X:%02X:%02X", m[0], m[1], m[2], m[3], m[4], m[5]);
  return String(b);
}

bool parseMac(const String& s, uint8_t* out) {
  if (s.length() != 17) return false;
  for (int i = 0; i < 6; i++) out[i] = (uint8_t)strtol(s.substring(i * 3, i * 3 + 2).c_str(), nullptr, 16);
  return true;
}

String jsonEsc(const String& s) {
  String o;
  for (size_t i = 0; i < s.length(); i++) {
    char c = s[i];
    if (c == '"' || c == '\\') { o += '\\'; o += c; }
    else if (c == '\n') o += "\\n";
    else if (c == '\r') o += "\\r";
    else if (c == '\t') o += "\\t";
    else if ((uint8_t)c >= 0x20) o += c;
  }
  return o;
}

String jget(const String& s, const char* key) {
  String pat = String("\"") + key + "\":\"";
  int i = s.indexOf(pat);
  if (i < 0) return "";
  i += pat.length();
  String out;
  while (i < (int)s.length()) {
    char c = s[i++];
    if (c == '\\' && i < (int)s.length()) { out += s[i++]; continue; }
    if (c == '"') break;
    out += c;
  }
  return out;
}

bool isSeen(uint32_t id) {
  for (int i = 0; i < SEEN_N; i++) if (seen[i] == id) return true;
  return false;
}
void markSeen(uint32_t id) {
  seen[seenIdx] = id;
  seenIdx = (seenIdx + 1) % SEEN_N;
}

void bleSend(const String& line) {
  if (!bleConnected || !txChar) return;
  String s = line + "\n";
  for (int i = 0; i < (int)s.length(); i += 20) {
    int n = min(20, (int)s.length() - i);
    txChar->setValue((uint8_t*)s.c_str() + i, n);
    txChar->notify();
    delay(8);
  }
}

void toPhone(const Pkt& p, int rssi, int hop) {
  String k;
  switch (p.type) {
    case T_HELLO: k = "hello"; break;
    case T_MSG: k = "msg"; break;
    case T_CALL_REQ: k = "call_req"; break;
    case T_CALL_ACC: k = "call_acc"; break;
    case T_CALL_END: k = "call_end"; break;
    case T_PTT: k = "ptt"; break;
    default: return;
  }
  String body;
  for (int i = 0; i < p.len && i < BODY_MAX; i++) body += p.body[i];
  String line = "{\"t\":\"rx\",\"k\":\"" + k + "\",\"from\":\"" + macStr(p.src) +
                "\",\"id\":" + String(p.id) + ",\"rssi\":" + String(rssi) +
                ",\"hop\":" + String(hop) + ",\"text\":\"" + jsonEsc(body) + "\"}";
  bleSend(line);
}

void onRecv(const esp_now_recv_info_t* info, const uint8_t* data, int len) {
  if (len < (int)offsetof(Pkt, body)) return;
  Pkt p;
  memcpy(&p, data, min(len, (int)sizeof(Pkt)));
  if (p.magic != MAGIC) return;
  if (memcmp(p.src, myMac, 6) == 0) return;
  if (isSeen(p.id)) return;
  markSeen(p.id);

  int rssi = info->rx_ctrl->rssi;
  int hop = MAX_TTL - p.ttl + 1;
  bool forMe = memcmp(p.dst, myMac, 6) == 0 || memcmp(p.dst, BCAST, 6) == 0;
  if (forMe) toPhone(p, rssi, hop);

  if (p.ttl > 1) {               // başkasına ait paketi bir adım ileri taşı
    Pkt r = p;
    r.ttl--;
    esp_now_send(BCAST, (uint8_t*)&r, sizeof(Pkt));
  }
}

void sendSelfToPhone() {
  bleSend("{\"t\":\"self\",\"mac\":\"" + macStr(myMac) + "\",\"name\":\"" + devName + "\"}");
}

void sendHello() {
  Pkt p;
  memset(&p, 0, sizeof(p));
  p.magic = MAGIC;
  p.type = T_HELLO;
  p.id = esp_random() | 1;
  p.ttl = MAX_TTL;
  memcpy(p.src, myMac, 6);
  memcpy(p.dst, BCAST, 6);
  p.len = min((int)devName.length(), BODY_MAX);
  memcpy(p.body, devName.c_str(), p.len);
  markSeen(p.id);
  esp_now_send(BCAST, (uint8_t*)&p, sizeof(Pkt));
}

// Telefondan gelen bir JSON satırını ESP-NOW paketine çevirir
void handleLine(const String& line) {
  String k = jget(line, "k");
  uint8_t type = 0;
  if (k == "msg") type = T_MSG;
  else if (k == "call_req") type = T_CALL_REQ;
  else if (k == "call_acc") type = T_CALL_ACC;
  else if (k == "call_end") type = T_CALL_END;
  else if (k == "ptt") type = T_PTT;
  if (!type) return;

  Pkt p;
  memset(&p, 0, sizeof(p));
  p.magic = MAGIC;
  p.type = type;
  p.id = esp_random() | 1;
  p.ttl = MAX_TTL;
  memcpy(p.src, myMac, 6);
  String to = jget(line, "to");
  if (!parseMac(to, p.dst)) memcpy(p.dst, BCAST, 6);
  String text = jget(line, "text");
  p.len = min((int)text.length(), BODY_MAX);
  memcpy(p.body, text.c_str(), p.len);
  markSeen(p.id);
  esp_now_send(BCAST, (uint8_t*)&p, sizeof(Pkt));
  bleSend("{\"t\":\"ack\",\"id\":" + String(p.id) + "}");
}

class ServerCb : public BLEServerCallbacks {
  void onConnect(BLEServer*) override {
    bleConnected = true;
    rxBuf = "";
  }
  void onDisconnect(BLEServer*) override {
    bleConnected = false;
    BLEDevice::getAdvertising()->start();
  }
};

class RxCb : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    String v = c->getValue().c_str();
    for (int i = 0; i < (int)v.length(); i++) {
      char ch = v[i];
      if (ch == '\n') { handleLine(rxBuf); rxBuf = ""; }
      else rxBuf += ch;
    }
  }
};

void setup() {
  Serial.begin(115200);

  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  esp_wifi_set_channel(WIFI_CHANNEL, WIFI_SECOND_CHAN_NONE);
  WiFi.setTxPower(WIFI_POWER_19_5dBm);
  // 1 Mbps: menzil için. Derleme hata verirse esp_wifi_config_espnow_rate ile değiştir (IDF sürümüne bağlı).
  esp_wifi_config_80211_tx_rate(WIFI_IF_STA, WIFI_PHY_RATE_1M_L);

  esp_wifi_get_mac(WIFI_IF_STA, myMac);
  String tail = macStr(myMac).substring(12);
  tail.replace(":", "");
  devName = "FreeMesh-" + tail;

  if (esp_now_init() != ESP_OK) { Serial.println("ESP-NOW init hatası"); return; }
  esp_now_register_recv_cb(onRecv);
  esp_now_peer_info_t peer;
  memset(&peer, 0, sizeof(peer));
  memcpy(peer.peer_addr, BCAST, 6);
  peer.channel = WIFI_CHANNEL;
  peer.encrypt = false;
  esp_now_add_peer(&peer);

  BLEDevice::init(devName.c_str());
  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new ServerCb());
  BLEService* svc = server->createService(SVC_UUID);

  txChar = svc->createCharacteristic(TX_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  txChar->addDescriptor(new BLE2902());

  BLECharacteristic* rx = svc->createCharacteristic(RX_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rx->setCallbacks(new RxCb());

  svc->start();
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SVC_UUID);
  adv->setScanResponse(true);
  adv->start();

  Serial.println("FreeMesh düğümü hazır: " + devName + " " + macStr(myMac));
}

void loop() {
  if (millis() - lastHello > HELLO_MS) {
    lastHello = millis();
    sendHello();
    if (bleConnected) sendSelfToPhone();
  }
  delay(10);
}
