plugins {
    id("com.android.application")
}

android {
    namespace = "com.mohan.zip2share"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mohan.zip2share"
        minSdk = 21
        targetSdk = 36
        versionCode = 6
        versionName = "6.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // 1.7.0 fixed the D8 dexing NPE that affected 1.6.x on minSdk 21 with newer AGP —
    // needed for correct predictive-back + edge-to-edge behavior on Android 15/16.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity:1.9.3")

    // Material 3 — includes DynamicColors for Material You system-palette theming
    implementation("com.google.android.material:material:1.12.0")

    // DocumentFile — required for directory tree traversal
    implementation("androidx.documentfile:documentfile:1.0.1")

    // ConstraintLayout (kept for future use)
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // CoordinatorLayout (used in activity_main.xml)
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
}
