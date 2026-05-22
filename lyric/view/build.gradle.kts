plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.proify.lyricon.lyric.view"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":lyric:model"))

    implementation("com.daimajia.androidanimations:library:2.4@aar")
    implementation("com.daimajia.easing:library:2.4@aar")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.interpolator)
}
