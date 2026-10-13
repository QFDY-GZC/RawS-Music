/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief Implementation of Super Audio CD Master Table of Contents (Master TOC) parser.
 * This file implements the Master TOC parsing and accessor functions. The Master TOC contains
 * disc-level and album-level metadata for SACD discs, stored in three redundant copies on the disc.
 * Master TOC Structure (10 sectors per copy):
 * - Sector 0: master_toc_0_t - General metadata (album info, disc info, text channels, weblink)
 * - Sectors 1-8: master_text_t[0-7] - Text strings for up to 8 text channels
 * - Sector 9: manuf_info_t - Manufacturer-specific information
 * The Master TOC copies are located at:
 * - Copy 1: Sectors 510-519 (MASTER_TOC1_START)
 * - Copy 2: Sectors 520-529 (MASTER_TOC2_START)
 * - Copy 3: Sectors 530-539 (MASTER_TOC3_START)
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

#include <libsacd/sacd.h>

#include "sacd_master_toc.h"
#include "sacd_charset.h"

#include <libsautil/mem.h>
#include <libsautil/sastring.h>

#include <stdlib.h>
#include <string.h>
#include <stdbool.h>

void sacd_master_toc_init(master_toc_t *ctx)
{
    if (!ctx) {
        return;
    }
    memset(&ctx->disc_info, 0, sizeof(master_toc_info_t));
    memset(&ctx->album_info, 0, sizeof(master_toc_info_t));
    ctx->web_link_info[0] = '\0';
    ctx->initialized = false;
}

void sacd_master_toc_destroy(master_toc_t *ctx)
{
    if (!ctx) {
        return;
    }
    sacd_master_toc_close(ctx);
}

/**
 * @brief Reads and parses the Master TOC from the SACD disc.
 *
 * This function performs the following operations:
 * 1. Determines the sector location based on master_toc_nr (510, 520, or 530)
 * 2. Reads 10 sectors from the disc
 * 3. Validates signatures of master_toc_0_t, master_text_t[0-7], and manuf_info_t
 * 4. Validates TOC pointer consistency
 * 5. Extracts and stores metadata (album info, disc info, text channels, manufacturer info)
 * 6. Converts text strings from native character sets to UTF-8
 *
 * Text Extraction:
 * - For each text channel (0-7), extracts album and disc text strings
 * - Text types include: title, artist, publisher, copyright, and phonetic variants
 * - Text strings are converted using sacd_special_string_to_utf8() based on character_set_code
 *
 * @return SACD_MASTER_TOC_OK on success, or error code on failure.
 */
