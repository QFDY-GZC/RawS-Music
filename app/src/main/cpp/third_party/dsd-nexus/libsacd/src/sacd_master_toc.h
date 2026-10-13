/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief Super Audio CD Master Table of Contents (Master TOC) parser and accessor functions.
 * This module provides functionality to read, parse, and access the Master TOC of a Super Audio CD.
 * The Master TOC contains disc-level and album-level metadata including:
 * - Album and disc catalog numbers
 * - Genre information
 * - Manufacturing information
 * - Pointers to Area TOCs (2-Channel Stereo and Multi Channel)
 * - Disc creation date and web link information
 * The Master TOC is stored in three redundant copies on the disc at sectors 510, 520, and 530,
 * occupying 10 sectors each (sectors 0-9 of each copy).
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

#ifndef LIBSACD_SACD_MASTER_TOC_H
#define LIBSACD_SACD_MASTER_TOC_H

#include <libsautil/bswap.h>

#include "sacd_input.h"
#include "sacd_specification.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {

	SACD_MASTER_TOC_OK = 0,
	/**< Operation completed successfully. */

	SACD_MASTER_TOC_UNINITIALIZED,
	/**< Master TOC context is uninitialized. Call sacd_master_toc_init() and
	 * sacd_master_toc_read() before using other operations.
	 */

    SACD_MASTER_TOC_IO_ERROR,
	/**< An I/O error occurred while reading disc sectors. */

	SACD_MASTER_TOC_MEMORY_ALLOCATION_ERROR,
	/**< Memory allocation failed during initialization or parsing. */

    SACD_MASTER_TOC_NO_DATA,
    /**< Incomplete or missing TOC data on disc.
     */

    SACD_MASTER_TOC_INVALID_SIGNATURE,
    /**< Invalid signature found in Master TOC structures.
     */

    SACD_MASTER_TOC_INVALID_AREA_POINTER,
    /**< Inconsistent Area TOC pointer values.
     */

    SACD_MASTER_TOC_INVALID_ARGUMENT
    /**< An invalid argument was provided to a function.
     */

} master_toc_state_t;

/**
 * @brief Genre information consisting of a table identifier and an index within that table.
 */
typedef struct {
    /**
     * @brief Identifies the specific genre table.
     * - 0: Not used
     * - 1: General genre Table
     * - 2: Japanese genre Table
     * - 3..255: Reserved
     */
    uint8_t genre_table;

    /**
     * @brief The index within the selected genre_table.
     */
    uint16_t index;
} master_toc_genre_t;

/**
 * @brief Contains metadata for either disc or album information.
 *
 * This structure holds catalog number, text strings in multiple languages/character sets,
 * and genre information. It is used for both disc-level and album-level metadata.
 */
typedef struct {
    /**
     * @brief Catalog number (e.g., UPC/EAN), null-terminated.
     */
    char catalog_num[MAX_CATALOG_LENGTH + 1];

    /**
     * @brief Text strings organized by text channel and text type.
     * - First dimension: Text channel (0 to MAX_TEXT_CHANNEL_COUNT-1)
     * - Second dimension: Text type (title, artist, publisher, copyright, and phonetic variants)
     * Each string is dynamically allocated and converted to UTF-8.
     */
    char* text[MAX_TEXT_CHANNEL_COUNT][MAX_TEXT_TYPE_COUNT];

    /**
     * @brief Genre codes (up to 4 genres can be specified).
     */
    master_toc_genre_t genre[MAX_GENRE_COUNT];
} master_toc_info_t;

/**
 * @brief Master TOC context structure containing all disc and album metadata.
 *
 * This structure is populated by reading the Master TOC sectors from the SACD disc.
 * It contains parsed information from master_toc_0_t, master_text_t, and manuf_info_t structures.
 */
