/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief Implementation of the high-level SACD disc reader interface.
 * This file implements the unified SACD reader API that combines Master TOC and
 * Area TOC functionality. It provides lifecycle management, channel selection,
 * metadata access, and audio data retrieval for SACD disc images.
 * The implementation handles:
 * - Context lifecycle (creation, initialization, cleanup, destruction)
 * - Master TOC and Area TOC management
 * - Automatic routing between 2-channel and multi-channel areas
 * - All metadata queries (disc, area, track information)
 * - Audio data extraction (main audio and supplementary data)
 * @see sacd.h for the public API documentation
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

#include "sacd_area_toc.h"
#include "sacd_master_toc.h"

#include <libsautil/mem.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/**
 * @struct sacd_t
 * @brief Main SACD reader context structure.
 *
 * This structure maintains the complete state for reading an SACD disc, including
 * pointers to the Master TOC and both Area TOCs (2-channel and multi-channel).
 * The reader automatically routes API calls to the appropriate Area TOC based on
 * the currently selected channel type.
 *
 * @note This is an opaque structure. Use the provided API functions for all access.
 */
struct sacd_s {
    /**
     * @brief Initialization state flag.
     * Set to true after successful initialization via sacd_init().
     */
    bool initialized;

    /**
     * @brief Pointer to the underlying input device for sector access.
     * @see sacd_input_t
     */
    sacd_input_t* input;

    /**
     * @brief Pointer to the Master TOC (disc-level metadata).
     * Contains album information, catalog numbers, genres, and area locations.
     */
    master_toc_t* master_toc;

    /**
     * @brief Pointer to the 2-channel stereo Area TOC.
     * NULL if disc does not contain a 2-channel area.
     */
    area_toc_t* st_area_toc;

    /**
     * @brief Pointer to the multi-channel Area TOC.
     * NULL if disc does not contain a multi-channel area (5.1 surround).
     */
    area_toc_t* mc_area_toc;

    /**
     * @brief Sector format detected from disc.
     * Indicates header/trailer sizes (2048, 2054, or 2064 bytes per sector).
     * Stored as int; corresponds to sacd_sector_format_t values.
     */
    sacd_sector_format_t sector_format;

    /**
     * @brief Currently selected channel type (TWO_CHANNEL or MULTI_CHANNEL).
     * API calls are routed to the corresponding Area TOC.
     */
    channel_t current_channel_type;

    /**
     * @brief Which Area TOC copy to use (1 or 2).
     * SACD discs store two copies of each Area TOC for redundancy.
     */
    int area_toc_num;
};

/**
 * @brief Internal helper: Returns pointer to currently selected Area TOC.
 *
 * Routes to either the 2-channel or multi-channel Area TOC based on the
 * current_channel_type setting in the context.
 *
 * @param[in] ctx Pointer to the sacd_t context
 * @return Pointer to the selected Area TOC, or NULL if none selected or invalid type
 */
area_toc_t *sacd_get_selected_area_toc(sacd_t *ctx)
{
    switch (ctx->current_channel_type)
    {
    case TWO_CHANNEL:
        return ctx->st_area_toc;
    case MULTI_CHANNEL:
        return ctx->mc_area_toc;
    default:
        return NULL;
    }
}

/**
 * @brief Creates and initializes a new SACD reader context.
 *
 * Allocates memory for the context and initializes all fields to safe defaults.
 * The context must be initialized with sacd_init() before use.
 *
 * @return Pointer to newly allocated context, or NULL if allocation failed
 */
sacd_t *sacd_create(void)
{
    /* Allocate and zero-initialize the context structure */
    sacd_t *ctx = (sacd_t *)sa_calloc(1, sizeof(sacd_t));
    if (!ctx)
    {
        return NULL;
    }

    /* Initialize all fields to safe defaults */
    ctx->initialized = false;
    ctx->input = NULL;
    ctx->master_toc = NULL;
    ctx->st_area_toc = NULL;
    ctx->mc_area_toc = NULL;
    ctx->sector_format = -1;
    ctx->current_channel_type = TWO_CHANNEL;  /* Default to 2-channel */
    ctx->area_toc_num = 1;                     /* Use primary TOC copy */

    return ctx;
}

/**
 * @brief Destroys an SACD reader context and frees all resources.
 *
 * Closes any open disc, frees all TOC structures, and deallocates the context itself.
 * This is the final cleanup function - the context pointer is invalid after this call.
 *
 * @param[in,out] ctx Pointer to the context to destroy (may be NULL)
 */
void sacd_destroy(sacd_t *ctx)
{
    if (!ctx)
    {
        return;  /* Silently ignore NULL pointers */
    }

    /* Close and cleanup all internal resources */
    sacd_close(ctx);

    /* Free the context structure itself */
    sa_free(ctx);
}

/**
 * @brief Initializes the SACD reader by opening a disc and reading all TOC structures.
 *
 * This is the main initialization function. It performs the following steps:
 * 1. Opens the disc image and initializes the input for sector-level access
 * 2. Authenticates with the device if required (e.g., PS3 drives)
 * 3. Reads and parses the Master TOC (disc-level metadata)
 * 4. Reads and parses the 2-channel Area TOC if present
 * 5. Reads and parses the multi-channel Area TOC if present
 *
 * The function will initialize all available areas. Use sacd_get_available_channel_types()
 * to determine which areas (2-channel and/or multi-channel) are available.
 *
 * @param[in,out] ctx           Pointer to the sacd_t context
 * @param[in]     filename      Path to the SACD disc image file
 * @param[in]     master_toc_nr Which Master TOC copy to use (1 or 2)
 * @param[in]     area_toc_nr   Which Area TOC copy to use (1 or 2)
 * @return SACD_OK on success, error code otherwise
 */
