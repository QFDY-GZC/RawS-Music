#pragma once

// Returns a normalized FFT-derived energy value for one PCM window.
float rawsmusic_fft_weighted_energy(const float* samples, int sample_count);
