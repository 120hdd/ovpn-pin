plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Go core, built by mobile/build-android.sh: pin, probe, race, DoH and the
// tunnel, for every phone CPU. Not in the repo - any checkout rebuilds it.
val relayAar = file("libs/relay.aar")

// The server installer the tunnel pane hands out as one pasteable line. It
// lives in tunnel/ for the desktop; copied in here at build time rather than
// committed twice.
val serverInstaller = rootProject.file("../tunnel/install-server.sh")
val generatedAssets = layout.buildDirectory.dir("generated/relay-assets")

val copyInstaller by tasks.registering(Copy::class) {
    from(serverInstaller)
    into(generatedAssets)
}

android {
    namespace = "com.erelay.relay"
    compileSdk = 36
    // The same NDK mobile/build-android.sh builds the core with. The app
    // compiles no C of its own; AGP only insists that one be named.
    ndkVersion = "27.3.13750724"

    defaultConfig {
        // The same id as the Flutter build, so this installs over it and
        // keeps its accounts, configs and VPN consent.
        applicationId = "com.erelay.relay"
        // 24: VpnService.Builder.setMetered wants it.
        minSdk = 24
        targetSdk = 36
        // Above the Flutter build's 1, or Android refuses the upgrade.
        versionCode = 2
        versionName = "2.0.0"
    }

    // Android identifies an app by the key that signed it, so a release has
    // to be signed by the same key every time. Same variables as the Flutter
    // build; without them it falls back to the debug key and says so.
    val keystore = System.getenv("RELAY_KEYSTORE")
    val keystorePassword = System.getenv("RELAY_KEYSTORE_PASSWORD")
    val signed = !keystore.isNullOrEmpty() && !keystorePassword.isNullOrEmpty() &&
        file(keystore).exists()

    signingConfigs {
        if (signed) {
            create("release") {
                storeFile = file(keystore!!)
                storePassword = keystorePassword
                keyAlias = System.getenv("RELAY_KEY_ALIAS") ?: "relay"
                keyPassword = System.getenv("RELAY_KEY_PASSWORD") ?: keystorePassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (signed) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets["main"].assets.directories.add(generatedAssets.get().asFile.path)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

tasks.named("preBuild") {
    dependsOn(copyInstaller)
    doFirst {
        if (!relayAar.exists()) {
            throw GradleException(
                "android/app/libs/relay.aar is missing. Build the Go core first:\n" +
                    "    mobile/build-android.sh\n" +
                    "It writes the .aar there as well as into mobile/.")
        }
    }
}

dependencies {
    implementation(files(relayAar))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)

    // Where account passwords live: a keystore-held key, so run-as on this
    // debuggable build cannot read them out of shared_prefs.
    implementation(libs.security.crypto)

    testImplementation(libs.junit)
    // Android's org.json is a stub on the JVM; the tests need the real one.
    testImplementation(libs.json)
}
