plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.digitallock"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.digitallock"
        minSdk = 33
        targetSdk = 35
        versionCode = 3
        versionName = "3.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Centralized single backend origin (HTTPS only)
        buildConfigField(
            "String",
            "BACKEND_BASE_URL",
            "\"https://digitalxiao.shopaccvt.site/digitallock\""
        )
        // Ed25519 public key (Base64, 32-byte raw key) injected at build time or default key
        val manifestPubKey = System.getenv("DIGITALLOCK_MANIFEST_PUBKEY_B64")
            ?: "MCowBQYDK2VwAyEA0000000000000000000000000000000000000000000="
        buildConfigField(
            "String",
            "MANIFEST_PUBLIC_KEY_B64",
            "\"$manifestPubKey\""
        )
        val licensePubKey = System.getenv("DIGITALLOCK_LICENSE_PUBKEY_B64")
            ?: "MCowBQYDK2VwAyEA0000000000000000000000000000000000000000000="
        buildConfigField(
            "String",
            "LICENSE_PUBLIC_KEY_B64",
            "\"$licensePubKey\""
        )
        val expectedCertSha256 = System.getenv("DIGITALLOCK_SIGNING_CERT_SHA256") ?: ""
        buildConfigField(
            "String",
            "EXPECTED_SIGNING_CERT_SHA256",
            "\"$expectedCertSha256\""
        )

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-O3", "-Wall", "-fvisibility=hidden")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("DIGITALLOCK_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("DIGITALLOCK_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("DIGITALLOCK_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("DIGITALLOCK_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseConfig = signingConfigs.findByName("release")
            signingConfig = if (releaseConfig?.storeFile != null) {
                releaseConfig
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation(libs.kotlinx.coroutines.test)
}
