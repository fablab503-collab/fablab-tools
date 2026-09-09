// Root build script. AGP 9.x compiles Kotlin itself; no Kotlin plugin is applied anywhere.
plugins {
    alias(libs.plugins.android.application) apply false
    // Declared here so :sync can apply it without a version. Naming the version in the module too
    // fails: AGP is already on the classpath from the application plugin, and Gradle cannot check
    // compatibility against a classpath entry of unknown version.
    alias(libs.plugins.android.library) apply false
}
