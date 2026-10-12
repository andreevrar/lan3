plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.tvremote"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.tvremote"
        minSdk = 21
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    // ADB-клиент: приложение подключается к adbd самого ТВ (127.0.0.1:5555) и шлёт настоящие нажатия кнопок
    implementation("dev.mobile:dadb:1.2.10")
    // QR-код на экране ТВ (только кодировщик, без камеры и интернета)
    implementation("com.google.zxing:core:3.5.3")
}