int sacd_master_toc_read(master_toc_t *ctx, uint32_t toc_copy_index,
                         sacd_input_t *input)
{
    int result = SACD_MASTER_TOC_OK;
    uint8_t *sector_buffer = NULL;
    uint32_t sector_size = 0;
    int16_t header_size = 0, trailer_size = 0;
    uint32_t toc_start_lsn = 0;
    uint32_t sectors_read = 0;

    // Struct pointers
    master_toc_0_t *toc_header = NULL;
    master_text_t *text_sectors_base = NULL;
    manuf_info_t *manuf_info = NULL;

    /* Validate parameters */
    if (!ctx || !input) {
        return SACD_MASTER_TOC_INVALID_ARGUMENT;
    }

    /* Clear any previous data */
    sacd_master_toc_close(ctx);

    /* Get sector format information */
    sacd_input_get_sector_size(input, &sector_size);
    sacd_input_get_header_size(input, &header_size);
    sacd_input_get_trailer_size(input, &trailer_size);

    /* Allocate buffer for 10 sectors */
    sector_buffer = (uint8_t *)sa_malloc((size_t)sector_size * 10);
    if (!sector_buffer) {
        return SACD_MASTER_TOC_MEMORY_ALLOCATION_ERROR;
    }

    /* Determine which Master TOC copy to read */
    switch (toc_copy_index) {
    case 2:  toc_start_lsn = MASTER_TOC3_START; break; /* Sector 530 */
    case 3:  toc_start_lsn = MASTER_TOC2_START; break; /* Sector 520 */
    default: toc_start_lsn = MASTER_TOC1_START; break; /* Sector 510 (default) */
    }

    /* Read 10 sectors */
    if (sacd_input_read_sectors(input, toc_start_lsn, 10, sector_buffer,
                                &sectors_read) != SACD_MASTER_TOC_OK) {
        result = SACD_MASTER_TOC_IO_ERROR;
        goto cleanup;
    }

    if (sectors_read != 10) {
        result = SACD_MASTER_TOC_NO_DATA;
        goto cleanup;
    }

    /* Map structures */
    // Sector 0: Main TOC
    toc_header = (master_toc_0_t *)(header_size + sector_buffer);

    // Sectors 1-8: Text Channels (base pointer)
    text_sectors_base = (master_text_t *)(1 * sector_size + header_size + sector_buffer);
    
    // Sector 9: Manufacturer Info
    manuf_info = (manuf_info_t *)(9 * sector_size + header_size + sector_buffer);

    /* 1. Validate Signatures */
    if (toc_header->signature != MASTER_TOC_SIGN) {
        result = SACD_MASTER_TOC_INVALID_SIGNATURE;
        goto cleanup;
    }

    if (manuf_info->signature != MANUF_INFO_SIGN) {
        result = SACD_MASTER_TOC_INVALID_SIGNATURE;
        goto cleanup;
    }

    /* Extract and validate text_channel_count first so we know how many text sectors to validate */
    uint8_t raw_text_channel_count = toc_header->text_channels.text_channel_count;
    ctx->text_channel_count = (raw_text_channel_count <= MAX_TEXT_CHANNEL_COUNT) ? raw_text_channel_count : 0;

    /* Only validate text sector signatures for channels that are actually used */
    for (uint32_t channel_idx = 0; channel_idx < ctx->text_channel_count && channel_idx < MAX_TEXT_CHANNEL_COUNT; channel_idx++) {
        master_text_t *curr_text_sector = (master_text_t *)((uint8_t *)text_sectors_base + (channel_idx * sector_size));
        if (curr_text_sector->signature != MASTER_TEXT_SIGN) {
            result = SACD_MASTER_TOC_INVALID_SIGNATURE;
            goto cleanup;
        }
    }

    /* 2. Validate TOC Pointer Consistency */
    bool stereo_toc_valid = (toc_header->disc.stereo_toc_1_lsn == toc_header->disc.stereo_toc_2_lsn) ||
                            (toc_header->disc.stereo_toc_1_lsn != 0 && toc_header->disc.stereo_toc_2_lsn != 0);

    bool multichannel_toc_valid = (toc_header->disc.mc_toc_1_lsn == toc_header->disc.mc_toc_2_lsn) ||
                                  (toc_header->disc.mc_toc_1_lsn != 0 && toc_header->disc.mc_toc_2_lsn != 0);

    if (!stereo_toc_valid || !multichannel_toc_valid) {
        result = SACD_MASTER_TOC_INVALID_AREA_POINTER;
        goto cleanup;
    }

    /* 3. Extract Fixed Metadata */
    sa_strlcpy(ctx->manufacturer_info, manuf_info->info, MAX_MANUFACTURER_INFO + 1);

    ctx->cur_text_channel = 0;
    /* text_channel_count already extracted above */
    ctx->disc_type_hybrid = (toc_header->disc.disc_type_hybrid == 1);
    ctx->version = toc_header->version;
    ctx->album_size = ntoh16(toc_header->album.album_set_size);
    ctx->album_sequence = ntoh16(toc_header->album.album_sequence_number);

    ctx->mc_toc_area1_start = ntoh32(toc_header->disc.mc_toc_1_lsn);
    ctx->mc_toc_area2_start = ntoh32(toc_header->disc.mc_toc_2_lsn);
    ctx->st_toc_area1_start = ntoh32(toc_header->disc.stereo_toc_1_lsn);
    ctx->st_toc_area2_start = ntoh32(toc_header->disc.stereo_toc_2_lsn);
    ctx->mc_toc_area_length = ntoh16(toc_header->disc.mc_toc_length);
    ctx->st_toc_area_length = ntoh16(toc_header->disc.stereo_toc_length);

    ctx->date.year = ntoh16(toc_header->disc.disc_date.year);
    ctx->date.month = toc_header->disc.disc_date.month;
    ctx->date.day = toc_header->disc.disc_date.day;

    sa_strlcpy(ctx->web_link_info, toc_header->disc_weblink_info.disc_weblink, MAX_DISC_WEB_LINK_INFO + 1);
    sa_strlcpy(ctx->disc_info.catalog_num, toc_header->disc.disc_catalog_number, MAX_CATALOG_LENGTH + 1);
    sa_strlcpy(ctx->album_info.catalog_num, toc_header->album.album_catalog_number, MAX_CATALOG_LENGTH + 1);

    /* 4. Extract Genres */
    for (int genre_idx = 0; genre_idx < MAX_GENRE_COUNT; genre_idx++) {
        ctx->album_info.genre[genre_idx].genre_table = toc_header->album.album_genre[genre_idx].genre_table;
        ctx->album_info.genre[genre_idx].index       = ntoh16(toc_header->album.album_genre[genre_idx].genre_index);

        ctx->disc_info.genre[genre_idx].genre_table  = toc_header->disc.disc_genre[genre_idx].genre_table;
        ctx->disc_info.genre[genre_idx].index        = ntoh16(toc_header->disc.disc_genre[genre_idx].genre_index);
    }

    /* 5. Extract Text Channels */
    // First, initialize ALL channel info and text pointers to safe defaults
    for (int channel_idx = 0; channel_idx < MAX_TEXT_CHANNEL_COUNT; channel_idx++) {
        ctx->channel_info[channel_idx].character_set_code = 0;
        ctx->channel_info[channel_idx].language_code = 0;
        for (int text_type_idx = 0; text_type_idx < MAX_TEXT_TYPE_COUNT; text_type_idx++) {
            ctx->album_info.text[channel_idx][text_type_idx] = NULL;
            ctx->disc_info.text[channel_idx][text_type_idx] = NULL;
        }
    }

    // Only process text channels that are actually used (i < text_channel_count)
    // Unused channels contain garbage data on disc
    for (uint32_t channel_idx = 0; channel_idx < ctx->text_channel_count && channel_idx < MAX_TEXT_CHANNEL_COUNT; channel_idx++) {
        // Calculate pointer to this specific text channel sector once
        master_text_t *channel_text_sector = (master_text_t *)((uint8_t *)text_sectors_base + (channel_idx * sector_size));

        ctx->channel_info[channel_idx].character_set_code = toc_header->text_channels.info[channel_idx].character_set_code;
        ctx->channel_info[channel_idx].language_code      = toc_header->text_channels.info[channel_idx].language_code;

        /* Skip text channels with invalid codes */
        if (ctx->channel_info[channel_idx].character_set_code == 0 || ctx->channel_info[channel_idx].language_code == 0) {
            continue;
        }

        // --- Album Text ---
        for (int text_type_idx = 0; text_type_idx < MAX_TEXT_TYPE_COUNT; text_type_idx++) {
            uint16_t text_offset = 0;
            switch (text_type_idx) {
                case ALBUM_TEXT_TYPE_TITLE:              text_offset = channel_text_sector->album_title_ptr; break;
                case ALBUM_TEXT_TYPE_ARTIST:             text_offset = channel_text_sector->album_artist_ptr; break;
                case ALBUM_TEXT_TYPE_PUBLISHER:          text_offset = channel_text_sector->album_publisher_ptr; break;
                case ALBUM_TEXT_TYPE_COPYRIGHT:          text_offset = channel_text_sector->album_copyright_ptr; break;
                case ALBUM_TEXT_TYPE_TITLE_PHONETIC:     text_offset = channel_text_sector->album_title_phonetic_ptr; break;
                case ALBUM_TEXT_TYPE_ARTIST_PHONETIC:    text_offset = channel_text_sector->album_artist_phonetic_ptr; break;
                case ALBUM_TEXT_TYPE_PUBLISHER_PHONETIC: text_offset = channel_text_sector->album_publisher_phonetic_ptr; break;
                case ALBUM_TEXT_TYPE_COPYRIGHT_PHONETIC: text_offset = channel_text_sector->album_copyright_phonetic_ptr; break;
            }
            text_offset = ntoh16(text_offset);

            // Validate offset is within sector boundaries
            if (text_offset != 0 && text_offset < (uint16_t)sector_size) {
                // Base address is the start of THIS specific sector (channel_text_sector)
                ctx->album_info.text[channel_idx][text_type_idx] = sacd_special_string_to_utf8(
                    (char *)channel_text_sector + text_offset,
                    ctx->channel_info[channel_idx].character_set_code
                );
            }
        }

        // --- Disc Text ---
        for (int text_type_idx = 0; text_type_idx < MAX_TEXT_TYPE_COUNT; text_type_idx++) {
            uint16_t text_offset = 0;
            switch (text_type_idx) {
                case ALBUM_TEXT_TYPE_TITLE:              text_offset = channel_text_sector->disc_title_ptr; break;
                case ALBUM_TEXT_TYPE_ARTIST:             text_offset = channel_text_sector->disc_artist_ptr; break;
                case ALBUM_TEXT_TYPE_PUBLISHER:          text_offset = channel_text_sector->disc_publisher_ptr; break;
                case ALBUM_TEXT_TYPE_COPYRIGHT:          text_offset = channel_text_sector->disc_copyright_ptr; break;
                case ALBUM_TEXT_TYPE_TITLE_PHONETIC:     text_offset = channel_text_sector->disc_title_phonetic_ptr; break;
                case ALBUM_TEXT_TYPE_ARTIST_PHONETIC:    text_offset = channel_text_sector->disc_artist_phonetic_ptr; break;
                case ALBUM_TEXT_TYPE_PUBLISHER_PHONETIC: text_offset = channel_text_sector->disc_publisher_phonetic_ptr; break;
                case ALBUM_TEXT_TYPE_COPYRIGHT_PHONETIC: text_offset = channel_text_sector->disc_copyright_phonetic_ptr; break;
            }
            text_offset = ntoh16(text_offset);

            // Validate offset is within sector boundaries
            if (text_offset != 0 && text_offset < (uint16_t)sector_size) {
                ctx->disc_info.text[channel_idx][text_type_idx] = sacd_special_string_to_utf8(
                    (char *)channel_text_sector + text_offset,
                    ctx->channel_info[channel_idx].character_set_code
                );
            }
        }
    }

cleanup:
    sa_free(sector_buffer);

    if (result != SACD_MASTER_TOC_OK) {
        sacd_master_toc_close(ctx);
    } else {
        ctx->initialized = true;
    }

    return result;
}

