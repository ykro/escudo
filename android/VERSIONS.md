# Versions (checked 2026-09-30)

Latest stable versions unless noted. Checked against Google Maven / Maven Central and the
official release notes on the date above.

| Component | Version | Notes |
| --- | --- | --- |
| Android Gradle Plugin | 9.4.1 | Built-in Kotlin (no `kotlin-android` plugin) |
| Gradle | 9.8.0 | AGP 9.4 needs ≥ 9.6.0 |
| Kotlin (compose compiler + serialization plugins) | 2.4.20 | |
| KSP | 2.3.12 | Runs the AppFunctions compiler |
| JDK | 17 | `kotlin { jvmToolchain(17) }` |
| compileSdk / targetSdk / minSdk | 37 / 37 / 31 | AppFunctions docs require compileSdk ≥ 36; service is `@RequiresApi(36)` |
| **androidx.appfunctions** (`appfunctions`, `appfunctions-compiler`) | **1.0.0-alpha12** (2026-09-23) | Preview API. No stable/beta exists. `appfunctions-service` is no longer needed (last 1.0.0-alpha09) |
| androidx.biometric | 1.1.0 | Latest stable (1.4.0-alpha07 adds Compose/ActivityResult APIs; not used) |
| androidx.fragment-ktx | 1.9.1 | Pinned: biometric 1.1.0 pulls fragment 1.2.5, which crashes modern ActivityResult launchers |
| Compose BoM | 2026.09.00 | Material 3 |
| androidx.core-ktx | 1.19.1 | |
| androidx.activity-compose | 1.13.0 | |
| androidx.lifecycle | 2.11.0 | |
| androidx.navigation-compose | 2.10.2 | Type-safe routes |
| Firebase BoM | 34.19.0 | `firebase-auth`, `firebase-messaging` |
| google-services plugin | 4.5.0 | Applied only when `app/google-services.json` exists |
| kotlinx-coroutines | 1.11.0 | incl. `-play-services` for `Task.await()` |
| kotlinx-serialization-json | 1.11.0 | |
| OkHttp / MockWebServer3 | 5.5.0 | REST + WebSocket |
| JUnit | 4.13.2 | |

## Platform / tooling

| Item | Value |
| --- | --- |
| `adb shell cmd app_function` | Available from Android 16 QPR2 (API 36.1). Not on the API 36.0 image (r7). |
| Emulator for AppFunctions | `Pixel_9_API_36_1`, `system-images;android-36.1;google_apis_playstore;arm64-v8a` r4 |
| Emulator for UI smoke tests | `Pixel_9_API_36` (API 36.0) |
| Gemini + third-party AppFunctions | Private preview / Early Access Program only (per developer.android.com/ai/appfunctions); not available to this app |

## Design assets

| Asset | Version / source |
| --- | --- |
| Onest | Variable TTF from google/fonts `ofl/onest` (SIL OFL 1.1), bundled as `res/font/onest.ttf` |
| Direction | «A · Sereno» tokens (`design/a-sereno/tokens.json`), logo mark redrawn as vectors (`design/a-sereno/mark.svg`) |

## Asistente Escudo

Typed chat over OkHttp WebSocket (`/assistant/chat`); no audio APIs.
