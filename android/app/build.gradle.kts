plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes a stable keystore so every build can be installed over the previous one.
val ciKeystore: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "dev.awbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.awbridge"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        if (ciKeystore != null) {
            create("ci") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("KEYSTORE_PASS")
                keyAlias = "awbridge"
                keyPassword = System.getenv("KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (ciKeystore != null) {
                signingConfig = signingConfigs.getByName("ci")
            }
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
