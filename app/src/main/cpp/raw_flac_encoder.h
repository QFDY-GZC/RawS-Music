#pragma once

// Encodes the float PCM WAV produced by the offline separation pipeline without
// requiring the optional FFmpeg FLAC encoder.
int raw_encode_wav_to_flac(const char* inputPath, const char* outputPath, int compressionLevel);
