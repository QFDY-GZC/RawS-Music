/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief High-level SACD (Super Audio CD) disc reader interface.
 * This module provides a unified, high-level API for reading SACD disc images.
 * It combines Master TOC and Area TOC functionality to provide convenient access
 * to all disc metadata, track information, and audio data.
 * The sacd manages:
 * - Disc-level metadata (Master TOC): album info, catalog numbers, genres, dates
 * - Area-level metadata (Area TOCs): 2-channel and multi-channel areas
 * - Track information: ISRC codes, genres, text metadata, timing
 * - Audio data extraction: both main audio and supplementary data
 * - Channel selection: automatic routing to the appropriate Area TOC
 * Key features:
 * - Transparent handling of 2-channel stereo and multi-channel (5.1) areas
 * - Support for both DST-compressed and plain DSD audio formats
 * - Multi-language text support via text channels
 * - Frame-accurate positioning and seeking
 * - ISRC and genre metadata per track
 * @see sacd_master_toc.h for Master TOC structures
 * @see sacd_area_toc.h for Area TOC structures
 * @see sacd_specification.h for SACD format definitions
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

#ifndef LIBSACD_SACD_H
#define LIBSACD_SACD_H

#include <libsautil/export.h>

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MAX_INDEX_COUNT 255
#define MAX_GENRE_COUNT 4
#define MAX_TEXT_CHANNEL_COUNT 8
#define MAX_TEXT_TYPE_COUNT 8
#define MAX_AREA_TEXT_TYPE_COUNT 4
#define MAX_CATALOG_LENGTH 16
#define MAX_TRACK_COUNT 255
#define MAX_CHANNEL_COUNT 6
#define SACD_FRAMES_PER_SEC            (75)
#define SACD_SAMPLES_PER_FRAME         (588)
#define SACD_FRAME_SIZE_64 (SACD_SAMPLES_PER_FRAME * 64 / 8)
#define SACD_SAMPLING_FREQUENCY        (SACD_SAMPLES_PER_FRAME * 64 * SACD_FRAMES_PER_SEC)
#define SACD_MAX_DST_SIZE (SACD_FRAME_SIZE_64 * MAX_CHANNEL_COUNT)
#define SACD_MAX_DSD_SIZE (SACD_FRAME_SIZE_64 * MAX_CHANNEL_COUNT)

typedef enum {

	SACD_OK = 0,
	/**< Operation completed successfully. */

	SACD_UNINITIALIZED,
	/**< SACD reader is uninitialized. Call sacd_init() before using other
	 * operations.
	 */

    SACD_IO_ERROR,
	/**< An I/O error occurred while reading disc sectors. */

	SACD_MEMORY_ALLOCATION_ERROR,
	/**< Memory allocation failed during initialization or parsing. */

    SACD_INVALID_ARGUMENT,
    /**< An invalid argument was provided to a function (e.g., NULL pointer,
     * out-of-range track number, invalid channel type).
     */

    SACD_NOT_AVAILABLE,
    /**< Requested resource is not available on this disc (e.g., multi-channel
     * area requested when only 2-channel area exists).
     */

    SACD_SECTOR_READER_INIT_FAILED,
    /**< Failed to initialize the underlying sector reader. Check that the
     * disc image file exists and is accessible.
     */

    SACD_ITEM_NOT_AVAILABLE
    /**< Requested metadata item is not present (e.g., text field not populated
     * for this track or channel).
     */

} reader_state_t;

typedef enum {
    TWO_CHANNEL = 0,
    MULTI_CHANNEL
} channel_t;

typedef enum
{
      FRAME_FORMAT_DST         = 0
    , FRAME_FORMAT_DSD_3_IN_14 = 2
    , FRAME_FORMAT_DSD_3_IN_16 = 3
    , FRAME_FORMAT_UNKNOWN
} frame_format_t;

typedef enum
{
      CATEGORY_NOT_USED = 0
    , CATEGORY_GENERAL  = 1
    , CATEGORY_JAPANESE = 2
} category_t;

typedef enum
{
      TRACK_TYPE_TITLE                  = 0x01
    , TRACK_TYPE_PERFORMER              = 0x02
    , TRACK_TYPE_SONGWRITER             = 0x03
    , TRACK_TYPE_COMPOSER               = 0x04
    , TRACK_TYPE_ARRANGER               = 0x05
    , TRACK_TYPE_MESSAGE                = 0x06
    , TRACK_TYPE_EXTRA_MESSAGE          = 0x07

    , TRACK_TYPE_TITLE_PHONETIC         = 0x81
    , TRACK_TYPE_PERFORMER_PHONETIC     = 0x82
    , TRACK_TYPE_SONGWRITER_PHONETIC    = 0x83
    , TRACK_TYPE_COMPOSER_PHONETIC      = 0x84
    , TRACK_TYPE_ARRANGER_PHONETIC      = 0x85
    , TRACK_TYPE_MESSAGE_PHONETIC       = 0x86
    , TRACK_TYPE_EXTRA_MESSAGE_PHONETIC = 0x87
} track_type_t;

typedef enum {
    ALBUM_TEXT_TYPE_TITLE = 0,
    ALBUM_TEXT_TYPE_ARTIST,
    ALBUM_TEXT_TYPE_PUBLISHER,
    ALBUM_TEXT_TYPE_COPYRIGHT,
    ALBUM_TEXT_TYPE_TITLE_PHONETIC,
    ALBUM_TEXT_TYPE_ARTIST_PHONETIC,
    ALBUM_TEXT_TYPE_PUBLISHER_PHONETIC,
    ALBUM_TEXT_TYPE_COPYRIGHT_PHONETIC
} album_text_type_t;

