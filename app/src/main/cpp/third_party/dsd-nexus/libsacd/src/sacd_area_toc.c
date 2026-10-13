/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief Implementation of Super Audio CD Area Table of Contents (Area TOC) Management
 * This file implements the complete Area TOC parser and accessor functions for SACD discs.
 * It handles reading and parsing all Area TOC structures defined in the SACD specification,
 * including:
 * - Area_TOC_0: Main area metadata (format, channels, track count, text channels)
 * - Track_List_1: Track start addresses and sector lengths
 * - Track_List_2: Track start times, playing times, and flags
 * - ISRC_and_Genre_List: ISRC codes and genre classifications for all tracks
 * - Access_List: Frame address lookup table for DST-coded audio seeking
 * - Track_Text: Multi-language text metadata (titles, performers, composers, etc.)
 * - Index_List: Sub-track index points
 * The implementation:
 * 1. Reads Area TOC sectors from disc using the provided read object
 * 2. Validates all structure signatures
 * 3. Converts multi-byte fields from big-endian to host byte order
 * 4. Converts text from disc character sets to UTF-8
 * 5. Builds index arrays for efficient track and frame lookup
 * 6. Initializes the appropriate audio data reader (DST or DSD) based on frame format
 * @see sacd_specification.h for Area TOC structure definitions
 * @see sacd_area_toc.h for public API
 *
 * DSD-Nexus is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * DSD-Nexus is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with DSD-Nexus; if not, see <https://www.gnu.org/licenses/>.
 */

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <libsautil/mem.h>

#include "sacd_area_toc.h"
#include "sacd_dst_reader.h"
#include "sacd_dsd_reader.h"
#include "sacd_charset.h"

void sacd_area_toc_init(area_toc_t *ctx)
{
    int channel_idx, text_type_idx;

    for (channel_idx = 0; channel_idx < MAX_TEXT_CHANNEL_COUNT; channel_idx++)
    {
        for (text_type_idx = 0; text_type_idx < MAX_AREA_TEXT_TYPE_COUNT; text_type_idx++)
        {
            ctx->area_info.text[channel_idx][text_type_idx] = NULL;
        }
    }

    ctx->input = NULL;
    ctx->track_info = NULL;
    ctx->frame_info.frame_start = NULL;
    ctx->frame_reader = NULL;

    ctx->frame_format = 0;
    ctx->channel_count = 6;

    ctx->track_area_start = 0;
    ctx->track_area_end = FRAME_START_USE_CURRENT - 1;
    ctx->track_offset = 0;
    ctx->track_count = 0;

    ctx->max_byte_rate = 0;
    ctx->fs_code = 4;

    ctx->max_available_channels = 0;
    ctx->mute_flags = 0;
    ctx->track_attribute = 0;
    ctx->total_area_play_time = 0;
    ctx->cur_frame_num_data = 0;
    ctx->cur_track_num = 0;
    ctx->cur_index_num = 0;
    ctx->cur_frame_num_text = 0;
    ctx->frame_start = 0;
    ctx->frame_stop = 0;

    ctx->frame_info.step_size = 180;
    ctx->frame_info.num_entries = 1;
    ctx->frame_info.frame_start = NULL;
    ctx->frame_info.access_margin = NULL;

    ctx->initialized = false;
}

void sacd_area_toc_destroy(area_toc_t *ctx)
{
    sacd_area_toc_close(ctx);
}

/**
 * @brief Close and cleanup Area TOC resources
 *
 * Frees all dynamically allocated memory:
 * - Area text strings
 * - Track information array
 * - Per-track index lists
 * - Per-track text items
 * - Frame access list
 * - Audio data reader (DST or DSD)
 */
void sacd_area_toc_close(area_toc_t *ctx)
{
    /* Mark as uninitialized */
    ctx->initialized = false;

    /* Free area text strings */
    for (int channel_idx = 0; channel_idx < MAX_TEXT_CHANNEL_COUNT; channel_idx++)
    {
        for (int text_type_idx = 0; text_type_idx < MAX_AREA_TEXT_TYPE_COUNT; text_type_idx++)
        {
            sa_free(ctx->area_info.text[channel_idx][text_type_idx]);
            ctx->area_info.text[channel_idx][text_type_idx] = NULL;
        }
    }

    /* Free track information and related data */
    if (ctx->track_info)
    {
        for (int track_idx = 0; track_idx < ctx->track_count; track_idx++)
        {
            /* Free index start array for this track */
            sa_free(ctx->track_info[track_idx].index_start);
            ctx->track_info[track_idx].index_start = NULL;

            /* Free track text for all text channels */
            for (int channel_idx = 0; channel_idx < MAX_TEXT_CHANNEL_COUNT; channel_idx++)
            {
                if (ctx->track_info[track_idx].track_text[channel_idx])
                {
                    /* Free individual text items */
                    for (int text_item_idx = 0; text_item_idx < ctx->track_info[track_idx].track_text_item_count[channel_idx]; text_item_idx++)
                    {
                        sa_free(ctx->track_info[track_idx].track_text[channel_idx][text_item_idx].text);
                        ctx->track_info[track_idx].track_text[channel_idx][text_item_idx].text = NULL;
                    }
                    sa_free(ctx->track_info[track_idx].track_text[channel_idx]);
                    ctx->track_info[track_idx].track_text[channel_idx] = NULL;
                }
            }
        }

        sa_free(ctx->track_info);
        ctx->track_info = NULL;
    }

    /* Free frame access list */
    sa_free(ctx->frame_info.frame_start);
    ctx->frame_info.frame_start = NULL;
    sa_free(ctx->frame_info.access_margin);
    ctx->frame_info.access_margin = NULL;

    /* Free audio data reader */
    if (ctx->frame_reader)
    {
      ctx->frame_reader->ops->destroy(ctx->frame_reader);
      ctx->frame_reader = NULL;
    }
}

/**
 * @brief Initialize Area TOC by reading and parsing disc data
 *
 * This function performs the complete Area TOC initialization:
 * 1. Reads TOC sectors from disc
 * 2. Validates structure signatures
 * 3. Extracts and converts all metadata (tracks, text, indices, etc.)
 * 4. Builds lookup tables for efficient access
 * 5. Initializes the appropriate audio reader
 *
 * The implementation reads these Area TOC structures:
 * - Area_TOC_0 (sector 0): Main area metadata
 * - Track_List_1 (sector 1): Track LSN addresses and sector lengths
 * - Track_List_2 (sector 2): Track time codes, lengths, and flags
 * - ISRC_and_Genre_List (sectors 3-4): ISRC codes and genre info
 * - Access_List (optional, 32 sectors): Frame address table for DST seeking
 * - Track_Text (optional, variable): Multi-language track metadata
 * - Index_List (optional, variable): Sub-track index points
 */
