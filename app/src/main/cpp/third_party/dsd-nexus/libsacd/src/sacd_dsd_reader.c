/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief Implementation of plain DSD (Direct Stream Digital) audio data readers.
 * This file implements readers for uncompressed 2-channel DSD audio in the two
 * fixed formats defined by the SACD specification:
 * - 3-in-14 format: 3 frames stored across 14 sectors
 * - 3-in-16 format: 3 frames stored across 16 sectors
 * Key implementation details:
 * - Block-based organization: frames are grouped in blocks of 3
 * - Fixed sector layout patterns defined by rdstate tables
 * - Deterministic sector calculations (no packet parsing needed)
 * - Frame assembly from multiple sector fragments with specific offsets
 * Unlike DST-coded audio, plain DSD doesn't use:
 * - Packet headers (audio_packet_info_t)
 * - Frame headers (frame_info_t)
 * - Access Lists (fixed layout is deterministic)
 * @see sacd_dsd_reader.h for public API documentation
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

#include <stdlib.h>
#include <string.h>
#include <libsautil/mem.h>

#include "sacd_dsd_reader.h"
#include "sacd_specification.h"

/**
 * @struct fixed_read_def_t
 * @brief Defines a byte range to read from a single sector.
 *
 * Each DSD frame is assembled by reading specific byte ranges from multiple
 * sectors. This structure defines one such range.
 */
typedef struct fixed_read_def_t
{
    /**
     * @brief Byte offset within the sector where the data starts.
     * Typically 32 or 284 bytes (after sector header).
     */
    int offset;

    /**
     * @brief Number of bytes to read from this sector.
     * Values range from 588 to 2016 bytes depending on the layout.
     */
    int length;
} fixed_read_def_t;

/**
 * @struct fixed_read_state_t
 * @brief Defines the complete sector layout pattern for one frame position within a block.
 *
 * In fixed DSD formats, 3 frames are grouped into a block. Each position (0, 1, 2)
 * within the block has a different sector layout pattern. This structure defines
 * the pattern for one position.
 */
typedef struct fixed_read_state_t
{
    /**
     * @brief Number of sectors this frame spans (5 or 6).
     */
    int sector_count;

    /**
     * @brief Multiplier for calculating sector offset based on block number.
     * This is the number of sectors per complete 3-frame block (14 or 16).
     */
    int sector_mul;

    /**
     * @brief Additional sector offset for this frame position within the block.
     * This places the frame at the correct position relative to the block start.
     */
    int sector_addition;

    /**
     * @brief Array of offset/length pairs defining what to read from each sector.
     * Up to 6 entries (one per sector). Unused entries have offset=0, length=0.
     */
    fixed_read_def_t state[6];
} fixed_read_state_t;

static void dsd_reader_fixed14_init(sacd_frame_reader_t *self)
{
    self->type = SACD_FRAME_READER_DSD14;
}

static void dsd_reader_fixed14_destroy(sacd_frame_reader_t *self)
{
    if (self) {
        sa_free(self);
    }
}

/**
 * @brief Generic fixed-format DSD frame reader implementation.
 *
 * This function implements the core logic for reading DSD frames from fixed
 * formats (both 3-in-14 and 3-in-16). It assembles a complete 9408-byte frame
 * by reading multiple sectors and copying specific byte ranges from each.
 *
 * Algorithm:
 * 1. Calculate block number and position within block from frame number
 * 2. Look up the sector layout pattern for this position
 * 3. For each sector in the pattern:
 *    a. Calculate the absolute sector number
 *    b. Read the sector
 *    c. Copy the specified byte range to the output buffer
 * 4. Return the assembled frame
 *
 * @param[in]     state     Pointer to the layout pattern table (rdstate_3_in_14 or rdstate_3_in_16)
 * @param[in]     self       Pointer to audio_frame_reader_t base
 * @param[out]    p_data    Buffer to receive the frame data
 * @param[out]    length    Pointer to receive bytes written (always 9408)
 * @param[in]     frame_num Frame number to read (0-based)
 * @param[in]     frame_lsn Unused parameter (kept for interface compatibility)
 * @param[in]     data_type Unused parameter (all data is audio in plain DSD)
 *
 * @return SACD_DSD_READER_OK (0) on success, or error code:
 *         - SACD_DSD_READER_MEMORY_ALLOCATION_ERROR: Failed to allocate sector buffer
 *         - SACD_DSD_READER_IO_ERROR: Reached end of Track Area
 */