/**
 * @brief Retrieves the sector numbers and length of a Track Area TOC.
 *
 * Returns the LSN locations of both TOC copies and the length for either
 * the 2-Channel Stereo or Multi Channel area.
 */
bool sacd_master_toc_get_area_toc_sector_range(master_toc_t *ctx, channel_t area_type,
                                    uint32_t *out_area1_start, uint32_t *out_area2_start,
                                    uint16_t *out_area_length)
{
    if (!ctx || !out_area1_start || !out_area2_start || !out_area_length) {
        return false;
    }

    if (!ctx->initialized) {
        return false;
    }

    switch (area_type)
    {
    case TWO_CHANNEL:
        *out_area1_start = ctx->st_toc_area1_start;
        *out_area2_start = ctx->st_toc_area2_start;
        *out_area_length = ctx->st_toc_area_length;
        break;
    case MULTI_CHANNEL:
        *out_area1_start = ctx->mc_toc_area1_start;
        *out_area2_start = ctx->mc_toc_area2_start;
        *out_area_length = ctx->mc_toc_area_length;
        break;
    default:
        return false;
    }
    return true;
}

/**
 * @brief Frees all dynamically allocated text strings in the context.
 *
 * Iterates through all text channels and text types for both album and disc,
 * freeing any allocated UTF-8 strings. This prevents memory leaks.
 */
