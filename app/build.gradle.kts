import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

// The published APK is signed with the project's private key, which exists only in the
// repository's Actions secrets. Without it (a fork, a local build) Android's own throwaway debug
// key signs the build; such a build cannot be installed over the published one.
val signingKeystore: String? = System.getenv("SIGNING_KEYSTORE_FILE")

android {
  namespace = "com.keonyrus.tupovision"
  compileSdk = 36

  defaultConfig {
    applicationId = "fr.urvoi.lunettesclaude"
    minSdk = 31
    targetSdk = 36
    versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
    versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")

    // Meta Wearables Device Access Toolkit. With Developer Mode switched on in the Meta AI app the
    // SDK skips attestation, so both values stay "0". A published build needs the real values from
    // the Wearables Developer Center.
    manifestPlaceholders["mwdat_application_id"] = "0"
    manifestPlaceholders["mwdat_client_token"] = "0"
  }

  signingConfigs {
    if (signingKeystore != null) {
      create("project") {
        storeFile = file(signingKeystore)
        storeType = "pkcs12"
        storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "lunettesclaude"
        keyPassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
      }
    }
  }

  buildTypes {
    debug {
      if (signingKeystore != null) signingConfig = signingConfigs.getByName("project")
    }
    release {
      isMinifyEnabled = false
      signingConfig =
          signingConfigs.getByName(if (signingKeystore != null) "project" else "debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.material3)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.browser)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.okhttp)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
}