int sacd_area_toc_read(area_toc_t *ctx, uint32_t toc_copy_index, uint32_t toc_area1_start,
                      uint32_t toc_area2_start, uint16_t toc_area_length,
                      channel_t area_type, sacd_input_t *input)
{
    int result = SACD_AREA_TOC_OK;
    uint32_t toc_start_lsn;
    uint8_t *sector_buffer = NULL;
    uint8_t *scratch_buffer = NULL;
    uint32_t sector_size = 0;
    int16_t header_size = 0, trailer_size = 0;
    uint32_t sectors_read;

    // Struct pointers mapping into sector buffer
    area_data_t *toc_header;
    track_list_1_t *track_list1;
    track_list_2_t *track_list2;
    isrc_genre_list_1_t *isrc_genre_list1;
    isrc_genre_list_2_t *isrc_genre_list2;
    access_list_t *access_list;
    track_text_header_t *track_text_header;
    index_list_t *index_list;

    // Reset context
    sacd_area_toc_close(ctx);

    ctx->cur_frame_num_data = 0;
    ctx->cur_track_num = 1;
    ctx->cur_index_num = 1;
    ctx->cur_frame_num_text = 0;
    ctx->frame_start = 1;
    ctx->frame_stop = 1;

    // 1. Get Geometry
    sacd_input_get_sector_size(input, &sector_size);
    sacd_input_get_header_size(input, &header_size);
    sacd_input_get_trailer_size(input, &trailer_size);

    // 2. Allocate Buffers
    /* RawSMusic: reject truncated/oversized metadata before mapping fixed TOC sectors. */
    /* Length zero is upstream's temporary initialization context, never mapped. */
    if (toc_area_length != 0 && (toc_area_length < 5 || toc_area_length > 4096))
        return SACD_AREA_TOC_INVALID_SIGNATURE;
    // Allocate the main sector buffer
    sector_buffer = (uint8_t *)sa_calloc((size_t)toc_area_length + 1, sector_size);
    if (sector_buffer == NULL) return SACD_AREA_TOC_MEMORY_ALLOCATION_ERROR;

    scratch_buffer = (uint8_t *)sa_malloc(65536);
    if (scratch_buffer == NULL) {
        sa_free(sector_buffer);
        return SACD_AREA_TOC_MEMORY_ALLOCATION_ERROR;
    }

    // 3. Read TOC Data
    toc_start_lsn = (toc_copy_index == 2) ? toc_area2_start : toc_area1_start;

    if (sacd_input_read_sectors(input, toc_start_lsn, toc_area_length, sector_buffer,
                                 &sectors_read) != 0) {
        result = SACD_AREA_TOC_IO_ERROR;
        goto cleanup;
    }

    if (sectors_read != toc_area_length) {
        result = SACD_AREA_TOC_NO_DATA;
        goto cleanup;
    }

    if (toc_area_length == 0) {
        goto cleanup;
    }

    // 4. Map Structures
    // Note: Pointer arithmetic depends on sector alignment being contiguous in buffer
    toc_header       = (area_data_t *)(header_size + sector_buffer);
    track_list1      = (track_list_1_t *)(1 * sector_size + header_size + sector_buffer);
    track_list2      = (track_list_2_t *)(2 * sector_size + header_size + sector_buffer);
    isrc_genre_list1 = (isrc_genre_list_1_t *)(3 * sector_size + header_size + sector_buffer);
    isrc_genre_list2 = (isrc_genre_list_2_t *)(4 * sector_size + header_size + sector_buffer);

    uint16_t access_list_offset = ntoh16(toc_header->access_list_ptr);
    uint16_t track_text_offset  = ntoh16(toc_header->track_text_ptr);
    uint16_t index_list_offset  = ntoh16(toc_header->index_list_ptr);

    // Validate offsets are within TOC bounds
    if ((access_list_offset != 0 && access_list_offset >= toc_area_length) ||
        (track_text_offset != 0 && track_text_offset >= toc_area_length) ||
        (index_list_offset != 0 && index_list_offset >= toc_area_length)) {
        result = SACD_AREA_TOC_INVALID_SIGNATURE;
        goto cleanup;
    }

    // Calculate absolute pointers
    access_list       = (access_list_t *)((uint8_t *)toc_header + (access_list_offset * sector_size));
    track_text_header = (track_text_header_t *)((uint8_t *)toc_header + (track_text_offset * sector_size));
    index_list        = (index_list_t *)((uint8_t *)toc_header + (index_list_offset * sector_size));

    // 5. Validate Signatures
    bool area_signature_valid = (area_type == TWO_CHANNEL) ?
                      (toc_header->signature == AREA_2CH_TOC_SIGN) :
                      (toc_header->signature == AREA_MCH_TOC_SIGN);

    bool required_signatures_valid = (track_list1->signature == TRACK_LIST1_SIGN &&
                      track_list2->signature == TRACK_LIST2_SIGN &&
                      isrc_genre_list1->signature == ISRC_GENRE_SIGN);

    bool optional_signatures_valid = ((access_list_offset == 0 || access_list->signature == ACCESS_LIST_SIGN) &&
                      (track_text_offset == 0 || track_text_header->signature == TRACK_TEXT_SIGN) &&
                      (index_list_offset == 0 || index_list->signature == INDEX_LIST_SIGN));

    if (!area_signature_valid || !required_signatures_valid || !optional_signatures_valid) {
        result = SACD_AREA_TOC_INVALID_SIGNATURE;
        goto cleanup;
    }

    // 6. Extract Header Info
    ctx->cur_text_channel = 0;
    /* Validate and clamp text_channel_count to valid range (0-8) */
    uint8_t raw_text_channel_count = toc_header->text_channels.text_channel_count;
    ctx->text_channel_count = (raw_text_channel_count <= MAX_TEXT_CHANNEL_COUNT) ? raw_text_channel_count : 0;
    ctx->frame_format = toc_header->frame_format;
    ctx->version = toc_header->version;
    ctx->max_byte_rate = ntoh32(toc_header->max_byte_rate);
    ctx->fs_code = toc_header->fs_code;
    ctx->loudspeaker_config = toc_header->loudspeaker_config;
    ctx->extra_settings = toc_header->extra_settings;
    ctx->track_offset = toc_header->track_offset;
    ctx->track_count = toc_header->track_count;
    ctx->max_available_channels = toc_header->max_available_channels;
    ctx->mute_flags = toc_header->area_mute_flags;
    ctx->track_attribute = toc_header->track_attribute;
    ctx->track_area_start = ntoh32(toc_header->track_area_start_address);
    ctx->track_area_end = ntoh32(toc_header->track_area_end_address);
    ctx->total_area_play_time = time_to_frame(toc_header->total_area_play_time);

    // Validate Channel Count
    bool channel_count_valid = ((toc_header->channel_count == 2 && area_type == TWO_CHANNEL) ||
                     (toc_header->channel_count == 5 && area_type == MULTI_CHANNEL) ||
                     (toc_header->channel_count == 6 && area_type == MULTI_CHANNEL));

    if (!channel_count_valid) {
        result = SACD_AREA_TOC_CHANNEL_COUNT;
        goto cleanup;
    }
    ctx->channel_count = toc_header->channel_count;

    // 7. Process Text Channels (Album Info)
    // First, initialize ALL channel info and text pointers to safe defaults
    for (int channel_idx = 0; channel_idx < MAX_TEXT_CHANNEL_COUNT; channel_idx++) {
        ctx->channel_info[channel_idx].character_set_code = 0;
        ctx->channel_info[channel_idx].language_code = 0;
        for (uint32_t text_type_idx = 0; text_type_idx < MAX_AREA_TEXT_TYPE_COUNT; text_type_idx++) {
            ctx->area_info.text[channel_idx][text_type_idx] = NULL;
        }
    }

    // Only process text channels that are actually used (channel_idx < text_channel_count)
    // Unused channels contain garbage data on disc
    for (uint32_t channel_idx = 0; channel_idx < ctx->text_channel_count && channel_idx < MAX_TEXT_CHANNEL_COUNT; channel_idx++) {
        ctx->channel_info[channel_idx].character_set_code = toc_header->text_channels.info[channel_idx].character_set_code;
        ctx->channel_info[channel_idx].language_code = toc_header->text_channels.info[channel_idx].language_code;

        /* Skip text channels with invalid codes */
        if (ctx->channel_info[channel_idx].character_set_code == 0 || ctx->channel_info[channel_idx].language_code == 0) {
            continue;
        }

        for (uint32_t text_type_idx = 0; text_type_idx < MAX_AREA_TEXT_TYPE_COUNT; text_type_idx++) {
            uint16_t text_offset = 0;
            switch (text_type_idx) {
                case AREA_TEXT_TYPE_NAME: text_offset = toc_header->area_text[channel_idx].area_description_ptr; break;
                case AREA_TEXT_TYPE_COPYRIGHT: text_offset = toc_header->area_text[channel_idx].area_copyright_ptr; break;
                case AREA_TEXT_TYPE_NAME_PHONETIC: text_offset = toc_header->area_text[channel_idx].area_description_phonetic_ptr; break;
                case AREA_TEXT_TYPE_COPYRIGHT_PHONETIC: text_offset = toc_header->area_text[channel_idx].area_copyright_phonetic_ptr; break;
            }
            text_offset = ntoh16(text_offset);

            /* Bounds check: offset must be non-zero and within sector 0 (area_data_t) */
            if (text_offset != 0 && text_offset < SACD_LSN_SIZE) {
                ctx->area_info.text[channel_idx][text_type_idx] = sacd_special_string_to_utf8((const char *)toc_header + text_offset, ctx->channel_info[channel_idx].character_set_code);
            }
        }
    }

    // 8. Allocate Track Info Array
    ctx->track_info = (area_toc_track_info_t *)sa_calloc(ctx->track_count, sizeof(area_toc_track_info_t));
    if (ctx->track_info == NULL) {
        result = SACD_AREA_TOC_MEMORY_ALLOCATION_ERROR;
        goto cleanup;
    }

    // 9. Process Tracks
    uint32_t running_track_start_frame = 0;

    for (uint8_t track_num = 0; track_num < ctx->track_count; track_num++) {
        area_toc_track_info_t *current_track = &ctx->track_info[track_num];

        // --- ISRC & Basic Info ---
        if (track_num < ISRC_FIRST_SECTOR_COUNT) {
            memcpy(&current_track->isrc,
                    &isrc_genre_list1->isrc_1[track_num], sizeof(area_isrc_t));
        } else {
            memcpy(&current_track->isrc,
                    &isrc_genre_list2->isrc_2[track_num - ISRC_FIRST_SECTOR_COUNT], sizeof(area_isrc_t));
        }

        current_track->genre.genre_table = isrc_genre_list2->genre[track_num].genre_table;
        current_track->genre.index       = ntoh16(isrc_genre_list2->genre[track_num].genre_index);
        current_track->track_length      = time_to_frame(track_list2->info_2[track_num].track_time_length);
        current_track->track_mode        = track_list2->info_1[track_num].track_mode;
        
        // Flags
        current_track->track_flag_tmf1   = track_list2->info_2[track_num].track_flag_tmf1 == 1;
        current_track->track_flag_tmf2   = track_list2->info_2[track_num].track_flag_tmf2 == 1;
        current_track->track_flag_tmf3   = track_list2->info_2[track_num].track_flag_tmf3 == 1;
        current_track->track_flag_tmf4   = track_list2->info_2[track_num].track_flag_tmf4 == 1;
        current_track->track_flag_ilp    = track_list2->info_2[track_num].track_flag_ilp == 1;
        
        /* Track start LSN: per the SACD spec (§3.2.2.2), Track_Start_Address[tno]
         * is the LSN of the first sector of Track[tno], which follows Pause[tno].
         * Track_Area_Start_Address points to the start of Pause[1], not Track[1].
         * All tracks use the per-track start address from Track_List_1. */
        current_track->track_start_lsn = ntoh32(track_list1->track_start_lsn[track_num]);

        /* Track sector length: use contiguous layout based on next track's start
         * (or area end for the last track) instead of stored lengths, ensuring
         * no sectors are missed between tracks. */
        if (track_num + 1 < ctx->track_count) {
            current_track->track_sector_length =
                ntoh32(track_list1->track_start_lsn[track_num + 1])
                - current_track->track_start_lsn + 1;
        } else {
            current_track->track_sector_length =
                ctx->track_area_end - current_track->track_start_lsn + 1;
        }
        current_track->index_start       = NULL;

        // --- Index Points ---
        uint8_t index_count = 0;
        uint16_t index_offset = 0;
        index_list_t *scratch_index_list = NULL;
        uint32_t index_scratch_size = 0;

        if (index_list_offset != 0) {
            // Reassemble index sectors into scratch buffer
            int sector_limit = (toc_area_length - index_list_offset < 10) ? (toc_area_length - index_list_offset) : 10;
            // Ensure we don't overflow the 65536 byte scratch buffer
            if (sector_limit * SACD_LSN_SIZE > 65536) {
                sector_limit = 65536 / SACD_LSN_SIZE;
            }
            index_scratch_size = (uint32_t)(sector_limit * SACD_LSN_SIZE);
            for (int s = 0; s < sector_limit; s++) {
                size_t dest_offset = s * SACD_LSN_SIZE;
                if (dest_offset + SACD_LSN_SIZE <= 65536) {
                    memcpy(&scratch_buffer[dest_offset],
                           (uint8_t *)index_list + s * sector_size, SACD_LSN_SIZE);
                }
            }
            scratch_index_list = (index_list_t *)scratch_buffer;

            index_offset = ntoh16(index_list->index_ptr[track_num]);
            /* Bounds check: index_offset must be non-zero and within scratch buffer */
            if (index_offset != 0 && index_offset < index_scratch_size) {
                index_count = scratch_index_list->index_start->stored_index_count;
                /* Ensure index_count doesn't exceed array bounds */
                if (index_count > (MAX_INDEX_COUNT - 1)) {
                    index_count = MAX_INDEX_COUNT - 1;
                }
            }
        }

        current_track->index_count = (uint8_t)(index_count + 2);
        current_track->index_start = (uint32_t *)sa_malloc((index_count + 2) * sizeof(uint32_t));
        if (current_track->index_start == NULL) {
            result = SACD_AREA_TOC_MEMORY_ALLOCATION_ERROR;
            goto cleanup;
        }

        // Set Index 0 and 1
        current_track->index_start[0] = running_track_start_frame;
        running_track_start_frame = time_to_frame(track_list2->info_1[track_num].track_start_time_code);
        current_track->index_start[1] = running_track_start_frame;
        
        // Update running start for next track
        running_track_start_frame += time_to_frame(track_list2->info_2[track_num].track_time_length);

        // Fill extra indexes
        if (index_count > 0 && scratch_index_list) {
             for (uint8_t idx = 0; idx < index_count; idx++) {
                 current_track->index_start[idx + 2] = time_to_frame(scratch_index_list->index_start->index_start_tc[idx]);
             }
        }

        // --- Track Text ---
        memset(current_track->track_text_item_count, 0, sizeof(current_track->track_text_item_count));
        for (int i = 0; i < MAX_TEXT_CHANNEL_COUNT; i++) { 
            current_track->track_text[i] = NULL;
        }

        if (track_text_offset != 0) {
            // Reassemble text sectors into scratch buffer
            int sector_limit = (toc_area_length - track_text_offset < 32) ? (toc_area_length - track_text_offset) : 32;
            // Ensure we don't overflow the 65536 byte scratch buffer
            if (sector_limit * SACD_LSN_SIZE > 65536) {
                sector_limit = 65536 / SACD_LSN_SIZE;
            }
            uint32_t track_text_scratch_size = (uint32_t)(sector_limit * SACD_LSN_SIZE);
            for (int s = 0; s < sector_limit; s++) {
                size_t dest_offset = s * SACD_LSN_SIZE;
                if (dest_offset + SACD_LSN_SIZE <= 65536) {
                    memcpy(&scratch_buffer[dest_offset],
                           (uint8_t *)track_text_header + s * sector_size, SACD_LSN_SIZE);
                }
            }
            // Use local var to shadow outer scope for clarity
            track_text_header_t *local_track_text = (track_text_header_t *)scratch_buffer;

            // Only process text channels that are actually used (i < text_channel_count)
            for (uint32_t i = 0; i < ctx->text_channel_count && i < MAX_TEXT_CHANNEL_COUNT; i++) {
                /* Skip text channels with invalid codes */
                if (ctx->channel_info[i].character_set_code == 0 || ctx->channel_info[i].language_code == 0) {
                    continue;
                }

                uint32_t pointer_index = (i * ctx->track_count) + track_num;
                if (8 + (pointer_index + 1) * 2 > track_text_scratch_size) {
                    result = SACD_AREA_TOC_INVALID_SIGNATURE;
                    goto cleanup;
                }
                uint16_t offset = ntoh16(local_track_text->track_text_item_ptr[pointer_index]);

                /* Bounds check: offset must be non-zero and within scratch buffer */
                if (offset != 0 && offset < track_text_scratch_size) {
                    text_item_t *p_text_item = (text_item_t *)((uint8_t *)local_track_text + offset);
                    uint8_t item_count = p_text_item->num_items;
                    const uint32_t item_available = track_text_scratch_size - offset;
                    if (item_available < 4 || item_count > 16) {
                        result = SACD_AREA_TOC_INVALID_SIGNATURE;
                        goto cleanup;
                    }
                    if (item_count == 0) continue;

                    current_track->track_text_item_count[i] = item_count;
                    current_track->track_text[i] = (area_toc_text_track_t *)sa_calloc(item_count, sizeof(area_toc_text_track_t));

                    if (current_track->track_text[i] == NULL) {
                        result = SACD_AREA_TOC_MEMORY_ALLOCATION_ERROR;
                        goto cleanup;
                    }

                    offset = 0;
                    for (uint8_t item_num = 0; item_num < item_count; item_num++) {
                        const uint32_t entry_offset = 4u + offset;
                        if (entry_offset + 3 > item_available) {
                            result = SACD_AREA_TOC_INVALID_SIGNATURE;
                            goto cleanup;
                        }
                        toc_text_t *text_entry = (toc_text_t *)((uint8_t *)&p_text_item->text + offset);
                        uint32_t available_text = item_available - entry_offset - 2;
                        if (available_text > 256) available_text = 256;
                        const char* terminator = memchr(text_entry->text, 0, available_text);
                        if (!terminator) {
                            result = SACD_AREA_TOC_INVALID_SIGNATURE;
                            goto cleanup;
                        }

                        current_track->track_text[i][item_num].text_type = text_entry->type;
                        current_track->track_text[i][item_num].text = sacd_special_string_to_utf8(text_entry->text, ctx->channel_info[i].character_set_code);

                        uint16_t text_length = sacd_special_string_len(text_entry->text, ctx->channel_info[i].character_set_code);
                        // Align to next 4-byte boundary
                        /* RawSMusic: include type/padding plus the string terminator. */
                        uint32_t terminator_bytes = (ctx->channel_info[i].character_set_code >= 3 &&
                            ctx->channel_info[i].character_set_code <= 6) ? 2 : 1;
                        offset = (uint16_t)(offset + ((2u + text_length + terminator_bytes + 3u) & ~0x03u));
                    }
                }
            }
        }
    }

    // 10. Parse Access List
    ctx->frame_info.frame_start = NULL;
    ctx->frame_info.access_margin = NULL;
    if (access_list_offset != 0) {
        // Ensure we don't overflow the 65536 byte scratch buffer
        int access_limit = (32 * SACD_LSN_SIZE > 65536) ? (65536 / SACD_LSN_SIZE) : 32;
        for (int s = 0; s < access_limit; s++) {
            size_t dest_offset = s * SACD_LSN_SIZE;
            if (dest_offset + SACD_LSN_SIZE <= 65536) {
                memcpy(&scratch_buffer[dest_offset],
                       (uint8_t *)access_list + (s * sector_size), SACD_LSN_SIZE);
            }
        }
        access_list_t *scratch_access_list = (access_list_t *)scratch_buffer;

        ctx->frame_info.step_size = scratch_access_list->main_step_size;
        ctx->frame_info.num_entries = ntoh16(scratch_access_list->entry_count);

        ctx->frame_info.frame_start = (uint32_t *)sa_malloc(ctx->frame_info.num_entries * sizeof(uint32_t));
        if (ctx->frame_info.frame_start == NULL) {
            result = SACD_AREA_TOC_MEMORY_ALLOCATION_ERROR;
            goto cleanup;
        }

        ctx->frame_info.access_margin = (uint16_t *)sa_malloc(ctx->frame_info.num_entries * sizeof(uint16_t));
        if (ctx->frame_info.access_margin == NULL) {
            result = SACD_AREA_TOC_MEMORY_ALLOCATION_ERROR;
            goto cleanup;
        }

        for (uint16_t entry_idx = 0; entry_idx < ctx->frame_info.num_entries; entry_idx++) {
            /* Parse 3-byte big-endian entry as: entry[0] << 16 | entry[1] << 8 | entry[2] */
            uint8_t *entry = scratch_access_list->main_acc_list[entry_idx].entry;
            ctx->frame_info.frame_start[entry_idx] = ((uint32_t)entry[0] << 16)
                                                   | ((uint32_t)entry[1] << 8)
                                                   | ((uint32_t)entry[2]);

            /* Parse access_flags and extract margin (bits 0-14, bit 15 is detailed_access flag) */
            uint16_t flags = ntoh16(scratch_access_list->main_acc_list[entry_idx].access_flags);
            ctx->frame_info.access_margin[entry_idx] = flags & 0x7FFF;
        }
    }

    // 11. Create Audio Structure
    switch (ctx->frame_format) {
        case FRAME_FORMAT_DSD_3_IN_14: {
            sacd_frame_reader_t *p;
            if (sacd_frame_reader_fixed14_create(&p) != SACD_FRAME_READER_OK) {
                result = SACD_AREA_TOC_MEMORY_ALLOCATION_ERROR;
                goto cleanup;
            }
            ctx->frame_reader = p;
            break;
        }
        case FRAME_FORMAT_DSD_3_IN_16: {
        sacd_frame_reader_t *p;
        if (sacd_frame_reader_fixed16_create(&p) != SACD_FRAME_READER_OK) {
            result = SACD_AREA_TOC_MEMORY_ALLOCATION_ERROR;
            goto cleanup;
        }
        ctx->frame_reader = p;
        break;
        }
        case FRAME_FORMAT_DST: {
        sacd_frame_reader_t *p;
        if (sacd_frame_reader_dst_create(&p, ctx) != SACD_FRAME_READER_OK) {
            result = SACD_AREA_TOC_MEMORY_ALLOCATION_ERROR;
            goto cleanup;
        }
        ctx->frame_reader = p;
        break;
        }
        default:
            result = SACD_AREA_TOC_FRAME_FORMAT;
            goto cleanup;
    }

    sacd_frame_reader_init(ctx->frame_reader, input, ctx->track_area_start,
                           ctx->track_area_end, sector_size, header_size,
                           trailer_size);

    // Success - mark as initialized
    ctx->initialized = true;

cleanup:
    sa_free(sector_buffer);
    sa_free(scratch_buffer);

    if (result != SACD_AREA_TOC_OK) {
        // If we failed, ensure we don't leak track_info contents
        sacd_area_toc_close(ctx);
    }

    return result;
}

