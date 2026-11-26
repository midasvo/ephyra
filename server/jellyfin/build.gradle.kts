// The Jellyfin adapter (#758, docs/architecture/3-target.md): the only module that declares the
// Jellyfin SDK. It implements the ports of `domain` and carries its own Hilt module. Only the two
// app modules depend on it (a rule in the architecture module); its code depends on neither `data`
// nor `core`.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

java { toolchain { languageVersion = Versions.JAVA } }

android {
    namespace = "dev.jdtech.jellyfin.server.jellyfin"
    compileSdk = Versions.COMPILE_SDK
    compileSdkMinor = Versions.COMPILE_SDK_MINOR
    buildToolsVersion = Versions.BUILD_TOOLS

    defaultConfig {
        minSdk = Versions.MIN_SDK

        consumerProguardFile("proguard-rules.pro")
    }

    // The build types of the apps: JellyfinApi suffixes the device id with BUILD_TYPE unless it is
    // `release`, so the staging beta and the release app do not log each other out.
    buildTypes {
        named("release") { isMinifyEnabled = false }
        register("staging") { initWith(getByName("release")) }
    }

    buildFeatures { buildConfig = true }

    // The image mapper tests need android.net.Uri, which Robolectric provides on the JVM.
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

// CE (#739): `-Dgolden.update=true` on the Gradle command line reaches the test JVM, so
// ItemMapperGoldenTest can write its expected files instead of comparing with them.
tasks.withType<Test>().configureEach {
    systemProperty("golden.update", System.getProperty("golden.update") ?: "")
    // #771: the contract tests run against the Docker test server when EPHYRA_TEST_SERVER is set,
    // and `-Dcontract.record=true` writes its answers as their recordings. The environment is no
    // input of the task by itself: without these, a run with the server would be up to date.
    systemProperty("contract.record", System.getProperty("contract.record") ?: "")
    systemProperty(
        "contract.resources",
        layout.projectDirectory.dir("src/test/resources/contract").asFile.absolutePath,
    )
    listOf("EPHYRA_TEST_SERVER", "EPHYRA_TEST_USER", "EPHYRA_TEST_PASSWORD").forEach {
        // A hash: the password is not kept in the build's task history.
        inputs.property(it, System.getenv(it).orEmpty().hashCode())
    }
}

dependencies {
    // #918: the ports the adapter reads its settings through (NetworkTimeouts), bound in core.
    implementation(projects.application)
    implementation(projects.domain)
    // #964: MediaHttpClient, the API's client handed to the player.
    implementation(projects.http)
    implementation(libs.coil.core)
    implementation(libs.coil.network.cache.control)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.jellyfin.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.timber)

    // The characterization tests of #738 run the real repository over this adapter and a stand-in
    // server, so they pin what goes over the wire end to end. Tests only: the adapter's own code
    // cannot see `data`.
    testImplementation(projects.data)
    // #965: the tests build the device profile from the real codec probe (DeviceCodecProbe) under
    // Robolectric, as the app does. Tests only: the adapter's code sees only DeviceDecoders.
    testImplementation(projects.device)
    // StandInServer builds the real AppPreferences; production code reads settings through ports
    // (R18).
    testImplementation(projects.settings)
    // #771: the contract suites the adapter passes.
    testImplementation(projects.testing)
    testImplementation(libs.androidx.room3.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // #761: the SDK's server discovery logs through SLF4J, which the app gets from core.
    testImplementation(libs.slf4j.api)
}
