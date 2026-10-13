/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief Super Audio CD Area Table of Contents (Area TOC) Management
 * This module provides comprehensive access to SACD Area TOC structures, which contain
 * metadata and indexing information for a specific audio area (2-Channel Stereo or Multi Channel).
 * The Area TOC contains:
 * - Track information (start addresses, lengths, ISRC codes)
 * - Audio format specifications (DST/DSD, sample rate, channel configuration)
 * - Text metadata (area descriptions, track titles, etc.) in multiple languages
 * - Index points within tracks
 * - Access lists for efficient seeking in DST-coded audio
 * @see sacd_specification.h for the underlying SACD data structures
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

#ifndef LIBSACD_SACD_AREA_TOC_H
#define LIBSACD_SACD_AREA_TOC_H

#include <libsacd/sacd.h>

#include "sacd_frame_reader.h"
#include "sacd_dst_reader.h"
#include "sacd_dsd_reader.h"
#include "sacd_input.h"
#include "sacd_specification.h"

#include <libsautil/bswap.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief Return status codes for Area TOC operations
 *
 * These status codes are returned by various Area TOC functions to indicate
 * success or specific error conditions encountered during initialization,
 * parsing, or data access operations.
 */
typedef enum {

	SACD_AREA_TOC_OK = 0,
	/**< Operation completed successfully. */

	SACD_AREA_TOC_UNINITIALIZED,
	/**< Area TOC context is uninitialized. Call sacd_area_toc_init() and
	 * sacd_area_toc_read() before using other operations.
	 */

    SACD_AREA_TOC_IO_ERROR,
	/**< An I/O error occurred while reading disc sectors. */

	SACD_AREA_TOC_MEMORY_ALLOCATION_ERROR,
	/**< Memory allocation failed during initialization or parsing. */

    SACD_AREA_TOC_INVALID_ARGUMENT,
    /**< An invalid argument was provided (e.g., out-of-range track number,
     * invalid text channel number).
     */

    SACD_AREA_TOC_NO_DATA,
    /**< Incomplete or missing TOC data on disc. The Area TOC structure
     * was not fully readable or is shorter than expected.
     */

    SACD_AREA_TOC_INVALID_SIGNATURE,
    /**< Invalid signature found in TOC structures. One or more TOC
     * components (track list, access list, etc.) have incorrect magic
     * numbers, indicating corrupted or non-standard disc data.
     */

    SACD_AREA_TOC_CHANNEL_COUNT,
    /**< Invalid channel count for the specified area type. For example,
     * a 2-channel area must have exactly 2 channels, not 5 or 6.
     */

    SACD_AREA_TOC_FRAME_FORMAT,
    /**< Unsupported or invalid frame format. The frame format code must
     * be 0 (DST), 2 (DSD 3-in-14), or 3 (DSD 3-in-16).
     */

    SACD_AREA_TOC_END_OF_AUDIO_DATA
    /**< End of audio data reached during playback.
     */
} area_toc_error_t;

/**
 * @brief Represents a single text item for a track
 *
 * Contains a specific type of text metadata (e.g., title, performer, composer)
 * for a track in a particular text channel.
 */
typedef struct {
    uint8_t text_type;      /**< Text type identifier (track_type_t: TITLE, PERFORMER, etc.) */
    char* text;          /**< UTF-8 text string (null-terminated, converted from disc encoding) */
} area_toc_text_track_t;

/**
 * @brief Genre classification for a track
 *
 * References a genre table and index to identify the musical genre.
 * @see genre_code_t in sacd_specification.h
 */
typedef struct {
    uint8_t genre_table;    /**< Genre table identifier (0=not used, 1=general, 2=Japanese) */
    uint16_t index;         /**< Genre index within the selected table */
} area_toc_genre_t;

/**
 * @brief Complete metadata and indexing information for a single track
 *
 * Contains all track-specific information extracted from the Area TOC, including:
 * - Physical location (LSN addresses and lengths)
 * - Timing information (frame lengths and index points)
 * - Metadata (ISRC codes, genre, text descriptions)
 * - Track flags and mode information
 */
