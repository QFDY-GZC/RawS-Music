/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief Implementation of DST (Direct Stream Transfer) coded audio data reader.
 * This file implements the DST reader for Super Audio CD disc images, handling
 * both DST-compressed. The implementation follows the
 * Scarlet Book specification for SACD audio frame and sector structures.
 * Key implementation details:
 * - Virtual method pattern for polymorphic audio data access
 * - Sector-based reading with dynamic memory allocation
 * - Frame location using time codes and packet headers
 * - Support for variable sector sizes (2048-2064 bytes)
 * - Multi-sector frame handling (1-16 sectors per frame)
 * @note Decryption handling:
 * Sector decryption is performed ONLY in the DST reader, not in DSD 14/16 readers.
 * This is because DST-coded audio requires sector-level decryption before the
 * compressed audio data can be parsed and extracted. DSD 14/16 readers operate
 * on already-decrypted or unencrypted data streams. The decrypt function from
 * sacd_input_t is called conditionally when available, and only for sectors
 * within the Track Area (start_sector to end_sector).
 * @see sacd_dst_reader.h for public API documentation
 * @see sacd_specification.h for SACD format structures
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

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <libsautil/mem.h>
#include <libsautil/bswap.h>

#include <libsautil/sastring.h>
#include <libsautil/compat.h>

#include "sacd_dst_reader.h"

/* Debug output - set to 1 to enable verbose debugging */
#define DST_READER_DEBUG 0

#if DST_READER_DEBUG
#define DST_DEBUG(fmt, ...) sa_fprintf(stderr, "[DST_DEBUG] " fmt "\n", ##__VA_ARGS__)
#else
#define DST_DEBUG(fmt, ...) ((void)0)
#endif

/**
 * @brief Maximum number of sectors a single DST frame can span.
 *
 * Per the Scarlet Book specification:
 * - 2-channel audio: maximum 7 sectors per frame
 * - 5-channel audio: maximum 14 sectors per frame
 * - 6-channel audio: maximum 16 sectors per frame
 *
 * This constant is used for buffer allocation when reading frames.
 */
#define MAX_DST_SECTORS 16

/**
 * @brief Size of audio sector header (1 byte for flags)
 */
#define AUDIO_SECTOR_HEADER_SIZE 1

/**
 * @brief Size of a single packet info entry (2 bytes)
 */
#define AUDIO_PACKET_INFO_SIZE 2

/**
 * @brief Size of frame info for DST coded audio (4 bytes: 3 for time code, 1 for channel/sector info)
 */
#define AUDIO_FRAME_INFO_SIZE_DST 4

/**
 * @brief Size of frame info for DSD audio (3 bytes: time code only)
 */
#define AUDIO_FRAME_INFO_SIZE_DSD 3

/**
 * @struct sacd_frame_reader_dst_t
 * @brief Extended structure for DST frame reading.
 *
 * The base struct MUST be the first member for safe casting.
 * This structure maintains position state for efficient sequential reading.
 */
typedef struct sacd_frame_reader_dst {
    sacd_frame_reader_t base;              /**< Base structure (must be first!) */
    uint8_t *sector_buffer;                /**< Buffer for reading sectors */
    uint32_t buffer_sector_count;          /**< Number of sectors in buffer */

    area_toc_t *area;

    /* Position tracking for sequential reads */
    uint32_t cached_frame_num;             /**< Last frame number read */
    uint32_t cached_frame_lsn;             /**< LSN where next frame starts */
    bool position_valid;                   /**< True if cached position is valid */
    bool next_frame_known;                 /**< True if cached_frame_lsn points to next frame */
} sacd_frame_reader_dst_t;

/**
 * @brief Extended audio sector structure with parsed frame_info.
 *
 * This structure extends audio_sector_t to include the frame_info array
 * which is located between packet_info and the audio data in the sector.
 * The audio_sector_t from sacd_specification.h doesn't include frame_info.
 */
typedef struct {
    audio_sector_t sector;      /**< Base sector structure (header + packet_info) */
    frame_info_t frames[7];     /**< Parsed frame info array */
} parsed_audio_sector_t;

