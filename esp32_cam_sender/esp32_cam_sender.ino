#include "esp_camera.h"
#include "WiFi.h"
#include <HTTPClient.h>
#include <Preferences.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#define PWDN_GPIO_NUM     -1
#define RESET_GPIO_NUM    -1
#define XCLK_GPIO_NUM     10
#define SIOD_GPIO_NUM     40
#define SIOC_GPIO_NUM     39
#define Y9_GPIO_NUM       48
#define Y8_GPIO_NUM       11
#define Y7_GPIO_NUM       12
#define Y6_GPIO_NUM       14
#define Y5_GPIO_NUM       16
#define Y4_GPIO_NUM       18
#define Y3_GPIO_NUM       17
#define Y2_GPIO_NUM       15
#define VSYNC_GPIO_NUM    38
#define HREF_GPIO_NUM     47
#define PCLK_GPIO_NUM     34

#define CAPTURE_BUTTON    2
#define ANALYZE_BUTTON    3
#define STATUS_LED        4

#define BLE_SERVICE_UUID "ffe0"
#define BLE_CHAR_UUID    "ffe1"

const int   WIFI_TIMEOUT_MS = 20000;

Preferences prefs;
camera_fb_t* storedFrame = nullptr;
bool cameraReady = false;
bool bleRunning = false;
volatile bool connectRequested = false;

enum LedMode {
  LED_OFF,
  LED_SLOW,
  LED_FAST,
  LED_ON,
  LED_ERROR
};

LedMode ledMode = LED_FAST;

class WifiCharCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* characteristic) {
    String payload = String((const char*)characteristic->getData(), characteristic->getLength());
    Serial.printf("ble write: %s\n", payload.c_str());

    String ssid, pass, ip, portStr;
    if (!parseWifiPayload(payload, ssid, pass, ip, portStr)) {
      Serial.println("ble payload parse failed");
      return;
    }

    prefs.putString("ssid", ssid);
    prefs.putString("pass", pass);
    if (ip.length() > 0) prefs.putString("ip", ip);
    if (portStr.length() > 0) prefs.putString("port", portStr);

    characteristic->setValue((uint8_t*)"OK\n", 3);
    characteristic->notify();

    connectRequested = true;
  }

  bool parseWifiPayload(const String& payload, String& ssid, String& pass, String& ip, String& port) {
    int start = 0;
    while (start < payload.length()) {
      int lineEnd = payload.indexOf('\n', start);
      if (lineEnd < 0) lineEnd = payload.length();
      String line = payload.substring(start, lineEnd);
      start = lineEnd + 1;

      int colon = line.indexOf(':');
      if (colon <= 0) continue;
      String key = line.substring(0, colon);
      String value = line.substring(colon + 1);
      value.trim();

      if (key == "SSID") ssid = value;
      else if (key == "PASS") pass = value;
      else if (key == "IP") ip = value;
      else if (key == "PORT") port = value;
    }
    return ssid.length() > 0 && pass.length() > 0;
  }
};

void updateLed();
void waitForRelease(int pin);
void captureFrame();
void sendStoredFrame();

bool getStoredWifi(String& ssid, String& pass) {
  ssid = prefs.getString("ssid", "");
  pass = prefs.getString("pass", "");
  return ssid.length() > 0;
}

bool connectWifi() {
  String ssid, pass;
  if (!getStoredWifi(ssid, pass)) return false;

  ledMode = LED_FAST;
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid.c_str(), pass.c_str());

  uint32_t started = millis();
  while (WiFi.status() != WL_CONNECTED) {
    updateLed();
    delay(50);
    if (millis() - started > WIFI_TIMEOUT_MS) {
      return false;
    }
  }
  Serial.println("wifi connected");
  return true;
}

void stopBle() {
  if (!bleRunning) return;
  BLEDevice::deinit();
  bleRunning = false;
}

void startBleProvisioning() {
  if (bleRunning) return;
  Serial.println("ble provisioning mode: send WiFi settings from the phone app");
  ledMode = LED_ERROR;

  BLEDevice::init("ESP32-CAM-Setup");
  BLEServer* server = BLEDevice::createServer();
  BLEService* service = server->createService(BLEUUID(BLE_SERVICE_UUID));

  BLECharacteristic* characteristic = service->createCharacteristic(
    BLEUUID(BLE_CHAR_UUID),
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_NOTIFY
  );
  characteristic->setCallbacks(new WifiCharCallbacks());
  characteristic->addDescriptor(new BLE2902());

  service->start();
  BLEAdvertising* advertising = server->getAdvertising();
  advertising->addServiceUUID(BLEUUID(BLE_SERVICE_UUID));
  advertising->start();
  bleRunning = true;
}

