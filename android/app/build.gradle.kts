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
        versionCode = 2
        versionName = "0.2.0"
    }

    val uploadFile = providers.environmentVariable("VITALIBRE_UPLOAD_KEYSTORE").orNull
    signingConfigs {
        if (uploadFile != null) {
            create("upload") {
                storeFile = file(uploadFile)
                storePassword = providers.environmentVariable("VITALIBRE_UPLOAD_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("VITALIBRE_UPLOAD_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("VITALIBRE_UPLOAD_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            // The synthetic pulse exists only here; a shipped build can never run it.
            buildConfigField("boolean", "SIMULATION", "true")
        }
        release {
            buildConfigField("boolean", "SIMULATION", "false")
            isMinifyEnabled = false
            if (uploadFile != null) signingConfig = signingConfigs.getByName("upload")
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
    implementation("androidx.fragment:fragment:1.9.1")
    implementation("com.android.billingclient:billing:9.1.0")

    val camerax = "1.5.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