/**
 * @brief Parse the audio sector header from raw sector data.
 *
 * Parses the audio sector header, packet info entries, and frame info entries
 * from raw sector data. All structures use conditional bitfield ordering for
 * endianness handling. Packet info entries (16-bit) require byte-swap from
 * big-endian disc format to native endian using ntoh16().
 *
 * @param[in]  sector_data  Pointer to raw sector data (2048 bytes of SACD data)
 * @param[out] parsed       Pointer to structure to receive parsed data
 * @param[out] data_offset  Receives the byte offset where audio data begins
 *
 * @return 0 on success, negative on error
 */
static inline int parse_audio_sector_header(const uint8_t *sector_data,
                                      parsed_audio_sector_t *parsed,
                                      uint32_t *data_offset)
{
    const uint8_t *ptr = sector_data;
    int i;

    if (!sector_data || !parsed || !data_offset) {
        return -1;
    }

    /* Clear output structure */
    memset(parsed, 0, sizeof(parsed_audio_sector_t));

    /*
     * Parse the 1-byte sector header.
     * Conditional bitfields in audio_sector_t handle endianness correctly.
     */
    memcpy(&parsed->sector, ptr, AUDIO_SECTOR_HEADER_SIZE);
    ptr += AUDIO_SECTOR_HEADER_SIZE;

    /* Validate packet count */
    if (parsed->sector.packet_count == 0 || parsed->sector.packet_count > 7) {
        return -1;
    }

    /*
     * Parse packet info entries (2 bytes each).
     * Disc data is big-endian; use ntoh16() to convert to native endian,
     * then the conditional bitfield layout in audio_packet_info_t works correctly.
     */
    for (i = 0; i < parsed->sector.packet_count; i++) {
        uint16_t raw;
        memcpy(&raw, ptr, sizeof(uint16_t));
        raw = ntoh16(raw);
        memcpy(&parsed->sector.packet_info[i], &raw, sizeof(audio_packet_info_t));
        ptr += sizeof(audio_packet_info_t);
    }

    /*
     * Parse frame info entries - located after packet_info, before audio data.
     * Frame info is 4 bytes for DST (time_code + channel/sector byte),
     * or 3 bytes for DSD (time_code only).
     * The 1-byte bitfields use conditional ordering and work with direct memcpy.
     */
    for (i = 0; i < parsed->sector.frame_start_count; i++) {
        if (parsed->sector.dst_coded) {
            /* DST: 4-byte frame_info */
            memcpy(&parsed->frames[i], ptr, AUDIO_FRAME_INFO_SIZE_DST);
            ptr += AUDIO_FRAME_INFO_SIZE_DST;
        } else {
            /* DSD: 3-byte frame_info (time_code only, no channel/sector byte) */
            memcpy(&parsed->frames[i].time_code, ptr, AUDIO_FRAME_INFO_SIZE_DSD);
            ptr += AUDIO_FRAME_INFO_SIZE_DSD;
            parsed->frames[i].sector_count = 1;
        }
    }

    /* Calculate data offset */
    *data_offset = (uint32_t)(ptr - sector_data);

    /* RawSMusic: corrupt packet lengths must not read beyond the SACD sector. */
    uint32_t payload_size = *data_offset;
    unsigned audio_starts = 0;
    for (i = 0; i < parsed->sector.packet_count; ++i) {
        if (!parsed->sector.packet_info[i].packet_length) return -1;
        payload_size += parsed->sector.packet_info[i].packet_length;
        if (payload_size > SACD_LSN_SIZE) return -1;
        if (parsed->sector.packet_info[i].data_type == DATA_TYPE_AUDIO &&
            parsed->sector.packet_info[i].frame_start) ++audio_starts;
    }
    if (audio_starts != parsed->sector.frame_start_count) return -1;

    return 0;
}

/**
 * @brief Find the LSN of a specific DST frame by scanning sectors.
 *
 * Searches through sectors from from_lsn to to_lsn looking for the
 * frame with the specified frame number (time code converted to frames).
 *
 * @param[in]  self          Pointer to the frame reader context
 * @param[in]  from_lsn      Starting LSN to search from
 * @param[in]  to_lsn        Ending LSN to search to (inclusive)
 * @param[in]  frame         Target frame number to find
 * @param[out] found_lsn     Receives the LSN where the frame starts
 * @param[out] sector_count  Receives the number of sectors the frame spans
 *
 * @return SACD_DST_READER_OK on success, or error code
 */