typedef enum {
    AREA_TEXT_TYPE_NAME = 0,
    AREA_TEXT_TYPE_COPYRIGHT,
    AREA_TEXT_TYPE_NAME_PHONETIC,
    AREA_TEXT_TYPE_COPYRIGHT_PHONETIC
} area_text_type_t;

/**
 * @brief Options for album directory/path generation.
 */
typedef enum {
    SACD_PATH_TITLE_ONLY = 0,        /**< "Album Title" format */
    SACD_PATH_ARTIST_TITLE = 1,      /**< "Artist - Album Title" format */
    SACD_PATH_YEAR_ARTIST_TITLE = 2  /**< "Year - Artist - Album Title" format */
} sacd_path_format_t;

/**
 * @brief Options for track filename generation.
 */
typedef enum {
    SACD_TRACK_NUM_TITLE = 0,        /**< "NN - Title" format */
    SACD_TRACK_NUM_ARTIST_TITLE = 1, /**< "NN - Artist - Title" format */
    SACD_TRACK_NUM_ONLY = 2          /**< "NN" format (track number only) */
} sacd_track_format_t;

typedef enum
{
      DATA_TYPE_AUDIO           = 2
    , DATA_TYPE_SUPPLEMENTARY   = 3
    , DATA_TYPE_PADDING         = 7
} 
audio_packet_data_type_t;

#undef ATTRIBUTE_PACKED
#undef PRAGMA_PACK_BEGIN
#undef PRAGMA_PACK_END

#if defined(__GNUC__)
#if __GNUC__ > 2 || (__GNUC__ == 2 && __GNUC_MINOR__ >= 95)
#define ATTRIBUTE_PACKED __attribute__((packed))
#define PRAGMA_PACK 0
#endif
#endif

#if !defined(ATTRIBUTE_PACKED)
#define ATTRIBUTE_PACKED
#define PRAGMA_PACK 1
#endif

/**
 * @brief Represents a time code using Minutes, Seconds, and Frames.
 * * This structure is typically used for time indexing in CD/SACD standards.
 * * The total size is 3 bytes (1 + 1 + 1).
 */
typedef struct
{
    /**
     * @brief The minutes component of the time code.
     * * Range: 0 to 255.
     * * An invalid time is indicated if all fields (minutes, seconds, and frames) are set to 0xFF.
     */
    uint8_t minutes;

    /**
     * @brief The seconds component of the time code.
     * * Range: 0 to 59.
     * * Values outside this range should be considered invalid for standard time representation.
     * * An invalid time is indicated if all fields (minutes, seconds, and frames) are set to 0xFF.
     */
    uint8_t seconds;

    /**
     * @brief The frames component of the time code.
     * * Range: 0 to 74.
     * * This range is typical for the CD/SACD standard (75 frames per second).
     * * An invalid time is indicated if all fields (minutes, seconds, and frames) are set to 0xFF.
     */
    uint8_t frames;
} ATTRIBUTE_PACKED time_sacd_t;

/**
 * @brief Represents an International Standard Recording Code (ISRC).
 * * This structure is used to identify a specific track/recording.
 * * If the ISRC code is not available, all fields MUST be set to NUL characters (0x00).
 * * When used, the code must comply with ISO 3901.
 * * The total size is 12 bytes (2 + 3 + 2 + 5).
 */
typedef struct
{
    /**
     * @brief The ISO 646 coded Country Code.
     * * See ISO 3901 section 4.1.
     */
    char country_code[2];

    /**
     * @brief The ISO 646 coded First Owner Code.
     * * See ISO 3901 section 4.2.
     */
    char owner_code[3];

    /**
     * @brief The year-of-recording code.
     * * Each digit is coded as one ISO 646 character.
     * * See ISO 3901 section 4.3.
     */
    char recording_year[2];

    /**
     * @brief The concatenation of the Recording code and the Recording-item code.
     * * Each digit is coded as one ISO 646 character.
     * * See ISO 3901 sections 4.4 and 4.5.
     */
    char designation_code[5];
} ATTRIBUTE_PACKED area_isrc_t;

#if PRAGMA_PACK
#pragma pack()
#endif

typedef struct sacd_s sacd_t;
typedef struct sacd_input_s sacd_input_t;
typedef struct area_toc_s area_toc_t;

SACD_API extern const char *album_category[];
SACD_API extern const char *album_genre_general[];
SACD_API extern const char *album_genre_japanese[];

/* ========================================================================
 * Lifecycle Management
 * ======================================================================== */

/**
 * @brief Creates and initializes a new SACD reader context.
 *
 * Allocates and zero-initializes a new sacd_t structure. The returned
 * context must be initialized with sacd_init() before use and destroyed
 * with sacd_destroy() when finished.
 *
 * @return Pointer to newly allocated sacd_t context, or NULL if allocation failed
 *
 * @see sacd_destroy
 * @see sacd_init
 */
SACD_API sacd_t* sacd_create(void);

/**
 * @brief Destroys an SACD reader context and frees all resources.
 *
 * Closes any open disc, frees all TOC structures, and deallocates the context.
 * Calls sacd_close() internally to clean up resources.
 *
 * @param[in,out] ctx Pointer to the sacd_t context to destroy
 *
 * @see sacd_create
 * @see sacd_close
 */
SACD_API void sacd_destroy(sacd_t* ctx);