typedef struct {
    area_isrc_t isrc;                   /**< International Standard Recording Code (ISO 3901) */
    uint32_t track_length;              /**< Track length in frames (including pre-gap) */
    uint8_t track_mode;                 /**< Track mode flags (usage of Audio Channel 4) */
    uint8_t track_flags;                /**< Combined track flags (raw value) */
    bool track_flag_tmf1;               /**< Track Mute Flag 1 (channel 1 not available) */
    bool track_flag_tmf2;               /**< Track Mute Flag 2 (channel 2 not available) */
    bool track_flag_tmf3;               /**< Track Mute Flag 3 (channel 3 not available) */
    bool track_flag_tmf4;               /**< Track Mute Flag 4 (channel 4 not available) */
    bool track_flag_ilp;                /**< Index List Present flag */
    area_toc_genre_t genre;             /**< Genre classification */
    area_toc_text_track_t* track_text[MAX_TEXT_CHANNEL_COUNT];  /**< Text items per channel */
    uint8_t track_text_item_count[MAX_TEXT_CHANNEL_COUNT]; /**< RawSMusic: each channel owns its allocation count */
    uint32_t* index_start;              /**< Array of index start frames */
    uint8_t index_count;                /**< Number of indices (includes index 0 and 1) */
    uint32_t track_start_lsn;           /**< Logical Sector Number of track start */
    uint32_t track_sector_length;       /**< Track length in sectors */
} area_toc_track_info_t;

/**
 * @brief Frame access information for DST-coded audio areas
 *
 * Provides a lookup table for efficient seeking to specific time codes in
 * DST (Direct Stream Transfer) compressed audio. Not used for plain DSD audio.
 */
typedef struct {
    uint8_t step_size;       /**< Time interval between entries (in frames, multiple of 10) */
    uint16_t num_entries;    /**< Number of entries in the access list */
    uint32_t* frame_start;   /**< Array of LSN addresses for frame starts */
    uint16_t* access_margin; /**< Array of access margins for interpolation safety */
} area_toc_frame_info_t;

/**
 * @brief Area-level text information
 *
 * Contains descriptive text for the entire audio area (not specific to any track).
 * Text is available in multiple channels (languages) and types.
 */
typedef struct {
    char* text[MAX_TEXT_CHANNEL_COUNT][MAX_AREA_TEXT_TYPE_COUNT];  /**< Area text strings [channel][type] */
} area_toc_info_t;

/**
 * @brief Main Area TOC context structure
 *
 * This structure maintains the complete state for accessing an SACD audio area
 * (either 2-Channel Stereo or Multi Channel). It contains:
 * - Parsed Area TOC metadata (audio format, channels, timing)
 * - Track information array
 * - Text channel definitions
 * - Current playback position state
 * - Audio data reader (DST or DSD)
 * - Frame access list for seeking
 *
 * @note This is an opaque structure. Use the provided API functions to access its members.
 */
struct area_toc_s {
    /* === Specification and Text === */
    sacd_version_t version;             /**< SACD specification version (major.minor) */
    uint32_t text_channel_count;        /**< Number of text channels (languages) available */
    uint32_t cur_text_channel;          /**< Currently selected text channel (0-based) */
    chan_info_t channel_info[MAX_TEXT_CHANNEL_COUNT];  /**< Language and character set per channel */

    /* === Audio Format === */
    uint32_t max_byte_rate;             /**< Maximum byte rate of multiplexed frames (bytes/sec) */
    uint8_t fs_code;                    /**< Sampling frequency code (4 = 64*44100 Hz = 2.8224 MHz) */
    frame_format_t frame_format;        /**< Frame format (0=DST, 2=DSD 3-in-14, 3=DSD 3-in-16) */
    uint16_t channel_count;             /**< Number of audio channels (2, 5, or 6) */
    uint8_t loudspeaker_config;         /**< Loudspeaker configuration (0=2Ch stereo, 3=5Ch ITU-R, 4=5.1Ch) */
    uint8_t extra_settings;             /**< Usage of audio channel 4 (for 6-channel areas) */
    uint8_t max_available_channels;     /**< Maximum channels available per track */
    uint8_t mute_flags;                 /**< Area-wide mute flags (which channels may be silent) */
    uint8_t track_attribute;            /**< Copy management and track attributes */

