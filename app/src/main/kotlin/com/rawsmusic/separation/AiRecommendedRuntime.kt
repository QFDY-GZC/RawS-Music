package com.rawsmusic.separation

/**
 * Pinned official Maven fallback used until an imported signed repository publishes a raw core.
 *
 * Both the AAR and the extracted arm64 library are verified. A signed repository runtime always
 * takes precedence because it avoids downloading the unused ABIs in the AAR.
 */
object AiRecommendedRuntime {
    const val ARCHIVE_SIZE_BYTES = 43_596_581L
    const val ARCHIVE_SHA256 =
        "09c0780ae8d734ef2774bdf498b624729a855e6f9a8e488a0e7398a4e7396032"
    const val AAR_LIBRARY_PATH = "jni/arm64-v8a/libonnxruntime.so"

    val ONNX_RUNTIME_1_26 = AiRuntimeCatalogEntry(
        schemaVersion = 1,
        id = "rawsmusic.onnxruntime",
        name = "ONNX Runtime",
        version = "1.26.0",
        abi = "arm64-v8a",
        libraryFile = "libonnxruntime.so",
        librarySizeBytes = 27_408_600L,
        librarySha256 = "9f8e49b209cbac4483c96e5fc82f0405747f39c0708fb673561cbb019db0c0bc",
        minimumAppVersion = "0.9.61 beta",
        downloadUrls = listOf(
            "https://maven.aliyun.com/repository/central/com/microsoft/onnxruntime/" +
                "onnxruntime-android/1.26.0/onnxruntime-android-1.26.0.aar",
            "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/" +
                "onnxruntime-android/1.26.0/onnxruntime-android-1.26.0.aar",
            "https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/" +
                "onnxruntime-android/1.26.0/onnxruntime-android-1.26.0.aar",
        ),
    )
}