/* ========================================================================
 * Specification and Text Channel Queries
 * ======================================================================== */

sacd_version_t sacd_area_toc_get_version(area_toc_t *ctx)
{
    static const sacd_version_t empty_version = {0, 0};
    if (!ctx->initialized) {
        return empty_version;
    }
    return ctx->version;
}

uint8_t sacd_area_toc_get_text_channel_count(area_toc_t *ctx)
{
    if (!ctx->initialized) {
        return 0;
    }
    return (uint8_t)ctx->text_channel_count;
}

/**
 * @brief Get language code and character set for a specific text channel.
 *
 * @param text_channel_nr Text channel number (1-based, 1 to text_channel_count)
 * @param language_code Output: Pointer to 2-character ISO 639 language code (NOT null-terminated)
 * @param character_set_code Output: Character set code (1=ISO646, 2=ISO8859-1, etc.)
 * @return SACD_AREA_TOC_OK on success, SACD_AREA_TOC_INVALID_ARGUMENT if channel number is out of range.
 */
int sacd_area_toc_get_text_channel_info(area_toc_t *ctx, uint8_t channel_number,
                                      char **out_language_code, uint8_t *out_charset_code)
{
    /* Validate context is initialized */
    if (!ctx->initialized) {
        return SACD_AREA_TOC_UNINITIALIZED;
    }

    /* Validate channel number is in valid range (1-based) */
    if ((channel_number > ctx->text_channel_count) || (channel_number < 1))
    {
        return SACD_AREA_TOC_INVALID_ARGUMENT;
    }
    *out_language_code = (char *)&(ctx->channel_info[channel_number - 1].language_code);
    *out_charset_code = ctx->channel_info[channel_number - 1].character_set_code;
    return SACD_AREA_TOC_OK;
}