void sacd_master_toc_close(master_toc_t *ctx)
{
    uint32_t channel_idx, text_type_idx;

    if (!ctx) {
        return;
    }

    for (channel_idx = 0; channel_idx < MAX_TEXT_CHANNEL_COUNT; channel_idx++)
    {
        for (text_type_idx = 0; text_type_idx < MAX_TEXT_TYPE_COUNT; text_type_idx++)
        {
            /* Free album text strings */
            if (ctx->album_info.text[channel_idx][text_type_idx])
            {
                sa_free(ctx->album_info.text[channel_idx][text_type_idx]);
            }
            ctx->album_info.text[channel_idx][text_type_idx] = NULL;

            /* Free disc text strings */
            if (ctx->disc_info.text[channel_idx][text_type_idx])
            {
                sa_free(ctx->disc_info.text[channel_idx][text_type_idx]);
            }
            ctx->disc_info.text[channel_idx][text_type_idx] = NULL;
        }
    }
    ctx->web_link_info[0] = '\0';
    ctx->initialized = false;
}

/* General Information Accessor Functions */

sacd_version_t sacd_master_toc_get_sacd_version(master_toc_t *ctx)
{
    sacd_version_t default_version = {0, 0};
    if (!ctx) {
        return default_version;
    }
    return ctx->version;
}