int sacd_init(sacd_t *ctx, const char *filename, unsigned int master_toc_nr,
                          unsigned int area_toc_nr)
{
    int res;
    uint32_t area1_start;   /* Start sector of primary Area TOC copy */
    uint32_t area2_start;   /* Start sector of backup Area TOC copy */
    uint16_t area_length;   /* Length of Area TOC in sectors */
    sacd_sector_format_t detected_format;

    if (!ctx || !filename)
    {
        return SACD_INVALID_ARGUMENT;
    }

    /* Store pointer to input for sector-level access */
    res = sacd_input_open(filename, &ctx->input);
    if (res != SACD_INPUT_OK) {
        return SACD_IO_ERROR;
    }

    /* Authenticate with the device if required (e.g., PS3 drives).
     * This performs BD authentication, SAC key exchange, and layer selection.
     * For file/memory inputs, this is a no-op (returns NOT_SUPPORTED which we ignore). */
    res = sacd_input_authenticate(ctx->input);
    if (res != SACD_INPUT_OK && res != SACD_INPUT_ERR_NOT_SUPPORTED) {
        sacd_input_close(ctx->input);
        ctx->input = NULL;
        return SACD_IO_ERROR;
    }

    /* Get the detected sector format from the input */
    res = sacd_input_get_sector_format(ctx->input, &detected_format);
    if (res != SACD_INPUT_OK)
    {
        ctx->input = NULL;
        return (SACD_SECTOR_READER_INIT_FAILED);
    }
    ctx->sector_format = detected_format;

    /* Mark reader as initialized (will be set again at end if fully successful) */
    ctx->initialized = true;

    /* Step 1: Clean up any existing 2-channel Area TOC from previous initialization */
    if (ctx->st_area_toc)
    {
        sacd_area_toc_destroy(ctx->st_area_toc);
        sa_free(ctx->st_area_toc);
        ctx->st_area_toc = NULL;
    }

    /* Step 2: Create a temporary minimal Area TOC for audio data reader setup.
     * This temporary TOC has no sectors (length=0) to avoid read errors.
     * It's initialized as TWO_CHANNEL to support both DST and DSD formats.
     * This will be replaced with the real Area TOC later. */
    ctx->st_area_toc = (area_toc_t *)sa_malloc(sizeof(area_toc_t));
    if (!ctx->st_area_toc)
    {
        return SACD_MEMORY_ALLOCATION_ERROR;
    }
    sacd_area_toc_init(ctx->st_area_toc);
    res = sacd_area_toc_read(ctx->st_area_toc, 1, 0, 0, 0, TWO_CHANNEL, ctx->input);
    if (res != SACD_OK)
    {
        /* Temporary Area TOC initialization failed - clean up and return error */
        sacd_input_close(ctx->input);
        ctx->input = NULL;

        sacd_area_toc_destroy(ctx->st_area_toc);
        sa_free(ctx->st_area_toc);
        ctx->st_area_toc = NULL;

        return (res);
    }

    ctx->current_channel_type = TWO_CHANNEL;

    /* Step 3: Clean up any existing Master TOC from previous initialization */
    if (ctx->master_toc)
    {
        sacd_master_toc_destroy(ctx->master_toc);
        sa_free(ctx->master_toc);
        ctx->master_toc = NULL;
    }

    /* Step 4: Read and parse the Master TOC (disc-level metadata).
     * The Master TOC contains album info, area locations, and disc catalog data. */
    ctx->master_toc = (master_toc_t *)sa_malloc(sizeof(master_toc_t));
    if (!ctx->master_toc)
    {
        return SACD_MEMORY_ALLOCATION_ERROR;
    }
    sacd_master_toc_init(ctx->master_toc);

    res = sacd_master_toc_read(ctx->master_toc, master_toc_nr, ctx->input);
    if (res != SACD_OK)
    {
        /* Master TOC read failed - clean up and return error */
        sacd_input_close(ctx->input);
        ctx->input = NULL;

        sacd_master_toc_destroy(ctx->master_toc);
        sa_free(ctx->master_toc);
        ctx->master_toc = NULL;

        return (res);
    }

    /* Step 5: Initialize 2-channel (stereo) Area TOC if present on the disc.
     * First destroy the temporary Area TOC created earlier. */
    ctx->current_channel_type = TWO_CHANNEL;
    if (ctx->st_area_toc)
    {
        sacd_area_toc_destroy(ctx->st_area_toc);
        sa_free(ctx->st_area_toc);
        ctx->st_area_toc = NULL;
    }

    /* Remember which Area TOC copy to use (1 = primary, 2 = backup) */
    ctx->area_toc_num = area_toc_nr;

    /* Query the Master TOC for 2-channel area location */
    sacd_master_toc_get_area_toc_sector_range(ctx->master_toc, TWO_CHANNEL, &area1_start,
                                   &area2_start, &area_length);

    /* Only initialize if 2-channel area exists (area1_start != 0) */
    if (area1_start)
    {
        ctx->st_area_toc = (area_toc_t *)sa_malloc(sizeof(area_toc_t));
        if (!ctx->st_area_toc)
        {
            return SACD_MEMORY_ALLOCATION_ERROR;
        }
        sacd_area_toc_init(ctx->st_area_toc);

        /* Read and parse the 2-channel Area TOC */
        res = sacd_area_toc_read(ctx->st_area_toc, area_toc_nr, area1_start,
                                  area2_start, area_length, TWO_CHANNEL,
                                  ctx->input);
        if (res != SACD_OK)
        {
            /* 2-channel Area TOC read failed - clean up and return error */
            sacd_input_close(ctx->input);
            ctx->input = NULL;

            sacd_area_toc_destroy(ctx->st_area_toc);
            sa_free(ctx->st_area_toc);
            ctx->st_area_toc = NULL;

            return (res);
        }
    }

    /* Step 6: Initialize multi-channel (5.1 surround) Area TOC if present on the disc.
     * Clean up any existing multi-channel Area TOC first. */
    if (ctx->mc_area_toc)
    {
        sacd_area_toc_destroy(ctx->mc_area_toc);
        sa_free(ctx->mc_area_toc);
        ctx->mc_area_toc = NULL;
    }

    /* Query the Master TOC for multi-channel area location */
    sacd_master_toc_get_area_toc_sector_range(ctx->master_toc, MULTI_CHANNEL, &area1_start,
                                   &area2_start, &area_length);

    /* Only initialize if multi-channel area exists (area1_start != 0) */
    if (area1_start)
    {
        ctx->current_channel_type = MULTI_CHANNEL;
        ctx->mc_area_toc = (area_toc_t *)sa_malloc(sizeof(area_toc_t));
        if (!ctx->mc_area_toc)
        {
            return SACD_MEMORY_ALLOCATION_ERROR;
        }
        sacd_area_toc_init(ctx->mc_area_toc);

        /* Read and parse the multi-channel Area TOC */
        res = sacd_area_toc_read(ctx->mc_area_toc, area_toc_nr, area1_start,
                                  area2_start, area_length, MULTI_CHANNEL,
                                  ctx->input);
        if (res != SACD_OK)
        {
            /* Multi-channel Area TOC read failed - clean up and return error */
            sacd_input_close(ctx->input);
            ctx->input = NULL;

            /* Also clean up the 2-channel Area TOC if it was successfully initialized */
            if (ctx->st_area_toc)
            {
                sacd_area_toc_destroy(ctx->st_area_toc);
                sa_free(ctx->st_area_toc);
                ctx->st_area_toc = NULL;
            }

            return (res);
        }
    }

    /* Mark reader as fully initialized and ready for use */
    ctx->initialized = true;

    return SACD_OK;
}

