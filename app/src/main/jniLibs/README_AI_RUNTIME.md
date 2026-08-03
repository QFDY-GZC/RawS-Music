# Optional AI separation runtime

RawSMusic keeps the stable JNI bridge in `rawscoreservice` and the small ONNX Java/JNI adapter in
the APK. Large separation models and the ONNX Runtime core are installed on demand.

Do not place the full runtime in `jniLibs`; release packaging excludes it. Create the arm64 runtime
catalog item with:

```text
python tools/ai_model_repository.py package-runtime ...
```

The app accepts this executable only from an imported developer-signed repository index and only
after ABI, exact filename, byte size, and SHA-256 verification. Model packages remain data-only and
still reject `.so`, `.dex`, `.jar`, scripts, and nested paths.

The separation engine that performs STFT, tensor preparation, overlap-add, and output encoding must
remain in the dedicated `separation` native module. It must never run from the USB writer, AAudio,
OpenSL, decoder callback, or DSP realtime thread.