uint8_t sacd_master_toc_get_text_channel_count(master_toc_t *ctx)
{
    if (!ctx) {
        return 0;
    }
    return (uint8_t)ctx->text_channel_count;
}

/**
 * @brief Gets the language code and character set for a specific text channel.
 *
 * Note: The language code is a 2-character ISO 639 code stored in a uint16_t,
 * and is NOT null-terminated.
 */
int sacd_master_toc_get_text_channel_info(master_toc_t *ctx, uint8_t channel_number,
                                      char **out_language_code, uint8_t *out_charset_code)
{
    /* Validate parameters */
    if (!ctx || !out_language_code || !out_charset_code) {
        return SACD_MASTER_TOC_INVALID_ARGUMENT;
    }

    /* Validate context is initialized */
    if (!ctx->initialized) {
        return SACD_MASTER_TOC_UNINITIALIZED;
    }

    /* Validate channel_number is in valid range (1-based) */
    if ((channel_number > ctx->text_channel_count) || (channel_number < 1))
    {
        return SACD_MASTER_TOC_INVALID_ARGUMENT;
    }
    *out_language_code = (char *)&(ctx->channel_info[channel_number - 1].language_code);
    *out_charset_code = ctx->channel_info[channel_number - 1].character_set_code;
    return SACD_MASTER_TOC_OK;
}

/* Album Information Accessor Functions */

uint16_t sacd_master_toc_get_album_size(master_toc_t *ctx)
{
    if (!ctx) {
        return 0;
    }
    return ctx->album_size;
}

uint16_t sacd_master_toc_get_disc_sequence_num(master_toc_t *ctx)
{
    if (!ctx) {
        return 0;
    }
    return ctx->album_sequence;
}

char *sacd_master_toc_get_album_catalog_num(master_toc_t *ctx)
{
    if (!ctx) {
        return NULL;
    }
    return ctx->album_info.catalog_num;
}

void sacd_master_toc_get_album_genre(master_toc_t *ctx, uint16_t genre_number,
                             uint8_t *out_genre_table, uint16_t *out_genre_index)
{
    if (!ctx || !out_genre_table || !out_genre_index) {
        return;
    }
    /* Validate genre_number is in valid range (1-based, 1 to 4) */
    if (genre_number < 1 || genre_number > MAX_GENRE_COUNT) {
        *out_genre_table = 0;
        *out_genre_index = 0;
        return;
    }
    *out_genre_table = ctx->album_info.genre[genre_number - 1].genre_table;
    *out_genre_index = ctx->album_info.genre[genre_number - 1].index;
}

char *sacd_master_toc_get_album_text(master_toc_t *ctx, uint8_t channel_number,
                             album_text_type_t text_type)
{
    if (!ctx) {
        return NULL;
    }
    /* Validate channel_number is in valid range (1-based) */
    if (channel_number < 1 || channel_number > MAX_TEXT_CHANNEL_COUNT) {
        return NULL;
    }
    /* Validate text_type is in valid range */
    if (text_type >= MAX_TEXT_TYPE_COUNT) {
        return NULL;
    }
    return ctx->album_info.text[channel_number - 1][text_type];
}

/* Disc Information Accessor Functions */

bool sacd_master_toc_is_disc_hybrid(master_toc_t *ctx)
{
    if (!ctx) {
        return false;
    }
    return ctx->disc_type_hybrid;
}

char *sacd_master_toc_get_manufacturer_info(master_toc_t *ctx)
{
    if (!ctx) {
        return NULL;
    }
    return ctx->manufacturer_info;
}

char *sacd_master_toc_get_disc_catalog_num(master_toc_t *ctx)
{
    if (!ctx) {
        return NULL;
    }
    return ctx->disc_info.catalog_num;
}