/**
 * @brief Closes the SACD reader and releases all TOC resources.
 *
 * Frees all dynamically allocated TOC structures (Master TOC and Area TOCs),
 * closes the underlying read object, and resets the context to an uninitialized state.
 * The context structure itself is NOT freed (use sacd_destroy for that).
 *
 * After calling this function, the context can be re-initialized with sacd_init().
 *
 * @param[in,out] ctx Pointer to the sacd_t context to close
 * @return SACD_OK on success, SACD_INVALID_ARGUMENT if ctx is NULL
 */
int sacd_close(sacd_t *ctx)
{
    if (!ctx)
    {
        return SACD_INVALID_ARGUMENT;
    }

    if (ctx->input) 
    {
        sacd_input_close(ctx->input);
        ctx->input = NULL;
    }

    /* Free Master TOC and its internal structures */
    if (ctx->master_toc)
    {
        sacd_master_toc_destroy(ctx->master_toc);
        sa_free(ctx->master_toc);
    }

    /* Free 2-channel Area TOC and its internal structures */
    if (ctx->st_area_toc)
    {
        sacd_area_toc_destroy(ctx->st_area_toc);
        sa_free(ctx->st_area_toc);
    }

    /* Free multi-channel Area TOC and its internal structures */
    if (ctx->mc_area_toc)
    {
        sacd_area_toc_destroy(ctx->mc_area_toc);
        sa_free(ctx->mc_area_toc);
    }

    /* Reset all pointers to NULL and mark as uninitialized */
    ctx->master_toc = NULL;
    ctx->st_area_toc = NULL;
    ctx->mc_area_toc = NULL;
    ctx->initialized = false;

    return SACD_OK;
}

/**
 * @brief Selects which audio area (channel type) to use for subsequent operations.
 *
 * SACD discs may contain separate 2-channel stereo and multi-channel (5.1) areas.
 * This function selects which area to use for all subsequent track and audio data operations.
 * All API calls that depend on the Area TOC will automatically route to the selected area.
 *
 * @param[in,out] ctx          Pointer to the sacd_t context
 * @param[in]     channel_type Channel type to select (TWO_CHANNEL or MULTI_CHANNEL)
 * @return SACD_OK on success, SACD_UNINITIALIZED or SACD_NOT_AVAILABLE on error
 */
int sacd_select_channel_type(sacd_t *ctx, channel_t channel_type)
{
    if (!ctx || !ctx->initialized)
    {
        return (SACD_UNINITIALIZED);
    }

    /* Verify that the requested channel type is available on this disc */
    switch (channel_type)
    {
    case TWO_CHANNEL:
        if (!ctx->st_area_toc)
        {
            return (SACD_NOT_AVAILABLE);  /* No 2-channel area on this disc */
        }
        break;
    case MULTI_CHANNEL:
        if (!ctx->mc_area_toc)
        {
            return (SACD_NOT_AVAILABLE);  /* No multi-channel area on this disc */
        }
        break;
    default:
        return (SACD_NOT_AVAILABLE);  /* Invalid channel type */
    }

    /* Set the current channel type - all subsequent Area TOC calls will route to this area */
    ctx->current_channel_type = channel_type;
    return SACD_OK;
}

/**
 * @brief Queries which audio areas (channel types) are available on the disc.
 *
 * Returns an array of available channel types. Multi-channel areas are returned first
 * if present, followed by 2-channel.
 *
 * @param[in]     ctx             Pointer to the sacd_t context
 * @param[out]    channel_types   Array to receive available channel types
 * @param[in,out] nr_types        Input: size of channel_types array
 *                                Output: number of available types found
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
int sacd_get_available_channel_types(sacd_t *ctx,
                                              channel_t *channel_types,
                                              uint16_t *nr_types)
{
    uint16_t channel_type_count;

    if (!ctx || !ctx->initialized)
    {
        return (SACD_UNINITIALIZED);
    }

    channel_type_count = 0;

    /* Return early if output array has no capacity */
    if (*nr_types == 0)
    {
        return SACD_OK;
    }

    /* Add multi-channel first if available and there's space in the output array */
    if (*nr_types > 0)
    {
        if (ctx->mc_area_toc)
        {
            channel_types[channel_type_count++] = MULTI_CHANNEL;
        }
    }

    /* Add 2-channel if available and there's space in the output array */
    if (*nr_types > channel_type_count)
    {
        if (ctx->st_area_toc)
        {
            channel_types[channel_type_count++] = TWO_CHANNEL;
        }
    }

    /* Return the actual number of types found */
    *nr_types = channel_type_count;
    return SACD_OK;
}

/* ========================================================================
 * Frame Position Management
 * ======================================================================== */