typedef struct {
    /**
     * @brief SACD specification version (major.minor).
     */
    sacd_version_t version;

    /**
     * @brief Number of text channels available (0 to 8).
     * Each text channel can have a different language and character set.
     */
    uint32_t text_channel_count;

    /**
     * @brief Current text channel index (0-based).
     */
    uint32_t cur_text_channel;

    /**
     * @brief Language and character set information for each text channel.
     */
    chan_info_t channel_info[MAX_TEXT_CHANNEL_COUNT];

    /**
     * @brief Disc-specific metadata (catalog number, genres, text).
     */
    master_toc_info_t disc_info;

    /**
     * @brief Album-specific metadata (catalog number, genres, text).
     */
    master_toc_info_t album_info;

    /**
     * @brief Logical Sector Number (LSN) of the first Multi Channel Area TOC-1.
     * Set to 0 if Multi Channel area is not present.
     */
    uint32_t mc_toc_area1_start;

    /**
     * @brief LSN of the second Multi Channel Area TOC-2.
     * Set to 0 if Multi Channel area is not present.
     */
    uint32_t mc_toc_area2_start;

    /**
     * @brief Length in sectors of the Multi Channel Area TOC.
     * Set to 0 if Multi Channel area is not present.
     */
    uint16_t mc_toc_area_length;

    /**
     * @brief LSN of the first 2-Channel Stereo Area TOC-1.
     * Set to 544 if present, 0 if not present.
     */
    uint32_t st_toc_area1_start;

    /**
     * @brief LSN of the second 2-Channel Stereo Area TOC-2.
     * Set to 0 if 2-Channel area is not present.
     */
    uint32_t st_toc_area2_start;

    /**
     * @brief Length in sectors of the 2-Channel Stereo Area TOC.
     * Set to 0 if 2-Channel area is not present.
     */
    uint16_t st_toc_area_length;

    /**
     * @brief Manufacturer-specific information string, null-terminated.
     * Format is determined by the disc manufacturer.
     */
    char manufacturer_info[MAX_MANUFACTURER_INFO + 1];

    /**
     * @brief Indicates whether this is a hybrid disc.
     * - true: Hybrid disc (contains both SACD and CD layers)
     * - false: Non-hybrid disc (SACD only)
     */
    bool disc_type_hybrid;

    /**
     * @brief Total number of discs in the album set.
     * Minimum value is 1.
     */
    uint16_t album_size;

    /**
     * @brief Sequence number of this disc within the album.
     * Numbering starts with 1 for the first disc.
     */
    uint16_t album_sequence;

    /**
     * @brief Disc creation date (year, month, day).
     * All fields are 0 if date is not available.
     */
    date_sacd_t date;

    /**
     * @brief URL pointing to a web page with disc information, null-terminated.
     */
    char web_link_info[MAX_DISC_WEB_LINK_INFO + 1];

    /**
     * @brief Indicates whether this context has been successfully initialized.
     * - true: Context has been initialized and TOC has been read successfully
     * - false: Context is uninitialized or has been closed
     */
    bool initialized;
} master_toc_t;

/* Lifecycle Management */

/**
 * @brief Initializes a master_toc_t context structure.
 *
 * Clears all disc and album information fields to zero. Must be called before using the context.
 *
 * @param ctx Pointer to the master_toc_t context to initialize.
 */
void sacd_master_toc_init(master_toc_t* ctx);

/**
 * @brief Destroys a master_toc_t context and frees all allocated resources.
 *
 * Calls sacd_master_toc_close() to free dynamically allocated text strings.
 *
 * @param ctx Pointer to the master_toc_t context to destroy.
 */
void sacd_master_toc_destroy(master_toc_t* ctx);

/**
 * @brief Reads and parses the Master TOC from the SACD disc.
 *
 * Reads 10 sectors from the specified Master TOC copy location (510, 520, or 530), validates
 * signatures, extracts metadata from master_toc_0_t, master_text_t, and manuf_info_t structures,
 * and populates the context. Text strings are converted from their native character sets to UTF-8.
 *
 * The Master TOC consists of:
 * - Sector 0: master_toc_0_t (general disc/album metadata)
 * - Sectors 1-8: master_text_t[0-7] (text strings for each text channel)
 * - Sector 9: manuf_info_t (manufacturer information)
 *
 * @param ctx Pointer to the master_toc_t context to populate.
 * @param toc_copy_index Master TOC copy number (1, 2, or 3).
 *                       - 1 (default): Read from sector 510 (MASTER_TOC1_START)
 *                       - 2: Read from sector 530 (MASTER_TOC3_START)
 *                       - 3: Read from sector 520 (MASTER_TOC2_START)
 * @param input Pointer to the input device for accessing disc sectors.
 * @return SACD_MASTER_TOC_OK on success, or an error code:
 *         - SACD_MASTER_TOC_MEMORY_ALLOCATION_ERROR: Memory allocation failed
 *         - SACD_MASTER_TOC_IO_ERROR: Failed to read sectors
 *         - SACD_MASTER_TOC_NO_DATA: Did not read expected 10 sectors
 *         - SACD_MASTER_TOC_INVALID_SIGNATURE: Invalid signature in Master TOC structures
 *         - SACD_MASTER_TOC_INVALID_AREA_POINTER: Inconsistent TOC pointer values
 */
int sacd_master_toc_read(master_toc_t* ctx, uint32_t toc_copy_index,
                            sacd_input_t* input);

/**
 * @brief Frees all dynamically allocated text strings in the context.
 *
 * Frees all album and disc text strings for all text channels and text types.
 * Does not free the context itself.
 *
 * @param ctx Pointer to the master_toc_t context to clear.
 */
void sacd_master_toc_close(master_toc_t* ctx);

/* Area TOC Access */

