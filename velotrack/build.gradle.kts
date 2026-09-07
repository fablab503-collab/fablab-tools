// Root build script. AGP 9.x compiles Kotlin itself; no Kotlin plugin is applied anywhere.
plugins {
    alias(libs.plugins.android.application) apply false
}
