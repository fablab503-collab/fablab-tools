plugins {
    alias(libs.plugins.android.library)
}

// The ride engine, shared by the phone (:app) and the watch (:wear).
//
// Everything here is deliberately free of UI: no activity, no layout, no theme. That is what lets a
// watch record a ride with exactly the same filtering, statistics and storage as the handset,
// rather than a second implementation that would slowly drift out of agreement with it.
//
// Files were moved here from :app keeping their original package names, so no import anywhere
// changed. The one exception is the R class: gradle.properties sets android.nonTransitiveRClass,
// so each module's R holds only its own resources, and RecordingService now imports
// com.fablab503.velotrack.core.R for the notification icon and strings that moved with it.
android {
    namespace = "com.fablab503.velotrack.core"
    compileSdk = 36

    defaultConfig {
        // The phone's floor. The watch is higher (30) and can depend on a lower-minSdk library.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Name every test in the CI log, for the same reason :app does: a green build otherwise proves only
// that the task ran, not which tests ran.
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // Real org.json, shadowing the android.jar stub that returns null under
    // unitTests.isReturnDefaultValues. Without it a parsing test passes by asserting nothing.
    testImplementation(libs.json)
}
