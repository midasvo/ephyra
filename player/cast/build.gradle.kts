plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
}

java { toolchain { languageVersion = Versions.JAVA } }

android {
    namespace = "dev.jdtech.jellyfin.player.cast"
    compileSdk = Versions.COMPILE_SDK

    defaultConfig { minSdk = Versions.MIN_SDK }

    buildTypes {
        named("release") { isMinifyEnabled = false }
        register("staging") { initWith(getByName("release")) }
    }

    flavorDimensions += "variant"
    productFlavors {
        register("libre") {
            dimension = "variant"
            isDefault = true
        }
        register("proprietary") {
            dimension = "variant"
            matchingFallbacks += listOf("libre")
        }
    }
}

dependencies {
    implementation(projects.core)
    implementation(projects.data)
    implementation(projects.player.core)
    implementation(projects.player.local)
    implementation(projects.settings)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.jellyfin.core)
    implementation(libs.timber)

    // Only the proprietary flavor has Google Cast; libre gets none of it.
    "proprietaryImplementation"(libs.androidx.mediarouter)
    "proprietaryImplementation"(libs.play.services.cast.framework)
}