static inline int dsd_audio_fixed_read_frame(const fixed_read_state_t *state,
                                            sacd_frame_reader_t *self,
                                            uint8_t *p_data,
                                            uint32_t *length,
                                            uint32_t frame_num,
                                            uint32_t frame_lsn,
                                            audio_packet_data_type_t data_type)
{
    int i;
    int block_count, block_idx;

    /* Calculate which 3-frame block and position within the block */
    block_count = frame_num / 3;  /* Which block (0, 1, 2, ...) */
    block_idx = frame_num % 3;    /* Position within block (0, 1, or 2) */

    uint8_t *sector;
    uint32_t num_sectors_read;

    /* Unused parameters (kept for interface compatibility) */
    (void)data_type;
    (void)frame_lsn;

    /* Allocate buffer for one sector */
    sector = (uint8_t *)sa_malloc(self->sector_size);
    if (!sector)
        return SACD_DSD_READER_MEMORY_ALLOCATION_ERROR;

    /* Assemble frame by reading sectors and copying byte ranges */
    *length = 0;
    for (i = 0; i < state[block_idx].sector_count; i++)
    {
        /* Calculate absolute sector number:
         * start_sector + (block_count * sectors_per_block) + block_offset + sector_index */
        sacd_input_read_sectors(
            self->input,
            self->start_sector + (block_count * state[block_idx].sector_mul) + state[block_idx].sector_addition + i,
            1,
            sector,
            &num_sectors_read);

        if (num_sectors_read != 1)
        {
            sa_free(sector);
            return SACD_DSD_READER_IO_ERROR;
        }

        /* Copy the specified byte range from this sector */
        memcpy(p_data + *length,
               sector + self->header_size + state[block_idx].state[i].offset,
               state[block_idx].state[i].length);
        *length += state[block_idx].state[i].length;
    }

    sa_free(sector);

    return SACD_DSD_READER_OK;
}

/**
 * @brief Generic fixed-format sector location calculator.
 *
 * This function calculates which sectors contain a specific frame using simple
 * block-based arithmetic. Unlike DST-coded audio which requires searching through
 * sectors, fixed DSD formats have deterministic layouts.
 *
 * Calculation:
 * - block_num = frame / 3 (which 3-frame block contains this frame)
 * - block_index = frame % 3 (position within the block: 0, 1, or 2)
 * - start_sector = area_start + (block_num * sectors_per_block) + block_offset
 *
 * @param[in]  state           Pointer to layout pattern table (rdstate_3_in_14 or rdstate_3_in_16)
 * @param[in]  self             Pointer to audio_frame_reader_t base
 * @param[in]  frame           Frame number to locate (0-based)
 * @param[in]  frame_lsn       Unused parameter (kept for interface compatibility)
 * @param[out] start_sector_nr Pointer to receive the first sector LSN
 * @param[out] sector_count    Pointer to receive the sector count
 *
 * @return SACD_DSD_READER_OK (0) always succeeds
 */