static dst_reader_state_t find_dst_frame(sacd_frame_reader_t *self,
                                         uint32_t from_lsn,
                                         uint32_t to_lsn,
                                         uint32_t frame,
                                         uint32_t *found_lsn,
                                         int *sector_count)
{
    sacd_frame_reader_dst_t *dst = (sacd_frame_reader_dst_t *)self;
    parsed_audio_sector_t parsed;
    uint32_t lsn;
    int packet_idx = 0;
    int frame_info_idx = 0;
    uint32_t data_offset;
    uint32_t sectors_read;
    int current_packet_count = 0;

    DST_DEBUG("find_dst_frame: searching for frame=%u in range [%u, %u]", frame, from_lsn, to_lsn);

    if (!self || !self->input || !dst->sector_buffer || !found_lsn) {
        DST_DEBUG("find_dst_frame: INVALID_ARG - self=%p, input=%p, buffer=%p, found_lsn=%p",
                  (void*)self, self ? (void*)self->input : NULL,
                  (void*)dst->sector_buffer, (void*)found_lsn);
        return SACD_DST_READER_INVALID_ARG;
    }

    /* Initialize outputs */
    *found_lsn = 0;
    if (sector_count) {
        *sector_count = 1;
    }

    for (lsn = from_lsn; lsn <= to_lsn; lsn++) {
        /* Read one sector using sector_size from base */
        if (sacd_input_read_sectors(self->input, lsn, 1, dst->sector_buffer, &sectors_read) != 0
            || sectors_read != 1) {
            return SACD_DST_READER_IO_ERROR;
        }

        /*
         * Decrypt sector if within encrypted range and decrypt function is available.
         * Note: Decryption is only performed in the DST reader (not DSD 14/16) because
         * DST data must be decrypted at the sector level before parsing packet headers.
         */
        if (self->input->ops && self->input->ops->decrypt &&
            lsn >= self->start_sector && lsn <= self->end_sector) {
            if (self->input->ops->decrypt(self->input, dst->sector_buffer, 1) != SACD_INPUT_OK) {
                return SACD_DST_READER_IO_ERROR;
            }
        }

        /* Skip header bytes to get to SACD audio data (2048 bytes) */
        uint8_t *sector_data = dst->sector_buffer + self->header_size;

        /* Check if we need to parse a new audio sector header */
        if (packet_idx >= current_packet_count) {
            /* Parse the new sector header */
            if (parse_audio_sector_header(sector_data, &parsed, &data_offset) != 0) {
                return SACD_DST_READER_IO_ERROR;
            }

            packet_idx = 0;
            frame_info_idx = 0;
            current_packet_count = parsed.sector.packet_count;
        }

        /* Process packets in this sector */
        while (packet_idx < current_packet_count) {
            /* Check if this packet starts a new audio frame */
            if (parsed.sector.packet_info[packet_idx].data_type == DATA_TYPE_AUDIO &&
                parsed.sector.packet_info[packet_idx].frame_start) {

                /* Get the frame number from the time code */
                uint32_t current_frame = time_to_frame(parsed.frames[frame_info_idx].time_code);

                if (current_frame > frame) {
                    /* We've passed the target frame - it's before our search range */
                    DST_DEBUG("find_dst_frame: PASSED target frame=%u, found current_frame=%u at lsn=%u",
                              frame, current_frame, lsn);
                    return SACD_DST_READER_ACCESS_LIST_INVALID;
                } else if (current_frame == frame) {
                    /* Found the target frame */
                    *found_lsn = lsn;
                    if (sector_count) {
                        *sector_count = parsed.frames[frame_info_idx].sector_count;
                        if (*sector_count == 0) {
                            *sector_count = 1;
                        }
                    }
                    DST_DEBUG("find_dst_frame: FOUND frame=%u at lsn=%u, sector_count=%d",
                              frame, lsn, sector_count ? *sector_count : -1);
                    return SACD_DST_READER_OK;
                }

                frame_info_idx++;
            }

            packet_idx++;
        }
    }

    /* Frame not found in the search range */
    DST_DEBUG("find_dst_frame: FRAME_NOT_FOUND frame=%u in range [%u, %u]", frame, from_lsn, to_lsn);
    return SACD_DST_READER_FRAME_NOT_FOUND;
}

