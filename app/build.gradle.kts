import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.manc.voice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.manc.voice"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables { useSupportLibrary = true }
        // Fold 7 and essentially all modern phones are arm64; shipping only this ABI
        // keeps the ONNX Runtime native libs from bloating the APK ~4x.
        ndk { abiFilters += listOf("arm64-v8a") }
        // Expose whether ONNX models were bundled at build time.
        val modelsDir = file("src/main/assets/models")
        val hasModels = modelsDir.isDirectory && (modelsDir.listFiles()?.any { it.name.endsWith(".onnx") } == true)
        buildConfigField("boolean", "MODELS_BUNDLED", hasModels.toString())
    }

    signingConfigs {
        // A stable debug key so CI-built APKs can be upgraded in place by testers.
        getByName("debug") {
            val ks = file("debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Fall back to the debug key so `assembleRelease` produces an installable APK in CI
            // without provisioning secrets. Replace for Play distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    lint {
        abortOnError = false
        disable += setOf("ExpiredTargetSdkVersion")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.onnxruntime.android)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
