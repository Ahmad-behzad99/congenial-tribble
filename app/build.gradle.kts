plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.faktorsaz.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.faktorsaz.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 40
        versionName = "2.3.1"
    }

    // A fixed key keeps the signing fingerprint (SHA-1) the same on every build,
    // which Google sign-in for Drive backup needs.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("store") {
            storeFile = file("release.keystore")
            storePassword = "iavDALJNy2rOW3kwazGA"
            keyAlias = "faktorsaz2"
            keyPassword = "iavDALJNy2rOW3kwazGA"
        }
    }

    buildTypes {
        // The workflow builds "assembleDebug"; this makes that output a real,
        // non-debuggable APK signed with the store key (ready for app markets).
        debug {
            signingConfig = signingConfigs.getByName("store")
            isDebuggable = false
        }
        release {
            signingConfig = signingConfigs.getByName("store")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("androidx.biometric:biometric:1.1.0")
    // Background daily report / reminders to Telegram while the app is closed
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // OCR for scanning purchase receipts (Persian + digits), runs on the phone
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.7.0")
}
