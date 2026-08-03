package com.rawsmusic.separation

/**
 * Data-only model profiles pinned into the APK.
 *
 * The executable ONNX is still downloaded on demand. Size and SHA-256 are immutable here, so a
 * changed remote object is rejected before it can be opened by ONNX Runtime.
 */
object AiRecommendedModels {
    const val UVR_9482_ID = "uvr.mdxnet.9482"
    const val UVR_9482_VERSION = "1.0.0"
    const val UVR_VOC_FT_ID = "uvr.mdxnet.voc.ft"
    const val UVR_VOC_FT_VERSION = "1.0.0"

    val UVR_MDXNET_9482 = AiSeparationCatalogEntry(
        schemaVersion = 3,
        id = UVR_9482_ID,
        name = "UVR MDX-Net 9482",
        version = UVR_9482_VERSION,
        description = "轻量双轨人声模型，适合作为手机端首个推荐模型",
        architecture = "UVR MDX-Net",
        modelFormat = "onnx",
        archiveSizeBytes = 29_704_436L,
        archiveSha256 = "f4f365207c56deb115bceedff3ad8fe98a751c745f9e370cecec6226b8b47184",
        modelFile = "model.onnx",
        modelSizeBytes = 29_704_436L,
        modelSha256 = "f4f365207c56deb115bceedff3ad8fe98a751c745f9e370cecec6226b8b47184",
        sampleRate = 44_100,
        channels = 2,
        segmentSamples = 261_120L,
        overlap = 0.10,
        estimatedMemoryMb = 420,
        minimumAppVersion = "0.9.61 beta",
        downloadUrls = listOf(
            // GitHub release assets are tried first because Hugging Face can be unreachable on
            // some networks. Every source must still match the pinned size and SHA-256 below.
            "https://github.com/nomadkaraoke/python-audio-separator/releases/download/model-configs/UVR_MDXNET_9482.onnx",
            "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR_MDXNET_9482.onnx",
            "https://huggingface.co/Politrees/UVR_resources/resolve/main/models/MDXNet/UVR_MDXNET_9482.onnx?download=true",
            "https://huggingface.co/seanghay/uvr_models/resolve/main/UVR_MDXNET_9482.onnx?download=true",
        ),
        contract = AiSeparationModelContract(
            task = "vocals_2stem",
            tensorLayout = "bcft_complex_channels",
            tensorDataType = "float32",
            inputName = "*",
            outputName = "*",
            inputShape = listOf(1L, 4L, 2048L, 256L),
            outputShape = listOf(1L, 4L, 2048L, 256L),
            fftSize = 6144,
            hopLength = 1024,
            frequencyBins = 2048,
            timeFrames = 256,
            window = "hann",
            center = true,
            paddingMode = "reflect",
            normalization = "none",
            outputType = "complex_spectrogram",
            intraOpThreads = 4,
            chunkMode = "uvr_mdx_center_trim",
            edgeTrimSamples = 3072,
            compensation = 1.035,
            supportsDenoise = true,
        ),
    )

