plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ec.abrelo.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ec.abrelo.app"
        minSdk = 24
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Llave fija para que cada versión nueva se instale encima de la anterior.
    signingConfigs {
        create("familia") {
            storeFile = file("abrelo.jks")
            storePassword = "abrelo-familia"
            keyAlias = "abrelo"
            keyPassword = "abrelo-familia"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("familia")
        }
        debug { signingConfig = signingConfigs.getByName("familia") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("js", "html") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.webkit:webkit:1.11.0")
}