/**
 * @brief Gets the current playback frame number.
 *
 * Delegates to the currently selected Area TOC to retrieve the frame position.
 *
 * @param[in]  ctx       Pointer to the sacd_t context
 * @param[out] frame_num Pointer to receive the current frame number (75 frames per second)
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
int sacd_get_current_frame_num(sacd_t *ctx, uint32_t *frame_num)
{
    area_toc_t *area_toc;

    if (!ctx || !frame_num)
    {
        return (SACD_INVALID_ARGUMENT);
    }

    if (!ctx->initialized)
    {
        return (SACD_UNINITIALIZED);
    }

    /* Get selected Area TOC and validate it exists */
    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
    {
        return (SACD_NOT_AVAILABLE);
    }

    /* Delegate to the currently selected Area TOC */
    *frame_num = sacd_area_toc_get_current_frame_num(area_toc);
    return SACD_OK;
}

/**
 * @brief Sets the current playback frame number for seeking.
 *
 * Delegates to the currently selected Area TOC to update the frame position.
 *
 * @param[in,out] ctx       Pointer to the sacd_t context
 * @param[in]     frame_num Frame number to seek to (75 frames per second)
 * @return SACD_OK on success, SACD_UNINITIALIZED if reader not initialized
 */
int sacd_set_current_frame_num(sacd_t *ctx, uint32_t frame_num)
{
    area_toc_t *area_toc;

    if (!ctx || !ctx->initialized)
    {
        return (SACD_UNINITIALIZED);
    }

    /* Get selected Area TOC and validate it exists */
    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
    {
        return (SACD_NOT_AVAILABLE);
    }

    /* Delegate to the currently selected Area TOC */
    sacd_area_toc_set_current_frame_num(area_toc, frame_num);
    return SACD_OK;
}

/* ========================================================================
 * Delegation Functions - Master TOC and Area TOC
 * ========================================================================
 *
 * The remaining functions in this file follow a simple delegation pattern:
 *
 * 1. **Disc-Level Functions** delegate to the Master TOC:
 *    - sacd_get_disc_*()
 *    - sacd_get_album_*()
 *    - sacd_get_master_*()
 *    These query disc-wide metadata like catalog numbers, album info, and genres.
 *
 * 2. **Area-Level Functions** delegate to the currently selected Area TOC:
 *    - sacd_get_area_*()
 *    - sacd_get_track_*()
 *    - sacd_get_sample_*()
 *    - sacd_get_frame_*()
 *    - sacd_get_channel_*()
 *    These query area-specific metadata (2-channel or multi-channel) and track information.
 *
 * 3. **Audio Data Functions** delegate to the currently selected Area TOC:
 *    - sacd_get_sound_data()
 *    - sacd_get_supplementary_data()
 *    - sacd_get_sector_nr_*()
 *    These handle audio frame extraction and sector mapping.
 *
 * All functions follow this pattern:
 *    1. Validate ctx and ctx->initialized
 *    2. Call the corresponding master_toc_* or area_toc_* function
 *    3. Return the result
 *
 * The sacd_get_selected_area_toc() helper function automatically routes
 * to either st_area_toc (2-channel) or mc_area_toc (multi-channel) based on
 * the current_channel_type setting.
 * ======================================================================== */

/* ========================================================================
 * Disc-Level Information (Master TOC delegation)
 * ======================================================================== */

/** @brief Delegate to Master TOC: Get SACD spec version */
int sacd_get_disc_spec_version(sacd_t *ctx, uint8_t *major, uint8_t *minor)
{
    sacd_version_t version;

    if (!ctx || !major || !minor)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    version = sacd_master_toc_get_sacd_version(ctx->master_toc);
    *major = version.major;
    *minor = version.minor;
    return SACD_OK;
}

/** @brief Delegate to Master TOC: Get number of discs in album */
int sacd_get_album_disc_count(sacd_t *ctx, uint16_t *num_disc)
{
    if (!ctx || !num_disc)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    *num_disc = sacd_master_toc_get_album_size(ctx->master_toc);
    return SACD_OK;
}

/** @brief Delegate to Master TOC: Get disc sequence number */
int sacd_get_disc_sequence_num(sacd_t *ctx, uint16_t *disc_sequence_num)
{
    if (!ctx || !disc_sequence_num)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    *disc_sequence_num = sacd_master_toc_get_disc_sequence_num(ctx->master_toc);
    return SACD_OK;
}

/** @brief Delegate to Master TOC: Get album catalog number */
int sacd_get_album_catalog_num(sacd_t *ctx, const char **album_catalog_num)
{
    if (!ctx || !album_catalog_num)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    *album_catalog_num = sacd_master_toc_get_album_catalog_num(ctx->master_toc);
    return SACD_OK;
}

/** @brief Delegate to Master TOC: Check if disc is hybrid SACD */
int sacd_get_disc_is_hybrid(sacd_t *ctx, bool *hybrid)
{
    if (!ctx || !hybrid)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    *hybrid = sacd_master_toc_is_disc_hybrid(ctx->master_toc);
    return SACD_OK;
}

/** @brief Delegate to Master TOC: Get manufacturer info */
int sacd_get_disc_manufacturer_info(sacd_t *ctx, const char **manufacturer_info)
{
    if (!ctx || !manufacturer_info)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    *manufacturer_info = sacd_master_toc_get_manufacturer_info(ctx->master_toc);
    return SACD_OK;
}

/** @brief Delegate to Master TOC: Get disc catalog number */
int sacd_get_disc_catalog_num(sacd_t *ctx, const char **disc_catalog_num)
{
    if (!ctx || !disc_catalog_num)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    *disc_catalog_num = sacd_master_toc_get_disc_catalog_num(ctx->master_toc);
    return SACD_OK;
}

/** @brief Delegate to Master TOC: Get album genre (validates 1-4 range) */
int sacd_get_album_genre(sacd_t *ctx, uint16_t genre_nr,
                                   uint8_t *genre_table, uint16_t *genre_index)
{
    if (!ctx || !genre_table || !genre_index)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    if ((genre_nr < 1) || (genre_nr > 4))  /* Albums may have up to 4 genres */
        return (SACD_INVALID_ARGUMENT);

    sacd_master_toc_get_album_genre(ctx->master_toc, genre_nr, genre_table, genre_index);
    return SACD_OK;
}

