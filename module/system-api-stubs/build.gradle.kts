plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.rawsmusic.systemapistubs"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
}