/**
 * @brief Initializes the SACD reader by opening a disc and reading TOC structures.
 *
 * This function:
 * 1. Opens the disc image and initializes the input for sector access
 * 2. Reads and parses the Master TOC
 * 3. Reads and parses available Area TOCs (2-channel and/or multi-channel)
 * 4. Sets up audio data readers based on detected frame format
 *
 * @param[in,out] ctx            Pointer to the sacd_t context (must be created first)
 * @param[in]     filename       Path to the SACD disc image file
 * @param[in]     master_toc_nr  Which Master TOC copy to use (1 or 2, typically use 1)
 * @param[in]     area_toc_nr    Which Area TOC copy to use (1 or 2, typically use 1)
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_INVALID_ARGUMENT: Invalid context pointer
 *         - SACD_MEMORY_ALLOCATION_ERROR: Memory allocation failed
 *         - SACD_SECTOR_READER_INIT_FAILED: Failed to initialize input device
 *         - (Other error codes from Master TOC or Area TOC initialization)
 *
 * @note The reader will initialize all available areas (2-channel and/or multi-channel).
 *       Use sacd_get_available_channel_types() to determine which areas are present.
 *
 * @see sacd_close
 * @see sacd_select_channel_type
 */
SACD_API int sacd_init(sacd_t* ctx, const char* filename,
                            unsigned int master_toc_nr, unsigned int area_toc_nr);

/**
 * @brief Closes the SACD reader and releases all TOC resources.
 *
 * Frees all dynamically allocated TOC structures (Master TOC and Area TOCs),
 * closes the read object, and resets the context to uninitialized state.
 * Does not free the context structure itself.
 *
 * @param[in,out] ctx Pointer to the sacd_t context to close
 *
 * @return SACD_OK on success, SACD_INVALID_ARGUMENT if ctx is NULL
 *
 * @see sacd_init
 * @see sacd_destroy
 */
SACD_API int sacd_close(sacd_t* ctx);

/* ========================================================================
 * Channel Selection
 * ======================================================================== */

/**
 * @brief Selects which audio area (channel type) to use for subsequent operations.
 *
 * SACD discs may contain separate 2-channel stereo and multi-channel (5.1) areas.
 * This function selects which area to use for all track and audio data operations.
 *
 * @param[in,out] ctx          Pointer to the sacd_t context
 * @param[in]     channel_type Channel type to select (TWO_CHANNEL or MULTI_CHANNEL)
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_NOT_AVAILABLE: Requested channel type not present on disc
 *
 * @see sacd_get_available_channel_types
 */
SACD_API int sacd_select_channel_type(sacd_t* ctx, channel_t channel_type);

/**
 * @brief Queries which audio areas (channel types) are available on the disc.
 *
 * @param[in]     ctx             Pointer to the sacd_t context
 * @param[out]    channel_types Array to receive available channel types
 * @param[in,out] nr_types        Input: size of channel_types array
 *                                Output: number of available types
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 *
 * @note Multi-channel areas are returned first if present, followed by 2-channel.
 *
 * @see sacd_select_channel_type
 */
SACD_API int sacd_get_available_channel_types(sacd_t* ctx,
                                              channel_t* channel_types,
                                              uint16_t* nr_types);

/* ========================================================================
 * Frame Position Management
 * ======================================================================== */

/**
 * @brief Gets the current playback frame number.
 *
 * @param[in]  ctx       Pointer to the sacd_t context
 * @param[out] frame_num Pointer to receive the current frame number (75 frames per second)
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 *
 * @see sacd_set_current_frame_num
 */
SACD_API int sacd_get_current_frame_num(sacd_t* ctx, uint32_t* frame_num);

/**
 * @brief Sets the current playback frame number for seeking.
 *
 * @param[in,out] ctx       Pointer to the sacd_t context
 * @param[in]     frame_num Frame number to seek to (75 frames per second)
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 *
 * @see sacd_get_current_frame_num
 */
SACD_API int sacd_set_current_frame_num(sacd_t* ctx, uint32_t frame_num);

/* ========================================================================
 * Disc-Level Information (from Master TOC)
 * ======================================================================== */

/**
 * @brief Gets the SACD specification version of the disc.
 *
 * @param[in]  ctx   Pointer to the sacd_t context
 * @param[out] major Pointer to receive major version number (typically 2)
 * @param[out] minor Pointer to receive minor version number (typically 0)
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_disc_spec_version(sacd_t* ctx, uint8_t *major, uint8_t *minor);

/**
 * @brief Gets the number of discs in the album (for multi-disc sets).
 *
 * @param[in]  ctx      Pointer to the sacd_t context
 * @param[out] num_disc Pointer to receive the album size (number of discs)
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_album_disc_count(sacd_t* ctx, uint16_t* num_disc);

/**
 * @brief Gets the sequence number of this disc within the album.
 *
 * For multi-disc albums, indicates which disc this is (1, 2, 3, etc.).
 *
 * @param[in]  ctx               Pointer to the sacd_t context
 * @param[out] disc_sequence_num Pointer to receive the disc sequence number
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_disc_sequence_num(sacd_t* ctx, uint16_t* disc_sequence_num);

/**
 * @brief Gets the album catalog number.
 *
 * @param[in]  ctx                  Pointer to the sacd_t context
 * @param[out] album_catalog_num  Pointer to receive the catalog number string
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 *
 * @note The returned pointer is valid until the reader is closed.
 */
SACD_API int sacd_get_album_catalog_num(sacd_t* ctx, const char** album_catalog_num);

