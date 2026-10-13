plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.rawsmusic"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rawsmusic"
        minSdk = 24
        targetSdk = 37
        versionCode = 113
        versionName = "1.0.13-release"

        // Match locales_config.xml. Keep default values and the four supported languages,
        // rather than shipping every dependency's unused translations.
        resourceConfigurations += listOf("en", "zh", "ja", "ko")

        testInstrumentationRunner = providers.gradleProperty("backupValidationRunner")
            .orElse("androidx.test.runner.AndroidJUnitRunner").get()

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    signingConfigs {
        getByName("debug") {
            // 使用默认的 debug 签名
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
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }


    androidResources {
        noCompress += listOf("ttf")
    }

    packaging {
        jniLibs {
            // Compress native libraries in the distributable APK. Android extracts
            // them at install time on our minSdk 24 devices; this keeps runtime
            // loading unchanged while avoiding several megabytes of APK padding.
            useLegacyPackaging = true
            // No packaged ELF currently declares this dependency; the checked-in
            // copy only inflated every arm64 APK.
            excludes += "**/libc++_shared.so"
            excludes += listOf(
                "**/libswscale.so",
                // The 27 MB core is installed on demand from the signed AI repository.
                // Keep libonnxruntime4j_jni.so: it is the small Java/JNI loader bridge.
                "**/libonnxruntime.so"
            )
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

tasks.whenTaskAdded {
    if (name == "checkDebugAarMetadata" || name == "checkReleaseAarMetadata") {
        enabled = false
    }
}

configurations.all {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions-runtime")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":module:player"))
    implementation(project(":module:scanner"))
    implementation(project(":module:data"))
    // Backup coordinates both durable stores in one existing Room transaction.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")

    implementation(libs.lottie)
    implementation(libs.lottie.compose)

    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.media)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(project(":lyric:model"))
    implementation(project(":lyric:bridge:provider"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.ripple)
    implementation(libs.activity.compose)
    implementation(libs.compose.runtime.livedata)

    implementation(project(":backdrop"))
    // Settings owns a Haze source in its separate Activity; runtime is already used by core:ui.
    implementation("dev.chrisbanes.haze:haze:2.0.0-alpha03")
    implementation(libs.gson)

    // Miuix UI 库
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.preference)
    implementation(libs.focus.api)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    compileOnly(project(":module:system-api-stubs"))
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    // navigationevent-compose (miuix SearchBar 内部需要 LocalNavigationEventDispatcherOwner)
    implementation("androidx.navigationevent:navigationevent-compose:1.1.1")
}
