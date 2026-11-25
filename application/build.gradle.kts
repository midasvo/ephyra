// The application layer (#896, #966, #967, ADR 0002 decision 1, docs/architecture/5-hexagonal-ddd.md T3):
// the repository roles (`ItemCatalog`, `ItemLists`, `PlaybackMedia`, …), the store ports, the event
// flows, and `MediaRepositoryImpl` itself (which implements the repository roles over `domain`'s
// server ports and the store ports). Pure Kotlin like domain: no Android, no Room, no Jellyfin SDK.
// Rule R5 and R19 in the architecture module keep it that way; R9 keeps the features off `data`,
// which implements the store ports.
plugins { alias(libs.plugins.kotlin.jvm) }

java { toolchain { languageVersion = Versions.JAVA } }

dependencies {
    implementation(projects.domain)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    // #966: the fakes of the server and the stores, for the repository's tests.
    testImplementation(projects.testing)
    testImplementation(libs.kotlinx.serialization.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }

// CI and CLAUDE.md run `./gradlew testDebugUnitTest`. A JVM module has only `test`, so this name
// runs it too.
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs the application tests (the test task)."
    dependsOn(tasks.test)
}