/**
 * @brief Seek to a specific frame using the access list for fast positioning.
 *
 * This function provides efficient seeking to any frame in the track area by:
 * 1. Using the access list to calculate the search range (from_lsn, to_lsn)
 * 2. Calling find_dst_frame() to locate the exact sector within that range
 *
 * The access list significantly reduces seek time for random access by
 * narrowing down the search range to a small number of sectors.
 *
 * @param[in]  self          Pointer to the frame reader context
 * @param[in]  frame         Target frame number to seek to
 * @param[out] found_lsn     Receives the LSN where the frame starts
 * @param[out] sector_count  Receives the number of sectors the frame spans
 *
 * @return SACD_DST_READER_OK on success, or error code
 */
static dst_reader_state_t dst_sector_seek(sacd_frame_reader_t *self,
                                          uint32_t frame,
                                          uint32_t *found_lsn,
                                          int *sector_count)
{
    uint32_t from_lsn, to_lsn;
    dst_reader_state_t result;
    sacd_frame_reader_dst_t *dst = (sacd_frame_reader_dst_t *)self;

    if (!self || !found_lsn) {
        return SACD_DST_READER_INVALID_ARG;
    }

    /* Use access list to calculate search range */
    DST_DEBUG("dst_sector_seek: area=%p, area->frame_info.step_size=%u, area->frame_info.num_entries=%u",
              (void*)dst->area, dst->area->frame_info.step_size, dst->area->frame_info.num_entries);
    if (sacd_area_toc_get_access_list_range(dst->area, frame, self->start_sector, self->end_sector, &from_lsn, &to_lsn) != SACD_AREA_TOC_OK) {
        /* No access list or error - search entire track area */
        from_lsn = self->start_sector;
        to_lsn = self->end_sector;
        DST_DEBUG("dst_sector_seek: access list error, using full range [%u, %u]", from_lsn, to_lsn);
    } else {
        DST_DEBUG("dst_sector_seek: access list returned range [%u, %u] for frame=%u", from_lsn, to_lsn, frame);
    }

    /* Find the frame within the calculated range */
    result = find_dst_frame(self, from_lsn, to_lsn, frame, found_lsn, sector_count);

    /* If not found in the optimized range, try the full range as fallback.
     * This handles two cases:
     * - FRAME_NOT_FOUND: frame is beyond the search range
     * - ACCESS_LIST_INVALID: frame is before the search range (access list gave wrong start)
     */
    if ((result == SACD_DST_READER_FRAME_NOT_FOUND ||
         result == SACD_DST_READER_ACCESS_LIST_INVALID) &&
        from_lsn != self->start_sector) {
        DST_DEBUG("dst_sector_seek: fallback to full range search for frame=%u", frame);
        result = find_dst_frame(self, self->start_sector, self->end_sector,
                                frame, found_lsn, sector_count);
    }

    return result;
}

/**
 * @brief Initializes a DST reader context.
 *
 * Sets the reader type and allocates the sector buffer for reading.
 * Buffer size is based on sector_size from base (set by sacd_frame_reader_init
 * before this function is called).
 *
 * @param[in,out] self  Pointer to the frame reader structure
 */
static void dst_reader_init(sacd_frame_reader_t *self)
{
    sacd_frame_reader_dst_t *dst = (sacd_frame_reader_dst_t *)self;

    self->type = SACD_FRAME_READER_DST;

    DST_DEBUG("dst_reader_init: sector_size=%d, header_size=%d, start_sector=%u, end_sector=%u",
              self->sector_size, self->header_size, self->start_sector, self->end_sector);

    /* Allocate sector buffer for MAX_DST_SECTORS using actual sector_size */
    dst->sector_buffer = (uint8_t *)sa_calloc(MAX_DST_SECTORS, (size_t)self->sector_size);
    DST_DEBUG("dst_reader_init: allocated buffer=%p, size=%zu",
              (void*)dst->sector_buffer, (size_t)MAX_DST_SECTORS * (size_t)self->sector_size);

    dst->buffer_sector_count = 0;

    /* Initialize position tracking */
    dst->cached_frame_num = 0;
    dst->cached_frame_lsn = 0;
    dst->position_valid = false;
    dst->next_frame_known = false;
}

/**
 * @brief Destroys a DST reader context and releases any associated resources.
 *
 * @param[in,out] self  Pointer to the frame reader structure to destroy
 */
static void dst_reader_destroy(sacd_frame_reader_t *self)
{
    if (self) {
        sacd_frame_reader_dst_t *dst = (sacd_frame_reader_dst_t *)self;
        sa_free(dst->sector_buffer);
        dst->sector_buffer = NULL;
        sa_free(self);
    }
}

