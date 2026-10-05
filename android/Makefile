# Escudo for Android. Run from escudo/android (or via the repo-root Makefile).
#   make build                 assemble debug APKs (fake + live flavors)
#   make test                  unit tests (offline)
#   make chat-scenario         full chat scenario on the emulator (report → 3 fingerprint approvals → signed), timed
#   make install [FLAVOR=live] install on the connected device/emulator and launch
#   make google-services       download app/google-services.json from Firebase
#   make appfunctions          list Escudo's AppFunctions on the device (Android 16 QPR2+)
#   make serial                adb serial of the running $(ANDROID_AVD) emulator
#   make prep                  leave the emulator ready for the talk (PIN, app, permissions, photos, fingerprint, token)

GRADLE  ?= ./gradlew --console=plain
FLAVOR  ?= live
ADB     ?= $(shell command -v adb 2>/dev/null || echo $(HOME)/Library/Android/sdk/platform-tools/adb)
PACKAGE := app.escudo.android
FIREBASE_PROJECT ?= ai-experiments-487722
FIREBASE_APP_ID  ?= 1:611681112050:android:cecc13cdf49afe50f6e56b

# Every target talks to the running emulator of this AVD (or ANDROID_SERIAL if you set it);
# adb and Gradle's connected tasks both honor ANDROID_SERIAL.
ANDROID_AVD ?= Pixel_9_API_36_1
export ANDROID_AVD
ANDROID_SERIAL ?= $(shell ADB=$(ADB) scripts/emulator_serial.sh $(ANDROID_AVD) 2>/dev/null)
export ANDROID_SERIAL

FLAVOR_CAP := $(shell echo $(FLAVOR) | awk '{print toupper(substr($$0,1,1)) substr($$0,2)}')

.PHONY: build test chat-scenario install google-services appfunctions serial prep clean

build:
	$(GRADLE) assembleDebug

test:
	$(GRADLE) testDebugUnitTest

# Typed-chat scenario on the emulator against the cloud, event-driven; prints the real time.
# Reset the backend first: `make reset CLOUD=1` at the repo root.
chat-scenario:
	ADB=$(ADB) scripts/chat_scenario.sh

install:
	$(GRADLE) install$(FLAVOR_CAP)Debug
	$(ADB) shell am start -n $(PACKAGE)/.MainActivity

google-services:
	firebase apps:sdkconfig ANDROID $(FIREBASE_APP_ID) --project $(FIREBASE_PROJECT) -o app/google-services.json

appfunctions:
	@$(ADB) shell cmd app_function list-app-functions | grep -o '"$(PACKAGE).appfunctions[^"]*#[^"]*"' | sort -u

serial:
	@ADB=$(ADB) scripts/emulator_serial.sh $(ANDROID_AVD)

prep:
	ADB=$(ADB) scripts/prep_emulator.sh

clean:
	$(GRADLE) clean
