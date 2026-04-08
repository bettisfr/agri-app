#include "esp_camera.h"

// ESP32-S3 camera model selection (pick one if needed).
// #define CAMERA_MODEL_ESP32S3_EYE
#define CAMERA_MODEL_ESP32S3_CAM_LCD
// #define CAMERA_MODEL_XIAO_ESP32S3

#include "../esp32-cam/camera_pins.h"

static sensor_t *g_sensor = nullptr;

static bool apply_control(const String &name, int val) {
  if (g_sensor == nullptr) return false;
  if (name == "hmirror") return g_sensor->set_hmirror(g_sensor, val) == 0;
  if (name == "vflip") return g_sensor->set_vflip(g_sensor, val) == 0;
  if (name == "brightness") return g_sensor->set_brightness(g_sensor, val) == 0;
  if (name == "contrast") return g_sensor->set_contrast(g_sensor, val) == 0;
  if (name == "saturation") return g_sensor->set_saturation(g_sensor, val) == 0;
  if (name == "sharpness") return g_sensor->set_sharpness(g_sensor, val) == 0;
  if (name == "quality") return g_sensor->set_quality(g_sensor, val) == 0;
  if (name == "framesize") return g_sensor->set_framesize(g_sensor, (framesize_t)val) == 0;
  return false;
}

static void handle_line_command(String line) {
  line.trim();
  if (line.length() == 0) return;

  String lower = line;
  lower.toLowerCase();

  if (lower.startsWith("set ")) {
    int eq = line.indexOf('=');
    if (eq > 4) {
      String key = line.substring(4, eq);
      String val_str = line.substring(eq + 1);
      key.trim();
      val_str.trim();
      key.toLowerCase();
      int val = val_str.toInt();
      if (apply_control(key, val)) {
        Serial.printf("OK set %s=%d\n", key.c_str(), val);
      } else {
        Serial.printf("ERR set %s=%d\n", key.c_str(), val);
      }
      return;
    }
    Serial.println("ERR set syntax");
    return;
  }

  if (lower == "status") {
    Serial.println("OK status serial-cam-ready");
    return;
  }

  Serial.println("ERR unknown");
}

static bool init_camera() {
  camera_config_t config;
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
  config.frame_size = FRAMESIZE_QXGA;   // 2048x1536
  config.jpeg_quality = 10;
  config.fb_count = psramFound() ? 2 : 1;
  config.grab_mode = CAMERA_GRAB_LATEST;
  config.fb_location = psramFound() ? CAMERA_FB_IN_PSRAM : CAMERA_FB_IN_DRAM;

  if (!psramFound()) {
    Serial.println("CAM_INIT_FAIL NOPSRAM");
    return false;
  }

  esp_err_t err = esp_camera_init(&config);
  if (err != ESP_OK) {
    Serial.printf("CAM_INIT_FAIL 0x%x\n", err);
    return false;
  }

  sensor_t *s = esp_camera_sensor_get();
  g_sensor = s;
  if (s != nullptr) {
    s->set_framesize(s, config.frame_size);
    s->set_quality(s, config.jpeg_quality);
    s->set_hmirror(s, 0);
    s->set_vflip(s, 0);
    s->set_brightness(s, 0);
    s->set_contrast(s, 0);
    s->set_saturation(s, 0);
    s->set_sharpness(s, 1);
    s->set_special_effect(s, 0);
    s->set_whitebal(s, 1);
    s->set_awb_gain(s, 1);
    s->set_wb_mode(s, 0);
  }
  return true;
}

static bool send_one_capture() {
  camera_fb_t *fb = esp_camera_fb_get();
  if (!fb || !fb->buf || fb->len == 0) {
    Serial.println("CAPTURE_FAIL");
    if (fb) esp_camera_fb_return(fb);
    return false;
  }

  Serial.printf("BEGIN %u\n", (unsigned int)fb->len);
  Serial.write(fb->buf, fb->len);
  Serial.flush();
  esp_camera_fb_return(fb);
  Serial.println("CAPTURE_OK");
  return true;
}

void setup() {
  Serial.begin(115200);
  Serial.setDebugOutput(false);
  delay(200);

  if (!init_camera()) {
    return;
  }

  sensor_t *s = esp_camera_sensor_get();
  if (s) {
    Serial.printf("SENSOR_PID 0x%04x\n", s->id.PID);
  }
  Serial.println("SERIAL_CAM_READY");
}

void loop() {
  while (Serial.available() > 0) {
    int ch = Serial.read();
    if (ch == 'c' || ch == 'C') {
      send_one_capture();
    } else if (ch == '\n' || ch == '\r') {
      // ignore
    } else {
      String line;
      line += (char)ch;
      line += Serial.readStringUntil('\n');
      handle_line_command(line);
    }
  }
  delay(20);
}
