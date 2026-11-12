plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

java { toolchain { languageVersion = Versions.JAVA } }

android {
    namespace = "dev.jdtech.jellyfin.baselineprofile.tv"
    compileSdk = Versions.COMPILE_SDK
    buildToolsVersion = Versions.BUILD_TOOLS

    defaultConfig {
        minSdk = 28
        targetSdk = Versions.TARGET_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app:tv"

    // Must match app/tv's only flavor.
    flavorDimensions += "variant"
    productFlavors { register("libre") { dimension = "variant" } }
}

// The journey needs a logged-in Android TV device, so it always runs on the connected one (Gradle
// managed devices are phone images). See README.md.
baselineProfile { useConnectedDevices = true }

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.junit)
}