    val UVR_MDXNET_VOC_FT = AiSeparationCatalogEntry(
        schemaVersion = 3,
        id = UVR_VOC_FT_ID,
        name = "UVR MDX-Net Voc FT",
        version = UVR_VOC_FT_VERSION,
        description = "高质量移动版人声模型，兼顾分离质量、速度与内存占用",
        architecture = "UVR MDX-Net",
        modelFormat = "onnx",
        archiveSizeBytes = 66_762_795L,
        archiveSha256 = "e411182ce2c53541fefdcc99a8f46f2fe03978eb22038b9497c1d9d95a00fad4",
        modelFile = "model.onnx",
        modelSizeBytes = 66_762_795L,
        modelSha256 = "e411182ce2c53541fefdcc99a8f46f2fe03978eb22038b9497c1d9d95a00fad4",
        sampleRate = 44_100,
        channels = 2,
        segmentSamples = 261_120L,
        overlap = 0.10,
        estimatedMemoryMb = 720,
        minimumAppVersion = "0.9.61 beta",
        downloadUrls = listOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
                "source-separation-models/UVR-MDX-NET-Voc_FT.onnx",
            "https://github.com/nomadkaraoke/python-audio-separator/releases/download/" +
                "model-configs/UVR-MDX-NET-Voc_FT.onnx",
        ),
        contract = AiSeparationModelContract(
            task = "vocals_2stem",
            tensorLayout = "bcft_complex_channels",
            tensorDataType = "float32",
            inputName = "input",
            outputName = "output",
            inputShape = listOf(1L, 4L, 3072L, 256L),
            outputShape = listOf(1L, 4L, 3072L, 256L),
            fftSize = 6144,
            hopLength = 1024,
            frequencyBins = 3072,
            timeFrames = 256,
            window = "hann",
            center = true,
            paddingMode = "reflect",
            normalization = "none",
            outputType = "complex_spectrogram",
            intraOpThreads = 4,
            chunkMode = "uvr_mdx_center_trim",
            edgeTrimSamples = 3072,
            compensation = 1.0,
            supportsDenoise = true,
        ),
    )

    val all: List<AiSeparationCatalogEntry> = listOf(
        UVR_MDXNET_9482,
        UVR_MDXNET_VOC_FT,
    )

    fun find(id: String, version: String): AiSeparationCatalogEntry? =
        all.firstOrNull { it.id == id && it.version == version }

    fun isRecommended(id: String, version: String): Boolean = find(id, version) != null

    fun isRealtime(entry: AiSeparationCatalogEntry): Boolean =
        entry.id == UVR_9482_ID &&
            entry.contract?.tensorLayout == "bcft_complex_channels"

    fun licenseFor(entry: AiSeparationCatalogEntry): String = UVR_LICENSE

    fun modelCardFor(entry: AiSeparationCatalogEntry): String =
        if (entry.id == UVR_VOC_FT_ID) UVR_VOC_FT_MODEL_CARD else UVR_9482_MODEL_CARD

    const val UVR_LICENSE = """License declaration: MIT

Source repository: Politrees/UVR_resources
Model file: models/MDXNet/UVR_MDXNET_9482.onnx
Source page: https://huggingface.co/Politrees/UVR_resources

The source repository declares the repository license as MIT. RawSMusic does not bundle or redistribute the model bytes; the user downloads the pinned model directly from the configured source after an explicit action. Refer to the original repository and model page for authoritative attribution, notices, and any model-specific terms.
"""

    const val UVR_9482_MODEL_CARD = """RawSMusic recommended data-only model profile

Model: UVR_MDXNET_9482.onnx
Task: stereo vocals / instrumental separation
Source family: Ultimate Vocal Remover MDX-Net
Sample rate: 44100 Hz
Input/output: float32 [1, 4, 2048, 256]
Plane order: L real, L imaginary, R real, R imaginary
Frequency crop: keep bins 0..2047 from the 3073-bin real FFT
STFT: periodic Hann, n_fft=6144, hop=1024, center=true, reflect padding
Chunking: 261120 samples, global trim 3072, 10% symmetric-Hann weighted overlap-add
SHA-256: f4f365207c56deb115bceedff3ad8fe98a751c745f9e370cecec6226b8b47184

The model is downloaded only after the user requests it. RawSMusic verifies the exact byte size and SHA-256 before probing the graph. The original song is never overwritten.
"""

    const val UVR_VOC_FT_MODEL_CARD = """RawSMusic high-quality mobile model profile

Model: UVR-MDX-NET-Voc_FT.onnx
Task: stereo vocals / instrumental separation
Architecture: UVR MDX-Net
Sample rate: 44100 Hz
Input/output: float32 [1, 4, 3072, 256]
STFT: periodic Hann, n_fft=6144, hop=1024, center=true, reflect padding
Chunking: 261120 samples, global trim 3072, 10% symmetric-Hann weighted overlap-add
SHA-256: e411182ce2c53541fefdcc99a8f46f2fe03978eb22038b9497c1d9d95a00fad4

This profile uses the wider 3072-bin Voc FT graph for better mobile vocal separation without the
multi-gigabyte session memory required by full-waveform RoFormer exports.
"""
}
