plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

subprojects {
    apply(
        plugin =
            rootProject.libs.plugins.ktlint
                .get()
                .pluginId,
    )
    apply(
        plugin =
            rootProject.libs.plugins.detekt
                .get()
                .pluginId,
    )

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(
            rootProject.libs.versions.ktlintTool
                .get(),
        )
        // Generated Compose sources are not ours to format.
        filter {
            exclude { it.file.path.contains("/build/") }
        }
    }

    configure<dev.detekt.gradle.extensions.DetektExtension> {
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
    }

    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        reports {
            html.required.set(true)
            sarif.required.set(true)
            // `xml` is `checkstyle` in 2.x and `txt` is gone entirely.
            checkstyle.required.set(false)
        }
    }
}

/** One command for everything CI checks, so `check` locally means the same thing. */
tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs ktlint, detekt and Android lint across every module."
    dependsOn(subprojects.map { "${it.path}:ktlintCheck" })

    // detektMain and detektTest, not the plain `detekt` task: detekt 2 wires type
    // resolution only into the per-source-set tasks, and the bare one reports nothing.
    dependsOn(":app:detektMain", ":app:detektTest")

    // Android lint too, so a green run here means the same as a green CI run.
    dependsOn(":app:lintDebug")
}
