import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

// google-services.json is not committed (see README.md). Without it the app still builds and
// runs against the fake API; Firebase Auth and FCM are simply unavailable.
val hasGoogleServices = file("google-services.json").exists()
if (hasGoogleServices) {
  apply(plugin = libs.plugins.google.services.get().pluginId)
} else {
  logger.warn("app/google-services.json not found: building without Firebase (run `make google-services`).")
}

// Local, never-committed configuration: API URL and the seeded customer's credentials.
val localProps = Properties().apply {
  val f = rootProject.file("local.properties")
  if (f.exists()) f.inputStream().use { load(it) }
}
fun localOrEnv(key: String, env: String): String =
  (localProps.getProperty(key) ?: System.getenv(env) ?: "").trim()
fun String.quoted() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
  namespace = "app.escudo.android"
  compileSdk = 37

  defaultConfig {
    applicationId = "app.escudo.android"
    minSdk = 31
    targetSdk = 37
    versionCode = 1
    versionName = "0.1.0"

    buildConfigField("String", "ESCUDO_API_URL", localOrEnv("escudo.apiUrl", "ESCUDO_API_URL").quoted())
    buildConfigField("String", "CUSTOMER_EMAIL", localOrEnv("escudo.customerEmail", "ESCUDO_CUSTOMER_EMAIL").quoted())
    buildConfigField("String", "CUSTOMER_PASSWORD", localOrEnv("escudo.customerPassword", "ESCUDO_CUSTOMER_PASSWORD").quoted())
    buildConfigField("boolean", "HAS_FIREBASE", hasGoogleServices.toString())
    // Only for a local backend started with MOCK_AUTH=1 (`make up-mock`): skip Firebase sign-in.
    buildConfigField("boolean", "MOCK_AUTH", (localOrEnv("escudo.mockAuth", "ESCUDO_MOCK_AUTH") == "true").toString())
  }

  // "fake" talks to an in-memory API (no network, no Firebase needed) for development and
  // rehearsals; "live" talks to the escudo-api Cloud Run service at ESCUDO_API_URL.
  flavorDimensions += "api"
  productFlavors {
    create("live") {
      dimension = "api"
      buildConfigField("boolean", "USE_FAKE_API", "false")
    }
    create("fake") {
      dimension = "api"
      buildConfigField("boolean", "USE_FAKE_API", "true")
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { jvmToolchain(17) }

// Aggregate every @AppFunction into the generated service + XML metadata.
ksp { arg("appfunctions:aggregateAppFunctions", "true") }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.core)
  debugImplementation(libs.androidx.compose.ui.tooling)

  implementation(libs.androidx.biometric)
  // biometric 1.1.0 (latest stable) drags in fragment 1.2.x, whose FragmentActivity rejects the
  // request codes of modern ActivityResult launchers (photo picker crash). Pin a current fragment.
  implementation(libs.androidx.fragment.ktx)

  implementation(libs.androidx.appfunctions)
  ksp(libs.androidx.appfunctions.compiler)

  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.auth)
  implementation(libs.firebase.messaging)

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okhttp)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)
}

// With product flavors AGP has no plain `testDebugUnitTest`; keep that name working
// (`./gradlew assembleDebug testDebugUnitTest`) by running the unit tests of both flavors.
tasks.register("testDebugUnitTest") {
  group = "verification"
  description = "Runs debug unit tests for the fake and live flavors."
  dependsOn("testFakeDebugUnitTest", "testLiveDebugUnitTest")
}
