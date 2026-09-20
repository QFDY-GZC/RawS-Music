#include "../../main/cpp/dsf_writer.h"

#include <cassert>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <vector>

int main() {
    using namespace rawsmusic::transcode;
    const char* path = "/tmp/rawsmusic_dsf_writer_test.dsf";
    std::remove(path);

    std::vector<uint8_t> left(5000);
    std::vector<uint8_t> right(5000);
    for (size_t i = 0; i < left.size(); ++i) {
        left[i] = static_cast<uint8_t>(i & 0xFFu);
        right[i] = static_cast<uint8_t>((255u - i) & 0xFFu);
    }

    DsfWriter writer;
    assert(writer.open(path, 2822400, 2));
    assert(writer.writeChannelData(left.data(), right.data(), 3000));
    assert(writer.writeChannelData(left.data() + 3000, right.data() + 3000, 2000));
    assert(writer.logicalSampleCount() == 40000u);
    assert(writer.finalize());

    DsfProbeResult probe{};
    assert(probeDsfFile(path, probe));
    assert(probe.sample_rate_hz == 2822400);
    assert(probe.channels == 2);
    assert(probe.bits_per_sample == 8);
    assert(probe.sample_count == 40000u);
    assert(probe.block_size_per_channel == 4096);
    assert(probe.metadata_offset == 0u);
    assert(probe.data_chunk_size == 12u + 2u * 4096u * 2u);
    assert(probe.file_size == 92u + 2u * 4096u * 2u);

    std::ifstream file(path, std::ios::binary);
    assert(file.is_open());
    file.seekg(92, std::ios::beg);
    std::vector<uint8_t> block(4096);
    file.read(reinterpret_cast<char*>(block.data()), static_cast<std::streamsize>(block.size()));
    assert(file.good());
    for (size_t i = 0; i < block.size(); ++i) assert(block[i] == left[i]);
    file.read(reinterpret_cast<char*>(block.data()), static_cast<std::streamsize>(block.size()));
    assert(file.good());
    for (size_t i = 0; i < block.size(); ++i) assert(block[i] == right[i]);
    file.read(reinterpret_cast<char*>(block.data()), static_cast<std::streamsize>(block.size()));
    assert(file.good());
    for (size_t i = 0; i < 904; ++i) assert(block[i] == left[i + 4096]);
    for (size_t i = 904; i < block.size(); ++i) assert(block[i] == 0u);

    std::remove(path);
    return 0;
}