    /* === Area Boundaries === */
    uint32_t track_area_start;          /**< LSN of first sector in track area */
    uint32_t track_area_end;            /**< LSN of last sector in track area */
    uint32_t total_area_play_time;      /**< Total playing time in frames */

    /* === Track Information === */
    uint8_t track_offset;               /**< Track number offset for display */
    uint8_t track_count;                /**< Number of tracks in this area */
    area_toc_track_info_t* track_info;  /**< Array of track information structures */

    /* === Metadata === */
    area_toc_info_t area_info;          /**< Area-level text information */
    area_toc_frame_info_t frame_info;   /**< Frame access list (for DST seeking) */

    /* === Current Playback State === */
    uint32_t cur_frame_num_data;        /**< Current frame number for audio data */
    uint8_t cur_track_num;              /**< Current track number (1-based) */
    uint8_t cur_index_num;              /**< Current index number (1-based) */
    uint32_t cur_frame_num_text;        /**< Current frame number for text */
    uint32_t frame_start;               /**< Start frame for current operation */
    uint32_t frame_stop;                /**< Stop frame for current operation */

    /* === Audio Data Reader === */
    sacd_frame_reader_t *frame_reader;  /**< Audio data reader (DST or DSD implementation) */

    /* === Disc Access === */
    sacd_input_t* input;  /**< Input device for disc sector access */

    /* === Initialization State === */
    bool initialized;     /**< True if context has been successfully initialized and TOC has been read */
};

/* ========================================================================
 * Lifecycle Management
 * ======================================================================== */

/**
 * @brief Initialize an Area TOC context structure
 *
 * Initializes all fields to default/empty values. Must be called before
 * using any other Area TOC functions.
 *
 * @param ctx Pointer to the Area TOC context to initialize
 *
 * @note After calling this, you must call sacd_area_toc_read() to load actual TOC data
 */
void sacd_area_toc_init(area_toc_t* ctx);

/**
 * @brief Destroy an Area TOC context and free all resources
 *
 * Releases all dynamically allocated memory including track info, text strings,
 * index lists, and audio data readers. Calls sacd_area_toc_close() internally.
 *
 * @param ctx Pointer to the Area TOC context to destroy
 */
void sacd_area_toc_destroy(area_toc_t* ctx);

/**
 * @brief Initialize Area TOC by reading and parsing disc data
 *
 * Reads the specified Area TOC from the disc, parses all structures (track lists,
 * ISRC/genre lists, access lists, track text, index lists), and initializes the
 * appropriate audio data reader (DST or DSD) based on the frame format.
 *
 * @param ctx                Pointer to Area TOC context (must be created first)
 * @param toc_copy_index     Which TOC copy to read (1 or 2, use 1 for primary)
 * @param toc_area1_start    LSN of Area TOC copy 1
 * @param toc_area2_start    LSN of Area TOC copy 2
 * @param toc_area_length    Length of Area TOC in sectors
 * @param area_type          Type of area (TWO_CHANNEL or MULTI_CHANNEL)
 * @param input              Input device for disc sector access
 *
 * @return SACD_AREA_TOC_OK on success, or error code:
 *         - SACD_AREA_TOC_MEMORY_ALLOCATION_ERROR: Memory allocation failed
 *         - SACD_AREA_TOC_IO_ERROR: Failed to read sectors
 *         - SACD_AREA_TOC_NO_DATA: Incomplete TOC data
 *         - SACD_AREA_TOC_INVALID_SIGNATURE: Invalid signatures in TOC structures
 *         - SACD_AREA_TOC_CHANNEL_COUNT: Invalid channel count for area type
 *         - SACD_AREA_TOC_FRAME_FORMAT: Unsupported frame format
 */
