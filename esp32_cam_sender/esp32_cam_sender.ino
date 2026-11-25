#include "esp_camera.h"
#include "WiFi.h"
#include <HTTPClient.h>

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

const char* WIFI_SSID = "YOUR_PHONE_HOTSPOT_SSID";
const char* WIFI_PASS = "YOUR_PHONE_HOTSPOT_PASSWORD";
const char* PHONE_IP  = "192.168.43.1";
const int   PHONE_PORT = 8080;

camera_fb_t* storedFrame = nullptr;
bool cameraReady = false;

enum LedMode {
  LED_OFF,       
  LED_SLOW,      
  LED_FAST,      
  LED_ON,        
  LED_ERROR      
};

LedMode ledMode = LED_FAST;

void setup() {
  Serial.begin(115200);

  pinMode(CAPTURE_BUTTON, INPUT_PULLUP);
  pinMode(ANALYZE_BUTTON, INPUT_PULLUP);
  pinMode(STATUS_LED, OUTPUT);
  digitalWrite(STATUS_LED, LOW);

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

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  ledMode = LED_FAST;
  Serial.print("connecting wifi");
  while (WiFi.status() != WL_CONNECTED) {
    updateLed();
    delay(50);
    Serial.print(".");
  }
  Serial.println("\nwifi connected");
  ledMode = LED_OFF;
}

void loop() {
  updateLed();

  if (!cameraReady) {
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
    blinkError(3);
    return;
  }
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("wifi disconnected");
    blinkError(3);
    return;
  }

  LedMode previousMode = ledMode;
  ledMode = LED_FAST;

  HTTPClient http;
  String url = String("http://") + PHONE_IP + ":" + String(PHONE_PORT) + "/capture";
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
