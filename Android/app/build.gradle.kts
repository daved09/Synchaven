plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The app version comes from version.properties in the project root (like backend and Docker image).
// versionCode is derived from it: 1.2.3 → 10203, so every higher version installs as an update.
val appVersion: String = rootProject.file("../version.properties").readLines()
    .firstNotNullOfOrNull { Regex("""^\s*version\s*[=:]\s*(.*?)\s*$""").find(it)?.groupValues?.get(1) }
    ?.takeIf { it.isNotEmpty() }
    ?: error("No 'version=…' entry in version.properties")
val appVersionCode: Int = run {
    val parts = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(appVersion)?.destructured?.toList()
        ?.map { it.toInt() }
        ?: error("Version '$appVersion' must start with MAJOR.MINOR.PATCH")
    require(parts[1] < 100 && parts[2] < 100) { "MINOR and PATCH must be below 100 (version $appVersion)" }
    parts[0] * 10000 + parts[1] * 100 + parts[2]
}

// Release signing from environment variables (used by the GitHub release workflow). If they are not set,
// the release build stays unsigned as before, e.g. for signing in Android Studio.
val releaseKeystore: String? = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull

android {
    namespace = "de.dave.synchaven"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "de.dave.synchaven"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// The release APK is called SyncHaven.apk instead of app-release.apk.
@Suppress("UnstableApiUsage")
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { it.outputFileName.set("SyncHaven.apk") }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.material.kolor)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
