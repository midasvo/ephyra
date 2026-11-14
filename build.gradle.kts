plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.androidx.room3) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.aboutlibraries.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktfmt) apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

subprojects {
    plugins.apply("com.ncorti.ktfmt.gradle")

    configure<com.ncorti.ktfmt.gradle.KtfmtExtension> {
        kotlinLangStyle()
    }

    // The formatter version, instead of the plugin's default (see libs.versions.toml). The plugin
    // adds its own version to the configuration, so this one is forced.
    val ktfmtFormatter = rootProject.libs.ktfmt.formatter.get().toString()
    configurations.named("ktfmt") { resolutionStrategy.force(ktfmtFormatter) }

    // ktfmtCheck once passed while checking no Kotlin file at all, only the build scripts: the
    // plugin found no source sets under AGP 9's built-in Kotlin (#661). This fails ktfmtCheck when
    // a .kt file under src/ is not in any of the plugin's source-set tasks.
    val ktfmtSourceSetChecks =
        tasks.withType<com.ncorti.ktfmt.gradle.tasks.KtfmtCheckTask>().matching {
            it.name != "ktfmtCheckScripts"
        }
    val ktfmtCheckCoverage =
        tasks.register("ktfmtCheckCoverage") {
            description = "Fails if ktfmtCheck leaves out a Kotlin file under src/."
            val kotlinFiles = fileTree("src") { include("**/*.kt") }
            val checkedFiles = files(provider { ktfmtSourceSetChecks.map { it.source } })
            val projectDir = layout.projectDirectory
            inputs.files(kotlinFiles)
            inputs.files(checkedFiles)
            doLast {
                val missed = kotlinFiles.files - checkedFiles.files
                if (missed.isNotEmpty()) {
                    val list =
                        missed.map { it.relativeTo(projectDir.asFile).invariantSeparatorsPath }.sorted()
                    throw GradleException(
                        "ktfmtCheck doesn't check ${missed.size} Kotlin file(s), such as:\n" +
                            list.take(10).joinToString("\n") { "  $it" }
                    )
                }
            }
        }
    tasks.named("ktfmtCheck") { dependsOn(ktfmtCheckCoverage) }
}

tasks.register<Delete>("clean") {
    description = "Deletes all build artifacts."
    delete(rootProject.layout.buildDirectory)
}