int sacd_area_toc_read(area_toc_t* ctx, uint32_t toc_copy_index, uint32_t toc_area1_start,
                        uint32_t toc_area2_start, uint16_t toc_area_length,
                        channel_t area_type, sacd_input_t* input);

/**
 * @brief Close and cleanup Area TOC resources
 *
 * Frees all dynamically allocated memory (track info, text, indices, audio readers)
 * and resets the context to empty state. Does not free the context structure itself.
 *
 * @param ctx Pointer to the Area TOC context to close
 */
void sacd_area_toc_close(area_toc_t* ctx);

/* ========================================================================
 * Specification and Text Channel Queries
 * ======================================================================== */

/**
 * @brief Get the SACD specification version
 * @param ctx Pointer to Area TOC context
 * @return Version structure (should be 2.0 for standard SACD discs)
 */
sacd_version_t sacd_area_toc_get_version(area_toc_t *ctx);

/**
 * @brief Get the number of text channels (languages) available
 * @param ctx Pointer to Area TOC context
 * @return Number of text channels (0-8)
 */
uint8_t sacd_area_toc_get_text_channel_count(area_toc_t *ctx);

/**
 * @brief Get language and character set information for a text channel
 *
 * @param ctx                  Pointer to Area TOC context
 * @param channel_number       Text channel number (1-based, 1 to text_channel_count)
 * @param out_language_code    Output: Pointer to 2-character ISO 639 language code (not null-terminated)
 * @param out_charset_code     Output: Character set code (1=ISO646, 2=ISO8859-1, 3=RIS506, etc.)
 *
 * @return SACD_AREA_TOC_OK on success, SACD_AREA_TOC_INVALID_ARGUMENT if channel number is out of range
 */
int sacd_area_toc_get_text_channel_info(area_toc_t *ctx, uint8_t channel_number,
                                      char **out_language_code, uint8_t *out_charset_code);

/* ========================================================================
 * Current Position Management
 * ======================================================================== */

/**
 * @brief Get the current track number
 * @param ctx Pointer to Area TOC context
 * @return Current track number (1-based)
 */
uint8_t sacd_area_toc_get_current_track_num(area_toc_t* ctx);

/**
 * @brief Get the current index number within the current track
 * @param ctx Pointer to Area TOC context
 * @return Current index number (1-based)
 */
uint8_t sacd_area_toc_get_current_index_num(area_toc_t* ctx);

/**
 * @brief Get the current frame number for audio data
 * @param ctx Pointer to Area TOC context
 * @return Current frame number (75 frames per second)
 */
uint32_t sacd_area_toc_get_current_frame_num(area_toc_t* ctx);

/**
 * @brief Set the current frame position for playback
 * @param ctx        Pointer to Area TOC context
 * @param frame_num  Frame number to seek to
 * @return true on success
 */
bool sacd_area_toc_set_current_frame_num(area_toc_t* ctx, uint32_t frame_num);

/**
 * @brief Set the current track number
 * @param ctx        Pointer to Area TOC context
 * @param track_num  Track number to set (1-based)
 * @return true on success
 */
bool sacd_area_toc_set_current_track_num(area_toc_t* ctx, uint8_t track_num);

/**
 * @brief Set the current index number within the track
 * @param ctx        Pointer to Area TOC context
 * @param index_num  Index number to set (1-based)
 * @return true on success
 */
bool sacd_area_toc_set_current_index_num(area_toc_t* ctx, uint8_t index_num);

/* ========================================================================
 * Frame and Sector Operations
 * ======================================================================== */

/**
 * @brief Get the Logical Sector Number (LSN) for a specific frame
 *
 * Uses the Access List (for DST-coded audio) to determine the disc sector
 * address containing the specified frame number. For DST audio, this performs
 * a lookup in the frame_info access table. For plain DSD, returns 0.
 *
 * @param ctx        Pointer to Area TOC context
 * @param frame_num  Frame number to look up (0 to total_area_play_time-1)
 *
 * @return LSN of the sector containing the frame, or 0 if not available
 */
uint32_t sacd_area_toc_get_frame_lsn(area_toc_t* ctx, uint32_t frame_num);

