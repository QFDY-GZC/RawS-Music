/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief Abstract frame reader interface for SACD audio frame extraction.
 * This module provides a polymorphic interface for reading audio frames from
 * SACD Track Areas. It sits above the sacd_input layer and handles the
 * extraction of Multiplexed Frames from raw disc sectors.
 * Three frame reader backends are supported, corresponding to the SACD
 * frame formats:
 * - **DSD14**: Fixed-format DSD with 14-sector frames (frame_format=2)
 * - **DSD16**: Fixed-format DSD with 16-sector frames (frame_format=3)
 * - **DST**: DST-encoded frames with variable sector spans (frame_format=0)
 * Each backend implements the sacd_frame_reader_ops_t vtable. The base
 * sacd_frame_reader_t structure must be embedded as the first member of
 * each implementation struct to allow safe casting.
 * @see sacd_input.h for the underlying disc I/O interface
 * @see sacd_specification.h for SACD disc format definitions
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

#ifndef LIBSACD_SACD_FRAME_READER_H
#define LIBSACD_SACD_FRAME_READER_H

#include <libsacd/sacd.h>

#include "sacd_input.h"
#include "sacd_specification.h"

#ifdef __cplusplus
extern "C"
{
#endif

/**
 * @enum sacd_frame_reader_error_t
 * @brief Error codes for frame reader operations.
 *
 * Functions return 0 (SACD_FRAME_READER_OK) on success, negative values on error.
 */
typedef enum sacd_frame_reader_error {
    SACD_FRAME_READER_OK               =  0,   /**< Operation completed successfully */
    SACD_FRAME_READER_ERR_OUT_OF_MEMORY = -1,   /**< Memory allocation failed */
    SACD_FRAME_READER_ERR_INVALID_ARG  = -2,    /**< Invalid argument value */
} sacd_frame_reader_error_t;

/* Forward declaration */
typedef struct sacd_frame_reader sacd_frame_reader_t;

/**
 * @struct sacd_frame_reader_ops_t
 * @brief Virtual function table for input device operations.
 *
 * All backend implementations must provide this vtable. Function pointers
 * marked as optional may be NULL if the operation is not supported.
 */
typedef struct sacd_frame_reader_ops {

    /**
     * @brief Perform implementation-specific initialization.
     *
     * Called by sacd_frame_reader_init() after the base fields (sector range,
     * format parameters, input device) have been set. Implementations should
     * allocate internal buffers and prepare for frame reading.
     *
     * @param[in,out] ctx  Pointer to the frame reader context
     */
    void (*init)(sacd_frame_reader_t *ctx);

    /**
     * @brief Destroy the frame reader and free implementation-specific resources.
     *
     * Called by sacd_frame_reader_destroy(). Implementations should release
     * any buffers or state allocated during init().
     *
     * @param[in,out] ctx  Pointer to the frame reader context
     */
    void (*destroy)(sacd_frame_reader_t *ctx);

    /**
     * @brief Determine the sector range occupied by a given frame.
     *
     * Calculates which disc sectors contain the specified audio frame.
     * For fixed-format readers (DSD14/DSD16), the sector range is deterministic.
     * For DST, the Access List may be consulted.
     *
     * @param[in]  ctx              Pointer to the frame reader context
     * @param[in]  frame            Frame index (0-based within the Track Area)
     * @param[in]  frame_lsn        Logical sector number where the frame starts
     * @param[out] start_sector_nr  Receives the first sector number of the frame
     * @param[out] sector_count     Receives the number of sectors the frame spans
     * @return Number of sectors on success, 0 on failure
     */
    int (*get_sector)(sacd_frame_reader_t *ctx,
                      uint32_t frame, uint32_t frame_lsn,
                      uint32_t *start_sector_nr,
                      int *sector_count);

    /**
     * @brief Read and extract a complete audio frame from disc sectors.
     *
     * Reads the specified frame from disc, parses the audio sector headers,
     * and extracts the elementary frame data (audio, supplementary, or padding)
     * of the requested type.
     *
     * @param[in]  ctx        Pointer to the frame reader context
     * @param[out] data       Buffer to receive the extracted frame data
     * @param[out] length     Receives the number of bytes written to @p data
     * @param[in]  frame_num  Frame index (0-based within the Track Area)
     * @param[in]  frame_lsn  Logical sector number where the frame starts
     * @param[in]  data_type  Type of data to extract (audio, supplementary, or padding)
     * @return Number of bytes extracted on success, 0 on failure
     */
    int (*read_frame)(sacd_frame_reader_t *ctx,
                      uint8_t *data,
                      uint32_t *length,
                      uint32_t frame_num,
                      uint32_t frame_lsn,
                      audio_packet_data_type_t data_type);

} sacd_frame_reader_ops_t;

/**
 * @enum sacd_frame_reader_type_t
 * @brief Identifies the SACD audio frame format handled by a reader instance.
 *
 * Each type corresponds to a different frame_format value from the Area TOC:
 * - DSD14: Fixed-format DSD, 14-sector frames (frame_format=2)
 * - DSD16: Fixed-format DSD, 16-sector frames (frame_format=3)
 * - DST: DST-encoded, variable-length frames (frame_format=0)
 */
typedef enum sacd_frame_reader_type {
    SACD_FRAME_READER_DSD14 = 0,    /**< Fixed DSD, 14 sectors per frame */
    SACD_FRAME_READER_DSD16,        /**< Fixed DSD, 16 sectors per frame */
    SACD_FRAME_READER_DST           /**< DST-encoded, variable-length frames */
} sacd_frame_reader_type_t;

/**
 * @struct sacd_frame_reader
 * @brief Base frame reader structure.
 *
 * All backend implementations must embed this structure as their first member.
 * This allows safe casting between the base type and derived types.
 *
 * @code
 * typedef struct {
 *     sacd_frame_reader_t base;  // Must be first!
 *     // Implementation-specific fields...
 * } my_frame_reader_impl_t;
 * @endcode
 */
struct sacd_frame_reader {
    const sacd_frame_reader_ops_t *ops;    /**< Pointer to vtable */
    sacd_frame_reader_type_t       type;   /**< Device type identifier */

    /* Member variables */
    uint32_t start_sector;      /**< First sector (LSN) of the Track Area */
    uint32_t end_sector;        /**< Last sector (LSN) of the Track Area */
    uint32_t sector_count;      /**< Total number of sectors in the Track Area */
    uint32_t last_sector_read;  /**< Last sector read (for caching/optimization) */
    int32_t sector_size;        /**< Total sector size (2048, 2054, or 2064) */
    int32_t header_size;        /**< Header size (0, 6, or 12) */
    int32_t trailer_size;       /**< Trailer size (0, 0, or 4) */

    sacd_input_t *input;        /**< Input device for disc sector access */
};


/**
 * @brief Initializes a frame reader context for reading a Track Area.
 *
 * This function sets up the reader to access a specific range of sectors containing
 * audio data. The sector range should correspond to a Track Area as defined
 * in the SACD Area TOC (track_area_start_address to track_area_end_address).
 *
 * @param[out] self          Pointer to the frame reader structure to initialize
 * @param[in]  input         Pointer to the input device providing sector access
 * @param[in]  start_sector  First sector (LSN) of the Track Area
 * @param[in]  end_sector    Last sector (LSN) of the Track Area
 * @param[in]  sector_size   Total sector size in bytes (including header and trailer).
 *                           Common values:
 *                           - 2048 (FS_SECTOR_SIZE_48): No header/trailer
 *                           - 2054 (FS_SECTOR_SIZE_54): 6-byte header
 *                           - 2064 (FS_SECTOR_SIZE_64): 12-byte header + 4-byte trailer
 *                           Pass 0 to use the maximum size (2064)
 * @param[in]  header_size   Size of the frame header in bytes (FS_HEADER_48/54/64: 0, 6, or 12)
 * @param[in]  trailer_size  Size of the frame trailer in bytes (FS_TRAILER_48/54/64: 0, 0, or 4)
 *
 * @note The sector_size should equal SACD_LSN_SIZE (2048) + header_size + trailer_size.
 * @see sacd_specification.h for FS_SECTOR_SIZE_* and SACD_LSN_SIZE definitions
 * @see area_data_t for Track Area structure
 */
static inline void sacd_frame_reader_init(sacd_frame_reader_t *self, sacd_input_t *input,
                uint32_t start_sector, uint32_t end_sector,
                int32_t sector_size, int32_t header_size,
                int32_t trailer_size)
{
    if (!self || !self->ops || !self->ops->init) {
        return;
    }
    /* Initialize sector range */
    self->start_sector = start_sector;
    self->end_sector = end_sector;
    self->last_sector_read = start_sector;
    self->sector_count = (end_sector - start_sector) + 1;

    /* Set sector format parameters */
    self->sector_size = sector_size;
    self->header_size = header_size;
    self->trailer_size = trailer_size;

    /* Store input for sector access */
    self->input = input;

    /* Call implementation-specific init */
    self->ops->init(self);
}

/**
 * @brief Destroy a frame reader and free all associated resources.
 *
 * Calls the implementation-specific destroy function to release internal
 * buffers and state. After this call, @p ctx should not be used.
 *
 * @param[in,out] ctx  Pointer to the frame reader context to destroy
 * @return SACD_INPUT_OK on success, SACD_INPUT_ERR_NULL_PTR if ctx is NULL
 */
static inline int sacd_frame_reader_destroy(sacd_frame_reader_t *ctx)
{
    if (!ctx || !ctx->ops || !ctx->ops->destroy) {
        return SACD_INPUT_ERR_NULL_PTR;
    }
    ctx->ops->destroy(ctx);
    return SACD_INPUT_OK;
}

/**
 * @brief Determine the sector range occupied by a given audio frame.
 *
 * Calculates which disc sectors contain the specified Multiplexed Frame.
 * This is useful for pre-fetching or determining read sizes before calling
 * sacd_frame_reader_read_frame().
 *
 * @param[in]  ctx              Pointer to the frame reader context
 * @param[in]  frame            Frame index (0-based within the Track Area)
 * @param[in]  frame_lsn        Logical sector number where the frame starts
 * @param[out] start_sector_nr  Receives the first sector number of the frame
 * @param[out] sector_count     Receives the number of sectors the frame spans
 * @return Number of sectors on success, 0 on failure or if get_sector is not implemented
 */
static inline int sacd_frame_reader_get_sector(sacd_frame_reader_t *ctx,
                      uint32_t frame, uint32_t frame_lsn,
                      uint32_t *start_sector_nr,
                      int *sector_count)
{
    if (!ctx || !ctx->ops || !ctx->ops->get_sector) {
        return 0;
    }
    return ctx->ops->get_sector(ctx, frame, frame_lsn, start_sector_nr, sector_count);
}

/**
 * @brief Read and extract a complete audio frame from disc sectors.
 *
 * Reads the specified Multiplexed Frame from disc, parses the audio sector
 * headers, and extracts the elementary frame data of the requested type.
 *
 * @param[in]  ctx        Pointer to the frame reader context
 * @param[out] data       Buffer to receive the extracted frame data (must be
 *                        large enough for the frame; typically up to
 *                        MAX_DST_SECTORS * SACD_LSN_SIZE bytes)
 * @param[out] length     Receives the number of bytes written to @p data
 * @param[in]  frame_num  Frame index (0-based within the Track Area)
 * @param[in]  frame_lsn  Logical sector number where the frame starts
 * @param[in]  data_type  Type of data to extract from the Multiplexed Frame
 *                        (DATA_TYPE_AUDIO, DATA_TYPE_SUPPLEMENTARY, or DATA_TYPE_PADDING)
 * @return Number of bytes extracted on success, 0 on failure
 *
 * @see audio_packet_data_type_t for data type definitions
 */
static inline int sacd_frame_reader_read_frame(sacd_frame_reader_t *ctx,
                    uint8_t *data,
                    uint32_t *length,
                    uint32_t frame_num,
                    uint32_t frame_lsn,
                    audio_packet_data_type_t data_type)
{
    if (!ctx || !ctx->ops || !ctx->ops->read_frame || !data) {
        return 0;
    }
    return ctx->ops->read_frame(ctx, data, length, frame_num, frame_lsn, data_type);
}

#ifdef __cplusplus
}
#endif

#endif /* LIBSACD_SACD_FRAME_READER_H */