/** @brief Delegate to Master TOC: Get disc genre (validates 1-4 range) */
int sacd_get_disc_genre(sacd_t *ctx, uint16_t genre_nr,
                                  uint8_t *genre_table, uint16_t *genre_index)
{
    if (!ctx || !genre_table || !genre_index)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    if ((genre_nr < 1) || (genre_nr > 4))  /* Discs may have up to 4 genres */
        return (SACD_INVALID_ARGUMENT);

    sacd_master_toc_get_disc_genre(ctx->master_toc, genre_nr, genre_table, genre_index);
    return SACD_OK;
}

/** @brief Delegate to Master TOC: Get disc date (year/month/day) */
int sacd_get_disc_date(sacd_t *ctx, uint16_t *year,
                                 uint8_t *month, uint8_t *day)
{
    if (!ctx || !year || !month || !day)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    sacd_master_toc_get_disc_date(ctx->master_toc, year, month, day);
    return SACD_OK;
}

/* ========================================================================
 * Disc-Level Text Information (Master TOC delegation)
 * ======================================================================== */

/** @brief Delegate to Area TOC: Get total area play time */
int sacd_get_total_area_play_time(sacd_t *ctx, uint32_t *total_area_play_time)
{
    area_toc_t *area_toc;

    if (!ctx || !total_area_play_time)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    *total_area_play_time = sacd_area_toc_get_total_play_time(area_toc);
    return SACD_OK;
}

/** @brief Delegate to Master TOC: Get number of text channels (languages) */
int sacd_get_master_text_channel_count(sacd_t *ctx, uint8_t *num_text_channels)
{
    if (!ctx || !num_text_channels)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    *num_text_channels = sacd_master_toc_get_text_channel_count(ctx->master_toc);
    return SACD_OK;
}

/** @brief Delegate to Master TOC: Get text channel language and character set */
int sacd_get_master_text_channel_info(sacd_t *ctx, uint8_t text_channel_nr,
                                              const char **language_code, uint8_t *character_set_code)
{
    int res;
    char *temp_language_code;

    if (!ctx || !language_code || !character_set_code || text_channel_nr < 1)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    /* Validate text channel number (1-based, max 8 channels) */
    if ((sacd_master_toc_get_text_channel_count(ctx->master_toc) < text_channel_nr))
        return (SACD_INVALID_ARGUMENT);

    res = sacd_master_toc_get_text_channel_info(ctx->master_toc, text_channel_nr,
                                            &temp_language_code, character_set_code);

    *language_code = temp_language_code;  /* 2-character ISO 639 code */
    return (res);
}

/** @brief Delegate to Master TOC: Get album text (validates channel number) */
int sacd_get_album_text(sacd_t *ctx, uint8_t text_channel_nr,
                                  album_text_type_t text_type, const char **album_text)
{
    if (!ctx || !album_text || text_channel_nr < 1)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    /* Validate text channel number (1-based, max 8 channels) */
    if ((sacd_master_toc_get_text_channel_count(ctx->master_toc) < text_channel_nr))
        return (SACD_INVALID_ARGUMENT);

    *album_text = sacd_master_toc_get_album_text(ctx->master_toc, text_channel_nr, text_type);
    return SACD_OK;
}

/** @brief Delegate to Master TOC: Get disc text (validates channel number) */
int sacd_get_disc_text(sacd_t *ctx, uint8_t text_channel_nr,
                                 album_text_type_t text_type, const char **disc_text)
{
    if (!ctx || !disc_text || text_channel_nr < 1)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    /* Validate text channel number (1-based, max 8 channels) */
    if ((sacd_master_toc_get_text_channel_count(ctx->master_toc) < text_channel_nr))
        return (SACD_INVALID_ARGUMENT);

    *disc_text = sacd_master_toc_get_disc_text(ctx->master_toc, text_channel_nr, text_type);
    return SACD_OK;
}

/* ========================================================================
 * Area-Level Information (Area TOC delegation)
 * ======================================================================== */

/** @brief Delegate to Area TOC: Get SACD spec version for selected area */
int sacd_get_area_spec_version(sacd_t *ctx, uint8_t *major, uint8_t *minor)
{
    area_toc_t *area_toc;
    sacd_version_t version;

    if (!ctx || !major || !minor)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    version = sacd_area_toc_get_version(area_toc);
    *major = version.major;
    *minor = version.minor;
    return SACD_OK;
}

/** @brief Delegate to Area TOC: Get sample frequency (typically 2822400 Hz) */
int sacd_get_area_sample_frequency(sacd_t *ctx, uint32_t *sample_frequency)
{
    area_toc_t *area_toc;

    if (!ctx || !sample_frequency)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    *sample_frequency = sacd_area_toc_get_sample_frequency(area_toc);
    return SACD_OK;
}

/** @brief Delegate to Area TOC: Get sample frequency code (4 = 64*44100 Hz) */
int sacd_get_area_sample_frequency_code(sacd_t *ctx, uint8_t *sample_frequency_code)
{
    area_toc_t *area_toc;

    if (!ctx || !sample_frequency_code)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    *sample_frequency_code = sacd_area_toc_get_sample_frequency_code(area_toc);
    return SACD_OK;
}

/** @brief Delegate to Area TOC: Get frame format (0=DST, 2=DSD 3-in-14, 3=DSD 3-in-16) */
int sacd_get_area_frame_format_code(sacd_t *ctx, uint8_t *frame_format)
{
    area_toc_t *area_toc;

    if (!ctx || !frame_format)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    *frame_format = sacd_area_toc_get_frame_format(area_toc);
    return SACD_OK;
}

/** @brief Delegate to Area TOC: Get maximum byte rate */
int sacd_get_area_max_byte_rate(sacd_t *ctx, uint32_t *max_byte_rate)
{
    area_toc_t *area_toc;

    if (!ctx || !max_byte_rate)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    *max_byte_rate = sacd_area_toc_get_max_byte_rate(area_toc);
    return SACD_OK;
}

