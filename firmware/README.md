# Free Mesh düğüm yazılımı (ESP32-WROOM-32U)

1. Arduino IDE'ye ESP32 kartlarını ekle (Boards Manager: "esp32" by Espressif, sürüm 3.x).
2. Kart: "ESP32 Dev Module". Port: USB portu.
3. `freemesh_node/freemesh_node.ino` dosyasını aç ve yükle.
4. Her düğüm kendi adını (`FreeMesh-XXXX`, MAC'ten türetilir) Bluetooth'ta yayınlar. Üç kartın üçüne de aynı kodu yükleyebilirsin.

Güvenlik: Pilleri takmadan önce anteni bağla. USB ile programlarken pilleri çıkar. Pil kutbunu kontrol et (negatif uca yay tarafı).

Not: Bu yazılım henüz gerçek donanımda test edilmedi. Derleme sırasında `esp_wifi_config_80211_tx_rate` hatası alırsan, IDF sürümüne göre `esp_wifi_config_espnow_rate` kullan.