/* ========================================================================
 * Current Position Management
 * ======================================================================== */

uint8_t sacd_area_toc_get_current_track_num(area_toc_t *ctx)
{
    if (!ctx->initialized) {
        return 0;
    }
    return ctx->cur_track_num;
}

uint8_t sacd_area_toc_get_current_index_num(area_toc_t *ctx)
{
    if (!ctx->initialized) {
        return 0;
    }
    return ctx->cur_index_num;
}

uint32_t sacd_area_toc_get_current_frame_num(area_toc_t *ctx)
{
    if (!ctx->initialized) {
        return 0;
    }
    return ctx->cur_frame_num_data;
}

bool sacd_area_toc_set_current_frame_num(area_toc_t *ctx, uint32_t frame_num)
{
    if (!ctx->initialized) {
        return false;
    }
    ctx->cur_frame_num_data = frame_num;
    ctx->cur_frame_num_text = frame_num;
    return true;
}

bool sacd_area_toc_set_current_track_num(area_toc_t *ctx, uint8_t track_num)
{
    if (!ctx->initialized) {
        return false;
    }
    ctx->cur_track_num = track_num;
    return true;
}

bool sacd_area_toc_set_current_index_num(area_toc_t *ctx, uint8_t index_num)
{
    if (!ctx->initialized) {
        return false;
    }
    ctx->cur_index_num = index_num;
    return true;
}

