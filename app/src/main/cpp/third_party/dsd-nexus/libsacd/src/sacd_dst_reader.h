/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief DST (Direct Stream Transfer) coded audio data reader for Super Audio CD.
 * This module provides functionality for reading and parsing DST-encoded audio frames
 * from SACD disc images. DST is a lossless compression format used in Super Audio CD
 * to reduce storage requirements while maintaining the full quality of DSD (Direct
 * Stream Digital) audio.
 * The reader handles:
 * - Locating audio frames within sectors using frame_info_t structures
 * - Extracting audio packets based on audio_packet_data_type_t (Audio, Supplementary, Padding)
 * - Managing multi-sector frames (DST frames can span 1-16 sectors depending on channel count)
 * - Parsing audio_sector_t headers to determine packet layout
 * - Supporting both DST-coded and plain DSD data (for streaming applications)
 * @see sacd_specification.h for detailed DST/DSD format documentation
 * @see audio_sector_t for sector structure definition
 * @see frame_info_t for frame header structure
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

#ifndef LIBSACD_SACD_DST_READER_H
#define LIBSACD_SACD_DST_READER_H

#include "sacd_frame_reader.h"
#include "sacd_input.h"
#include "sacd_area_toc.h"
#include "sacd_specification.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief Return status codes for DST reader operations.
 *
 * These status codes are returned by DST reader functions to indicate
 * success or specific error conditions during frame reading and data extraction.
 */
typedef enum {

	SACD_DST_READER_OK = 0,
	/**< Operation completed successfully. */

	SACD_DST_READER_UNINITIALIZED = -1,
	/**< DST reader context is uninitialized. Call sacd_dst_reader_init()
	 * before using other operations.
	 */

    SACD_DST_READER_IO_ERROR,
	/**< An I/O error occurred while reading disc sectors. */

	SACD_DST_READER_MEMORY_ALLOCATION_ERROR,
	/**< Memory allocation failed during sector buffer allocation. */

    SACD_DST_READER_ACCESS_LIST_INVALID,
    /**< The Access List contains invalid frame LSN values. Frame was found
     * before the specified starting LSN, indicating incorrect access list data.
     */

    SACD_DST_READER_BUFFER_TOO_SMALL,
    /**< The provided output buffer is too small to hold the extracted frame data.
     * Increase buffer size to accommodate the frame.
     */

    SACD_DST_READER_FRAME_INCOMPLETE_DATA,
    /**< Insufficient sectors were read to complete the frame. May occur when
     * reaching end of Track Area or during streaming operations.
     */

    SACD_DST_READER_FRAME_NOT_FOUND,
    /**< The requested frame was not found in the Track Area. The frame number
     * may be out of range or the Access List may be incorrect.
     */

    SACD_DST_READER_FRAME_SEARCH_OVERFLOW,
    /**< Frame search encountered conflicting position data during multi-sector
     * scan, indicating corrupted sector headers or invalid access list.
     */

    SACD_DST_READER_INVALID_ARG,
    /**< An invalid argument was provided (e.g., NULL pointer, out-of-range value).
     */

} dst_reader_state_t;

int sacd_frame_reader_dst_create(sacd_frame_reader_t **out, struct area_toc_s *area);

#ifdef __cplusplus
}
#endif

#endif /* LIBSACD_SACD_DST_READER_H */