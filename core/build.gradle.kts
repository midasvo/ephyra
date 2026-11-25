plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

composeCompiler {
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("compose_stability.conf")
    )
}

java { toolchain { languageVersion = Versions.JAVA } }

android {
    namespace = "dev.jdtech.jellyfin.core"
    compileSdk = Versions.COMPILE_SDK
    compileSdkMinor = Versions.COMPILE_SDK_MINOR
    buildToolsVersion = Versions.BUILD_TOOLS

    defaultConfig { minSdk = Versions.MIN_SDK }

    buildTypes {
        named("release") { isMinifyEnabled = false }
        register("staging") { initWith(getByName("release")) }
    }

    flavorDimensions += "variant"
    productFlavors { register("libre") }

    buildFeatures { compose = true }

    // CE-owned (#763): ServerSessionKeeperTest reads the sign-out toast, a string resource.
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(projects.application)
    implementation(projects.downloads)
    implementation(projects.domain)
    implementation(projects.player.core)
    implementation(projects.settings)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.paging)
    // #899: the tests build Room in memory; the production code goes through the store ports.
    testImplementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.work)
    implementation(libs.coil.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.media3.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.timber)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)

    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.junit)
    // #899 (R9): the tests build the real stores over Room; the production code cannot see data.
    testImplementation(projects.data)
    testImplementation(projects.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
