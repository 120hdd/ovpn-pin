plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.erelay.relay"
    compileSdk = flutter.compileSdkVersion
    // Pinned rather than flutter.ndkVersion, which asks for whichever NDK
    // that Flutter release was built against and downloads it - 800 MB over
    // a line that this repo exists because it drops downloads. This app
    // compiles no C of its own: the .so files come prebuilt inside relay.aar,
    // and the NDK is here only because AGP insists one be named.
    //
    // It is the same NDK build-android.sh uses, which is the point - one
    // toolchain, named in two places that have to agree.
    ndkVersion = "27.3.13750724"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.erelay.relay"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        // 24 rather than Flutter's default: VpnService.Builder.setMetered
        // wants it, and nothing this app does works on a phone old enough to
        // care.
        minSdk = 24
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    // Built by ../../build-android.sh: the Go core - pin, probe, race, DoH
    // and the tunnel - for all four phone architectures. Not in the repo.
    implementation(files("libs/relay.aar"))

    // What RelayVpnService needs: NotificationCompat so one call works either
    // side of API 26, and coroutines so the race runs off the main thread
    // without a Thread of its own.
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Where account passwords live. Private preferences are already out of
    // reach of other applications, but this build is debuggable - which is
    // what makes setup-phone.sh's run-as trick work - and run-as reads
    // shared_prefs. A key held by the keystore does not come out that way.
    implementation("androidx.security:security-crypto:1.0.0")
}
