# Escudo for Android. Run from escudo/android (or via the repo-root Makefile).
#   make build                 assemble debug APKs (fake + live flavors)
#   make test                  unit tests
#   make install [FLAVOR=live] install on the connected device/emulator and launch
#   make google-services       download app/google-services.json from Firebase
#   make appfunctions          list Escudo's AppFunctions on the device (Android 16 QPR2+)

GRADLE  ?= ./gradlew --console=plain
FLAVOR  ?= live
ADB     ?= $(shell command -v adb 2>/dev/null || echo $(HOME)/Library/Android/sdk/platform-tools/adb)
PACKAGE := app.escudo.android
FIREBASE_PROJECT ?= ai-experiments-487722
FIREBASE_APP_ID  ?= 1:611681112050:android:cecc13cdf49afe50f6e56b

FLAVOR_CAP := $(shell echo $(FLAVOR) | awk '{print toupper(substr($$0,1,1)) substr($$0,2)}')

.PHONY: build test install google-services appfunctions clean

build:
	$(GRADLE) assembleDebug

test:
	$(GRADLE) testDebugUnitTest

install:
	$(GRADLE) install$(FLAVOR_CAP)Debug
	$(ADB) shell am start -n $(PACKAGE)/.MainActivity

google-services:
	firebase apps:sdkconfig ANDROID $(FIREBASE_APP_ID) --project $(FIREBASE_PROJECT) -o app/google-services.json

appfunctions:
	$(ADB) shell cmd app_function list-app-functions | grep -A 3 '$(PACKAGE)'

clean:
	$(GRADLE) clean
