#ifndef BOARD_CONFIG_H
#define BOARD_CONFIG_H

//
// WARNING!!! PSRAM IC required for UXGA resolution and high JPEG quality
//            Ensure ESP32 Wrover Module or other board with PSRAM is selected
//            Partial images will be transmitted if image exceeds buffer size
//
//            You must select partition scheme from the board menu that has at least 3MB APP space.

// ============================================================
// Camera model selection from TestESP32.ino device profile.
// Define exactly one profile there:
// - DEVICE_PROFILE_CAM -> AI-Thinker ESP32-CAM
// - DEVICE_PROFILE_S3  -> ESP32-S3 camera module
// ============================================================
#include "device_profile.h"

#if defined(DEVICE_PROFILE_CAM) && defined(DEVICE_PROFILE_S3)
#error "Select only one device profile."
#endif

#if defined(DEVICE_PROFILE_CAM)
#define CAMERA_MODEL_AI_THINKER // Has PSRAM
#elif defined(DEVICE_PROFILE_S3)
#define CAMERA_MODEL_ESP32S3_EYE // Has PSRAM
#else
#error "No device profile selected. Choose one in TestESP32.ino."
#endif

#include "camera_pins.h"

#endif  // BOARD_CONFIG_H