/**
 * @brief Retrieves the sector numbers and length of a Track Area TOC.
 *
 * Returns the LSN locations of both TOC copies (TOC-1 and TOC-2) and the length in sectors
 * for the specified area type (2-Channel Stereo or Multi Channel).
 *
 * @param ctx Pointer to the master_toc_t context.
 * @param area_type Area type (TWO_CHANNEL or MULTI_CHANNEL).
 * @param out_area1_start Output: LSN of first Area TOC copy (Area TOC-1).
 * @param out_area2_start Output: LSN of second Area TOC copy (Area TOC-2).
 * @param out_area_length Output: Length of Area TOC in sectors.
 * @return true on success, false if area_type is invalid.
 */
bool sacd_master_toc_get_area_toc_sector_range(master_toc_t* ctx, channel_t area_type,
                                    uint32_t* out_area1_start, uint32_t* out_area2_start,
                                    uint16_t* out_area_length);

/* General Information Accessors */

/**
 * @brief Gets the SACD specification version.
 *
 * @param ctx Pointer to the master_toc_t context.
 * @return SACD specification version (major.minor). For this specification, should be 2.0.
 */
sacd_version_t sacd_master_toc_get_sacd_version(master_toc_t *ctx);

/**
 * @brief Gets the number of text channels available.
 *
 * @param ctx Pointer to the master_toc_t context.
 * @return Number of text channels (0 to 8). Each channel can have a different language/character set.
 */
uint8_t sacd_master_toc_get_text_channel_count(master_toc_t *ctx);

/**
 * @brief Gets the language code and character set for a specific text channel.
 *
 * @param ctx Pointer to the master_toc_t context.
 * @param channel_number Text channel number (1-based, range: 1 to text_channel_count).
 * @param out_language_code Output: Pointer to 2-character ISO 639 language code (not null-terminated).
 * @param out_charset_code Output: Character set code (1=ISO646, 2=ISO8859-1, 3=RIS506, etc.).
 * @return SACD_MASTER_TOC_OK on success, SACD_MASTER_TOC_INVALID_ARGUMENT if channel_number is out of range.
 */
int sacd_master_toc_get_text_channel_info(master_toc_t *ctx, uint8_t channel_number,
                                      char **out_language_code, uint8_t *out_charset_code);

/**
 * @brief Checks if the disc is a hybrid disc.
 *
 * @param ctx Pointer to the master_toc_t context.
 * @return true if the disc contains both SACD and CD layers, false otherwise.
 */
bool sacd_master_toc_is_disc_hybrid(master_toc_t* ctx);

/**
 * @brief Gets the manufacturer information string.
 *
 * @param ctx Pointer to the master_toc_t context.
 * @return Pointer to null-terminated manufacturer information string. Format is manufacturer-defined.
 */
char* sacd_master_toc_get_manufacturer_info(master_toc_t* ctx);

/* Album Information Accessors */

/**
 * @brief Gets the total number of discs in the album set.
 *
 * @param ctx Pointer to the master_toc_t context.
 * @return Total number of discs in the album (minimum value is 1).
 */
uint16_t sacd_master_toc_get_album_size(master_toc_t* ctx);

/**
 * @brief Gets the sequence number of this disc within the album.
 *
 * @param ctx Pointer to the master_toc_t context.
 * @return Disc sequence number (1 for the first disc, up to album_size).
 */
uint16_t sacd_master_toc_get_disc_sequence_num(master_toc_t* ctx);

/**
 * @brief Gets the album catalog number.
 *
 * @param ctx Pointer to the master_toc_t context.
 * @return Pointer to null-terminated album catalog number (e.g., UPC/EAN).
 *         All discs in the album have the same catalog number.
 */
char* sacd_master_toc_get_album_catalog_num(master_toc_t* ctx);

/**
 * @brief Gets genre information for the album.
 *
 * @param ctx Pointer to the master_toc_t context.
 * @param genre_number Genre number (1-based, range: 1 to 4).
 * @param out_genre_table Output: Genre table identifier (0=Not used, 1=General, 2=Japanese).
 * @param out_genre_index Output: Index within the specified genre table.
 */
void sacd_master_toc_get_album_genre(master_toc_t* ctx, uint16_t genre_number,
                             uint8_t* out_genre_table, uint16_t* out_genre_index);

/**
 * @brief Gets album text for a specific text channel and text type.
 *
 * @param ctx Pointer to the master_toc_t context.
 * @param channel_number Text channel number (1-based, range: 1 to text_channel_count).
 * @param text_type Type of text to retrieve (ALBUM_TEXT_TYPE_TITLE, ALBUM_TEXT_TYPE_ARTIST, etc.).
 * @return Pointer to UTF-8 string, or NULL if not available.
 */
