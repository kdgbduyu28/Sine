import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: keystore.properties (local, gitignored) or SINE_KEYSTORE_* environment variables (CI).
val keystore = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun signingValue(key: String, env: String): String? = keystore.getProperty(key) ?: System.getenv(env)

// CI passes -PsineVersion=1.2.3 from the tag; local builds stay 0.1.0.
val sineVersion = (findProperty("sineVersion") as String?)?.removePrefix("v") ?: "0.1.0"
fun versionCodeFor(version: String): Int {
    val (major, minor, patch) = (version.split('-')[0].split('.') + listOf("0", "0", "0")).take(3).map { it.toIntOrNull() ?: 0 }
    return major * 10_000 + minor * 100 + patch
}
val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)

android {
    namespace = "com.sine.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sine.player"
        minSdk = 24
        targetSdk = 35
        versionCode = versionCodeFor(sineVersion)
        versionName = sineVersion
        buildConfigField("String", "UPDATE_REPO", "\"kdgbduyu28/Sine\"")
    }

    signingConfigs {
        val path = signingValue("storeFile", "SINE_KEYSTORE_FILE")
        if (path != null) {
            create("release") {
                storeFile = rootProject.file(path)
                storePassword = signingValue("storePassword", "SINE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SINE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SINE_KEY_PASSWORD")
            }
        }
    }

    // One APK per CPU type (mpv and FFmpeg make a universal APK ~200 MB), plus a universal one.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release keystore, fall back to the debug key so local release builds still install.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        // Media3's @UnstableApi is used throughout the player on purpose.
        disable += "UnsafeOptInUsageError"
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

// Per-ABI version codes (base * 10 + ABI) so the TV always gets the build for its CPU over the universal one.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier
            output.versionCode.set(versionCodeFor(sineVersion) * 10 + (abiCodes[abi] ?: 0))
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.tv.material)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.ffmpeg)
    implementation(libs.media3.session)
    implementation(libs.coil.compose)
    implementation(libs.coil.core)
    implementation(libs.work.runtime)
    implementation(libs.tvprovider)
    implementation(libs.mlkit.translate)

    implementation(libs.smbj)
    implementation(libs.libmpv)
    implementation(libs.okhttp)
    implementation(libs.jsch)
    implementation(libs.nfs.client)

    testImplementation(libs.junit)
    // Android's org.json is a stub in local unit tests; the real one lets parsers be tested.
    testImplementation(libs.org.json)
}
