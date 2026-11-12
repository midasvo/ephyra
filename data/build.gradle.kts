plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.androidx.room3)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

java { toolchain { languageVersion = Versions.JAVA } }

android {
    namespace = "dev.jdtech.jellyfin.data"
    compileSdk = Versions.COMPILE_SDK
    buildToolsVersion = Versions.BUILD_TOOLS

    defaultConfig {
        minSdk = Versions.MIN_SDK

        buildConfigField("int", "VERSION_CODE", Versions.APP_CODE.toString())
        buildConfigField("String", "VERSION_NAME", "\"${Versions.APP_NAME}\"")

        consumerProguardFile("proguard-rules.pro")
    }

    buildTypes {
        named("release") { isMinifyEnabled = false }
        register("staging") { initWith(getByName("release")) }
    }

    buildFeatures { buildConfig = true }

    // CE-owned: the image mapper tests need android.net.Uri, which Robolectric provides on the JVM.
    testOptions { unitTests { isIncludeAndroidResources = true } }

    // CE (#380): MigrationTestHelper reads the exported schemas from the test assets.
    sourceSets { named("test") { assets.directories.add("$projectDir/schemas") } }
}

room3 { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(projects.settings)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.room3.runtime)
    ksp(libs.androidx.room3.compiler)
    implementation(libs.jellyfin.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.timber)
    testImplementation(libs.androidx.room3.testing)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