void sacd_master_toc_get_disc_genre(master_toc_t *ctx, uint16_t genre_number,
                            uint8_t *out_genre_table, uint16_t *out_genre_index)
{
    if (!ctx || !out_genre_table || !out_genre_index) {
        return;
    }
    /* Validate genre_number is in valid range (1-based, 1 to 4) */
    if (genre_number < 1 || genre_number > MAX_GENRE_COUNT) {
        *out_genre_table = 0;
        *out_genre_index = 0;
        return;
    }
    *out_genre_table = ctx->disc_info.genre[genre_number - 1].genre_table;
    *out_genre_index = ctx->disc_info.genre[genre_number - 1].index;
}

void sacd_master_toc_get_disc_date(master_toc_t *ctx, uint16_t *out_year, uint8_t *out_month, uint8_t *out_day)
{
    if (!ctx || !out_year || !out_month || !out_day) {
        return;
    }
    *out_year = ctx->date.year;
    *out_month = ctx->date.month;
    *out_day = ctx->date.day;
}

char *sacd_master_toc_get_disc_text(master_toc_t *ctx, uint8_t channel_number,
                            album_text_type_t text_type)
{
    if (!ctx) {
        return NULL;
    }
    /* Validate channel_number is in valid range (1-based) */
    if (channel_number < 1 || channel_number > MAX_TEXT_CHANNEL_COUNT) {
        return NULL;
    }
    /* Validate text_type is in valid range */
    if (text_type >= MAX_TEXT_TYPE_COUNT) {
        return NULL;
    }
    return ctx->disc_info.text[channel_number - 1][text_type];
}

char *sacd_master_toc_get_disc_web_link_info(master_toc_t *ctx)
{
    if (!ctx) {
        return NULL;
    }
    return ctx->web_link_info;
}

/* ========================================================================
 * Path Generation Helpers
 * ======================================================================== */

/* Internal maximum lengths for filename components */
#define _MAX_ARTIST_COMPONENT_LEN  60
#define _MAX_TITLE_COMPONENT_LEN   120

/**
 * @brief Gets the best available title (disc or album) from metadata.
 *
 * For multi-disc sets, prefers album_title. Otherwise prefers disc_title.
 */
static const char *_get_best_title(master_toc_t *ctx, uint8_t text_channel,
                                   bool is_multiset)
{
    const char *title = NULL;

    if (is_multiset) {
        /* Multi-disc: prefer album title */
        title = sacd_master_toc_get_album_text(ctx, text_channel, ALBUM_TEXT_TYPE_TITLE);
        if (title != NULL && title[0] != '\0') {
            return title;
        }
        /* Fall back to disc title */
        title = sacd_master_toc_get_disc_text(ctx, text_channel, ALBUM_TEXT_TYPE_TITLE);
    } else {
        /* Single disc: prefer disc title */
        title = sacd_master_toc_get_disc_text(ctx, text_channel, ALBUM_TEXT_TYPE_TITLE);
        if (title != NULL && title[0] != '\0') {
            return title;
        }
        /* Fall back to album title */
        title = sacd_master_toc_get_album_text(ctx, text_channel, ALBUM_TEXT_TYPE_TITLE);
    }

    return (title != NULL && title[0] != '\0') ? title : NULL;
}

/**
 * @brief Gets the best available artist (disc or album) from metadata.
 */
static const char *_get_best_artist(master_toc_t *ctx, uint8_t text_channel)
{
    const char *artist = NULL;

    /* Try disc artist first */
    artist = sacd_master_toc_get_disc_text(ctx, text_channel, ALBUM_TEXT_TYPE_ARTIST);
    if (artist != NULL && artist[0] != '\0') {
        return artist;
    }

    /* Fall back to album artist */
    artist = sacd_master_toc_get_album_text(ctx, text_channel, ALBUM_TEXT_TYPE_ARTIST);
    return (artist != NULL && artist[0] != '\0') ? artist : NULL;
}