/**
 * @brief Checks if the disc is a hybrid SACD (contains both CD and SACD layers).
 *
 * @param[in]  ctx    Pointer to the sacd_t context
 * @param[out] hybrid Pointer to receive hybrid status (true if hybrid)
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_disc_is_hybrid(sacd_t* ctx, bool* hybrid);

/**
 * @brief Gets the disc manufacturer information.
 *
 * @param[in]  ctx          Pointer to the sacd_t context
 * @param[out] manufacturer_info Pointer to receive the manufacturer info string
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 *
 * @note The returned pointer is valid until the reader is closed.
 */
SACD_API int sacd_get_disc_manufacturer_info(sacd_t* ctx, const char** manufacturer_info);

/**
 * @brief Gets the disc catalog number.
 *
 * @param[in]  ctx                 Pointer to the sacd_t context
 * @param[out] disc_catalog_num  Pointer to receive the catalog number string
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 *
 * @note The returned pointer is valid until the reader is closed.
 */
SACD_API int sacd_get_disc_catalog_num(sacd_t* ctx, const char** disc_catalog_num);

/**
 * @brief Gets an album genre classification.
 *
 * Albums may have up to 4 genre classifications.
 *
 * @param[in]  ctx          Pointer to the sacd_t context
 * @param[in]  genre_nr     Genre number to query (1-4)
 * @param[out] genre_table  Pointer to receive genre table ID (0=not used, 1=general, 2=Japanese)
 * @param[out] genre_index  Pointer to receive index within the genre table
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: genre_nr not in range 1-4
 */
SACD_API int sacd_get_album_genre(sacd_t* ctx, uint16_t genre_nr,
                                  uint8_t* genre_table, uint16_t* genre_index);

/**
 * @brief Gets a disc genre classification.
 *
 * Discs may have up to 4 genre classifications.
 *
 * @param[in]  ctx          Pointer to the sacd_t context
 * @param[in]  genre_nr     Genre number to query (1-4)
 * @param[out] genre_table  Pointer to receive genre table ID (0=not used, 1=general, 2=Japanese)
 * @param[out] genre_index  Pointer to receive index within the genre table
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: genre_nr not in range 1-4
 */
SACD_API int sacd_get_disc_genre(sacd_t* ctx, uint16_t genre_nr,
                                 uint8_t* genre_table, uint16_t* genre_index);

/**
 * @brief Gets the disc date (year, month, day).
 *
 * @param[in]  ctx   Pointer to the sacd_t context
 * @param[out] year  Pointer to receive the year
 * @param[out] month Pointer to receive the month (1-12)
 * @param[out] day   Pointer to receive the day (1-31)
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_disc_date(sacd_t* ctx, uint16_t* year,
                                uint8_t* month, uint8_t* day);

/* ========================================================================
 * Disc-Level Text Information (from Master TOC)
 * ======================================================================== */

/**
 * @brief Gets the number of text channels (languages) in the Master TOC.
 *
 * @param[in]  ctx               Pointer to the sacd_t context
 * @param[out] num_text_channels Pointer to receive the text channel count (0-8)
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_master_text_channel_count(sacd_t* ctx, uint8_t* num_text_channels);

/**
 * @brief Gets language and character set information for a Master TOC text channel.
 *
 * @param[in]  ctx                  Pointer to the sacd_t context
 * @param[in]  text_channel_nr      Text channel number (1-based, 1 to text_channel_count)
 * @param[out] language_code        Pointer to receive 2-character ISO 639 language code (not null-terminated)
 * @param[out] character_set_code   Pointer to receive character set code
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: text_channel_nr out of range
 */
SACD_API int sacd_get_master_text_channel_info(sacd_t* ctx, uint8_t text_channel_nr,
                                              const char** language_code, uint8_t* character_set_code);

/**
 * @brief Gets album-level text metadata.
 *
 * @param[in]  ctx             Pointer to the sacd_t context
 * @param[in]  text_channel_nr Text channel (language) number (1-based)
 * @param[in]  text_type       Type of text to retrieve (ALBUM_TEXT_TYPE_*)
 * @param[out] album_text    Pointer to receive the UTF-8 text string
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: text_channel_nr out of range
 *
 * @note The returned pointer is valid until the reader is closed.
 */
SACD_API int sacd_get_album_text(sacd_t* ctx, uint8_t text_channel_nr,
                                 album_text_type_t text_type, const char** album_text);

/**
 * @brief Gets disc-level text metadata.
 *
 * @param[in]  ctx             Pointer to the sacd_t context
 * @param[in]  text_channel_nr Text channel (language) number (1-based)
 * @param[in]  text_type       Type of text to retrieve (ALBUM_TEXT_TYPE_*)
 * @param[out] disc_text     Pointer to receive the UTF-8 text string
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: text_channel_nr out of range
 *
 * @note The returned pointer is valid until the reader is closed.
 */
SACD_API int sacd_get_disc_text(sacd_t* ctx, uint8_t text_channel_nr,
                                album_text_type_t text_type, const char** disc_text);

/* ========================================================================
 * Area-Level Information (from currently selected Area TOC)
 * ======================================================================== */

