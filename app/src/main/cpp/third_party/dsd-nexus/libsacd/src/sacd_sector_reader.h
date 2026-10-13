/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief Abstract sector reader interface with format-aware sector access.
 * This module provides a polymorphic interface for reading raw sectors from
 * SACD disc images that may contain different sector formats. Unlike
 * sacd_input.h which provides low-level device I/O, this interface handles
 * sector format variations (header/trailer sizes) transparently.
 * SACD disc images can have three sector formats:
 * - **2048 bytes**: Plain data sectors (no header or trailer)
 * - **2054 bytes**: 6-byte header + 2048-byte data (no trailer)
 * - **2064 bytes**: 12-byte header + 2048-byte data + 4-byte trailer
 * Implementations must provide a vtable (sacd_sector_reader_vtable_t) and
 * embed the sacd_sector_reader_t structure as their first member to allow
 * safe casting between base and derived types.
 * @code
 * typedef struct {
 *     sacd_sector_reader_t base;  // Must be first!
 *     FILE *fp;
 *     // Implementation-specific fields...
 * } my_sector_reader_t;
 * @endcode
 * @see sacd_input.h for the lower-level device I/O interface
 * @see sacd_frame_reader.h for higher-level audio frame reading
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

#ifndef LIBSACD_SACD_SECTOR_READER_H
#define LIBSACD_SACD_SECTOR_READER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @enum sector_format_t
 * @brief SACD disc image sector format identifiers.
 *
 * Identifies the raw sector size of an SACD disc image file. The format
 * determines the header and trailer sizes prepended/appended to each
 * 2048-byte logical sector.
 *
 * | Format       | Total Size | Header | Data | Trailer |
 * |:-------------|:-----------|:-------|:-----|:--------|
 * | SECTOR_2048  | 2048       | 0      | 2048 | 0       |
 * | SECTOR_2054  | 2054       | 6      | 2048 | 0       |
 * | SECTOR_2064  | 2064       | 12     | 2048 | 4       |
 */
typedef enum {
    SECTOR_2048 = 0,    /**< Plain 2048-byte sectors (no header/trailer) */
    SECTOR_2054 = 1,    /**< 6-byte header + 2048-byte data */
    SECTOR_2064 = 2,    /**< 12-byte header + 2048-byte data + 4-byte trailer */
} sector_format_t;

/** @brief Forward declaration of the sector reader context. */
struct sacd_sector_reader_t;

/**
 * @brief Initialize the sector reader and detect the sector format.
 *
 * Opens the specified file and determines its sector format by examining
 * the file size and/or header signatures.
 *
 * @param[in,out] ctx            Pointer to the sector reader context
 * @param[in]     file_name      Path to the disc image file (UTF-8 encoded)
 * @param[out]    sector_format  Receives the detected sector format
 * @return 0 on success, negative error code on failure
 */
typedef int (*sacd_sector_reader_init_fn)(
    struct sacd_sector_reader_t* ctx,
    const char* file_name,
    sector_format_t* sector_format);

/**
 * @brief Read one or more raw sectors from the disc image.
 *
 * Reads @p sector_count sectors starting at @p start_index. The returned
 * data includes headers and trailers if the sector format has them.
 *
 * @param[in,out] ctx               Pointer to the sector reader context
 * @param[out]    p_sector          Buffer to receive sector data (must be
 *                                  large enough for sector_count * sector_size bytes)
 * @param[out]    sector_count_read Receives the number of sectors actually read
 * @param[in]     start_index       Zero-based index of the first sector to read
 * @param[in]     sector_count      Number of sectors to read
 * @return 0 on success, negative error code on failure
 */
typedef int (*sacd_sector_reader_read_sectors_fn)(
    struct sacd_sector_reader_t* ctx,
    uint8_t * p_sector,
    uint32_t* sector_count_read,
    uint32_t start_index,
    uint32_t sector_count);

/**
 * @brief Close the sector reader and release all resources.
 *
 * @param[in,out] ctx  Pointer to the sector reader context
 * @return 0 on success, negative error code on failure
 *
 * @note After this call, the context should not be used.
 */
typedef int (*sacd_sector_reader_close_fn)(
    struct sacd_sector_reader_t* ctx);

/**
 * @brief Get the total sector size in bytes.
 *
 * Returns the full sector size including header and trailer bytes.
 * Common values: 2048, 2054, or 2064.
 *
 * @param[in]  ctx          Pointer to the sector reader context
 * @param[out] sector_size  Receives the sector size in bytes
 * @return 0 on success, negative error code on failure
 */
typedef int (*sacd_sector_reader_get_sector_size_fn)(
    struct sacd_sector_reader_t* ctx,
    int16_t* sector_size);

/**
 * @brief Get the sector header size in bytes.
 *
 * Returns the number of bytes prepended before the 2048-byte data payload
 * in each sector. Common values: 0, 6, or 12.
 *
 * @param[in]  ctx          Pointer to the sector reader context
 * @param[out] header_size  Receives the header size in bytes
 * @return 0 on success, negative error code on failure
 */
typedef int (*sacd_sector_reader_get_header_size_fn)(
    struct sacd_sector_reader_t* ctx,
    int16_t* header_size);

/**
 * @brief Get the sector trailer size in bytes.
 *
 * Returns the number of bytes appended after the 2048-byte data payload
 * in each sector. Common values: 0 or 4.
 *
 * @param[in]  ctx           Pointer to the sector reader context
 * @param[out] trailer_size  Receives the trailer size in bytes
 * @return 0 on success, negative error code on failure
 */
typedef int (*sacd_sector_reader_get_trailer_size_fn)(
    struct sacd_sector_reader_t* ctx,
    int16_t* trailer_size);

/**
 * @struct sacd_sector_reader_vtable_t
 * @brief Virtual function table for sector reader operations.
 *
 * All sector reader implementations must provide this vtable with
 * function pointers for each supported operation.
 */
typedef struct {
    sacd_sector_reader_init_fn init;                    /**< Initialize and detect format */
    sacd_sector_reader_read_sectors_fn get_sector_data; /**< Read raw sectors */
    sacd_sector_reader_close_fn close;                  /**< Close and release resources */
    sacd_sector_reader_get_sector_size_fn get_sector_size;      /**< Get total sector size */
    sacd_sector_reader_get_header_size_fn get_header_size;      /**< Get header size */
    sacd_sector_reader_get_trailer_size_fn get_trailer_size;    /**< Get trailer size */
} sacd_sector_reader_vtable_t;

/**
 * @struct sacd_sector_reader_t
 * @brief Base sector reader structure.
 *
 * All backend implementations must embed this structure as their first member.
 * This allows safe casting between the base type and derived types.
 *
 * @code
 * typedef struct {
 *     sacd_sector_reader_t base;  // Must be first!
 *     FILE *fp;
 *     uint32_t total_sectors;
 *     // ...
 * } my_sector_reader_impl_t;
 * @endcode
 */
typedef struct sacd_sector_reader_t {
    const sacd_sector_reader_vtable_t* vtable;  /**< Pointer to vtable */
} sacd_sector_reader_t;

#ifdef __cplusplus
}
#endif

#endif /* LIBSACD_SACD_SECTOR_READER_H */
