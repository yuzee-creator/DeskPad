plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.deskpad.browser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.deskpad.browser"
        minSdk = 24
        targetSdk = 34
        // Nomor versi naik otomatis tiap build di GitHub supaya APK baru bisa menimpa yang lama.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.1"
    }

    // Kunci tanda tangan tetap: update menimpa aplikasi lama tanpa uninstall, login tetap aman.
    signingConfigs {
        create("deskpad") {
            storeFile = file("deskpad.keystore")
            storePassword = "deskpad123"
            keyAlias = "deskpad"
            keyPassword = "deskpad123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("deskpad")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("deskpad")
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
    implementation("androidx.webkit:webkit:1.11.0")
}