char* sacd_master_toc_get_album_text(master_toc_t* ctx, uint8_t channel_number,
                             album_text_type_t text_type);

/* Disc Information Accessors */

/**
 * @brief Gets the disc catalog number.
 *
 * @param ctx Pointer to the master_toc_t context.
 * @return Pointer to null-terminated disc catalog number.
 *         This uniquely identifies the specific disc within the album.
 */
char* sacd_master_toc_get_disc_catalog_num(master_toc_t* ctx);

/**
 * @brief Gets genre information for the disc.
 *
 * @param ctx Pointer to the master_toc_t context.
 * @param genre_number Genre number (1-based, range: 1 to 4).
 * @param out_genre_table Output: Genre table identifier (0=Not used, 1=General, 2=Japanese).
 * @param out_genre_index Output: Index within the specified genre table.
 */
void sacd_master_toc_get_disc_genre(master_toc_t* ctx, uint16_t genre_number,
                            uint8_t* out_genre_table, uint16_t* out_genre_index);

/**
 * @brief Gets the disc creation date.
 *
 * @param ctx Pointer to the master_toc_t context.
 * @param out_year Output: Year (0 if not available).
 * @param out_month Output: Month (1-12, 0 if not available).
 * @param out_day Output: Day (1-31, 0 if not available).
 */
void sacd_master_toc_get_disc_date(master_toc_t* ctx, uint16_t* out_year, uint8_t* out_month,
                           uint8_t* out_day);

/**
 * @brief Gets disc text for a specific text channel and text type.
 *
 * @param ctx Pointer to the master_toc_t context.
 * @param channel_number Text channel number (1-based, range: 1 to text_channel_count).
 * @param text_type Type of text to retrieve (ALBUM_TEXT_TYPE_TITLE, ALBUM_TEXT_TYPE_ARTIST, etc.).
 * @return Pointer to UTF-8 string, or NULL if not available.
 */
char* sacd_master_toc_get_disc_text(master_toc_t* ctx, uint8_t channel_number,
                            album_text_type_t text_type);

/**
 * @brief Gets the disc web link URL.
 *
 * @param ctx Pointer to the master_toc_t context.
 * @return Pointer to null-terminated URL string pointing to a web page with disc information.
 */
char* sacd_master_toc_get_disc_web_link_info(master_toc_t* ctx);

/* ========================================================================
 * Path Generation Helpers
 * ======================================================================== */

/**
 * @brief Options for album directory name generation.
 */
typedef enum {
    MASTER_TOC_PATH_TITLE_ONLY = 0,       /**< Only include album/disc title */
    MASTER_TOC_PATH_ARTIST_TITLE = 1,     /**< Include "Artist - Title" format */
    MASTER_TOC_PATH_YEAR_ARTIST_TITLE = 2 /**< Include "Year - Artist - Title" format */
} master_toc_path_format_t;

/**
 * @brief Generates an album directory name from disc metadata.
 *
 * Creates a sanitized directory name suitable for filesystem use.
 * For multi-disc albums, appends "(Disc N of M)" to the name.
 *
 * @param[in] ctx          Master TOC context (must be initialized)
 * @param[in] format       Path format option controlling included fields
 * @param[in] text_channel Text channel number (1-based) for metadata lookup
 *
 * @return Newly allocated string with the album directory name, or NULL on error.
 *         Caller must free with sa_free().
 *
 * @note Characters invalid for filenames are replaced with underscores.
 *
 * Example outputs:
 * - MASTER_TOC_PATH_TITLE_ONLY: "Album Title"
 * - MASTER_TOC_PATH_ARTIST_TITLE: "Artist Name - Album Title"
 * - Multi-disc: "Album Title (Disc 1 of 3)"
 */
char *sacd_master_toc_get_album_dir(master_toc_t *ctx,
                                    master_toc_path_format_t format,
                                    uint8_t text_channel);

/**
 * @brief Generates a full album path with optional disc subdirectory.
 *
 * Similar to sacd_master_toc_get_album_dir(), but for multi-disc albums creates
 * a path with a "Disc N" subdirectory.
 *
 * @param[in] ctx          Master TOC context (must be initialized)
 * @param[in] format       Path format option controlling included fields
 * @param[in] text_channel Text channel number (1-based) for metadata lookup
 *
 * @return Newly allocated string with the path, or NULL on error.
 *         Caller must free with sa_free().
 *
 * Example outputs:
 * - Single disc: "Artist - Album Title"
 * - Multi-disc: "Artist - Album Title/Disc 1"
 */
char *sacd_master_toc_get_album_path(master_toc_t *ctx,
                                     master_toc_path_format_t format,
                                     uint8_t text_channel);

#ifdef __cplusplus
}
#endif

#endif /* LIBSACD_SACD_MASTER_TOC_H */