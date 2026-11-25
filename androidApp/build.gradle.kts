import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.opaq"
    compileSdk = 37
    // The NDK isn't used to compile anything: AGP uses its tools to strip the native libraries and
    // to pull out their symbol tables for Play. Pinned so CI can install exactly this one
    // (play-bundle.yml reads it from here). Without it the libraries ship unstripped and Play
    // warns that the bundle has no native debug symbols.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        // The id the app is listed under on Google Play (the App Store uses the same one). The code
        // stays in the app.opaq package; only the installed id changes.
        applicationId = "me.opaq.app"
        minSdk = 26
        // Google Play needs at least 36 (Android 16) for new apps and updates.
        targetSdk = 36
        // CI builds count up, so each new APK installs over the previous one.
        // A Play bundle sets both (play-bundle.yml); test builds use the CI run number.
        versionCode = providers.environmentVariable("OPAQ_VERSION_CODE").orNull?.toIntOrNull()
            ?: providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("OPAQ_VERSION_NAME").orNull?.takeIf { it.isNotBlank() } ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // CI signs test builds with one long-lived key (kept in the Actions cache, never in the repo),
    // so a new APK can be installed over an older one. Local builds use the normal debug key.
    val ciKeystore = providers.environmentVariable("OPAQ_KEYSTORE").orNull?.let { file(it) }?.takeIf { it.exists() }
    // The Play upload key, only in the "Play bundle" workflow (from GitHub secrets, never in the
    // repo). Without it a release build is unsigned and Play won't take it.
    val uploadKeystore = providers.environmentVariable("OPAQ_UPLOAD_KEYSTORE").orNull?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = uploadKeystore
                storePassword = providers.environmentVariable("OPAQ_UPLOAD_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("OPAQ_UPLOAD_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("OPAQ_UPLOAD_KEY_PASSWORD").get()
            }
        }
        if (ciKeystore != null) {
            create("ci") {
                storeFile = ciKeystore
                storePassword = providers.environmentVariable("OPAQ_KEYSTORE_PASSWORD").get()
                keyAlias = "opaq-ci"
                keyPassword = providers.environmentVariable("OPAQ_KEYSTORE_PASSWORD").get()
            }
        }
    }
    val testSigning = if (ciKeystore != null) signingConfigs.getByName("ci") else signingConfigs.getByName("debug")

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = testSigning
        }
        // What goes to Google Play: R8-optimised, signed with the upload key when it's given.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (uploadKeystore != null) signingConfig = signingConfigs.getByName("upload")
            // Puts the native libraries' symbol tables in the bundle, so Play can show readable
            // native crashes and ANRs (MapLibre and secp256k1 ship native code).
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
        // Optimised like the release but signed with the test key, so it installs anywhere.
        // Compose is much smoother in this build than in debug.
        create("staging") {
            initWith(getByName("release"))
            signingConfig = testSigning
            applicationIdSuffix = ".staging"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":ui"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.zxing.android.embedded)
    implementation(libs.cmp.runtime)
    implementation(libs.cmp.ui)

    // UI smoke test that runs on an emulator in CI (see androidTest/).
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    // Store screenshots (StoreScreenshots.kt) write made-up saved state.
    androidTestImplementation(libs.kotlinx.serialization.json)
}
