plugins {
    alias(libs.plugins.android.application) // built-in Kotlin (AGP 9); no org.jetbrains.kotlin.android
}

// Signing: release builds are signed with the PKCS12 keystore named by KEYSTORE_FILE, using
// KEYSTORE_PASSWORD and KEY_ALIAS from the environment; CI decodes it from a repository secret.
// Without those variables the release APK falls back to the debug signature (fine for a local
// sideload, but it will not install over a copy signed with the release key).
val keystoreFile: File? = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { file(it) }
val keystorePassword: String = System.getenv("KEYSTORE_PASSWORD") ?: ""
val keystoreAlias: String = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "velotrack"
val hasReleaseKey = keystoreFile?.exists() == true && keystorePassword.isNotEmpty()

// versionCode comes from the GitHub Actions run number so every CI build is installable over the previous one.
val ciRunNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.fablab503.velotrack"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fablab503.velotrack"
        minSdk = 26
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
                keyPassword = keystorePassword // PKCS12: key password must equal store password
                storeType = "pkcs12"
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }

    packaging {
        // The glyph PBF files are already compressed; keep them stored to speed up asset reads.
        resources.excludes += setOf("META-INF/*.version", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Name every test in the CI log. Without this a green build only proves the task ran, not which
// tests ran, and a test that silently stops being discovered looks exactly like a passing one.
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maplibre.opengl)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