/**
 * @brief Gets the SACD specification version of the currently selected area.
 *
 * @param[in]  ctx   Pointer to the sacd_t context
 * @param[out] major Pointer to receive major version number (typically 2)
 * @param[out] minor Pointer to receive minor version number (typically 0)
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_area_spec_version(sacd_t* ctx, uint8_t *major, uint8_t *minor);

/**
 * @brief Gets the sample frequency in Hz.
 *
 * @param[in]  ctx              Pointer to the sacd_t context
 * @param[out] sample_frequency Pointer to receive the sample frequency (typically 2822400 Hz)
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_area_sample_frequency(sacd_t* ctx, uint32_t* sample_frequency);

/**
 * @brief Gets the sample frequency code.
 *
 * @param[in]  ctx                   Pointer to the sacd_t context
 * @param[out] sample_frequency_code Pointer to receive the frequency code (4 = 64*44100 Hz)
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_area_sample_frequency_code(sacd_t* ctx, uint8_t* sample_frequency_code);

/**
 * @brief Gets the frame format code.
 *
 * @param[in]  ctx          Pointer to the sacd_t context
 * @param[out] frame_format Pointer to receive the format (0=DST, 2=DSD 3-in-14, 3=DSD 3-in-16)
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_area_frame_format_code(sacd_t* ctx, uint8_t* frame_format);

/**
 * @brief Gets the maximum byte rate of multiplexed frames.
 *
 * @param[in]  ctx           Pointer to the sacd_t context
 * @param[out] max_byte_rate Pointer to receive the maximum byte rate (bytes per second)
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_area_max_byte_rate(sacd_t* ctx, uint32_t* max_byte_rate);

/**
 * @brief Gets loudspeaker configuration information.
 *
 * @param[in]  ctx               Pointer to the sacd_t context
 * @param[out] loudspeaker_config Pointer to receive loudspeaker config (0=2Ch, 3=5Ch ITU-R, 4=5.1Ch)
 * @param[out] usage_ch4          Pointer to receive usage of audio channel 4 (0=LFE for 6-channel)
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_area_loudspeaker_config(sacd_t* ctx, uint8_t* loudspeaker_config,
                                         uint8_t* usage_ch4);

/**
 * @brief Gets area-wide mute flags (which channels may be silent).
 *
 * @param[in]  ctx             Pointer to the sacd_t context
 * @param[out] area_mute_flags Pointer to receive the mute flags bitfield
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_area_mute_flags(sacd_t* ctx, uint8_t* area_mute_flags);

/**
 * @brief Gets the maximum number of available channels per track.
 *
 * @param[in]  ctx                   Pointer to the sacd_t context
 * @param[out] max_available_channels Pointer to receive max channels (0 means equals channel_count)
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_area_max_available_channels(sacd_t* ctx, uint8_t* max_available_channels);

/**
 * @brief Gets the area track attribute (copy management).
 *
 * @param[in]  ctx                  Pointer to the sacd_t context
 * @param[out] area_track_attribute Pointer to receive the track attribute flags
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_area_track_attribute(sacd_t* ctx, uint8_t* area_track_attribute);

/**
 * @brief Gets the total playing time of the area.
 *
 * @param[in]  ctx                    Pointer to the sacd_t context
 * @param[out] total_area_play_time   Pointer to receive total time in frames (75 frames/sec)
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_total_area_play_time(sacd_t* ctx, uint32_t* total_area_play_time);

/**
 * @brief Gets the frame type/format.
 *
 * @param[in]  ctx        Pointer to the sacd_t context
 * @param[out] frame_type Pointer to receive the frame format type
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_area_frame_format_enum(sacd_t* ctx, frame_format_t* frame_type);

/**
 * @brief Gets the number of audio channels in the area.
 *
 * @param[in]  ctx           Pointer to the sacd_t context
 * @param[out] channel_count Pointer to receive the channel count (2, 5, or 6)
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_area_channel_count(sacd_t* ctx, uint16_t* channel_count);

/**
 * @brief Gets the track number offset for display.
 *
 * The display track number = physical track number + track offset.
 * Used for multi-disc albums.
 *
 * @param[in]  ctx          Pointer to the sacd_t context
 * @param[out] track_offset Pointer to receive the track offset
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_area_track_offset(sacd_t* ctx, uint8_t* track_offset);

/* ========================================================================
 * Area-Level Text Information (from currently selected Area TOC)
 * ======================================================================== */

/**
 * @brief Gets the number of text channels (languages) in the current area.
 *
 * @param[in]  ctx               Pointer to the sacd_t context
 * @param[out] num_text_channels Pointer to receive the text channel count (0-8)
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_area_text_channel_count(sacd_t* ctx, uint8_t* num_text_channels);

/**
 * @brief Gets language and character set information for an Area TOC text channel.
 *
 * @param[in]  ctx                  Pointer to the sacd_t context
 * @param[in]  text_channel_nr      Text channel number (1-based, 1 to text_channel_count)
 * @param[out] language_code        Pointer to receive 2-character ISO 639 language code (not null-terminated)
 * @param[out] character_set_code   Pointer to receive character set code
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: text_channel_nr out of range
 */
SACD_API int sacd_get_area_text_channel_info(sacd_t* ctx, uint8_t text_channel_nr,
                                           const char** language_code, uint8_t* character_set_code);

/**
 * @brief Gets area-level text metadata.
 *
 * @param[in]  ctx             Pointer to the sacd_t context
 * @param[in]  text_channel_nr Text channel (language) number (1-based)
 * @param[in]  text_type       Type of text to retrieve (AREA_TEXT_TYPE_*)
 * @param[out] area_text     Pointer to receive the UTF-8 text string
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: text_channel_nr out of range
 *
 * @note The returned pointer is valid until the reader is closed.
 */
SACD_API int sacd_get_area_text(sacd_t* ctx, uint8_t text_channel_nr,
                                area_text_type_t text_type, const char** area_text);

/* ========================================================================
 * Track Information (from currently selected Area TOC)
 * ======================================================================== */

