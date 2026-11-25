// The download engine, the queue and its scheduling (#901, docs/architecture/5-hexagonal-ddd.md
// T9): out of `core`, over the store and repository roles of `application` (`DownloadList`,
// `DownloadSources`, `ItemCatalog`, `PlaybackMedia`, …; #967, #1006). It does
// not depend on `data`, `server/jellyfin`, the apps or `core` (rule R15 in the architecture
// module): `core` depends on it, and gives it the texts of its resources through `DownloadTexts`.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

java { toolchain { languageVersion = Versions.JAVA } }

android {
    namespace = "dev.jdtech.jellyfin.downloads"
    compileSdk = Versions.COMPILE_SDK
    compileSdkMinor = Versions.COMPILE_SDK_MINOR
    buildToolsVersion = Versions.BUILD_TOOLS

    defaultConfig { minSdk = Versions.MIN_SDK }

    buildTypes {
        named("release") { isMinifyEnabled = false }
        register("staging") { initWith(getByName("release")) }
    }

    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(projects.application)
    implementation(projects.domain)
    // UiText (and nothing of AppPreferences: rule R17).
    implementation(projects.settings)
    implementation(libs.androidx.core)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.work)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.timber)

    testImplementation(libs.junit)
    // The tests build the real stores over Room; the production code cannot see data (R9, R15).
    testImplementation(projects.data)
    testImplementation(projects.testing)
    testImplementation(libs.androidx.room3.runtime)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