/**
 * @brief Get the frame format for the audio area
 *
 * @param ctx Pointer to Area TOC context
 * @return Frame format type:
 *         - FRAME_FORMAT_DST (0): DST compressed
 *         - FRAME_FORMAT_DSD_3_IN_14 (2): Fixed DSD 2Ch/14 sectors
 *         - FRAME_FORMAT_DSD_3_IN_16 (3): Fixed DSD 2Ch/16 sectors
 */
frame_format_t sacd_area_toc_get_frame_format_enum(area_toc_t* ctx);

/**
 * @brief Read audio data for a specific frame
 *
 * Retrieves decoded audio data for the specified frame. Automatically advances
 * the current frame position if FRAME_START_USE_CURRENT is used.
 *
 * @param ctx        Pointer to Area TOC context
 * @param out_data   Output buffer for audio data
 * @param length     Input/Output: Buffer size on input, actual data length on output
 * @param frame_num  Frame number to read, or FRAME_START_USE_CURRENT for current position
 * @param data_type  Type of data to extract (DATA_TYPE_AUDIO, DATA_TYPE_SUPPLEMENTARY, etc.)
 *
 * @return SACD_AREA_TOC_OK on success, SACD_AREA_TOC_END_OF_AUDIO_DATA at end of area, or error code
 */
int sacd_area_toc_get_audio_data(area_toc_t* ctx, uint8_t* out_data, uint32_t* length,
                             uint32_t frame_num, audio_packet_data_type_t data_type);

/**
 * @brief Get sector information for a specific frame
 *
 * Determines which disc sectors contain the audio data for the specified frame.
 *
 * @param ctx              Pointer to Area TOC context
 * @param frame            Frame number to query
 * @param out_start_sector Output: LSN of first sector for this frame
 * @param out_sector_count Output: Number of sectors for this frame
 *
 * @return SACD_AREA_TOC_OK on success, or error code
 */
int sacd_area_toc_get_frame_sector_range(area_toc_t* ctx, uint32_t frame,
                                 uint32_t* out_start_sector, int* out_sector_count);

/* ========================================================================
 * Area Properties
 * ======================================================================== */

/**
 * @brief Get total playing time of the audio area
 * @param ctx Pointer to Area TOC context
 * @return Total playing time in frames (75 frames per second)
 */
uint32_t sacd_area_toc_get_total_play_time(area_toc_t* ctx);

/**
 * @brief Get the sample frequency in Hz
 * @param ctx Pointer to Area TOC context
 * @return Sample frequency (typically 2822400 Hz = 64 * 44100 Hz), or 0 if invalid
 */
uint32_t sacd_area_toc_get_sample_frequency(area_toc_t* ctx);

/**
 * @brief Get the sample frequency code
 * @param ctx Pointer to Area TOC context
 * @return Sample frequency code (4 = 64*44100 Hz)
 */
uint8_t sacd_area_toc_get_sample_frequency_code(area_toc_t* ctx);

/**
 * @brief Get the frame format code
 * @param ctx Pointer to Area TOC context
 * @return Frame format (0=DST, 2=DSD 3-in-14, 3=DSD 3-in-16)
 */
uint8_t sacd_area_toc_get_frame_format(area_toc_t* ctx);

/**
 * @brief Get maximum byte rate of multiplexed frames
 * @param ctx Pointer to Area TOC context
 * @return Maximum byte rate in bytes per second
 */
uint32_t sacd_area_toc_get_max_byte_rate(area_toc_t* ctx);

/**
 * @brief Get loudspeaker configuration information
 *
 * @param ctx                    Pointer to Area TOC context
 * @param out_loudspeaker_config Output: Loudspeaker configuration (0=2Ch stereo, 3=5Ch ITU-R, 4=5.1Ch)
 * @param out_ch4_usage          Output: Usage of audio channel 4 for 6-channel areas (0=LFE)
 */
void sacd_area_toc_get_loudspeaker_config(area_toc_t* ctx, uint8_t* out_loudspeaker_config,
                                  uint8_t* out_ch4_usage);

