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

    packaging {
        jniLibs {
            // onyxsdk-pen ships its own libc++_shared.so.
            pickFirsts += "**/libc++_shared.so"
            // Native libs stay compressed-off and extracted the classic way, matching what the
            // Onyx samples ship with.
            useLegacyPackaging = true
        }
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

    // Onyx pen SDK, vendored (see README "Onyx SDK dependencies"). AARs as local files carry no
    // POM, so nothing transitive is pulled in.
    implementation(files("libs/onyxsdk-pen-1.4.12.aar"))
    implementation(files("libs/onyxsdk-base-1.7.7.aar"))
    implementation(files("libs/onyxsdk-device-1.2.31.aar"))
    implementation(libs.hiddenapibypass) { isTransitive = false }
    // RxJava stays transitive: it needs org.reactivestreams, which resolves cleanly.
    implementation(libs.rxjava)
    implementation(libs.rxandroid)

    testImplementation(libs.junit)
}
