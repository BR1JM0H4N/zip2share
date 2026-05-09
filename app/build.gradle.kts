plugins {
    id("com.android.application")
}

android {
    namespace = "com.mohan.zip2share"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mohan.zip2share"
        minSdk = 21
        targetSdk = 34
        versionCode = 4
        versionName = "4.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // AppCompat + Activity (activity-ktx brings registerForActivityResult compat)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity:1.9.0")

    // Material 3
    implementation("com.google.android.material:material:1.12.0")

    // ConstraintLayout (kept for future use)
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // CoordinatorLayout (used in activity_main.xml)
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
}
