# ESP32 Vision TTS

시각장애인의 주변 환경 인식을 지원하기 위한 AI 기반 보조 장치 프로젝트.

ESP32-S3 + OV5640으로 촬영한 사진을 안드로이드 폰으로 전송하고, 폰이 vision API로 분석한 뒤 그 결과를 기본 TTS로 읽어줍니다.

## 시스템 구성

```
[ESP32-S3 + OV5640]  --WiFi(폰 핫스팟)-->  [안드로이드 앱]  --HTTPS-->  [OpenAI Vision API]
     촬영 버튼                                  수신 · 분석 · TTS 재생
```

- 폰이 핫스팟을 켜면 ESP32가 핫스팟에 접속해 촬영 사진을 폰의 HTTP 서버로 POST 합니다.
- 앱은 사진을 받으면 OpenAI vision API(`gpt-4o-mini`)로 분석하고, 결과 텍스트를 안드로이드 기본 TTS(한국어)로 재생합니다.
- API 키는 앱 설정 화면에 입력하며 펌웨어에는 포함되지 않습니다.

## 하드웨어

- ESP32-S3 (PSRAM 8MB, 예: Freenove ESP32-S3-WROOM CAM 보드)
- OV5640 카메라 모듈
- 버튼 2개 (촬영 / 전송)
- 18650 배터리 + 충전 모듈 (선택)

핀맵은 `esp32_cam_sender/esp32_cam_sender.ino` 상단의 `*_GPIO_NUM` 정의를 참고하세요. 다른 보드를 쓰면 해당 보드의 핀맵으로 바꿔주면 됩니다.

### 배선 (터미널 버튼 2개 + LED 1개)

코드의 기본 핀 번호 (`CAPTURE_BUTTON` GPIO2, `ANALYZE_BUTTON` GPIO3, `STATUS_LED` GPIO4):

| 부품 | ESP32-S3 핀 | 배선 |
|---|---|---|
| 촬영 버튼 | GPIO 2 | 한쪽 다리 GPIO 2, 반대쪽 다리 GND (내부 풀업 사용, 저항 불필요) |
| 분석 버튼 | GPIO 3 | 한쪽 다리 GPIO 3, 반대쪽 다리 GND |
| LED + 저항 | GPIO 4 | GPIO 4 → 저항(220Ω~330Ω) → LED 애노드(긴 다리), LED 캐소드 → GND |

### LED 상태 표시

| LED 패턴 | 의미 |
|---|---|
| 빠른 깜빡임 (약 0.2초) | WiFi 연결 중 (또는 전송 중) |
| 느린 깜빡임 (약 1초) | 사진 촬영됨 — 분석 버튼 대기 중 |
| 3회 짧게 깜빡임 | 폰으로 전송 성공 (또는 사진 없이 분석 버튼 누름) |
| 5회 빠른 깜빡임 | 전송 실패 (WiFi/서버 문제) |
| 아주 빠른 깜빡임 (지속) | 카메라 초기화 실패 |
| 꺼짐 | 대기 상태 (촬영된 사진 없음) |

## ESP32 펌웨어

Arduino IDE에서:

1. 보드 매니저로 `esp32` 설치
2. `esp32_cam_sender/esp32_cam_sender.ino` 열기
3. 상단 설정 수정:
   - `WIFI_SSID` / `WIFI_PASS`: 폰 핫스팟 이름/비밀번호
   - `PHONE_IP`: 폰의 핫스팟 IP (안드로이드 핫스팟 게이트웨이, 보통 `192.168.x.1` — 앱에서도 확인 가능)
   - `PHONE_PORT`: 기본 `8080`
4. 업로드 후 시리얼 모니터(115200)로 동작 확인

동작:
- 촬영 버튼(`CAPTURE_BUTTON`) → 촬영 후 내부 저장
- 분석 버튼(`ANALYZE_BUTTON`) → 저장된 사진을 폰으로 전송

## 안드로이드 앱

Kotlin + Jetpack Compose 기반이며 [Android Studio](https://developer.android.com/studio)로 빌드합니다.

1. Android Studio에서 이 저장소의 `app/` 프로젝트 열기
2. Gradle 동기화 (래퍼가 없으면 `gradle wrapper` 또는 Android Studio 안내에 따라 진행)
3. 실행(Run) 후 앱에서 설정:
   - OpenAI API 키 입력
   - 포트(기본 8080)와 프롬프트 확인
   - "받으면 자동 분석" 토글 켜기 (ESP32의 분석 버튼 없이 사진이 오면 바로 분석)
   - "전송 대기 시작" 눌러 폰에서 HTTP 서버 구동
4. 폰 핫스팟을 켜고 ESP32를 켜면 자동 연결됩니다

앱 UI:
- 서버 상태 및 포트 안내
- 받은 사진 미리보기
- 분석 결과 텍스트 + 자동 TTS 재생 (다시 듣기 / 재생 중지 버튼)

## 보안 / 운영 팁

- API 키는 SharedPreferences에 저장되며 펌웨어에는 들어가지 않습니다.
- 프로덕션 배포 시에는 직접 api.openai.com을 호출하는 대신, 키를 숨긴 프록시(Cloudflare Worker 등)를 경유하는 구조를 권장합니다.
- 폰 핫스팟 게이트웨이 IP는 기기/버전에 따라 다를 수 있으니 연결이 안 되면 앱의 서버 상태와 핫스팟 설정의 IP를 확인하세요.
