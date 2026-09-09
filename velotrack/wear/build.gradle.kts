plugins {
    alias(libs.plugins.android.application)
}

// The watch app is a separate artifact under the same Play listing, not a library inside the phone
// APK. Play routes it to watches by the `android.hardware.type.watch` feature in its manifest.
//
// The application ID and signing certificate must match the phone app exactly, or the Data Layer
// silently refuses to deliver anything between them. That is why the debug build carries the same
// `.debug` suffix the phone uses: a debug watch pairs with a debug phone, a release watch with a
// release phone, and the two pairs never cross.
val keystoreFile: File? = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { file(it) }
val keystorePassword: String = System.getenv("KEYSTORE_PASSWORD") ?: ""
val keystoreAlias: String = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "velotrack"
val hasReleaseKey = keystoreFile?.exists() == true && keystorePassword.isNotEmpty()

val ciRunNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

/** Keeps every watch versionCode clear of the phone's, which counts up from the CI run number. */
val WEAR_VERSION_OFFSET = 1_000_000

android {
    namespace = "com.fablab503.velotrack.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fablab503.velotrack"
        // Wear OS 3. Earlier watches run a different, much older platform that this UI does not
        // target, and they are a vanishing share of the installed base.
        minSdk = 30
        targetSdk = 36
        // The watch shares an application ID with the phone, so the two artifacts must never carry
        // the same versionCode - Play rejects the upload. The offset keeps them ordered and makes
        // the run number still readable at a glance: run 47 becomes 1000047.
        versionCode = WEAR_VERSION_OFFSET + ciRunNumber
        versionName = "1.0.$ciRunNumber"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = keystoreFile!!
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystorePassword
                storeType = "pkcs12"
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // No shrinking here. The watch app is a few hundred kilobytes of layout and one
            // activity; R8 would buy nothing and could strip the Data Layer callbacks.
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = false
    }
}

// Same reason as the phone module: a green build otherwise proves only that the task ran, not
// which tests ran, and a test that stops being discovered looks exactly like a passing one.
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.kotlinx.coroutines.android)
    // repeatOnLifecycle, for collecting the ride state only while the screen is on.
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    // BoxInsetLayout: keeps content inside the square that fits within a round screen.
    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.ongoing)
    // The same ride engine the phone uses: same filtering, same statistics, same database.
    implementation(project(":core"))
    implementation(project(":sync"))

    testImplementation(libs.junit)
}