/**
 * @brief Retrieves audio data for a specific frame (virtual method implementation).
 *
 * This is the main data extraction function for DST-coded audio. It implements a
 * multi-step process:
 * 1. Check if we can use cached position (for sequential reads)
 * 2. Find the frame's starting sector using find_dst_frame() if needed
 * 3. Read sectors containing the frame
 * 4. Extract packets matching the requested data_type
 * 5. Assemble packet data into the output buffer
 * 6. Cache position for next sequential read
 *
 * Position caching optimizes sequential playback by avoiding repeated frame searches
 * when reading frames in order.
 *
 * @param[in]     self       Pointer to frame reader structure
 * @param[out]    data       Buffer to receive audio data
 * @param[in,out] length     Input: buffer size; Output: bytes written
 * @param[in]     frame_num  Frame number to read
 * @param[in]     frame_lsn  Starting LSN for search (from Access List)
 * @param[in]     data_type  Packet type to extract (typically DATA_TYPE_AUDIO)
 *
 * @return SACD_DST_READER_OK or error code
 */
static int dst_reader_read_frame(sacd_frame_reader_t *self, uint8_t *data,
                                 uint32_t *length, uint32_t frame_num,
                                 uint32_t frame_lsn, audio_packet_data_type_t data_type)
{
    sacd_frame_reader_dst_t *dst = (sacd_frame_reader_dst_t *)self;
    parsed_audio_sector_t parsed;
    uint32_t data_offset;
    uint32_t output_length = 0;
    uint32_t max_length;
    int frame_sector_count = 0;
    uint32_t found_lsn = 0;
    dst_reader_state_t seek_result;
    uint32_t sectors_read;
    int i;
    bool frame_started = false;
    bool frame_complete = false;
    uint32_t next_frame_lsn = 0;

    (void)frame_lsn;  /* Not used - access list provides seeking info */

    DST_DEBUG("dst_reader_read_frame: frame_num=%u, data_type=%d", frame_num, data_type);

    if (!self || !data || !length) {
        DST_DEBUG("dst_reader_read_frame: INVALID_ARG");
        return SACD_DST_READER_INVALID_ARG;
    }

    max_length = *length;
    *length = 0;

    DST_DEBUG("dst_reader_read_frame: max_length=%u", max_length);

    /* Ensure we have a sector buffer */
    if (!dst->sector_buffer) {
        DST_DEBUG("dst_reader_read_frame: NO SECTOR BUFFER!");
        return SACD_DST_READER_MEMORY_ALLOCATION_ERROR;
    }

    /*
     * Optimization: For sequential reads (frame N+1 after reading frame N),
     * we already know where the next frame starts from parsing the previous frame.
     * This skips the expensive find_dst_frame() sector-by-sector scan.
     */
    if (dst->position_valid && dst->next_frame_known &&
        frame_num == dst->cached_frame_num + 1) {
        /* Sequential read - use cached position, skip the scan */
        DST_DEBUG("dst_reader_read_frame: SEQUENTIAL read, using cached_lsn=%u", dst->cached_frame_lsn);
        found_lsn = dst->cached_frame_lsn;
        frame_sector_count = MAX_DST_SECTORS;  /* Parsing determines actual boundary */
        seek_result = SACD_DST_READER_OK;
    } else {
        /* Random access or first read - use access list for fast seeking */
        DST_DEBUG("dst_reader_read_frame: RANDOM access, seeking via access list");
        seek_result = dst_sector_seek(self, frame_num, &found_lsn, &frame_sector_count);
    }

    if (seek_result != SACD_DST_READER_OK) {
        DST_DEBUG("dst_reader_read_frame: seek FAILED with result=%d", seek_result);
        dst->position_valid = false;
        return seek_result;
    }

    DST_DEBUG("dst_reader_read_frame: found_lsn=%u, frame_sector_count=%d", found_lsn, frame_sector_count);

    /* Validate sector count */
    if (frame_sector_count <= 0 || frame_sector_count > MAX_DST_SECTORS) {
        frame_sector_count = MAX_DST_SECTORS;
    }

    /* Read the sectors containing the frame */
    if (sacd_input_read_sectors(self->input, found_lsn, (uint32_t)frame_sector_count,
                                 dst->sector_buffer, &sectors_read) != 0) {
        dst->position_valid = false;
        return SACD_DST_READER_IO_ERROR;
    }

    /*
     * Decrypt sectors if within encrypted range and decrypt function is available.
     * Note: Decryption is only performed in the DST reader (not DSD 14/16) because
     * DST data must be decrypted at the sector level before parsing packet headers.
     */
    if (self->input->ops && self->input->ops->decrypt &&
        found_lsn >= self->start_sector &&
        found_lsn + sectors_read - 1 <= self->end_sector) {
        if (self->input->ops->decrypt(self->input, dst->sector_buffer, sectors_read) != SACD_INPUT_OK) {
            dst->position_valid = false;
            return SACD_DST_READER_IO_ERROR;
        }
    }

    /*
     * Process each sector to extract frame data.
     * DST frames track completion via sector_count (from frame_info).
     * The frame is complete when we've processed sector_count sectors or hit the next frame_start.
     */
    int remaining_sectors = frame_sector_count;
    int frame_info_idx = 0;
    bool sector_had_audio = false;

    DST_DEBUG("dst_reader_read_frame: processing %u sectors", sectors_read);

    for (uint32_t sector_idx = 0; sector_idx < sectors_read && !frame_complete; sector_idx++) {
        /* Calculate sector offset using sector_size, skip header_size to get to SACD data */
        uint8_t *sector_data = dst->sector_buffer + (sector_idx * (uint32_t)self->sector_size) + self->header_size;
        uint8_t *packet_data;

        /* Parse the sector header (operates on SACD_LSN_SIZE bytes after header) */
        if (parse_audio_sector_header(sector_data, &parsed, &data_offset) != 0) {
            DST_DEBUG("dst_reader_read_frame: parse_audio_sector_header FAILED at sector_idx=%u", sector_idx);
            dst->position_valid = false;
            return SACD_DST_READER_IO_ERROR;
        }

        DST_DEBUG("dst_reader_read_frame: sector[%u] packet_count=%u, frame_start_count=%u, dst_coded=%u, data_offset=%u",
                  sector_idx, parsed.sector.packet_count, parsed.sector.frame_start_count,
                  parsed.sector.dst_coded, data_offset);

        packet_data = sector_data + data_offset;
        frame_info_idx = 0;
        sector_had_audio = false;

        /* Process packets in this sector */
        for (i = 0; i < parsed.sector.packet_count && !frame_complete; i++) {
            uint16_t pkt_data_type = parsed.sector.packet_info[i].data_type;
            uint16_t pkt_length = parsed.sector.packet_info[i].packet_length;
            uint16_t pkt_frame_start = parsed.sector.packet_info[i].frame_start;

            DST_DEBUG("dst_reader_read_frame: packet[%d] data_type=%u, length=%u, frame_start=%u",
                      i, pkt_data_type, pkt_length, pkt_frame_start);

            /* Check if this is the start of a frame */
            if (pkt_frame_start && pkt_data_type == DATA_TYPE_AUDIO) {
                /* Get the frame number from the time code */
                uint32_t current_frame = time_to_frame(parsed.frames[frame_info_idx].time_code);
                DST_DEBUG("dst_reader_read_frame: frame_start detected, current_frame=%u (target=%u)",
                          current_frame, frame_num);

                if (!frame_started) {
                    /* Check if this is our target frame by comparing timecodes */
                    if (current_frame == frame_num) {
                        /* This is the start of our target frame */
                        frame_started = true;
                        remaining_sectors = parsed.frames[frame_info_idx].sector_count;
                        if (remaining_sectors == 0) {
                            remaining_sectors = 1;
                        }
                        DST_DEBUG("dst_reader_read_frame: TARGET FRAME FOUND, sector_count=%d",
                                  remaining_sectors);
                    }
                    /* else: skip this frame_start, it's not our target */
                } else {
                    /* This is the start of the next frame - current frame is complete */
                    /* Cache this position for the next sequential read */
                    next_frame_lsn = (uint32_t)found_lsn + sector_idx;
                    frame_complete = true;
                    DST_DEBUG("dst_reader_read_frame: frame COMPLETE (next frame_start detected at lsn=%u)", next_frame_lsn);
                    break;
                }
                frame_info_idx++;
            }

            /* Extract data from packets matching the requested type */
            if (frame_started && pkt_data_type == data_type) {
                uint32_t copy_len = pkt_length;
                if (output_length + copy_len > max_length) {
                    copy_len = max_length - output_length;
                    DST_DEBUG("dst_reader_read_frame: TRUNCATING copy_len from %u to %u (buffer full)",
                              pkt_length, copy_len);
                }

                if (copy_len > 0) {
                    memcpy(data + output_length, packet_data, copy_len);
                    output_length += copy_len;
                    sector_had_audio = true;
                    DST_DEBUG("dst_reader_read_frame: COPIED %u bytes, total output_length=%u",
                              copy_len, output_length);
                }
            } else if (frame_started) {
                DST_DEBUG("dst_reader_read_frame: SKIP packet - pkt_data_type=%u != data_type=%d",
                          pkt_data_type, data_type);
            }

            /* Advance to next packet's data */
            packet_data += pkt_length;
        }

        /* For DST, decrement remaining sectors after processing each sector with audio */
        /* Skip if frame is already complete (we found the next frame_start) */
        if (!frame_complete && parsed.sector.dst_coded && frame_started && sector_had_audio) {
            remaining_sectors--;
            DST_DEBUG("dst_reader_read_frame: remaining_sectors=%d", remaining_sectors);
            if (remaining_sectors <= 0) {
                /* Frame complete - next frame starts in next sector */
                next_frame_lsn = (uint32_t)found_lsn + sector_idx + 1;
                frame_complete = true;
                DST_DEBUG("dst_reader_read_frame: frame COMPLETE (sector count exhausted)");
            }
        }
    }

    /* Cache position for next sequential read.
     * Only enable sequential optimization if next frame is in a DIFFERENT sector.
     * When multiple frames share the same sector (next_frame_lsn == found_lsn),
     * we can't use LSN-based caching and must fall back to access list seeking.
     */
    dst->cached_frame_num = frame_num;
    dst->next_frame_known = (next_frame_lsn > 0 && next_frame_lsn > found_lsn);
    dst->cached_frame_lsn = dst->next_frame_known ? next_frame_lsn : (uint32_t)found_lsn;
    dst->position_valid = true;

    DST_DEBUG("dst_reader_read_frame: DONE frame_started=%d, output_length=%u", frame_started, output_length);

    *length = output_length;
    return SACD_DST_READER_OK;
}