/** @brief Delegate to Area TOC: Get loudspeaker configuration */
int sacd_get_area_loudspeaker_config(sacd_t *ctx, uint8_t *loudspeaker_config,
                                          uint8_t *usage_ch4)
{
    area_toc_t *area_toc;

    if (!ctx || !loudspeaker_config || !usage_ch4)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    sacd_area_toc_get_loudspeaker_config(area_toc, loudspeaker_config, usage_ch4);
    return SACD_OK;
}

/* Get area mute flags */
int sacd_get_area_mute_flags(sacd_t *ctx, uint8_t *area_mute_flags)
{
    area_toc_t *area_toc;

    if (!ctx || !area_mute_flags)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    *area_mute_flags = sacd_area_toc_get_mute_flags(area_toc);
    return SACD_OK;
}

/* Get max available channels */
int sacd_get_area_max_available_channels(sacd_t *ctx, uint8_t *max_available_channels)
{
    area_toc_t *area_toc;

    if (!ctx || !max_available_channels)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    *max_available_channels = sacd_area_toc_get_max_available_channels(area_toc);
    return SACD_OK;
}

/* Get area track attribute (copy protection flags) */
int sacd_get_area_track_attribute(sacd_t *ctx, uint8_t *area_track_attribute)
{
    area_toc_t *area_toc;

    if (!ctx || !area_track_attribute)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    *area_track_attribute = sacd_area_toc_get_copy_protection_flags(area_toc);
    return SACD_OK;
}

/* Get number of area text channels */
int sacd_get_area_text_channel_count(sacd_t *ctx, uint8_t *num_text_channels)
{
    area_toc_t *area_toc;

    if (!ctx || !num_text_channels)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    *num_text_channels = sacd_area_toc_get_text_channel_count(area_toc);
    return SACD_OK;
}

/* Get area text channel info */
int sacd_get_area_text_channel_info(sacd_t *ctx, uint8_t text_channel_nr,
                                            const char **language_code, uint8_t *character_set_code)
{
    int res;
    char *temp_language_code;
    area_toc_t *area_toc;

    if (!ctx || !language_code || !character_set_code || text_channel_nr < 1)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    if ((sacd_area_toc_get_text_channel_count(area_toc) < text_channel_nr))
        return (SACD_INVALID_ARGUMENT);

    res = sacd_area_toc_get_text_channel_info(area_toc, text_channel_nr,
                                            &temp_language_code, character_set_code);

    /* Language code is a 2-character string (not null-terminated in structure) */
    *language_code = temp_language_code;

    return (res);
}

/* Get area text */
int sacd_get_area_text(sacd_t *ctx, uint8_t text_channel_nr,
                                 area_text_type_t text_type, const char **area_text)
{
    area_toc_t *area_toc;

    if (!ctx || !area_text || text_channel_nr < 1)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    if ((sacd_area_toc_get_text_channel_count(area_toc) < text_channel_nr))
        return (SACD_INVALID_ARGUMENT);

    *area_text = sacd_area_toc_get_area_text(area_toc, text_channel_nr, text_type);
    return SACD_OK;
}

/* Get number of tracks */
int sacd_get_track_count(sacd_t *ctx, uint8_t *num_tracks)
{
    area_toc_t *area_toc;

    if (!ctx || !num_tracks)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    *num_tracks = sacd_area_toc_get_track_count(area_toc);
    return SACD_OK;
}

/* Get number of track indexes */
int sacd_get_track_index_count(sacd_t *ctx, uint8_t track_num,
                                        uint8_t *num_indexes)
{
    area_toc_t *area_toc;

    if (!ctx || !num_indexes || track_num < 1)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    if ((sacd_area_toc_get_track_count(area_toc) < track_num))
        return (SACD_INVALID_ARGUMENT);

    *num_indexes = sacd_area_toc_get_track_index_count(area_toc, track_num);
    return SACD_OK;
}

/* Get track ISRC number */
int sacd_get_track_isrc_num(sacd_t *ctx, uint8_t track_num,
                                     area_isrc_t *isrc)
{
    area_toc_t *area_toc;

    if (!ctx || !isrc || track_num < 1)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    if ((sacd_area_toc_get_track_count(area_toc) < track_num))
        return (SACD_INVALID_ARGUMENT);

    *isrc = sacd_area_toc_get_track_isrc_num(area_toc, track_num);

    return SACD_OK;
}

/* Get track mode */
int sacd_get_track_mode(sacd_t *ctx, uint8_t track_num,
                                  uint8_t *track_mode)
{
    area_toc_t *area_toc;

    if (!ctx || !track_mode || track_num < 1)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    if ((sacd_area_toc_get_track_count(area_toc) < track_num))
        return (SACD_INVALID_ARGUMENT);

    *track_mode = sacd_area_toc_get_track_mode(area_toc, track_num);
    return SACD_OK;
}

/* Get track flags */
int sacd_get_track_flags(sacd_t *ctx, uint8_t track_num,
                                   bool *track_flag_tmf1,
                                   bool *track_flag_tmf2,
                                   bool *track_flag_tmf3,
                                   bool *track_flag_tmf4,
                                   bool *track_flag_ilp)
{
    area_toc_t *area_toc;

    if (!ctx || !track_flag_tmf1 || !track_flag_tmf2 || !track_flag_tmf3 ||
        !track_flag_tmf4 || !track_flag_ilp || track_num < 1)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    if ((sacd_area_toc_get_track_count(area_toc) < track_num))
        return (SACD_INVALID_ARGUMENT);

    *track_flag_tmf1 = sacd_area_toc_get_track_flag_mute1(area_toc, track_num);
    *track_flag_tmf2 = sacd_area_toc_get_track_flag_mute2(area_toc, track_num);
    *track_flag_tmf3 = sacd_area_toc_get_track_flag_mute3(area_toc, track_num);
    *track_flag_tmf4 = sacd_area_toc_get_track_flag_mute4(area_toc, track_num);
    *track_flag_ilp = sacd_area_toc_get_track_flag_ilp(area_toc, track_num);

    return SACD_OK;
}