/* ========================================================================
 * Frame and Sector Operations
 * ======================================================================== */

/**
 * @brief Get the Logical Sector Number (LSN) for a specific frame.
 *
 * Uses the Access List (for DST-coded audio) to determine the approximate disc sector
 * address containing the specified frame. The Access List provides entry points at
 * regular intervals (step_size frames) for efficient seeking.
 *
 * For DST-compressed audio, the access list is essential since frame boundaries don't
 * align with sector boundaries. For plain DSD audio, returns 0 (not used).
 *
 * @param frame_num Frame number to look up (0 to total_area_play_time-1).
 * @return LSN of the sector containing or near the frame, or 0 if access list not available.
 */
uint32_t sacd_area_toc_get_frame_lsn(area_toc_t *ctx, uint32_t frame_num)
{
    if (!ctx->initialized) {
        return 0;
    }

    /* Return 0 if access list is not available (used for plain DSD audio) */
    if (!ctx->frame_info.frame_start || ctx->frame_info.num_entries == 0)
    {
        return 0;
    }

    /* Calculate which access list entry to use based on frame number and step size */
    uint16_t entry_index = (uint16_t)(frame_num / ctx->frame_info.step_size);
    uint16_t max_entry_index = (uint16_t)(ctx->frame_info.num_entries - 1);

    /* Clamp entry_index to valid range */
    if (entry_index > max_entry_index)
    {
        entry_index = max_entry_index;
    }

    /* Get LSN from access list */
    uint32_t lsn = ctx->frame_info.frame_start[entry_index];

    /* Ensure LSN doesn't exceed track area boundary */
    if (lsn > ctx->track_area_end)
    {
        lsn = ctx->track_area_end;
    }

    return lsn;
}

frame_format_t sacd_area_toc_get_frame_format_enum(area_toc_t *ctx)
{
    if (!ctx->initialized) {
        return FRAME_FORMAT_UNKNOWN;
    }
    return ctx->frame_format;
}

/**
 * @brief Read audio data for a specific frame.
 *
 * Retrieves decoded audio data for the specified frame from the appropriate audio reader
 * (DST or DSD). Automatically advances the current frame position if FRAME_START_USE_CURRENT is used.
 *
 * @param p_data Output buffer for audio data.
 * @param length Input/Output: Buffer size on input, actual data length on output.
 * @param frame_num Frame number to read, or FRAME_START_USE_CURRENT for current position.
 * @param data_type Type of data to extract (DATA_TYPE_AUDIO, DATA_TYPE_SUPPLEMENTARY, etc.).
 * @return SACD_AREA_TOC_OK on success, SACD_AREA_TOC_END_OF_AUDIO_DATA when reaching end of area, or error code.
 */
