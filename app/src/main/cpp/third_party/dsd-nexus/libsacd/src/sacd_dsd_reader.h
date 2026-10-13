/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief Plain DSD (Direct Stream Digital) audio data readers for Super Audio CD.
 * This module provides functionality for reading uncompressed DSD audio frames
 * from SACD disc images. Unlike DST-coded audio, plain DSD uses fixed frame
 * formats with predictable sector layouts, allowing for simpler and more
 * efficient reading.
 * Two fixed DSD formats are supported per the SACD specification:
 * - **3-in-14 format** (FRAME_FORMAT_DSD_3_IN_14): 3 frames in 14 sectors (2-channel)
 * - **3-in-16 format** (FRAME_FORMAT_DSD_3_IN_16): 3 frames in 16 sectors (2-channel)
 * Key characteristics:
 * - Fixed frame size: SACD_FRAME_LENGTH (9408 bytes)
 * - Deterministic sector layout (no packet headers needed)
 * - Frames may span multiple sectors with specific offset patterns
 * - Block-based organization: 3 frames grouped into blocks
 * @see sacd_specification.h for frame_format_t and SACD_FRAME_LENGTH definitions
 * @see area_data_t.frame_format for Track Area format specification
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

#ifndef LIBSACD_SACD_DSD_READER_H
#define LIBSACD_SACD_DSD_READER_H

#include "sacd_frame_reader.h"
#include "sacd_input.h"

#ifdef __cplusplus
extern "C"
{
#endif

typedef enum {

	SACD_DSD_READER_OK = 0,
	/**< Operation completed successfully. */

	SACD_DSD_READER_UNINITIALIZED,
	/**< DSD reader context is uninitialized. Call sacd_frame_reader_init()
	 * before using read or sector operations.
	 */

    SACD_DSD_READER_IO_ERROR,
	/**< An I/O error occurred while reading disc sectors. */

	SACD_DSD_READER_MEMORY_ALLOCATION_ERROR
	/**< Memory allocation failed during initialization or parsing. */

} dsd_reader_state_t;

int sacd_frame_reader_fixed14_create(sacd_frame_reader_t **out);
int sacd_frame_reader_fixed16_create(sacd_frame_reader_t **out);

#ifdef __cplusplus
}
#endif

#endif /* LIBSACD_SACD_DSD_READER_H */
