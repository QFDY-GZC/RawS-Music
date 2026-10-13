#pragma once
#include <cstdint>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>
extern "C" {
#include "../../main/cpp/third_party/dsd-nexus/libsacd/src/sacd_specification.h"
}

/** A copyright-free, two-track DSD64 SACD image, generated from packed TOC structures. */
inline void write_sacd_fixture(const std::string& path, uint16_t area_length = 8, bool dst = false) {
    constexpr uint32_t area_lba = 600, audio_lba = 700;
    uint32_t audio_sectors = dst ? 750 : 700;
    std::vector<uint8_t> image((audio_lba + audio_sectors) * 2048, 0);
    auto* master = reinterpret_cast<master_toc_0_t*>(image.data() + 510 * 2048);
    master->signature = MASTER_TOC_SIGN;
    master->version.major = 2;
    master->album.album_set_size = hton16(1);
    master->album.album_sequence_number = hton16(1);
    master->disc.stereo_toc_1_lsn = hton32(area_lba);
    master->disc.stereo_toc_2_lsn = hton32(area_lba);
    master->disc.stereo_toc_length = hton16(area_length);
    master->disc.disc_date.year = hton16(2026);
    master->disc.disc_date.month = 10;
    master->disc.disc_date.day = 12;
    master->text_channels.text_channel_count = 1;
    master->text_channels.info[0].character_set_code = CHAR_SET_ISO646;
    master->text_channels.info[0].language_code = hton16(0x656e);
    auto* text = reinterpret_cast<master_text_t*>(image.data() + 511 * 2048);
    text->signature = MASTER_TEXT_SIGN;
    text->album_title_ptr = hton16(128);
    text->disc_title_ptr = hton16(128);
    text->album_artist_ptr = hton16(200);
    std::memcpy(reinterpret_cast<uint8_t*>(text) + 128, "SACD fixture", 13);
    std::memcpy(reinterpret_cast<uint8_t*>(text) + 200, "RawSMusic", 10);
    auto* manufacturer = reinterpret_cast<manuf_info_t*>(image.data() + 519 * 2048);
    manufacturer->signature = MANUF_INFO_SIGN;
    auto* area = reinterpret_cast<area_data_t*>(image.data() + area_lba * 2048);
    area->signature = AREA_2CH_TOC_SIGN;
    area->version.major = 2;
    area->fs_code = 4;
    area->frame_format = dst ? FRAME_FORMAT_DST : FRAME_FORMAT_DSD_3_IN_14;
    area->channel_count = 2;
    area->track_count = 2;
    area->max_byte_rate = hton32(716800);
    area->track_area_start_address = hton32(audio_lba);
    area->track_area_end_address = hton32(audio_lba + audio_sectors - 1);
    area->total_area_play_time.seconds = 2;
    auto* sectors = reinterpret_cast<track_list_1_t*>(image.data() + (area_lba + 1) * 2048);
    sectors->signature = TRACK_LIST1_SIGN;
    sectors->track_start_lsn[0] = hton32(audio_lba);
    sectors->track_start_lsn[1] = hton32(audio_lba + audio_sectors / 2);
    sectors->track_length[0] = hton32(audio_sectors / 2);
    sectors->track_length[1] = hton32(audio_sectors / 2);
    auto* times = reinterpret_cast<track_list_2_t*>(image.data() + (area_lba + 2) * 2048);
    times->signature = TRACK_LIST2_SIGN;
    times->info_1[1].track_start_time_code.seconds = 1;
    times->info_2[0].track_time_length.seconds = 1;
    times->info_2[1].track_time_length.seconds = 1;
    auto* genres = reinterpret_cast<isrc_genre_list_1_t*>(image.data() + (area_lba + 3) * 2048);
    genres->signature = ISRC_GENRE_SIGN;
    if (area_length >= 8) {
        area->text_channels.text_channel_count = 1;
        area->text_channels.info[0].character_set_code = CHAR_SET_ISO646;
        area->text_channels.info[0].language_code = hton16(0x656e);
        area->track_text_ptr = hton16(5);
        auto* track_text = reinterpret_cast<track_text_header_t*>(image.data() + (area_lba + 5) * 2048);
        track_text->signature = TRACK_TEXT_SIGN;
        for (unsigned number = 0; number < 2; ++number) {
            unsigned offset = 4096 + number * 44;
            track_text->track_text_item_ptr[number] = hton16(static_cast<uint16_t>(offset));
            uint8_t* item = reinterpret_cast<uint8_t*>(track_text) + offset;
            item[0] = 2;
            item[4] = TRACK_TYPE_TITLE; item[5] = 0x20;
            std::string title = "Fixture track " + std::to_string(number + 1);
            std::memcpy(item + 6, title.c_str(), title.size() + 1);
            item[24] = TRACK_TYPE_PERFORMER; item[25] = 0x20;
            std::string artist = "Track artist " + std::to_string(number + 1);
            std::memcpy(item + 26, artist.c_str(), artist.size() + 1);
        }
    }
    if (!dst) {
        for (uint32_t sector = 0; sector < audio_sectors; ++sector) {
            std::memset(image.data() + (audio_lba + sector) * 2048 + 32,
                sector < 350 ? 0x69 : 0x96, 2016);
        }
    } else {
        // Valid DST uncoded frames exercise SACD packet assembly and lossless DST decode.
        for (unsigned frame = 0; frame < 150; ++frame) {
            std::vector<uint8_t> packet(9409, frame < 75 ? 0x69 : 0x96);
            packet[0] = 0;
            size_t copied = 0;
            for (unsigned part = 0; part < 5; ++part) {
                uint8_t* sector = image.data() + (audio_lba + frame * 5 + part) * 2048;
                const unsigned payload_offset = part == 0 ? 7 : 3;
                unsigned bytes = static_cast<unsigned>(std::min<size_t>(2048 - payload_offset, packet.size() - copied));
                sector[0] = part == 0 ? 0x25 : 0x21;
                uint16_t info = static_cast<uint16_t>((part == 0 ? 0x8000 : 0) |
                    (DATA_TYPE_AUDIO << 11) | bytes);
                sector[1] = static_cast<uint8_t>(info >> 8);
                sector[2] = static_cast<uint8_t>(info);
                if (part == 0) {
                    sector[3] = 0; sector[4] = static_cast<uint8_t>(frame / 75);
                    sector[5] = static_cast<uint8_t>(frame % 75); sector[6] = 5 << 2;
                }
                std::memcpy(sector + payload_offset, packet.data() + copied, bytes);
                copied += bytes;
            }
        }
    }
    std::ofstream file(path, std::ios::binary);
    file.write(reinterpret_cast<const char*>(image.data()), static_cast<std::streamsize>(image.size()));
}