int sacd_area_toc_get_audio_data(area_toc_t *ctx, uint8_t *out_data, uint32_t *length,
                             uint32_t frame_num, audio_packet_data_type_t data_type)
{
    uint32_t frame_lsn;
    uint32_t target_frame;
    int read_result;

    /* Check if audio_data reader is initialized */
    if (!ctx->initialized) {
      return SACD_AREA_TOC_UNINITIALIZED;
    }

    /* Determine which frame to read and get its LSN */
    if (frame_num == FRAME_START_USE_CURRENT)
    {
        frame_lsn = sacd_area_toc_get_frame_lsn(ctx, ctx->cur_frame_num_data);
        target_frame = ctx->cur_frame_num_data;
    }
    else
    {
        frame_lsn = sacd_area_toc_get_frame_lsn(ctx, frame_num);
        target_frame = frame_num;
    }

    /* Delegate to the audio data reader (DST or DSD) */
    read_result = sacd_frame_reader_read_frame(
        ctx->frame_reader, out_data, length,
                                     target_frame, frame_lsn, data_type);
    if (read_result == SACD_AREA_TOC_OK)
    {
        /* Auto-advance current position if using FRAME_START_USE_CURRENT */
        if (frame_num == FRAME_START_USE_CURRENT)
        {
            if ((ctx->cur_frame_num_data + 1) > (ctx->total_area_play_time - 1))
            {
                /* Wrapped to beginning - signal end of audio data */
                ctx->cur_frame_num_data = 0;
                read_result = SACD_AREA_TOC_END_OF_AUDIO_DATA;
            }
            else
            {
                ctx->cur_frame_num_data++;
            }
        }
    }
    return read_result;
}

/**
 * @brief Get sector information for a specific frame.
 *
 * Determines which disc sectors contain the audio data for the specified frame.
 * Delegates to the audio data reader's implementation.
 *
 * @param frame Frame number to query.
 * @param start_sector_nr Output: LSN of first sector for this frame.
 * @param num_sectors Output: Number of sectors for this frame.
 * @return SACD_AREA_TOC_OK on success, or error code.
 */
int sacd_area_toc_get_frame_sector_range(area_toc_t *ctx, uint32_t frame,
                                 uint32_t *out_start_sector, int *out_sector_count)
{
    uint32_t frame_lsn;

    /* Check if audio_data reader is initialized */
    if (!ctx->initialized) {
      return SACD_AREA_TOC_UNINITIALIZED;
    }

    frame_lsn = sacd_area_toc_get_frame_lsn(ctx, frame);
    if (sacd_frame_reader_get_sector(ctx->frame_reader, frame,
                                                       frame_lsn, out_start_sector,
                                                       out_sector_count) != 0) {
        return SACD_AREA_TOC_IO_ERROR;
    }
    return SACD_AREA_TOC_OK;
}

/* ========================================================================
 * Area Properties
 * ======================================================================== */

uint32_t sacd_area_toc_get_total_play_time(area_toc_t *ctx)
{
    if (!ctx->initialized) {
        return 0;
    }
    return ctx->total_area_play_time;
}

uint32_t sacd_area_toc_get_sample_frequency(area_toc_t* ctx) {
  if (!ctx->initialized) {
    return 0;
  }
  return ctx->fs_code == 4 ? SACD_SAMPLING_FREQUENCY : 0;
}

uint8_t sacd_area_toc_get_sample_frequency_code(area_toc_t* ctx) {
  if (!ctx->initialized) {
    return 0;
  }
  return ctx->fs_code;
}

uint8_t sacd_area_toc_get_frame_format(area_toc_t *ctx)
{
    if (!ctx->initialized) {
        return 0;
    }
    return ctx->frame_format;
}

uint32_t sacd_area_toc_get_max_byte_rate(area_toc_t *ctx)
{
    if (!ctx->initialized) {
        return 0;
    }
    return ctx->max_byte_rate;
}

void sacd_area_toc_get_loudspeaker_config(area_toc_t *ctx, uint8_t *out_loudspeaker_config,
                                  uint8_t *out_ch4_usage)
{
    if (!ctx->initialized) {
        return;
    }
    *out_loudspeaker_config = ctx->loudspeaker_config;
    *out_ch4_usage = ctx->extra_settings;
}

uint8_t sacd_area_toc_get_mute_flags(area_toc_t *ctx)
{
    if (!ctx->initialized) {
        return 0;
    }
    return ctx->mute_flags;
}

uint8_t sacd_area_toc_get_max_available_channels(area_toc_t *ctx)
{
    if (!ctx->initialized) {
        return 0;
    }
    return ctx->max_available_channels;
}

uint8_t sacd_area_toc_get_copy_protection_flags(area_toc_t *ctx)
{
    if (!ctx->initialized) {
        return 0;
    }
    return ctx->track_attribute;
}

uint16_t sacd_area_toc_get_channel_count(area_toc_t *ctx)
{
    if (!ctx->initialized) {
        return 0;
    }
    return ctx->channel_count;
}

uint8_t sacd_area_toc_get_track_offset(area_toc_t *ctx)
{
    if (!ctx->initialized) {
        return 0;
    }
    return ctx->track_offset;
}

/* ========================================================================
 * Track Information
 * ======================================================================== */

uint8_t sacd_area_toc_get_track_count(area_toc_t *ctx)
{
    if (!ctx->initialized) {
        return 0;
    }
    return ctx->track_count;
}

uint8_t sacd_area_toc_get_track_index_count(area_toc_t *ctx, uint8_t track_num)
{
    if (!ctx->initialized) {
        return 0;
    }
    if (track_num < 1 || track_num > ctx->track_count || !ctx->track_info) {
        return 0;
    }
    return (uint8_t)(ctx->track_info[track_num - 1].index_count - 1);
}

area_isrc_t sacd_area_toc_get_track_isrc_num(area_toc_t *ctx, uint8_t track_num)
{
    static const area_isrc_t empty_isrc = {0};
    if (!ctx->initialized || track_num < 1 || track_num > ctx->track_count || !ctx->track_info) {
        return empty_isrc;
    }
    return ctx->track_info[track_num - 1].isrc;
}

uint8_t sacd_area_toc_get_track_mode(area_toc_t *ctx, uint8_t track_num)
{
    if (!ctx->initialized) {
        return 0;
    }
    if (track_num < 1 || track_num > ctx->track_count || !ctx->track_info) {
        return 0;
    }
    return ctx->track_info[track_num - 1].track_mode;
}

bool sacd_area_toc_get_track_flag_mute1(area_toc_t *ctx, uint8_t track_num)
{
    if (!ctx->initialized) {
        return false;
    }
    if (track_num < 1 || track_num > ctx->track_count || !ctx->track_info) {
        return false;
    }
    return ctx->track_info[track_num - 1].track_flag_tmf1;
}

bool sacd_area_toc_get_track_flag_mute2(area_toc_t *ctx, uint8_t track_num)
{
    if (!ctx->initialized) {
        return false;
    }
    if (track_num < 1 || track_num > ctx->track_count || !ctx->track_info) {
        return false;
    }
    return ctx->track_info[track_num - 1].track_flag_tmf2;
}

