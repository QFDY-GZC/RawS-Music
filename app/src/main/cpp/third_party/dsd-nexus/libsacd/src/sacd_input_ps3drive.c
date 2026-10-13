/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief PS3 Drive input implementation for SACD reading using libps3drive.
 * This implementation uses libps3drive for all drive operations including
 * authentication, key exchange, reading, and decryption.
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

#include "sacd_input.h"

#include <libps3drive/ps3drive.h>

#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/* ============================================================================
 * Device Structure
 * ============================================================================ */

/**
 * @struct sacd_input_ps3drive_t
 * @brief Structure for PS3 drive input.
 *
 * The base struct MUST be the first member for safe casting.
 */
typedef struct sacd_input_ps3drive_s {
    sacd_input_t    base;           /**< Base structure (must be first!) */
    ps3drive_t     *drive;          /**< libps3drive handle */
    uint32_t        total_sectors;  /**< Cached total sectors */
    bool            authenticated;  /**< True if authentication completed */
    bool            keys_exchanged; /**< True if SAC key exchange completed */
} sacd_input_ps3drive_t;

/* Forward declarations of vtable functions */
static int          _ps3drive_close(sacd_input_t *self);
static uint32_t     _ps3drive_total_sectors(sacd_input_t *self);
static int          _ps3drive_authenticate(sacd_input_t *self);
static int          _ps3drive_decrypt(sacd_input_t *self, uint8_t *buffer,
                                      uint32_t block_count);
static const char  *_ps3drive_get_error(sacd_input_t *self);

/* Sector format methods - PS3 drives always provide 2048-byte sectors */
static int          _ps3drive_get_sector_format(sacd_input_t *self,
                                                 sacd_sector_format_t *format);
static int          _ps3drive_get_sector_size(sacd_input_t *self, uint32_t *size);
static int          _ps3drive_get_header_size(sacd_input_t *self, int16_t *size);
static int          _ps3drive_get_trailer_size(sacd_input_t *self, int16_t *size);
static int          _ps3drive_read_sectors(sacd_input_t *self, uint32_t sector_pos,
                                            uint32_t sector_count, void *buffer,
                                            uint32_t *sectors_read);

/**
 * @brief Static vtable for PS3 drive input instances.
 */
static const sacd_input_ops_t _ps3drive_input_ops = {
    .close             = _ps3drive_close,
    .read_sectors      = _ps3drive_read_sectors,
    .total_sectors     = _ps3drive_total_sectors,
    .authenticate      = _ps3drive_authenticate,
    .decrypt           = _ps3drive_decrypt,
    .get_error         = _ps3drive_get_error,
    /* Sector format methods */
    .get_sector_format = _ps3drive_get_sector_format,
    .get_sector_size   = _ps3drive_get_sector_size,
    .get_header_size   = _ps3drive_get_header_size,
    .get_trailer_size  = _ps3drive_get_trailer_size,
};

/* ============================================================================
 * Public API Implementation
 * ============================================================================ */

/**
 * @brief Open a PS3 drive device.
 */
int sacd_input_open_device(const char *device, sacd_input_t **out)
{
    sacd_input_ps3drive_t *self;
    ps3drive_error_t ret;

    if (!device || !out) {
        return SACD_INPUT_ERR_INVALID_ARG;
    }

    *out = NULL;

    /* Allocate structure */
    self = (sacd_input_ps3drive_t *)calloc(1, sizeof(*self));
    if (!self) {
        return SACD_INPUT_ERR_OUT_OF_MEMORY;
    }

    /* Initialize base */
    self->base.ops  = &_ps3drive_input_ops;
    self->base.type = SACD_INPUT_TYPE_DEVICE;
    self->base.last_error = SACD_INPUT_OK;

    /* Open drive using libps3drive */
    ret = ps3drive_open(device, &self->drive);
    if (ret != PS3DRIVE_OK) {
        snprintf(self->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                 "failed to open PS3 drive: %s", ps3drive_error_string(ret));
        self->base.last_error = SACD_INPUT_ERR_OPEN_FAILED;
        free(self);
        return SACD_INPUT_ERR_OPEN_FAILED;
    }

    /* Get total sectors */
    ret = ps3drive_get_total_sectors(self->drive, &self->total_sectors);
    if (ret != PS3DRIVE_OK) {
        snprintf(self->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                 "failed to get disc capacity: %s", ps3drive_error_string(ret));
        self->base.last_error = SACD_INPUT_ERR_READ_FAILED;
        ps3drive_close(self->drive);
        free(self);
        return SACD_INPUT_ERR_READ_FAILED;
    }

    *out = (sacd_input_t *)self;
    return SACD_INPUT_OK;
}

/**
 * @brief Close PS3 drive and free resources.
 */
static int _ps3drive_close(sacd_input_t *self)
{
    sacd_input_ps3drive_t *pself = (sacd_input_ps3drive_t *)self;

    if (!pself) {
        return SACD_INPUT_ERR_NULL_PTR;
    }

    if (pself->drive) {
        ps3drive_close(pself->drive);
        pself->drive = NULL;
    }

    free(pself);
    return SACD_INPUT_OK;
}


/**
 * @brief Get total number of sectors.
 */
static uint32_t _ps3drive_total_sectors(sacd_input_t *self)
{
    sacd_input_ps3drive_t *pself = (sacd_input_ps3drive_t *)self;

    if (!pself) {
        return 0;
    }

    return pself->total_sectors;
}

/**
 * @brief Authenticate with PS3 drive for encrypted SACD access.
 *
 * Performs BD authentication followed by SAC key exchange for decryption.
 */