/* Get track genre */
int sacd_get_track_genre(sacd_t *ctx, uint8_t track_num,
                                   uint8_t *genre_table, uint16_t *genre_index)
{
    area_toc_t *area_toc;

    if (!ctx || !genre_table || !genre_index || track_num < 1)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    if ((sacd_area_toc_get_track_count(area_toc) < track_num))
        return (SACD_INVALID_ARGUMENT);

    sacd_area_toc_get_track_genre(area_toc, track_num, genre_table, genre_index);
    return SACD_OK;
}

/* Get track text */
int sacd_get_track_text(sacd_t *ctx, uint8_t track_num,
                                  uint8_t text_channel_nr, track_type_t text_item,
                                  const char **track_text)
{
    bool available;
    char *temp_track_text;
    area_toc_t *area_toc;

    if (!ctx || !track_text || track_num < 1)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    if ((sacd_area_toc_get_track_count(area_toc) < track_num))
        return (SACD_INVALID_ARGUMENT);

    temp_track_text = sacd_area_toc_get_track_text(area_toc, track_num, text_channel_nr,
                                     text_item, &available);
    *track_text = temp_track_text;

    if (!available)
        return (SACD_ITEM_NOT_AVAILABLE);

    return SACD_OK;
}

/* Get track sectors */
int sacd_get_track_sectors(sacd_t *ctx, uint8_t track_num,
                                     uint32_t *start_sector_nr, uint32_t *num_sectors)
{
    area_toc_t *area_toc;

    if (!ctx || !start_sector_nr || !num_sectors || track_num < 1)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    if ((sacd_area_toc_get_track_count(area_toc) < track_num))
        return (SACD_INVALID_ARGUMENT);

    sacd_area_toc_get_track_sectors(area_toc, track_num, start_sector_nr, num_sectors);
    return SACD_OK;
}

/* Get sector number for track area */
int sacd_get_track_area_sector_range(sacd_t *ctx, channel_t area_type,
                                          uint32_t *track_area_start,
                                          uint16_t *track_area_length)
{
    uint32_t area1_start;
    uint32_t area2_start;

    if (!ctx || !track_area_start || !track_area_length)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    sacd_master_toc_get_area_toc_sector_range(ctx->master_toc, area_type, &area1_start,
                                   &area2_start, track_area_length);

    if (ctx->area_toc_num == 2)
        *track_area_start = area2_start;
    else
        *track_area_start = area1_start;

    return SACD_OK;
}

/* Get track frame length */
int sacd_get_track_frame_length(sacd_t *ctx, uint8_t track_num,
                                         uint32_t *track_frame_length)
{
    area_toc_t *area_toc;

    if (!ctx || !track_frame_length || track_num < 1)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    if ((sacd_area_toc_get_track_count(area_toc) < track_num))
        return (SACD_INVALID_ARGUMENT);

    *track_frame_length = sacd_area_toc_get_track_frame_length(area_toc, track_num);
    return SACD_OK;
}

/* Get index start */
int sacd_get_track_index_start(sacd_t *ctx, uint8_t track_num,
                                   uint8_t index_num, uint32_t *index_start)
{
    area_toc_t *area_toc;

    if (!ctx || !index_start || track_num < 1 || index_num < 1)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    if ((sacd_area_toc_get_track_count(area_toc) < track_num))
        return (SACD_INVALID_ARGUMENT);

    if ((sacd_area_toc_get_track_index_count(area_toc, track_num) < index_num))
        return (SACD_INVALID_ARGUMENT);

    *index_start = sacd_area_toc_get_index_start(area_toc, track_num, index_num);
    return SACD_OK;
}

/* Get index end */
int sacd_get_track_index_end(sacd_t *ctx, uint8_t track_num,
                                 uint8_t index_num, uint32_t *index_end)
{
    area_toc_t *area_toc;

    if (!ctx || !index_end || track_num < 1 || index_num < 1)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    if ((sacd_area_toc_get_track_count(area_toc) < track_num))
        return (SACD_INVALID_ARGUMENT);

    if ((sacd_area_toc_get_track_index_count(area_toc, track_num) < index_num))
        return (SACD_INVALID_ARGUMENT);

    *index_end = sacd_area_toc_get_index_end(area_toc, track_num, index_num);
    return SACD_OK;
}

/* Get track pause */
int sacd_get_track_pause(sacd_t *ctx, uint8_t track_num,
                                   uint32_t *track_pause)
{
    area_toc_t *area_toc;

    if (!ctx || !track_pause || track_num < 1)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    if ((sacd_area_toc_get_track_count(area_toc) < track_num))
        return (SACD_INVALID_ARGUMENT);

    *track_pause = sacd_area_toc_get_track_pause(area_toc, track_num);
    return SACD_OK;
}

/* Get frame type */
int sacd_get_area_frame_format_enum(sacd_t *ctx, frame_format_t *frame_type)
{
    area_toc_t *area_toc;

    if (!ctx || !frame_type)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    *frame_type = sacd_area_toc_get_frame_format_enum(area_toc);
    return SACD_OK;
}

/* Get number of channels */
int sacd_get_area_channel_count(sacd_t *ctx, uint16_t *channel_count)
{
    area_toc_t *area_toc;

    if (!ctx || !channel_count)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    *channel_count = sacd_area_toc_get_channel_count(area_toc);
    return SACD_OK;
}

/* Get track offset */
int sacd_get_area_track_offset(sacd_t *ctx, uint8_t *track_offset)
{
    area_toc_t *area_toc;

    if (!ctx || !track_offset)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    *track_offset = sacd_area_toc_get_track_offset(area_toc);
    return SACD_OK;
}

/* Get sector number for frame */
int sacd_get_frame_sector_range(sacd_t *ctx, uint32_t frame_nr,
                                      uint32_t *start_sector_nr, int *num_sectors)
{
    area_toc_t *area_toc;

    if (!ctx || !start_sector_nr || !num_sectors)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    if (sacd_area_toc_get_total_play_time(area_toc) < frame_nr)
        return (SACD_INVALID_ARGUMENT);

    return sacd_area_toc_get_frame_sector_range(area_toc, frame_nr, start_sector_nr, num_sectors);
}

