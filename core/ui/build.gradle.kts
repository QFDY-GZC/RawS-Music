plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.rawsmusic.core.ui"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":module:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Experimental PowerList A/B branch: Coil handles list/grid Compose request state while
    // BitmapProvider keeps RawSMusic-specific audio/folder/embedded artwork decoding.
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation(libs.lottie)
    implementation(libs.lottie.compose)

    implementation(libs.androidx.palette.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(project(":backdrop"))
    implementation("dev.chrisbanes.haze:haze:2.0.0-alpha03")
    implementation("dev.chrisbanes.haze:haze-blur:2.0.0-alpha03")

    // Miuix UI 库
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.squircle)
    implementation("androidx.navigationevent:navigationevent-compose:1.1.1")

    testImplementation("junit:junit:4.13.2")
}
