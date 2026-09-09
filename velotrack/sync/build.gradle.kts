plugins {
    alias(libs.plugins.android.library)
}

// The wire format shared by the phone and the watch. Both modules depend on this one so a field
// can never be renamed on one side only: the Data Layer matches on plain string keys, and a
// mismatch is silent - the watch simply shows nothing, with no error anywhere.
android {
    namespace = "com.fablab503.velotrack.sync"
    compileSdk = 36

    defaultConfig {
        // The watch module needs Wear OS 3 (API 30); the phone module goes back to 26. This is the
        // lower of the two so both can depend on it.
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
