plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.rawsmusic.core.common"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

}

dependencies {
    api(project(":lyric:model"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // AudioFile is consumed by Compose PowerList. Keep its immutable contract visible to the
    // Compose compiler so pixel-only scroll frames can skip unchanged holders.
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.runtime:runtime")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    implementation(libs.mmkv)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")
    api(libs.dexter)

    testImplementation("junit:junit:4.13.2")
}