static inline int dsd_audio_fixed_get_sector(
    const fixed_read_state_t *state,
    sacd_frame_reader_t *self, uint32_t frame, uint32_t frame_lsn, uint32_t *start_sector_nr,
    int *sector_count)
{
    uint32_t block_num;
    uint8_t block_index;

    /* Unused parameter (fixed formats don't use Access Lists) */
    (void)frame_lsn;

    /* Calculate block number and position within block */
    block_num = frame / 3;
    block_index = (uint8_t)(frame % 3);

    /* Look up sector count for this position */
    *sector_count = state[block_index].sector_count;

    /* Calculate absolute starting sector:
     * area_start + (block_num * sectors_per_block) + block_position_offset */
    *start_sector_nr = self->start_sector + (block_num * state[block_index].sector_mul) + state[block_index].sector_addition;

    return SACD_DSD_READER_OK;
}

/**
 * @brief Sector layout pattern table for 3-in-14 fixed DSD format.
 *
 * This table defines how 3 frames are distributed across 14 sectors.
 * Each entry corresponds to one position within a 3-frame block (0, 1, or 2).
 *
 * Layout pattern (per the Scarlet Book specification):
 * - Position 0: 5 sectors, starts at block_start + 0
 *   - Sector 0: offset=32, length=2016 bytes
 *   - Sector 1: offset=32, length=2016 bytes
 *   - Sector 2: offset=32, length=2016 bytes
 *   - Sector 3: offset=32, length=2016 bytes
 *   - Sector 4: offset=32, length=1344 bytes
 *   Total: 9408 bytes (one complete frame)
 *
 * - Position 1: 6 sectors, starts at block_start + 4
 *   - Sector 0: offset=1376 (32+1344), length=672 bytes (continuation from prev frame)
 *   - Sector 1: offset=32, length=2016 bytes
 *   - Sector 2: offset=32, length=2016 bytes
 *   - Sector 3: offset=32, length=2016 bytes
 *   - Sector 4: offset=32, length=2016 bytes
 *   - Sector 5: offset=32, length=672 bytes
 *   Total: 9408 bytes
 *
 * - Position 2: 5 sectors, starts at block_start + 9
 *   - Sector 0: offset=704 (32+672), length=1344 bytes (continuation from prev frame)
 *   - Sector 1: offset=32, length=2016 bytes
 *   - Sector 2: offset=32, length=2016 bytes
 *   - Sector 3: offset=32, length=2016 bytes
 *   - Sector 4: offset=32, length=2016 bytes
 *   Total: 9408 bytes
 *
 * Total sectors per 3-frame block: 5 + 6 + 5 = 16, but only 14 are used
 * because frames overlap (frame boundaries don't align with sector boundaries).
 */
static const fixed_read_state_t rdstate_3_in_14[3] = {
    {5,      /* sector_count: Frame 0 uses 5 sectors */
     14,     /* sector_mul: 14 sectors per complete block */
     0,      /* sector_addition: Frame 0 starts at block_start + 0 */
     {{32, 2016},   /* Sector 0: skip 32-byte header, read 2016 bytes */
      {32, 2016},   /* Sector 1 */
      {32, 2016},   /* Sector 2 */
      {32, 2016},   /* Sector 3 */
      {32, 1344},   /* Sector 4: last 1344 bytes of frame */
      {0, 0}}},     /* Unused */
    {6,      /* sector_count: Frame 1 uses 6 sectors */
     14,     /* sector_mul: 14 sectors per block */
     4,      /* sector_addition: Frame 1 starts at block_start + 4 */
     {{32 + 1344, 672},  /* Sector 0: skip past Frame 0's data, read 672 bytes */
      {32, 2016},        /* Sector 1 */
      {32, 2016},        /* Sector 2 */
      {32, 2016},        /* Sector 3 */
      {32, 2016},        /* Sector 4 */
      {32, 672}}},       /* Sector 5: first 672 bytes only */
    {5,      /* sector_count: Frame 2 uses 5 sectors */
     14,     /* sector_mul: 14 sectors per block */
     9,      /* sector_addition: Frame 2 starts at block_start + 9 */
     {{32 + 672, 1344},  /* Sector 0: skip past Frame 1's data, read 1344 bytes */
      {32, 2016},        /* Sector 1 */
      {32, 2016},        /* Sector 2 */
      {32, 2016},        /* Sector 3 */
      {32, 2016},        /* Sector 4 */
      {0, 0}}}};         /* Unused */