/**
 * @brief Gets the number of tracks in the current area.
 *
 * @param[in]  ctx        Pointer to the sacd_t context
 * @param[out] num_tracks Pointer to receive the track count (1-255)
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_track_count(sacd_t* ctx, uint8_t* num_tracks);

/**
 * @brief Gets the number of indices within a track.
 *
 * @param[in]  ctx          Pointer to the sacd_t context
 * @param[in]  track_num    Track number (1-based)
 * @param[out] num_indexes  Pointer to receive the index count
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: track_num out of range
 */
SACD_API int sacd_get_track_index_count(sacd_t* ctx, uint8_t track_num,
                                       uint8_t* num_indexes);

/**
 * @brief Gets the ISRC (International Standard Recording Code) for a track.
 *
 * The ISRC is a 12-character code defined by ISO 3901, composed of:
 * - Country code (2 chars)
 * - Owner code (3 chars)
 * - Recording year (2 chars)
 * - Designation code (5 chars)
 *
 * @param[in]  ctx       Pointer to the sacd_t context
 * @param[in]  track_num Track number (1-based)
 * @param[out] isrc      Pointer to area_isrc_t structure to receive the ISRC data
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: track_num out of range or NULL output pointer
 */
SACD_API int sacd_get_track_isrc_num(sacd_t* ctx, uint8_t track_num,
                                    area_isrc_t* isrc);

/**
 * @brief Gets the track mode flags.
 *
 * @param[in]  ctx        Pointer to the sacd_t context
 * @param[in]  track_num  Track number (1-based)
 * @param[out] track_mode Pointer to receive the track mode
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: track_num out of range
 */
SACD_API int sacd_get_track_mode(sacd_t* ctx, uint8_t track_num,
                                 uint8_t* track_mode);

/**
 * @brief Gets all track mute flags.
 *
 * @param[in]  ctx             Pointer to the sacd_t context
 * @param[in]  track_num       Track number (1-based)
 * @param[out] track_flag_tmf1 Pointer to receive TMF1 (true if channel 1 muted)
 * @param[out] track_flag_tmf2 Pointer to receive TMF2 (true if channel 2 muted)
 * @param[out] track_flag_tmf3 Pointer to receive TMF3 (true if channel 3 muted)
 * @param[out] track_flag_tmf4 Pointer to receive TMF4 (true if channel 4 muted)
 * @param[out] track_flag_ilp  Pointer to receive ILP (true if index list present)
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: track_num out of range
 */
SACD_API int sacd_get_track_flags(sacd_t *ctx, uint8_t track_num,
                                   bool *track_flag_tmf1,
                                   bool *track_flag_tmf2,
                                   bool *track_flag_tmf3,
                                   bool *track_flag_tmf4,
                                   bool *track_flag_ilp);

/**
 * @brief Gets the genre classification for a track.
 *
 * @param[in]  ctx          Pointer to the sacd_t context
 * @param[in]  track_num    Track number (1-based)
 * @param[out] genre_table  Pointer to receive genre table ID (0=not used, 1=general, 2=Japanese)
 * @param[out] genre_index  Pointer to receive index within the genre table
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: track_num out of range
 */
SACD_API int sacd_get_track_genre(sacd_t* ctx, uint8_t track_num,
                                  uint8_t* genre_table, uint16_t* genre_index);

/**
 * @brief Gets track-specific text metadata.
 *
 * @param[in]  ctx             Pointer to the sacd_t context
 * @param[in]  track_num       Track number (1-based)
 * @param[in]  text_channel_nr Text channel (language) number (1-based)
 * @param[in]  text_item       Type of text to retrieve (TRACK_TYPE_*)
 * @param[out] track_text    Pointer to receive the UTF-8 text string
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: track_num out of range
 *         - SACD_ITEM_NOT_AVAILABLE: Text item not available for this track
 *
 * @note The returned pointer is valid until the reader is closed.
 */
SACD_API int sacd_get_track_text(sacd_t* ctx, uint8_t track_num,
                                 uint8_t text_channel_nr, track_type_t text_item,
                                 const char** track_text);

/**
 * @brief Gets the sector range for a track.
 *
 * @param[in]  ctx              Pointer to the sacd_t context
 * @param[in]  track_num        Track number (1-based)
 * @param[out] start_sector_nr  Pointer to receive the first sector LSN
 * @param[out] num_sectors      Pointer to receive the number of sectors
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: track_num out of range
 */
SACD_API int sacd_get_track_sectors(sacd_t* ctx, uint8_t track_num,
                                    uint32_t* start_sector_nr, uint32_t* num_sectors);

/**
 * @brief Gets the sector range for a track area.
 *
 * @param[in]  ctx               Pointer to the sacd_t context
 * @param[in]  area_type         Area type (TWO_CHANNEL or MULTI_CHANNEL)
 * @param[out] track_area_start  Pointer to receive the first sector LSN
 * @param[out] track_area_length Pointer to receive the area length in sectors
 *
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
SACD_API int sacd_get_track_area_sector_range(sacd_t* ctx, channel_t area_type,
                                         uint32_t* track_area_start,
                                         uint16_t* track_area_length);

/**
 * @brief Gets the track length in frames.
 *
 * @param[in]  ctx                Pointer to the sacd_t context
 * @param[in]  track_num          Track number (1-based)
 * @param[out] track_frame_length Pointer to receive the length in frames (75 frames/sec)
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: track_num out of range
 */
SACD_API int sacd_get_track_frame_length(sacd_t* ctx, uint8_t track_num,
                                        uint32_t* track_frame_length);

