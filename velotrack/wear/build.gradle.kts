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

android {
    namespace = "com.fablab503.velotrack.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fablab503.velotrack"
        // Wear OS 3. Earlier watches run a different, much older platform that this UI does not
        // target, and they are a vanishing share of the installed base.
        minSdk = 30
        targetSdk = 36
        versionCode = ciRunNumber
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
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    // BoxInsetLayout: keeps content inside the square that fits within a round screen.
    implementation(libs.androidx.wear)
    implementation(project(":sync"))

    testImplementation(libs.junit)
}