/**
 * @brief Get area-wide mute flags
 *
 * Indicates which audio channels may be silent (muted) across all tracks.
 *
 * @param ctx Pointer to Area TOC context
 * @return Mute flags bitfield
 */
uint8_t sacd_area_toc_get_mute_flags(area_toc_t* ctx);

/**
 * @brief Get maximum number of available channels per track
 * @param ctx Pointer to Area TOC context
 * @return Maximum available channels (0 means equals channel_count)
 */
uint8_t sacd_area_toc_get_max_available_channels(area_toc_t* ctx);

/**
 * @brief Get area track attribute (copy management)
 * @param ctx Pointer to Area TOC context
 * @return Track attribute flags
 */
uint8_t sacd_area_toc_get_copy_protection_flags(area_toc_t* ctx);

/**
 * @brief Get the number of audio channels in the area
 * @param ctx Pointer to Area TOC context
 * @return Number of channels (2, 5, or 6)
 */
uint16_t sacd_area_toc_get_channel_count(area_toc_t* ctx);

/**
 * @brief Get the track number offset for display
 *
 * The display track number = physical track number + track offset.
 * Used when an album spans multiple discs.
 *
 * @param ctx Pointer to Area TOC context
 * @return Track offset (0 for first disc in album)
 */
uint8_t sacd_area_toc_get_track_offset(area_toc_t* ctx);

/* ========================================================================
 * Track Information
 * ======================================================================== */

/**
 * @brief Get the number of tracks in the audio area
 * @param ctx Pointer to Area TOC context
 * @return Number of tracks (1 to 255)
 */
uint8_t sacd_area_toc_get_track_count(area_toc_t* ctx);

/**
 * @brief Get the number of indices within a track
 *
 * Returns the count of index points, not including the pre-gap (index 0).
 * Most tracks have only index 1 (the main track).
 *
 * @param ctx        Pointer to Area TOC context
 * @param track_num  Track number (1-based)
 * @return Number of indices (typically 1, range 1 to 255)
 */
uint8_t sacd_area_toc_get_track_index_count(area_toc_t* ctx, uint8_t track_num);

/**
 * @brief Get the ISRC (International Standard Recording Code) for a track
 *
 * @param ctx        Pointer to Area TOC context
 * @param track_num  Track number (1-based)
 * @return ISRC structure (may be all zeros if not available)
 * @see area_isrc_t for ISRC structure format (country code, owner code, year, designation)
 */
area_isrc_t sacd_area_toc_get_track_isrc_num(area_toc_t* ctx, uint8_t track_num);

/**
 * @brief Get the track mode flags
 *
 * @param ctx        Pointer to Area TOC context
 * @param track_num  Track number (1-based)
 * @return Track mode (indicates usage of audio channel 4 for 6-channel areas)
 */
uint8_t sacd_area_toc_get_track_mode(area_toc_t* ctx, uint8_t track_num);

/**
 * @brief Get Track Mute Flag 1 (channel 1 availability)
 * @param ctx        Pointer to Area TOC context
 * @param track_num  Track number (1-based)
 * @return true if channel 1 is NOT available (muted), false if available
 */
bool sacd_area_toc_get_track_flag_mute1(area_toc_t* ctx, uint8_t track_num);

/**
 * @brief Get Track Mute Flag 2 (channel 2 availability)
 * @param ctx        Pointer to Area TOC context
 * @param track_num  Track number (1-based)
 * @return true if channel 2 is NOT available (muted), false if available
 */
bool sacd_area_toc_get_track_flag_mute2(area_toc_t* ctx, uint8_t track_num);

/**
 * @brief Get Track Mute Flag 3 (channel 3 availability)
 * @param ctx        Pointer to Area TOC context
 * @param track_num  Track number (1-based)
 * @return true if channel 3 is NOT available (muted), false if available
 */
bool sacd_area_toc_get_track_flag_mute3(area_toc_t* ctx, uint8_t track_num);

/**
 * @brief Get Track Mute Flag 4 (channel 4 availability)
 * @param ctx        Pointer to Area TOC context
 * @param track_num  Track number (1-based)
 * @return true if channel 4 is NOT available (muted), false if available
 */