/**
 * @brief Gets the start frame of an index within a track.
 *
 * @param[in]  ctx         Pointer to the sacd_t context
 * @param[in]  track_num   Track number (1-based)
 * @param[in]  index_num   Index number (1-based)
 * @param[out] index_start Pointer to receive the start frame number
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: track_num or index_num out of range
 */
SACD_API int sacd_get_track_index_start(sacd_t* ctx, uint8_t track_num,
                                  uint8_t index_num, uint32_t* index_start);

/**
 * @brief Gets the end frame of an index within a track.
 *
 * @param[in]  ctx       Pointer to the sacd_t context
 * @param[in]  track_num Track number (1-based)
 * @param[in]  index_num Index number (1-based)
 * @param[out] index_end Pointer to receive the end frame number (inclusive)
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: track_num or index_num out of range
 */
SACD_API int sacd_get_track_index_end(sacd_t* ctx, uint8_t track_num,
                                uint8_t index_num, uint32_t* index_end);

/**
 * @brief Gets the pre-gap (pause) length for a track.
 *
 * @param[in]  ctx         Pointer to the sacd_t context
 * @param[in]  track_num   Track number (1-based)
 * @param[out] track_pause Pointer to receive the pre-gap length in frames
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: track_num out of range
 */
SACD_API int sacd_get_track_pause(sacd_t* ctx, uint8_t track_num,
                                  uint32_t* track_pause);

/* ========================================================================
 * Helper Functions (Filename and Path Generation)
 * ======================================================================== */

/**
 * @brief Gets a human-readable speaker configuration string for the current area.
 *
 * Returns strings like "2.0 Stereo", "5.0 Surround", "5.1 Surround", or "Unknown".
 *
 * @param[in] ctx Pointer to the sacd_t context
 *
 * @return Static string describing the speaker configuration.
 *         Returns "Unknown" if ctx is NULL or area not selected.
 *
 * @note The returned pointer is to static string data; do not free.
 */
SACD_API const char *sacd_get_speaker_config_string(sacd_t *ctx);

/**
 * @brief Gets a human-readable frame format string for the current area.
 *
 * Returns strings like "DST", "DSD (3-in-14)", "DSD (3-in-16)", or "Unknown".
 *
 * @param[in] ctx Pointer to the sacd_t context
 *
 * @return Static string describing the frame format.
 *         Returns "Unknown" if ctx is NULL or area not selected.
 *
 * @note The returned pointer is to static string data; do not free.
 */
SACD_API const char *sacd_get_frame_format_string(sacd_t *ctx);

/**
 * @brief Generates a sanitized album directory name from disc metadata.
 *
 * Creates a directory name suitable for filesystem use based on album metadata.
 * Does not include any path prefix or disc subdirectory.
 *
 * @param[in] ctx          Pointer to the sacd_t context
 * @param[in] format       Path format option (title only, artist-title, or year-artist-title)
 * @param[in] text_channel Text channel number (1-based) for metadata lookup
 *
 * @return Newly allocated string with the album directory name, or NULL on error.
 *         Caller must free with sa_free().
 *
 * Example outputs:
 * - SACD_PATH_TITLE_ONLY: "Album Title"
 * - SACD_PATH_ARTIST_TITLE: "Artist Name - Album Title"
 * - SACD_PATH_YEAR_ARTIST_TITLE: "2023 - Artist Name - Album Title"
 */
SACD_API char *sacd_get_album_dir(sacd_t *ctx,
                                         sacd_path_format_t format,
                                         uint8_t text_channel);

/**
 * @brief Generates a full album path including disc subdirectory if needed.
 *
 * For multi-disc albums, appends a disc subdirectory (e.g., "Album/Disc 1").
 * For single-disc albums, returns just the album directory.
 *
 * @param[in] ctx          Pointer to the sacd_t context
 * @param[in] format       Path format option (title only, artist-title, or year-artist-title)
 * @param[in] text_channel Text channel number (1-based) for metadata lookup
 *
 * @return Newly allocated string with the full album path, or NULL on error.
 *         Caller must free with sa_free().
 *
 * Example outputs:
 * - Single disc: "Artist Name - Album Title"
 * - Multi-disc: "Artist Name - Album Title/Disc 2"
 */
SACD_API char *sacd_get_album_path(sacd_t *ctx,
                                          sacd_path_format_t format,
                                          uint8_t text_channel);

/**
 * @brief Generates a sanitized track filename from track metadata.
 *
 * Creates a filename suitable for filesystem use. Does not include file extension.
 *
 * This function uses:
 * - Track text from the Area TOC (title, performer)
 * - Disc/album artist from the Master TOC as fallback if no track performer
 *
 * @param[in] ctx          Pointer to the sacd_t context
 * @param[in] track_num    Track number (1-based)
 * @param[in] format       Track filename format option
 * @param[in] text_channel Text channel number (1-based) for metadata lookup
 *
 * @return Newly allocated string with the track filename, or NULL on error.
 *         Caller must free with sa_free().
 *
 * Example outputs:
 * - SACD_TRACK_NUM_TITLE: "01 - Track Title"
 * - SACD_TRACK_NUM_ARTIST_TITLE: "01 - Artist Name - Track Title"
 * - SACD_TRACK_NUM_ONLY: "01"
 *
 * @note Track numbers are zero-padded to 2 digits.
 * @note Characters invalid for filenames are replaced with underscores.
 */
SACD_API char *sacd_get_track_filename(sacd_t *ctx,
                                              uint8_t track_num,
                                              sacd_track_format_t format,
                                              uint8_t text_channel);

/* ========================================================================
 * Audio Data Retrieval
 * ======================================================================== */

