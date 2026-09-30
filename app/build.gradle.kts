import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/**
 * Local signing credentials, absent on CI and on a fresh clone.
 *
 * The file is gitignored and points at a keystore stored outside the repository. Android
 * identifies an installed app by applicationId plus signing certificate, so losing this
 * key means the app can never be updated in place again - and an uninstall signs you out
 * of Instagram. See docs/INSTALLING.md.
 */
val keystoreProperties =
    rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }
    }

plugins {
    // AGP 9 applies the Kotlin Android plugin itself; applying it here as well fails.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.instachat.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.instachat.app"
        minSdk = 26
        targetSdk = 37
        // Bump on anything that gets installed on a real phone. The app is sideloaded, so
        // the version is the only thing that says which build is installed.
        versionCode = 3
        versionName = "0.3"
        resValue("string", "app_name", "Insta Chat")
    }

    signingConfigs {
        // Only declared when the credentials are present. On CI and on a fresh clone the
        // release build simply comes out unsigned, which is correct: an unsigned artifact
        // is obviously unusable, whereas one silently signed with the debug key looks fine
        // and then cannot be updated by a real release later.
        keystoreProperties?.let { props ->
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // A separate application id, so a debug build can never collide with the
            // release install and force an uninstall. It also gets its own WebView
            // profile, so it can be logged in to a second account for testing.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Insta Chat (debug)")
        }
        release {
            signingConfig = signingConfigs.findByName("release")

            // Left off for now: an unminified release is one fewer variable if a
            // release-only failure ever appears. The app is a WebView and a worker, so
            // there is little code for R8 to remove anyway.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // AGP 9 turns custom resource values off by default. app_name is declared per build
        // type so the debug install is labelled distinctly on the launcher.
        resValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        // A personal app with no release train, so a warning that never gets triaged is
        // just noise. Fail the build instead.
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        disable +=
            setOf(
                // Version bumps are Dependabot's job, not a build failure's.
                "GradleDependency",
                "NewerVersionAvailable",
                "AndroidGradlePluginVersion",
            )
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

// AGP 9 dropped the `kotlinOptions` block in favour of the Kotlin plugin's own DSL.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    implementation(libs.kotlinx.coroutines.android)

    // The periodic inbox check behind the new-message notification.
    implementation(libs.androidx.work.runtime.ktx)

    // Plain OkHttp: the worker makes one GET against the web inbox endpoint, which is not
    // a REST API a declarative client would fit.
    implementation(libs.okhttp)

    // Only the JsonElement tree API, to read the inbox response. No serialization plugin:
    // the response is undocumented and large, and binding it to classes would turn every
    // field Instagram renames into a parse failure rather than one missing value.
    implementation(platform(libs.kotlinx.serialization.bom))
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Exercises the wire handling over a real socket, without touching Instagram.
    testImplementation(libs.okhttp.mockwebserver)
}
