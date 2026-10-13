/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief Implementation of SACD track filename generation.
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

#include <libsautil/export.h>
#include <libsautil/sastring.h>
#include <libsautil/mem.h>

#include <string.h>
#include <stddef.h>

/* Internal maximum lengths for filename components */
#define _MAX_ARTIST_COMPONENT_LEN  60
#define _MAX_TITLE_COMPONENT_LEN   120

/**
 * @brief Gets the best available artist from Master TOC metadata.
 */
static const char *_get_best_artist(sacd_t *ctx, uint8_t text_channel)
{
    const char *artist = NULL;

    /* Try disc artist first */
    if (sacd_get_disc_text(ctx, text_channel,
                                  ALBUM_TEXT_TYPE_ARTIST, &artist) == SACD_OK) {
        if (artist != NULL && artist[0] != '\0') {
            return artist;
        }
    }

    /* Fall back to album artist */
    if (sacd_get_album_text(ctx, text_channel,
                                   ALBUM_TEXT_TYPE_ARTIST, &artist) == SACD_OK) {
        if (artist != NULL && artist[0] != '\0') {
            return artist;
        }
    }

    return NULL;
}

char *sacd_get_track_filename(sacd_t *ctx, uint8_t track_num,
                              sacd_track_format_t format, uint8_t text_channel)
{
    if (ctx == NULL || track_num == 0) {
        return NULL;
    }

    /* Get track count to validate */
    uint8_t track_count = 0;
    if (sacd_get_track_count(ctx, &track_count) != SACD_OK) {
        return NULL;
    }
    if (track_num > track_count) {
        return NULL;
    }

    /* Simple case: track number only */
    if (format == SACD_TRACK_NUM_ONLY) {
        return sa_asprintf("%02u", track_num);
    }

    /* Get track title */
    const char *raw_title = NULL;
    sacd_get_track_text(ctx, track_num, text_channel,
                               TRACK_TYPE_TITLE, &raw_title);

    /* Get performer for this track */
    const char *raw_performer = NULL;
    if (format == SACD_TRACK_NUM_ARTIST_TITLE) {
        sacd_get_track_text(ctx, track_num, text_channel,
                                   TRACK_TYPE_PERFORMER, &raw_performer);
        /* Fall back to disc/album artist if no track performer */
        if (raw_performer == NULL || raw_performer[0] == '\0') {
            raw_performer = _get_best_artist(ctx, text_channel);
        }
    }

    /* Prepare sanitized components */
    char title[_MAX_TITLE_COMPONENT_LEN + 1] = {0};
    char performer[_MAX_ARTIST_COMPONENT_LEN + 1] = {0};

    if (raw_title != NULL && raw_title[0] != '\0') {
        sa_utf8_strlcpy(title, raw_title, sizeof(title));
        sa_sanitize_filename(title, sizeof(title));
    }

    if (raw_performer != NULL && raw_performer[0] != '\0') {
        sa_extract_first_token(performer, raw_performer, sizeof(performer), NULL);
        sa_sanitize_filename(performer, sizeof(performer));
    }

    /* Build the filename */
    char *result = NULL;

    if (format == SACD_TRACK_NUM_ARTIST_TITLE &&
        performer[0] != '\0' && title[0] != '\0') {
        result = sa_asprintf("%02u - %s - %s", track_num, performer, title);
    } else if (title[0] != '\0') {
        result = sa_asprintf("%02u - %s", track_num, title);
    } else if (performer[0] != '\0') {
        result = sa_asprintf("%02u - %s", track_num, performer);
    } else {
        result = sa_asprintf("%02u - Track %u", track_num, track_num);
    }

    return result;
}