/**
 * @brief Retrieves main audio data for one or more frames.
 *
 * Reads audio frames from the currently selected area. For DST-compressed audio,
 * each frame may have a different size. For plain DSD, frames are fixed at 9408 bytes.
 *
 * @param[in]     ctx            Pointer to the sacd_t context
 * @param[out]    data         Buffer to receive the audio data
 * @param[in]     frame_nr_start Starting frame number, or FRAME_START_USE_CURRENT for current position
 * @param[in,out] frame_count   Input: number of frames to read
 *                               Output: number of frames successfully read
 * @param[out]    frame_size     Array to receive size of each frame (required for DST, optional for DSD)
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: Invalid parameters or frame range
 *         - (Other error codes from audio data reading)
 *
 * @note For DST audio (frame_format=0), frame_size must not be NULL.
 * @note The function updates the current frame position when FRAME_START_USE_CURRENT is used.
 */
SACD_API int sacd_get_sound_data(sacd_t* ctx, uint8_t* data,
                                 uint32_t frame_nr_start, uint32_t* frame_count,
                                 uint16_t* frame_size);

/**
 * @brief Retrieves supplementary audio data for one or more frames.
 *
 * Reads supplementary data packets (if present) from the currently selected area.
 *
 * @param[in]     ctx            Pointer to the sacd_t context
 * @param[out]    data         Buffer to receive the supplementary data
 * @param[in]     frame_nr_start Starting frame number, or FRAME_START_USE_CURRENT for current position
 * @param[in,out] frame_count   Input: number of frames to read
 *                               Output: number of frames successfully read
 * @param[out]    frame_size     Array to receive size of each frame's supplementary data
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: Invalid parameters or frame range
 *         - (Other error codes from audio data reading)
 */
SACD_API int sacd_get_supplementary_data(sacd_t* ctx, uint8_t* data,
                                         uint32_t frame_nr_start, uint32_t* frame_count,
                                         uint16_t* frame_size);

/* ========================================================================
 * Advanced Functions
 * ======================================================================== */

/**
 * @brief Determines the sector location and span of a specific frame.
 *
 * This function is useful for seeking and understanding disc layout.
 *
 * @param[in]  ctx              Pointer to the sacd_t context
 * @param[in]  frame_nr         Frame number to query
 * @param[out] start_sector_nr  Pointer to receive the first sector LSN
 * @param[out] num_sectors      Pointer to receive the number of sectors for this frame
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_INVALID_ARGUMENT: frame_nr out of range
 */
SACD_API int sacd_get_frame_sector_range(sacd_t* ctx, uint32_t frame_nr,
                                     uint32_t* start_sector_nr, int* num_sectors);

/**
 * @brief Gets the total number of sectors on the disc.
 *
 * Returns the total number of 2048-byte sectors in the disc image file.
 * This is useful for raw ISO dumping operations.
 *
 * @param[in]  ctx           Pointer to the sacd_t context
 * @param[out] total_sectors Pointer to receive the total sector count
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_INVALID_ARGUMENT: NULL pointer argument
 *         - SACD_UNINITIALIZED: Reader not initialized
 */
SACD_API int sacd_get_total_sectors(sacd_t* ctx, uint32_t* total_sectors);

/**
 * @brief Reads raw sectors from the disc without audio processing.
 *
 * Reads sectors directly from the disc image without any SACD-specific
 * processing, decoding, or decryption. Useful for raw ISO dumping.
 *
 * @param[in]  ctx          Pointer to the sacd_t context
 * @param[in]  sector_pos   Starting sector number (0-based)
 * @param[in]  sector_count Number of sectors to read
 * @param[out] buffer       Buffer to receive the data (must be at least
 *                          sector_count * 2048 bytes)
 * @param[out] sectors_read Pointer to receive number of sectors actually read
 *
 * @return SACD_OK on success, or error code:
 *         - SACD_INVALID_ARGUMENT: NULL pointer argument
 *         - SACD_UNINITIALIZED: Reader not initialized
 *         - SACD_IO_ERROR: Read operation failed
 */
SACD_API int sacd_read_raw_sectors(sacd_t* ctx,
                                          uint32_t sector_pos,
                                          uint32_t sector_count,
                                          uint8_t* buffer,
                                          uint32_t* sectors_read);

/* ========================================================================
 * Internal Helper Functions (Private - Do Not Use Directly)
 * ======================================================================== */

/**
 * @brief Gets a pointer to the currently selected Area TOC (internal use only).
 *
 * Returns a pointer to either st_area_toc or mc_area_toc based on current_channel_type.
 *
 * @param[in] ctx Pointer to the sacd_t context
 *
 * @return Pointer to the selected Area TOC, or NULL if none selected
 *
 * @note This is an internal helper function. Use the public API instead.
 */
area_toc_t* sacd_get_selected_area_toc(sacd_t* ctx);

static inline uint32_t time_to_frame(time_sacd_t time)
{
    return (time.frames +
            ((time.seconds + (time.minutes * 60)) * SACD_FRAMES_PER_SEC));
}

static inline time_sacd_t frame_to_time(uint32_t frame_num)
{
    time_sacd_t time = {0};
    time.frames = (uint8_t)((frame_num) % SACD_FRAMES_PER_SEC);
    time.seconds = (uint8_t)((frame_num / SACD_FRAMES_PER_SEC) % 60);
    time.minutes = (uint8_t)((frame_num / SACD_FRAMES_PER_SEC) / 60);
    return time;
}

#ifdef __cplusplus
}
#endif

#endif /* LIBSACD_SACD_H */
