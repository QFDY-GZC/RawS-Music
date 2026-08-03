#pragma once

#include <cstdint>
#include <vector>

struct RawFileSpectrumAnalyzer;

RawFileSpectrumAnalyzer* raw_file_spectrum_create(
    int sample_rate,
    int channels,
    int64_t duration_ms,
    int fft_size
);

void raw_file_spectrum_release(RawFileSpectrumAnalyzer* analyzer);

void raw_file_spectrum_process_s32le(
    RawFileSpectrumAnalyzer* analyzer,
    const uint8_t* data,
    int byte_count
);

std::vector<uint8_t> raw_file_spectrum_finish(RawFileSpectrumAnalyzer* analyzer);

void raw_file_spectrum_current_levels(
    const RawFileSpectrumAnalyzer* analyzer,
    float* left_db,
    float* right_db
);