static int dsd_audio_fixed14_read_frame(sacd_frame_reader_t *self,
                                                       uint8_t *p_data,
                                                       uint32_t *length,
                                                       uint32_t frame_num,
                                                       uint32_t frame_lsn,
                                                       audio_packet_data_type_t data_type)
{
    return dsd_audio_fixed_read_frame(rdstate_3_in_14, self, p_data, length, frame_num, frame_lsn, data_type);
}

static int dsd_audio_fixed14_get_sector(
    sacd_frame_reader_t *self, uint32_t frame, uint32_t frame_lsn, uint32_t *start_sector_nr,
    int *sector_count)
{
    return dsd_audio_fixed_get_sector(rdstate_3_in_14, self, frame, frame_lsn, start_sector_nr, sector_count);
}

static const sacd_frame_reader_ops_t dsd_reader_fixed14_ops = {
    .init              = dsd_reader_fixed14_init,
    .destroy           = dsd_reader_fixed14_destroy,
    .get_sector        = dsd_audio_fixed14_get_sector,
    .read_frame        = dsd_audio_fixed14_read_frame
};

int sacd_frame_reader_fixed14_create(sacd_frame_reader_t **out)
{
    sacd_frame_reader_t *self;

    if (!out) {
        return SACD_FRAME_READER_ERR_INVALID_ARG;
    }

    *out = NULL;

    /* Allocate structure */
    self = (sacd_frame_reader_t *)sa_calloc(1, sizeof(*self));
    if (!self) {
        return SACD_FRAME_READER_ERR_OUT_OF_MEMORY;
    }

    /* Initialize base */
    self->ops  = &dsd_reader_fixed14_ops;

    *out = (sacd_frame_reader_t *)self;
    return SACD_FRAME_READER_OK;
}

/**
 * @brief Sector layout pattern table for 3-in-16 fixed DSD format.
 *
 * This table defines how 3 frames are distributed across 16 sectors.
 * Each entry corresponds to one position within a 3-frame block (0, 1, or 2).
 *
 * Layout pattern (per the Scarlet Book specification):
 * - Position 0: 6 sectors, starts at block_start + 0
 *   - Sector 0: offset=284, length=1764 bytes
 *   - Sector 1: offset=284, length=1764 bytes
 *   - Sector 2: offset=284, length=1764 bytes
 *   - Sector 3: offset=284, length=1764 bytes
 *   - Sector 4: offset=284, length=1764 bytes
 *   - Sector 5: offset=284, length=588 bytes
 *   Total: 9408 bytes (one complete frame)
 *
 * - Position 1: 6 sectors, starts at block_start + 5
 *   - Sector 0: offset=872 (284+588), length=1176 bytes (continuation from prev frame)
 *   - Sector 1: offset=284, length=1764 bytes
 *   - Sector 2: offset=284, length=1764 bytes
 *   - Sector 3: offset=284, length=1764 bytes
 *   - Sector 4: offset=284, length=1764 bytes
 *   - Sector 5: offset=284, length=1176 bytes
 *   Total: 9408 bytes
 *
 * - Position 2: 6 sectors, starts at block_start + 10
 *   - Sector 0: offset=1460 (284+1176), length=588 bytes (continuation from prev frame)
 *   - Sector 1: offset=284, length=1764 bytes
 *   - Sector 2: offset=284, length=1764 bytes
 *   - Sector 3: offset=284, length=1764 bytes
 *   - Sector 4: offset=284, length=1764 bytes
 *   - Sector 5: offset=284, length=1764 bytes
 *   Total: 9408 bytes
 *
 * Total sectors per 3-frame block: 6 + 6 + 6 = 18, but only 16 are used
 * because frames overlap (frame boundaries don't align with sector boundaries).
 *
 * @note The header offset (284 bytes) is larger than 3-in-14 format (32 bytes).
 */