static int _ps3drive_authenticate(sacd_input_t *self)
{
    sacd_input_ps3drive_t *pself = (sacd_input_ps3drive_t *)self;
    ps3drive_error_t ret;

    if (!pself || !pself->drive) {
        return SACD_INPUT_ERR_NULL_PTR;
    }

    /* BD authentication must be done first */
    ret = ps3drive_authenticate(pself->drive);
    if (ret != PS3DRIVE_OK) {
        snprintf(pself->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                 "BD authentication failed: %s", ps3drive_get_error(pself->drive));
        pself->base.last_error = SACD_INPUT_ERR_AUTH_FAILED;
        return SACD_INPUT_ERR_AUTH_FAILED;
    }

    pself->authenticated = true;

    /* SAC key exchange for SACD decryption */
    ret = ps3drive_sac_key_exchange(pself->drive, NULL, NULL);
    if (ret != PS3DRIVE_OK) {
        snprintf(pself->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                 "SAC key exchange failed: %s", ps3drive_get_error(pself->drive));
        pself->base.last_error = SACD_INPUT_ERR_AUTH_FAILED;
        return SACD_INPUT_ERR_AUTH_FAILED;
    }

    pself->keys_exchanged = true;

    /* Update total sectors (may have changed after layer selection) */
    ps3drive_get_total_sectors(pself->drive, &pself->total_sectors);

    return SACD_INPUT_OK;
}

/**
 * @brief Decrypt sector data using exchanged keys.
 */
static int _ps3drive_decrypt(sacd_input_t *self, uint8_t *buffer,
                             uint32_t block_count)
{
    sacd_input_ps3drive_t *pself = (sacd_input_ps3drive_t *)self;
    ps3drive_error_t ret;

    if (!pself || !pself->drive || !buffer) {
        return SACD_INPUT_ERR_NULL_PTR;
    }

    if (!pself->keys_exchanged) {
        snprintf(pself->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                 "decrypt called before key exchange");
        pself->base.last_error = SACD_INPUT_ERR_AUTH_FAILED;
        return SACD_INPUT_ERR_AUTH_FAILED;
    }

    ret = ps3drive_decrypt(pself->drive, buffer, block_count);
    if (ret != PS3DRIVE_OK) {
        snprintf(pself->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                 "decryption failed: %s", ps3drive_get_error(pself->drive));
        pself->base.last_error = SACD_INPUT_ERR_READ_FAILED;
        return SACD_INPUT_ERR_READ_FAILED;
    }

    return SACD_INPUT_OK;
}

/**
 * @brief Get error message.
 */
static const char *_ps3drive_get_error(sacd_input_t *self)
{
    sacd_input_ps3drive_t *pself = (sacd_input_ps3drive_t *)self;

    if (!pself) {
        return "null pointer";
    }

    if (pself->base.error_msg[0]) {
        return pself->base.error_msg;
    }

    if (pself->drive) {
        return ps3drive_get_error(pself->drive);
    }

    return sacd_input_error_string(pself->base.last_error);
}

/* ============================================================================
 * Sector Format Methods
 *
 * PS3 drives always provide 2048-byte sectors with no headers/trailers.
 * The drive hardware strips any headers/trailers from the raw disc format.
 * ============================================================================ */

/**
 * @brief Get sector format - always 2048 for PS3 drives.
 */
static int _ps3drive_get_sector_format(sacd_input_t *self,
                                        sacd_sector_format_t *format)
{
    if (!self || !format) {
        return SACD_INPUT_ERR_NULL_PTR;
    }
    *format = SACD_SECTOR_2048;
    return SACD_INPUT_OK;
}

/**
 * @brief Get sector size - always 2048 for PS3 drives.
 */
static int _ps3drive_get_sector_size(sacd_input_t *self, uint32_t *size)
{
    if (!self || !size) {
        return SACD_INPUT_ERR_NULL_PTR;
    }
    *size = SACD_LSN_SIZE;
    return SACD_INPUT_OK;
}

/**
 * @brief Get header size - always 0 for PS3 drives.
 */
static int _ps3drive_get_header_size(sacd_input_t *self, int16_t *size)
{
    if (!self || !size) {
        return SACD_INPUT_ERR_NULL_PTR;
    }
    *size = 0;
    return SACD_INPUT_OK;
}

/**
 * @brief Get trailer size - always 0 for PS3 drives.
 */
static int _ps3drive_get_trailer_size(sacd_input_t *self, int16_t *size)
{
    if (!self || !size) {
        return SACD_INPUT_ERR_NULL_PTR;
    }
    *size = 0;
    return SACD_INPUT_OK;
}

/**
 * @brief Read sectors from PS3 drive.
 */
static int _ps3drive_read_sectors(sacd_input_t *self, uint32_t sector_pos,
                                   uint32_t sector_count, void *buffer,
                                   uint32_t *sectors_read)
{
    sacd_input_ps3drive_t *pself = (sacd_input_ps3drive_t *)self;

    if (!pself || !buffer || !sectors_read) {
        if (sectors_read) *sectors_read = 0;
        return SACD_INPUT_ERR_NULL_PTR;
    }

    if (!pself->drive || sector_count == 0) {
        *sectors_read = 0;
        return (sector_count == 0) ? SACD_INPUT_OK : SACD_INPUT_ERR_NULL_PTR;
    }

    *sectors_read = ps3drive_read_sectors(pself->drive, sector_pos, sector_count, buffer);

    return (*sectors_read == sector_count) ? SACD_INPUT_OK : SACD_INPUT_ERR_READ_FAILED;
}