char *sacd_master_toc_get_album_dir(master_toc_t *ctx,
                                    master_toc_path_format_t format,
                                    uint8_t text_channel)
{
    if (ctx == NULL) {
        return NULL;
    }

    /* Get disc info for multi-disc detection */
    uint16_t album_count = ctx->album_size;
    uint16_t disc_num = ctx->album_sequence;
    bool is_multiset = (album_count > 1);

    /* Get year if needed */
    uint16_t year = 0;
    if (format == MASTER_TOC_PATH_YEAR_ARTIST_TITLE) {
        year = ctx->date.year;
    }

    /* Get title and artist */
    const char *raw_title = _get_best_title(ctx, text_channel, is_multiset);
    const char *raw_artist = _get_best_artist(ctx, text_channel);

    /* Prepare sanitized components */
    char title[_MAX_TITLE_COMPONENT_LEN + 1] = {0};
    char artist[_MAX_ARTIST_COMPONENT_LEN + 1] = {0};

    if (raw_title != NULL) {
        sa_utf8_strlcpy(title, raw_title, sizeof(title));
        sa_sanitize_filename(title, sizeof(title));
    }

    if (raw_artist != NULL && format != MASTER_TOC_PATH_TITLE_ONLY) {
        sa_extract_first_token(artist, raw_artist, sizeof(artist), NULL);
        sa_sanitize_filename(artist, sizeof(artist));
    }

    /* Build the result string */
    char *result = NULL;

    if (format == MASTER_TOC_PATH_YEAR_ARTIST_TITLE && year > 0 &&
        artist[0] != '\0' && title[0] != '\0') {
        result = sa_asprintf("%04u - %s - %s", year, artist, title);
    } else if (format != MASTER_TOC_PATH_TITLE_ONLY &&
               artist[0] != '\0' && title[0] != '\0') {
        result = sa_asprintf("%s - %s", artist, title);
    } else if (title[0] != '\0') {
        result = sa_strdup(title);
    } else if (artist[0] != '\0') {
        result = sa_strdup(artist);
    } else {
        result = sa_strdup("Unknown Album");
    }

    if (result == NULL) {
        return NULL;
    }

    /* Append disc number for multi-disc sets */
    if (is_multiset) {
        char *with_disc = sa_asprintf("%s (Disc %u of %u)",
                                      result, disc_num, album_count);
        sa_free(result);
        result = with_disc;
    }

    return result;
}

char *sacd_master_toc_get_album_path(master_toc_t *ctx,
                                     master_toc_path_format_t format,
                                     uint8_t text_channel)
{
    if (ctx == NULL) {
        return NULL;
    }

    /* Get disc info for multi-disc detection */
    uint16_t album_count = ctx->album_size;
    uint16_t disc_num = ctx->album_sequence;
    bool is_multiset = (album_count > 1);

    /* Get year if needed */
    uint16_t year = 0;
    if (format == MASTER_TOC_PATH_YEAR_ARTIST_TITLE) {
        year = ctx->date.year;
    }

    /* Get title and artist */
    const char *raw_title = _get_best_title(ctx, text_channel, is_multiset);
    const char *raw_artist = _get_best_artist(ctx, text_channel);

    /* Prepare sanitized components */
    char title[_MAX_TITLE_COMPONENT_LEN + 1] = {0};
    char artist[_MAX_ARTIST_COMPONENT_LEN + 1] = {0};

    if (raw_title != NULL) {
        sa_utf8_strlcpy(title, raw_title, sizeof(title));
        sa_sanitize_filename(title, sizeof(title));
    }

    if (raw_artist != NULL && format != MASTER_TOC_PATH_TITLE_ONLY) {
        sa_extract_first_token(artist, raw_artist, sizeof(artist), NULL);
        sa_sanitize_filename(artist, sizeof(artist));
    }

    /* Build the base path */
    char *base = NULL;

    if (format == MASTER_TOC_PATH_YEAR_ARTIST_TITLE && year > 0 &&
        artist[0] != '\0' && title[0] != '\0') {
        base = sa_asprintf("%04u - %s - %s", year, artist, title);
    } else if (format != MASTER_TOC_PATH_TITLE_ONLY &&
               artist[0] != '\0' && title[0] != '\0') {
        base = sa_asprintf("%s - %s", artist, title);
    } else if (title[0] != '\0') {
        base = sa_strdup(title);
    } else if (artist[0] != '\0') {
        base = sa_strdup(artist);
    } else {
        base = sa_strdup("Unknown Album");
    }

    if (base == NULL) {
        return NULL;
    }

    /* For multi-disc sets, append a disc subdirectory */
    if (is_multiset) {
        char *with_disc = sa_asprintf("%s/Disc %u", base, disc_num);
        sa_free(base);
        return with_disc;
    }

    return base;
}