bool sacd_area_toc_get_track_flag_mute3(area_toc_t *ctx, uint8_t track_num)
{
    if (!ctx->initialized) {
        return false;
    }
    if (track_num < 1 || track_num > ctx->track_count || !ctx->track_info) {
        return false;
    }
    return ctx->track_info[track_num - 1].track_flag_tmf3;
}

bool sacd_area_toc_get_track_flag_mute4(area_toc_t *ctx, uint8_t track_num)
{
    if (!ctx->initialized) {
        return false;
    }
    if (track_num < 1 || track_num > ctx->track_count || !ctx->track_info) {
        return false;
    }
    return ctx->track_info[track_num - 1].track_flag_tmf4;
}

bool sacd_area_toc_get_track_flag_ilp(area_toc_t *ctx, uint8_t track_num)
{
    if (!ctx->initialized) {
        return false;
    }
    if (track_num < 1 || track_num > ctx->track_count || !ctx->track_info) {
        return false;
    }
    return ctx->track_info[track_num - 1].track_flag_ilp;
}

void sacd_area_toc_get_track_genre(area_toc_t *ctx, uint8_t track_num, uint8_t *out_genre_table,
                           uint16_t *out_genre_index)
{
    if (!ctx->initialized) {
        return;
    }
    if (track_num < 1 || track_num > ctx->track_count || !ctx->track_info) {
        return;
    }
    *out_genre_table = ctx->track_info[track_num - 1].genre.genre_table;
    *out_genre_index = ctx->track_info[track_num - 1].genre.index;
}

void sacd_area_toc_get_track_sectors(area_toc_t *ctx, uint8_t track_num,
                             uint32_t *out_start_sector, uint32_t *out_sector_count)
{
    if (!ctx->initialized) {
        return;
    }
    if (track_num < 1 || track_num > ctx->track_count || !ctx->track_info) {
        return;
    }
    *out_start_sector = ctx->track_info[track_num - 1].track_start_lsn;
    *out_sector_count = ctx->track_info[track_num - 1].track_sector_length;
}

uint32_t sacd_area_toc_get_track_frame_length(area_toc_t *ctx, uint8_t track_num)
{
    if (!ctx->initialized) {
        return 0;
    }
    if (track_num < 1 || track_num > ctx->track_count || !ctx->track_info) {
        return 0;
    }
    return ctx->track_info[track_num - 1].track_length;
}

uint32_t sacd_area_toc_get_track_pause(area_toc_t *ctx, uint8_t track_num)
{
    uint32_t pregap_start;
    uint32_t pregap_end;
    uint32_t pregap_length;

    pregap_start = sacd_area_toc_get_index_start(ctx, track_num, 0);
    pregap_end = sacd_area_toc_get_index_end(ctx, track_num, 0);
    pregap_length = (pregap_end + 1) - pregap_start;
    return pregap_length;
}

/* ========================================================================
 * Index Operations
 * ======================================================================== */

/**
 * @brief Get the start frame of an index within a track.
 *
 * Index 0 is the pre-gap, index 1 is the main track start. Additional indices
 * mark positions within the track (e.g., movements in classical music).
 *
 * @param track_num Track number (1-based).
 * @param index_num Index number (0 = pre-gap, 1 = main track, 2+ = sub-indices).
 * @return Start frame number for the index.
 */
uint32_t sacd_area_toc_get_index_start(area_toc_t *ctx, uint8_t track_num, uint8_t index_num)
{
    if (!ctx->initialized) {
        return 0;
    }
    if (track_num < 1 || track_num > ctx->track_count || !ctx->track_info) {
        return 0;
    }
    /* Validate index_num is within bounds */
    if (!ctx->track_info[track_num - 1].index_start ||
        index_num >= ctx->track_info[track_num - 1].index_count) {
        return 0;
    }
    /* Array uses 0-based indexing; index_num is NOT decremented because
     * index[0] stores the pre-gap start, not a real index point */
    return ctx->track_info[track_num - 1].index_start[index_num];
}

/**
 * @brief Get the end frame of an index within a track.
 *
 * @param track_num Track number (1-based).
 * @param index_num Index number (0-based).
 * @return Last frame number for the index (inclusive).
 */
uint32_t sacd_area_toc_get_index_end(area_toc_t *ctx, uint8_t track_num, uint8_t index_num)
{
    if (!ctx->initialized) {
        return 0;
    }
    if (track_num < 1 || track_num > ctx->track_count || !ctx->track_info) {
        return 0;
    }
    /* Validate index_num is within bounds */
    if (!ctx->track_info[track_num - 1].index_start ||
        index_num >= ctx->track_info[track_num - 1].index_count) {
        return 0;
    }
    /* If this is the last index, end is at track end; otherwise, end is one frame before next index */
    if (ctx->track_info[track_num - 1].index_count == (index_num + 1))
        return ctx->track_info[track_num - 1].track_length - 1 +
               sacd_area_toc_get_index_start(ctx, track_num, index_num);
    else
        return ctx->track_info[track_num - 1].index_start[index_num + 1] - 1;
}

/* ========================================================================
 * Text Metadata Operations
 * ======================================================================== */

/**
 * @brief Get area-level text metadata.
 *
 * Retrieves descriptive text for the entire audio area (not track-specific).
 *
 * @param text_channel_nr Text channel (language) number (1-based).
 * @param text_type Type of text to retrieve:
 *                  - AREA_TEXT_TYPE_NAME (0): Area description
 *                  - AREA_TEXT_TYPE_COPYRIGHT (1): Copyright notice
 *                  - AREA_TEXT_TYPE_NAME_PHONETIC (2): Phonetic area name
 *                  - AREA_TEXT_TYPE_COPYRIGHT_PHONETIC (3): Phonetic copyright
 * @return Pointer to UTF-8 text string, or NULL if not available.
 */
char *sacd_area_toc_get_area_text(area_toc_t *ctx, uint8_t channel_number,
                          area_text_type_t text_type)
{
    /* Validate context is initialized */
    if (!ctx->initialized) {
        return NULL;
    }
    /* Validate channel_number is within bounds (1-based) */
    if (channel_number < 1 || channel_number > MAX_TEXT_CHANNEL_COUNT) {
        return NULL;
    }
    /* Validate text_type is within bounds */
    if (text_type >= MAX_AREA_TEXT_TYPE_COUNT) {
        return NULL;
    }
    return ctx->area_info.text[channel_number - 1][text_type];
}

/**
 * @brief Get track-specific text metadata.
 *
 * Retrieves text metadata for a specific track (title, performer, composer, etc.).
 * Searches through the track's text items to find the requested type.
 *
 * @param track_num Track number (1-based).
 * @param text_channel_nr Text channel (language) number (1-based).
 * @param text_item Type of text to retrieve (track_type_t):
 *                  - TRACK_TYPE_TITLE (0x01): Track title
 *                  - TRACK_TYPE_PERFORMER (0x02): Performer/artist
 *                  - TRACK_TYPE_SONGWRITER (0x03): Songwriter
 *                  - TRACK_TYPE_COMPOSER (0x04): Composer
 *                  - TRACK_TYPE_ARRANGER (0x05): Arranger
 *                  - TRACK_TYPE_MESSAGE (0x06): Message
 *                  - TRACK_TYPE_EXTRA_MESSAGE (0x07): Extra message
 *                  - 0x81-0x87: Phonetic versions of the above
 * @param p_available Output: Set to false if text item not available for this track.
 * @return Pointer to UTF-8 text string, or NULL if not available.
 */
