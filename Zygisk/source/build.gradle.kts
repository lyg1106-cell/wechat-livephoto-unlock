plugins {
    id("com.android.application")
}

android {
    namespace = "me.livephoto.zygisk"
    compileSdk = 34

    defaultConfig {
        applicationId = "me.livephoto.zygisk"
        minSdk = 26
        targetSdk = 34
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

    packaging {
        resources {
            excludes += "/META-INF/**"
        }
    }
}

dependencies {
    compileOnly(files("libs/pine-core.aar"))
    compileOnly(files("libs/pine-xposed.aar"))
    compileOnly(files("libs/pine-enhances.aar"))
}