static const fixed_read_state_t rdstate_3_in_16[3] = {
    {6,      /* sector_count: Frame 0 uses 6 sectors */
     16,     /* sector_mul: 16 sectors per complete block */
     0,      /* sector_addition: Frame 0 starts at block_start + 0 */
     {{284, 1764},   /* Sector 0: skip 284-byte header, read 1764 bytes */
      {284, 1764},   /* Sector 1 */
      {284, 1764},   /* Sector 2 */
      {284, 1764},   /* Sector 3 */
      {284, 1764},   /* Sector 4 */
      {284, 588}}},  /* Sector 5: last 588 bytes of frame */
    {6,      /* sector_count: Frame 1 uses 6 sectors */
     16,     /* sector_mul: 16 sectors per block */
     5,      /* sector_addition: Frame 1 starts at block_start + 5 */
     {{284 + 588, 1176},  /* Sector 0: skip past Frame 0's data, read 1176 bytes */
      {284, 1764},        /* Sector 1 */
      {284, 1764},        /* Sector 2 */
      {284, 1764},        /* Sector 3 */
      {284, 1764},        /* Sector 4 */
      {284, 1176}}},      /* Sector 5: first 1176 bytes only */
    {6,      /* sector_count: Frame 2 uses 6 sectors */
     16,     /* sector_mul: 16 sectors per block */
     10,     /* sector_addition: Frame 2 starts at block_start + 10 */
     {{284 + 1176, 588},  /* Sector 0: skip past Frame 1's data, read 588 bytes */
      {284, 1764},        /* Sector 1 */
      {284, 1764},        /* Sector 2 */
      {284, 1764},        /* Sector 3 */
      {284, 1764},        /* Sector 4 */
      {284, 1764}}}};     /* Sector 5 */

static void dsd_reader_fixed16_init(sacd_frame_reader_t *self)
{
    self->type = SACD_FRAME_READER_DSD16;
}

static void dsd_reader_fixed16_destroy(sacd_frame_reader_t *self)
{
    if (self) {
        sa_free(self);
    }
}

static int dsd_audio_fixed16_read_frame(sacd_frame_reader_t *self,
                                                    uint8_t *p_data,
                                                    uint32_t *length,
                                                    uint32_t frame_num,
                                                    uint32_t frame_lsn,
                                                    audio_packet_data_type_t data_type)
{
    return dsd_audio_fixed_read_frame(rdstate_3_in_16, self, p_data, length, frame_num, frame_lsn, data_type);
}

static int dsd_audio_fixed16_get_sector(
    sacd_frame_reader_t *self, uint32_t frame, uint32_t frame_lsn, uint32_t *start_sector_nr,
    int *sector_count)
{
    return dsd_audio_fixed_get_sector(rdstate_3_in_16, self, frame, frame_lsn, start_sector_nr, sector_count);
}

static const sacd_frame_reader_ops_t dsd_reader_fixed16_ops = {
    .init              = dsd_reader_fixed16_init,
    .destroy           = dsd_reader_fixed16_destroy,
    .get_sector        = dsd_audio_fixed16_get_sector,
    .read_frame        = dsd_audio_fixed16_read_frame
};

int sacd_frame_reader_fixed16_create(sacd_frame_reader_t **out)
{
    sacd_frame_reader_t *self;

    if (!out) {
        return SACD_FRAME_READER_ERR_INVALID_ARG;
    }

    *out = NULL;

    /* Allocate structure */
    self = (sacd_frame_reader_t *)sa_calloc(1, sizeof(*self));
    if (!self) {
        return SACD_FRAME_READER_ERR_OUT_OF_MEMORY;
    }

    /* Initialize base */
    self->ops  = &dsd_reader_fixed16_ops;

    *out = (sacd_frame_reader_t *)self;
    return SACD_FRAME_READER_OK;
}
