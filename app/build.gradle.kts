import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// CI passes these as environment variables. Local builds fall back to sensible defaults.
val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val ciVersionName = System.getenv("VERSION_NAME")?.removePrefix("v") ?: "0.0.0-dev"
val keystorePath: String? = System.getenv("KEYSTORE_PATH")
val hasReleaseKey = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "app.hachure"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.hachure"
        minSdk = 30
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = ciVersionName
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off: the Onyx SDK (Milestone 2) relies on reflection and hidden APIs,
            // and the app is small enough that shrinking buys little.
            isMinifyEnabled = false
            // Without the real key (forks, local builds) sign with the debug key so the APK
            // still installs. Such a build cannot update one signed with the release key.
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit)
}