void setup() {
  Serial.begin(115200);

  pinMode(CAPTURE_BUTTON, INPUT_PULLUP);
  pinMode(ANALYZE_BUTTON, INPUT_PULLUP);
  pinMode(STATUS_LED, OUTPUT);
  digitalWrite(STATUS_LED, LOW);

  prefs.begin("device", false);

  camera_config_t config = {};
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = Y2_GPIO_NUM;
  config.pin_d1 = Y3_GPIO_NUM;
  config.pin_d2 = Y4_GPIO_NUM;
  config.pin_d3 = Y5_GPIO_NUM;
  config.pin_d4 = Y6_GPIO_NUM;
  config.pin_d5 = Y7_GPIO_NUM;
  config.pin_d6 = Y8_GPIO_NUM;
  config.pin_d7 = Y9_GPIO_NUM;
  config.pin_xclk = XCLK_GPIO_NUM;
  config.pin_pclk = PCLK_GPIO_NUM;
  config.pin_vsync = VSYNC_GPIO_NUM;
  config.pin_href = HREF_GPIO_NUM;
  config.pin_sccb_sda = SIOD_GPIO_NUM;
  config.pin_sccb_scl = SIOC_GPIO_NUM;
  config.pin_pwdn = PWDN_GPIO_NUM;
  config.pin_reset = RESET_GPIO_NUM;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.frame_size = FRAMESIZE_VGA;
  config.jpeg_quality = 12;
  config.fb_count = 2;
  config.fb_location = CAMERA_FB_IN_PSRAM;
  config.grab_mode = CAMERA_GRAB_LATEST;

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("camera init failed: 0x%x\n", err);
    ledMode = LED_ERROR;
    return;
  }

  sensor_t* s = esp_camera_sensor_get();
  s->set_vflip(s, 1);
  s->set_hmirror(s, 1);
  cameraReady = true;

  if (!connectWifi()) {
    startBleProvisioning();
  } else {
    ledMode = LED_OFF;
  }
}

void loop() {
  updateLed();

  if (connectRequested) {
    connectRequested = false;
    stopBle();
    if (!connectWifi()) {
      startBleProvisioning();
    } else {
      ledMode = LED_OFF;
    }
    return;
  }

  if (!cameraReady || bleRunning) {
    return;
  }

  if (digitalRead(CAPTURE_BUTTON) == LOW) {
    captureFrame();
    waitForRelease(CAPTURE_BUTTON);
  }

  if (digitalRead(ANALYZE_BUTTON) == LOW) {
    sendStoredFrame();
    waitForRelease(ANALYZE_BUTTON);
  }

  delay(20);
}

void waitForRelease(int pin) {
  while (digitalRead(pin) == LOW) {
    updateLed();
    delay(10);
  }
  delay(50);
}

void captureFrame() {
  if (storedFrame) {
    esp_camera_fb_return(storedFrame);
    storedFrame = nullptr;
  }
  camera_fb_t* fb = esp_camera_fb_get();
  if (!fb) {
    Serial.println("capture failed");
    return;
  }
  storedFrame = fb;
  ledMode = LED_SLOW;
  Serial.printf("captured: %u bytes\n", fb->len);
}

void sendStoredFrame() {
  if (!storedFrame) {
    Serial.println("no stored frame");
    return;
  }
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("wifi disconnected");
    blinkError(3);
    return;
  }

  String phoneIp = prefs.getString("ip", "192.168.43.1");
  String phonePort = prefs.getString("port", "8080");

  LedMode previousMode = ledMode;
  ledMode = LED_FAST;

  HTTPClient http;
  String url = String("http://") + phoneIp + ":" + phonePort + "/capture";
  if (!http.begin(url)) {
    Serial.println("http begin failed");
    blinkError(5);
    ledMode = previousMode;
    return;
  }
  http.setTimeout(20000);
  http.addHeader("Content-Type", "image/jpeg");
  int code = http.POST(storedFrame->buf, storedFrame->len);
  http.end();

  Serial.printf("post result: %d\n", code);
  if (code == 200) {
    flashLed(3, 120);
    ledMode = LED_OFF;
  } else if (code < 0) {
    blinkError(5);
    ledMode = LED_SLOW;
  } else {
    blinkError(3);
    ledMode = LED_SLOW;
  }
}

void updateLed() {
  static uint32_t lastToggle = 0;
  static bool on = false;

  switch (ledMode) {
    case LED_ON:
      digitalWrite(STATUS_LED, HIGH);
      return;
    case LED_OFF:
      digitalWrite(STATUS_LED, LOW);
      return;
    default:
      break;
  }

  uint32_t interval = (ledMode == LED_SLOW) ? 900UL
                    : (ledMode == LED_FAST) ? 180UL
                    : 80UL;
  uint32_t now = millis();
  if (now - lastToggle >= interval) {
    lastToggle = now;
    on = !on;
    digitalWrite(STATUS_LED, on ? HIGH : LOW);
  }
}

void blinkError(int times) {
  digitalWrite(STATUS_LED, LOW);
  delay(150);
  for (int i = 0; i < times; i++) {
    digitalWrite(STATUS_LED, HIGH);
    delay(120);
    digitalWrite(STATUS_LED, LOW);
    delay(120);
  }
}

void flashLed(int times, int ms) {
  digitalWrite(STATUS_LED, LOW);
  delay(ms);
  for (int i = 0; i < times; i++) {
    digitalWrite(STATUS_LED, HIGH);
    delay(ms);
    digitalWrite(STATUS_LED, LOW);
    delay(ms);
  }
}