char *sacd_area_toc_get_track_text(area_toc_t *ctx, uint8_t track_num, uint8_t channel_number,
                           track_type_t text_item, bool *out_available)
{
    /* Validate context is initialized */
    if (!ctx->initialized) {
        *out_available = false;
        return NULL;
    }

    /* Validate track number */
    if (track_num < 1 || track_num > ctx->track_count || !ctx->track_info) {
        *out_available = false;
        return NULL;
    }

    /* Validate text channel number */
    if (channel_number < 1 || channel_number > MAX_TEXT_CHANNEL_COUNT) {
        *out_available = false;
        return NULL;
    }

    /* Convert to 0-based index for internal array access */
    int track_idx = track_num - 1;
    int channel_idx = channel_number - 1;

    /* Check if this track has any text for the specified channel */
    if (!ctx->track_info[track_idx].track_text[channel_idx])
    {
        *out_available = false;
        return NULL;
    }

    /* Search for the requested text type among the track's text items */
    for (int item_idx = 0; item_idx < ctx->track_info[track_idx].track_text_item_count[channel_idx]; item_idx++)
    {
        if (ctx->track_info[track_idx].track_text[channel_idx][item_idx].text_type == text_item)
        {
            *out_available = true;
            return ctx->track_info[track_idx].track_text[channel_idx][item_idx].text;
        }
    }

    /* Text item not found */
    *out_available = false;
    return NULL;
}

/**
 * @brief Calculate the search range for a frame using the access list.
 *
 * @param[in]  self             Pointer to the frame reader context
 * @param[in]  frame            Target frame number
 * @param[in]  start_lsn        First sector (LSN) of the Track Area
 * @param[in]  end_lsn          Last sector (LSN) of the Track Area
 * @param[out] from_lsn         Receives the starting LSN for the search
 * @param[out] to_lsn           Receives the ending LSN for the search
 *
 * @return SACD_AREA_TOC_OK on success, or error code
 */
int sacd_area_toc_get_access_list_range(area_toc_t *ctx,
                                    uint32_t frame,
                                    uint32_t start_lsn,
                                    uint32_t end_lsn,
                                    uint32_t *out_from_lsn,
                                    uint32_t *out_to_lsn)
{
    area_toc_frame_info_t *access_info;
    uint32_t access_index;
    uint32_t step_size;

    if (!ctx || !out_from_lsn || !out_to_lsn) {
        return SACD_AREA_TOC_INVALID_ARGUMENT;
    }

    access_info = &ctx->frame_info;

    /* Check if access list is available */
    if (access_info->step_size == 0 || access_info->num_entries == 0 ||
        !access_info->frame_start) {
        /* No access list - search entire track area */
        *out_from_lsn = start_lsn;
        *out_to_lsn = end_lsn;
        return SACD_AREA_TOC_OK;
    }

    step_size = access_info->step_size;

    /* Calculate which access list entry to use */
    access_index = frame / step_size;


    /* Validate entry index */
    if (access_index >= access_info->num_entries) {
        /* Frame is beyond access list - use last entry */
        access_index = access_info->num_entries - 1;
    }

    /* Get the entry's base LSN and access margin for safe interpolation */
    uint32_t entry_lsn = access_info->frame_start[access_index];
    uint16_t access_margin = access_info->access_margin ? access_info->access_margin[access_index] : 0;

    /*
     * Calculate search range:
     * - from_lsn: Start from estimated position with safety margin (never below entry_lsn)
     * - to_lsn: End at next entry or track area end
     *
     * The access_margin from the disc ensures the search starts early enough.
     */
    if (access_index + 1 < access_info->num_entries) {
        /* Calculate interpolated position within interval */
        uint32_t next_entry_lsn = access_info->frame_start[access_index + 1];
        uint32_t interval_sectors = next_entry_lsn - entry_lsn;
        uint32_t frame_offset = frame % step_size;
        uint32_t estimated_offset = (frame_offset * interval_sectors) / step_size;

        /* Calculate interpolated from_lsn with safety clamp */
        uint32_t interpolated_lsn = entry_lsn + estimated_offset;
        if (interpolated_lsn > access_margin) {
            *out_from_lsn = interpolated_lsn - access_margin;
        } else {
            *out_from_lsn = entry_lsn;
        }

        /* CRITICAL: Never start before the entry's base LSN */
        if (*out_from_lsn < entry_lsn) {
            *out_from_lsn = entry_lsn;
        }

        /* Upper bound is the next access list entry */
        *out_to_lsn = next_entry_lsn;
    } else {
        /* Last entry - search to track area end */
        uint32_t remaining_frames = ctx->total_area_play_time - (access_index * step_size);
        if (remaining_frames > 0) {
            uint32_t remaining_sectors = end_lsn - entry_lsn;
            uint32_t frame_offset = frame % step_size;
            uint32_t estimated_offset = (frame_offset * remaining_sectors) / remaining_frames;

            /* Calculate interpolated from_lsn with safety clamp */
            uint32_t interpolated_lsn = entry_lsn + estimated_offset;
            if (interpolated_lsn > access_margin) {
                *out_from_lsn = interpolated_lsn - access_margin;
            } else {
                *out_from_lsn = entry_lsn;
            }

            /* CRITICAL: Never start before the entry's base LSN */
            if (*out_from_lsn < entry_lsn) {
                *out_from_lsn = entry_lsn;
            }
        } else {
            *out_from_lsn = entry_lsn;
        }
        *out_to_lsn = end_lsn;
    }

    /* Defensive clamp to track area bounds (handles corrupted disc data) */
    if (*out_from_lsn < start_lsn) *out_from_lsn = start_lsn;
    if (*out_to_lsn > end_lsn) *out_to_lsn = end_lsn;

    return SACD_AREA_TOC_OK;
}

/* ========================================================================
 * Format String Helpers
 * ======================================================================== */

const char *sacd_area_toc_get_speaker_config_string(area_toc_t *ctx)
{
    if (ctx == NULL) {
        return "Unknown";
    }

    if (ctx->channel_count == 2 && ctx->loudspeaker_config == 0) {
        return "2.0 Stereo";
    }
    if (ctx->channel_count == 5 && ctx->loudspeaker_config == 3) {
        return "5.0 Surround";
    }
    if (ctx->channel_count == 6 && ctx->loudspeaker_config == 4) {
        return "5.1 Surround";
    }
    return "Unknown";
}

const char *sacd_area_toc_get_frame_format_string(area_toc_t *ctx)
{
    if (ctx == NULL) {
        return "Unknown";
    }

    switch (ctx->frame_format) {
    case FRAME_FORMAT_DST:
        return "DST";
    case FRAME_FORMAT_DSD_3_IN_14:
        return "DSD (3-in-14)";
    case FRAME_FORMAT_DSD_3_IN_16:
        return "DSD (3-in-16)";
    default:
        return "Unknown";
    }
}
