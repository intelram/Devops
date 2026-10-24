plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.netpilot.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.netpilot.app"
        minSdk = 29          // Settings.Panel, NetworkCapabilities.signalStrength
        targetSdk = 35       // screens apply window insets themselves (edge-to-edge)
        versionCode = 5
        versionName = "0.2.3"
    }

    // Release signing reads keystore.properties (git-ignored). See docs/DESIGN.md §5.
    val ksFile = rootProject.file("keystore.properties")
    if (ksFile.exists()) {
        val props = java.util.Properties().apply { ksFile.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (ksFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true // Robolectric launch tests use the real resources
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Framework APIs only (minSdk 29 covers everything AndroidX was used for), so the only
// third-party runtime dependency is kotlinx-coroutines from Maven Central.
dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