bool sacd_area_toc_get_track_flag_mute4(area_toc_t* ctx, uint8_t track_num);

/**
 * @brief Get Index List Present flag
 * @param ctx        Pointer to Area TOC context
 * @param track_num  Track number (1-based)
 * @return true if track has multiple indices (index list present)
 */
bool sacd_area_toc_get_track_flag_ilp(area_toc_t* ctx, uint8_t track_num);

/**
 * @brief Get the genre classification for a track
 *
 * @param ctx              Pointer to Area TOC context
 * @param track_num        Track number (1-based)
 * @param out_genre_table  Output: Genre table ID (0=not used, 1=general, 2=Japanese)
 * @param out_genre_index  Output: Index within the genre table
 */
void sacd_area_toc_get_track_genre(area_toc_t* ctx, uint8_t track_num, uint8_t* out_genre_table,
                           uint16_t* out_genre_index);

/**
 * @brief Get the sector range for a track
 *
 * @param ctx              Pointer to Area TOC context
 * @param track_num        Track number (1-based)
 * @param out_start_sector Output: LSN of first sector
 * @param out_sector_count Output: Number of sectors in track
 */
void sacd_area_toc_get_track_sectors(area_toc_t* ctx, uint8_t track_num,
                             uint32_t* out_start_sector, uint32_t* out_sector_count);

/**
 * @brief Get the track length in frames
 *
 * Includes the pre-gap (index 0) if present.
 *
 * @param ctx        Pointer to Area TOC context
 * @param track_num  Track number (1-based)
 * @return Track length in frames (75 frames per second)
 */
uint32_t sacd_area_toc_get_track_frame_length(area_toc_t* ctx, uint8_t track_num);

/**
 * @brief Get the pre-gap (pause) length for a track
 *
 * The pre-gap is the silence or lead-in before the main track starts (index 0).
 *
 * @param ctx        Pointer to Area TOC context
 * @param track_num  Track number (1-based)
 * @return Pre-gap length in frames
 */
uint32_t sacd_area_toc_get_track_pause(area_toc_t* ctx, uint8_t track_num);

/* ========================================================================
 * Index Operations
 * ======================================================================== */

/**
 * @brief Get the start frame of an index within a track
 *
 * Index 0 is the pre-gap, index 1 is the main track start. Additional indices
 * mark positions within the track (e.g., movements in classical music).
 *
 * @param ctx        Pointer to Area TOC context
 * @param track_num  Track number (1-based)
 * @param index_num  Index number (0 = pre-gap, 1 = main track, 2+ = sub-indices)
 * @return Start frame number for the index
 */
uint32_t sacd_area_toc_get_index_start(area_toc_t* ctx, uint8_t track_num,
                               uint8_t index_num);

/**
 * @brief Get the end frame of an index within a track
 *
 * @param ctx        Pointer to Area TOC context
 * @param track_num  Track number (1-based)
 * @param index_num  Index number (0-based)
 * @return Last frame number for the index (inclusive)
 */
uint32_t sacd_area_toc_get_index_end(area_toc_t* ctx, uint8_t track_num, uint8_t index_num);

/* ========================================================================
 * Text Metadata Operations
 * ======================================================================== */

/**
 * @brief Get area-level text metadata
 *
 * Retrieves descriptive text for the entire audio area (not track-specific).
 *
 * @param ctx              Pointer to Area TOC context
 * @param channel_number   Text channel (language) number (1-based)
 * @param text_type        Type of text to retrieve:
 *                         - AREA_TEXT_TYPE_NAME (0): Area description
 *                         - AREA_TEXT_TYPE_COPYRIGHT (1): Copyright notice
 *                         - AREA_TEXT_TYPE_NAME_PHONETIC (2): Phonetic area name
 *                         - AREA_TEXT_TYPE_COPYRIGHT_PHONETIC (3): Phonetic copyright
 *
 * @return Pointer to UTF-8 text string, or NULL if not available
 */