/**
 * @brief Determines the sector location and span of a specific frame.
 *
 * Searches for a frame and returns its sector position and size without
 * extracting the actual audio data.
 *
 * @param[in]  self             Pointer to the frame reader structure
 * @param[in]  frame            Frame number to locate
 * @param[in]  frame_lsn        LSN to start the search (from Access List)
 * @param[out] start_sector_nr  Receives the LSN of the first sector
 * @param[out] sector_count     Receives the number of sectors the frame spans
 *
 * @return SACD_DST_READER_OK on success, or error code
 */
static int dst_reader_get_sector(sacd_frame_reader_t *self,
                                 uint32_t frame,
                                 uint32_t frame_lsn,
                                 uint32_t *start_sector_nr,
                                 int *sector_count)
{
    uint32_t found_lsn = 0;
    dst_reader_state_t result;

    (void)frame_lsn;  /* Not used - we use access list for seeking */

    if (!self || !start_sector_nr || !sector_count) {
        return SACD_DST_READER_INVALID_ARG;
    }

    /* Use access list-based seeking */
    result = dst_sector_seek(self, frame, &found_lsn, sector_count);

    if (result != SACD_DST_READER_OK) {
        *start_sector_nr = 0;
        *sector_count = 0;
        return result;
    }

    *start_sector_nr = found_lsn;
    return SACD_DST_READER_OK;
}

static const sacd_frame_reader_ops_t dst_reader_ops = {
    .init              = dst_reader_init,
    .destroy           = dst_reader_destroy,
    .get_sector        = dst_reader_get_sector,
    .read_frame        = dst_reader_read_frame
};

int sacd_frame_reader_dst_create(sacd_frame_reader_t **out, struct area_toc_s *area)
{
    sacd_frame_reader_dst_t *self;

    if (!out) {
        return SACD_FRAME_READER_ERR_INVALID_ARG;
    }

    *out = NULL;

    /* Allocate structure */
    self = (sacd_frame_reader_dst_t *)sa_calloc(1, sizeof(*self));
    if (!self) {
        return SACD_FRAME_READER_ERR_OUT_OF_MEMORY;
    }

    /* Initialize base */
    self->base.ops = &dst_reader_ops;
    self->area = area;

    *out = (sacd_frame_reader_t *)self;
    return SACD_FRAME_READER_OK;
}