/* Get total sectors on disc */
int sacd_get_total_sectors(sacd_t *ctx, uint32_t *total_sectors)
{
    if (!ctx || !total_sectors)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->input)
        return (SACD_UNINITIALIZED);

    *total_sectors = sacd_input_total_sectors(ctx->input);
    return (SACD_OK);
}

/* Read raw sectors from disc */
int sacd_read_raw_sectors(sacd_t *ctx, uint32_t sector_pos,
                                 uint32_t sector_count, uint8_t *buffer,
                                 uint32_t *sectors_read)
{
    if (!ctx || !buffer || !sectors_read)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->input)
        return (SACD_UNINITIALIZED);

    int ret = sacd_input_read_sectors(ctx->input, sector_pos, sector_count, buffer, sectors_read);
    if (ret == SACD_INPUT_OK && ctx->input->ops->decrypt) {
        area_toc_t *areas[] = { ctx->st_area_toc, ctx->mc_area_toc };
        for (int i = 0; i < 2; i++) {
            if (!areas[i])
                continue;
            if (sacd_area_toc_get_frame_format_enum(areas[i]) != FRAME_FORMAT_DST)
                continue;
            uint32_t startlsn = areas[i]->track_area_start;
            uint32_t endlsn = areas[i]->track_area_end;
            if (sector_pos >= startlsn && sector_pos <= endlsn) {
                sacd_input_decrypt(ctx->input, buffer, *sectors_read);
                break;
            }
        }
    }

    return (ret == SACD_INPUT_OK) ? SACD_OK : SACD_IO_ERROR;
}

/* Get sound data */
int sacd_get_sound_data(sacd_t *ctx, uint8_t *data,
                                  uint32_t frame_nr_start, uint32_t *frame_count,
                                  uint16_t *frame_size)
{
    uint32_t frames, total_frame_size, length;
    area_toc_t *area_toc;
    int res = SACD_OK;

    if (!ctx || !data || !frame_count)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    if (sacd_area_toc_get_total_play_time(area_toc) < (frame_nr_start + *frame_count))
    {
        return (SACD_INVALID_ARGUMENT);
    }

    frame_format_t frame_format = sacd_area_toc_get_frame_format_enum(area_toc);
    if ((frame_format == 0) && (frame_size == NULL))
    {
        return SACD_INVALID_ARGUMENT;
    }

    frames = 0;
    total_frame_size = 0;

    while (frames < *frame_count)
    {
        uint32_t cur_frame_num = (frame_nr_start != FRAME_START_USE_CURRENT)
                                  ? frame_nr_start + frames
                                  : FRAME_START_USE_CURRENT;

        length = (4704 + 1) * sacd_area_toc_get_channel_count(area_toc);

        res = sacd_area_toc_get_audio_data(area_toc, data + total_frame_size, &length,
                                       cur_frame_num, DATA_TYPE_AUDIO);
        if (res != SACD_OK)
        {
            break;
        }

        total_frame_size += length;
        if (frame_size)
        {
            frame_size[frames] = (uint16_t)length;
        }
        frames++;
    }

    *frame_count = frames;
    return (res);
}

/* Get supplementary data */
int sacd_get_supplementary_data(sacd_t *ctx, uint8_t *data,
                                          uint32_t frame_nr_start, uint32_t *frame_count,
                                          uint16_t *frame_size)
{
    uint32_t frames, total_frame_size, length;
    area_toc_t *area_toc;
    int res = SACD_OK;

    if (!ctx || !data || !frame_count || !frame_size)
        return (SACD_INVALID_ARGUMENT);

    if (!ctx->initialized)
        return (SACD_UNINITIALIZED);

    area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return (SACD_NOT_AVAILABLE);

    if (sacd_area_toc_get_total_play_time(area_toc) < (frame_nr_start + *frame_count))
    {
        return (SACD_INVALID_ARGUMENT);
    }

    frames = 0;
    total_frame_size = 0;

    while (frames < *frame_count)
    {
        uint32_t cur_frame_num = (frame_nr_start != FRAME_START_USE_CURRENT)
                                  ? frame_nr_start + frames
                                  : FRAME_START_USE_CURRENT;

        length = 4704 * 8;
        res = sacd_area_toc_get_audio_data(area_toc, data + total_frame_size, &length,
                                       cur_frame_num, DATA_TYPE_SUPPLEMENTARY);
        if (res != SACD_OK)
        {
            break;
        }

        total_frame_size += length;
        frame_size[frames++] = (uint16_t)length;
    }

    *frame_count = frames;
    return (res);
}

/* ========================================================================
 * Helper Functions (Filename and Path Generation)
 * ======================================================================== */

const char *sacd_get_speaker_config_string(sacd_t *ctx)
{
    if (!ctx || !ctx->initialized)
        return "Unknown";

    area_toc_t *area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return "Unknown";

    return sacd_area_toc_get_speaker_config_string(area_toc);
}

const char *sacd_get_frame_format_string(sacd_t *ctx)
{
    if (!ctx || !ctx->initialized)
        return "Unknown";

    area_toc_t *area_toc = sacd_get_selected_area_toc(ctx);
    if (!area_toc)
        return "Unknown";

    return sacd_area_toc_get_frame_format_string(area_toc);
}

char *sacd_get_album_dir(sacd_t *ctx, sacd_path_format_t format,
                                uint8_t text_channel)
{
    if (!ctx || !ctx->initialized || !ctx->master_toc)
        return NULL;

    /* The public enum values match the internal enum values */
    return sacd_master_toc_get_album_dir(ctx->master_toc,
                                         (master_toc_path_format_t)format,
                                         text_channel);
}

char *sacd_get_album_path(sacd_t *ctx, sacd_path_format_t format,
                                 uint8_t text_channel)
{
    if (!ctx || !ctx->initialized || !ctx->master_toc)
        return NULL;

    /* The public enum values match the internal enum values */
    return sacd_master_toc_get_album_path(ctx->master_toc,
                                          (master_toc_path_format_t)format,
                                          text_channel);
}