char* sacd_area_toc_get_area_text(area_toc_t* ctx, uint8_t channel_number,
                          area_text_type_t text_type);

/**
 * @brief Get track-specific text metadata
 *
 * Retrieves text metadata for a specific track (title, performer, composer, etc.).
 *
 * @param ctx              Pointer to Area TOC context
 * @param track_num        Track number (1-based)
 * @param channel_number   Text channel (language) number (1-based)
 * @param text_item        Type of text to retrieve (track_type_t):
 *                         - TRACK_TYPE_TITLE (0x01): Track title
 *                         - TRACK_TYPE_PERFORMER (0x02): Performer/artist
 *                         - TRACK_TYPE_SONGWRITER (0x03): Songwriter
 *                         - TRACK_TYPE_COMPOSER (0x04): Composer
 *                         - TRACK_TYPE_ARRANGER (0x05): Arranger
 *                         - TRACK_TYPE_MESSAGE (0x06): Message
 *                         - TRACK_TYPE_EXTRA_MESSAGE (0x07): Extra message
 *                         - 0x81-0x87: Phonetic versions of the above
 * @param out_available    Output: Set to false if text item not available
 *
 * @return Pointer to UTF-8 text string, or NULL if not available
 */
char* sacd_area_toc_get_track_text(area_toc_t* ctx, uint8_t track_num,
                           uint8_t channel_number, track_type_t text_item,
                           bool* out_available);

/**
 * @brief Calculate the search range for a frame using the access list.
 *
 * Uses the access list (frame_info) from the Area TOC to calculate the
 * LSN range where a specific frame should be located. This enables fast
 * seeking without scanning the entire track area.
 *
 * The access list provides LSN addresses at regular frame intervals
 * (step_size). This function calculates:
 * - from_lsn: Lower bound from access list entry
 * - to_lsn: Upper bound from next access list entry or track area end
 *
 * @param[in]  ctx              Pointer to the Area TOC context
 * @param[in]  frame            Target frame number
 * @param[in]  start_lsn        First sector (LSN) of the Track Area
 * @param[in]  end_lsn          Last sector (LSN) of the Track Area
 * @param[out] out_from_lsn     Receives the starting LSN for the search
 * @param[out] out_to_lsn       Receives the ending LSN for the search
 *
 * @return SACD_AREA_TOC_OK on success, or error code
 */
int sacd_area_toc_get_access_list_range(area_toc_t *ctx,
                                    uint32_t frame,
                                    uint32_t start_lsn,
                                    uint32_t end_lsn,
                                    uint32_t *out_from_lsn,
                                    uint32_t *out_to_lsn);

/* ========================================================================
 * Format String Helpers
 * ======================================================================== */

/**
 * @brief Get a human-readable string for the speaker configuration.
 *
 * Returns a static string describing the speaker configuration based on
 * channel count and loudspeaker settings from the Area TOC.
 *
 * @param[in] ctx Pointer to Area TOC context
 *
 * @return Static string describing the configuration:
 *         - "2.0 Stereo" for 2-channel stereo
 *         - "5.0 Surround" for 5-channel
 *         - "5.1 Surround" for 6-channel with LFE
 *         - "Unknown" for unrecognized configurations
 *
 * @note The returned string is static and must not be freed.
 */
const char *sacd_area_toc_get_speaker_config_string(area_toc_t *ctx);

/**
 * @brief Get a human-readable string for the frame format.
 *
 * Returns a static string describing the audio frame format from the Area TOC.
 *
 * @param[in] ctx Pointer to Area TOC context
 *
 * @return Static string describing the format:
 *         - "DST" for lossless DST compression
 *         - "DSD (3-in-14)" for DSD 3-in-14 packed format
 *         - "DSD (3-in-16)" for DSD 3-in-16 packed format
 *         - "Unknown" for unrecognized formats
 *
 * @note The returned string is static and must not be freed.
 */
const char *sacd_area_toc_get_frame_format_string(area_toc_t *ctx);

#ifdef __cplusplus
}
#endif

#endif /* LIBSACD_SACD_AREA_TOC_H */
