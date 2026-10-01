plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin { jvmToolchain(17) }

android {
    namespace = "nz.skull.vitalibre"
    compileSdk = 36

    defaultConfig {
        applicationId = "nz.skull.vitalibre.claude"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // The synthetic pulse exists only here; a shipped build can never run it.
            buildConfigField("boolean", "SIMULATION", "true")
        }
        release {
            buildConfigField("boolean", "SIMULATION", "false")
            isMinifyEnabled = false
            // Signed with the debug key until a real release key exists; this is a test build.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Fonts, sounds, the model weights and the publisher config are shared with the iOS build, not copied.
    sourceSets["main"].assets.srcDirs("../../App/Resources", "../../publisher")
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2025.09.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")

    val camerax = "1.5.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
