/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief File-based input implementation for SACD reading.
 * This implementation reads sector data from disc image files (ISO format).
 * Supports 64-bit file sizes on both Windows and POSIX platforms.
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
#include "sacd_specification.h"

#include <libsautil/mem.h>
#include <libsautil/compat.h>

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdarg.h>
#include <errno.h>

/**
 * @struct sacd_input_file_t
 * @brief Extended structure for file-based input.
 *
 * The base struct MUST be the first member for safe casting.
 */
typedef struct sacd_input_file {
    sacd_input_t base;              /**< Base structure (must be first!) */
    FILE        *fp;                /**< File pointer */
    uint64_t     file_size;         /**< File size in bytes */
    sacd_sector_format_t sector_format; /**< Detected sector format */
    bool         format_detected;   /**< True if format has been detected */
} sacd_input_file_t;

/* Forward declarations of vtable functions */
static int          _file_close(sacd_input_t *self);
static uint32_t     _file_total_sectors(sacd_input_t *self);
static const char  *_file_get_error(sacd_input_t *self);

/* Sector format methods */
static int          _file_get_sector_format(sacd_input_t *self,
                                            sacd_sector_format_t *format);
static int          _file_get_sector_size(sacd_input_t *self, uint32_t *size);
static int          _file_get_header_size(sacd_input_t *self, int16_t *size);
static int          _file_get_trailer_size(sacd_input_t *self, int16_t *size);
static int          _file_read_sectors(sacd_input_t *self, uint32_t sector_pos,
                                       uint32_t sector_count, void *buffer,
                                       uint32_t *sectors_read);

/**
 * @brief Static vtable for file input instances.
 */
static const sacd_input_ops_t _file_input_ops = {
    .close             = _file_close,
    .read_sectors      = _file_read_sectors,
    .total_sectors     = _file_total_sectors,
    .authenticate      = NULL,  /* Not supported for files */
    .decrypt           = NULL,  /* Not supported for files */
    .get_error         = _file_get_error,
    /* Sector format methods */
    .get_sector_format = _file_get_sector_format,
    .get_sector_size   = _file_get_sector_size,
    .get_header_size   = _file_get_header_size,
    .get_trailer_size  = _file_get_trailer_size,
};

/**
 * @brief Set error message with snprintf safety.
 */
static void _file_set_error(sacd_input_file_t *self, sacd_input_error_t code,
                            const char *fmt, ...)
{
    va_list args;
    self->base.last_error = code;

    va_start(args, fmt);
    vsnprintf(self->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE, fmt, args);
    va_end(args);
}

/**
 * @brief Open a file-based input.
 */
int sacd_input_open_file(const char *path, sacd_input_t **out)
{
    sacd_input_file_t *self;
    int64_t size;

    if (!path || !out) {
        return SACD_INPUT_ERR_INVALID_ARG;
    }

    *out = NULL;

    /* Allocate structure */
    self = (sacd_input_file_t *)sa_calloc(1, sizeof(*self));
    if (!self) {
        return SACD_INPUT_ERR_OUT_OF_MEMORY;
    }

    /* Initialize base */
    self->base.ops  = &_file_input_ops;
    self->base.type = SACD_INPUT_TYPE_FILE;
    self->base.last_error = SACD_INPUT_OK;
    self->fp = NULL;

    /* Open file (sa_fopen handles UTF-8 paths on Windows) */
    self->fp = sa_fopen(path, "rb");
    if (!self->fp) {
        _file_set_error(self, SACD_INPUT_ERR_OPEN_FAILED,
                        "failed to open file: %s (%s)", path, strerror(errno));
        sa_free(self);
        return SACD_INPUT_ERR_OPEN_FAILED;
    }

    /* Get file size via seek */
    if (sa_fseek64(self->fp, 0, SEEK_END) != 0) {
        _file_set_error(self, SACD_INPUT_ERR_OPEN_FAILED,
                        "failed to seek file: %s (%s)", path, strerror(errno));
        fclose(self->fp);
        sa_free(self);
        return SACD_INPUT_ERR_OPEN_FAILED;
    }

    size = sa_ftell64(self->fp);
    if (size < 0) {
        _file_set_error(self, SACD_INPUT_ERR_OPEN_FAILED,
                        "failed to get file size: %s (%s)", path, strerror(errno));
        fclose(self->fp);
        sa_free(self);
        return SACD_INPUT_ERR_OPEN_FAILED;
    }
    self->file_size = (uint64_t)size;

    /* Seek back to beginning */
    sa_fseek64(self->fp, 0, SEEK_SET);

    *out = (sacd_input_t *)self;
    return SACD_INPUT_OK;
}

/**
 * @brief Close file and free resources.
 */
static int _file_close(sacd_input_t *self)
{
    sacd_input_file_t *fself = (sacd_input_file_t *)self;

    if (!fself) {
        return SACD_INPUT_ERR_NULL_PTR;
    }

    if (fself->fp) {
        fclose(fself->fp);
        fself->fp = NULL;
    }

    sa_free(fself);
    return SACD_INPUT_OK;
}

/**
 * @brief Get total number of sectors in file.
 */
static uint32_t _file_total_sectors(sacd_input_t *self)
{
    sacd_input_file_t *fself = (sacd_input_file_t *)self;

    if (!fself) {
        return 0;
    }

    return (uint32_t)(fself->file_size / SACD_LSN_SIZE);
}

/**
 * @brief Get error message.
 */
static const char *_file_get_error(sacd_input_t *self)
{
    if (!self) {
        return "null pointer";
    }

    if (self->error_msg[0]) {
        return self->error_msg;
    }

    return sacd_input_error_string(self->last_error);
}

/**
 * @brief Read bytes at a specific offset.
 *
 * Reads raw bytes from the file at the specified byte offset.
 */
static size_t _file_read_bytes(sacd_input_t *self, uint64_t offset,
                               size_t size, void *buffer)
{
    sacd_input_file_t *fself = (sacd_input_file_t *)self;

    if (!fself || !buffer || size == 0) {
        return 0;
    }

    /* Check bounds */
    if (offset >= fself->file_size) {
        fself->base.last_error = SACD_INPUT_ERR_EOF;
        return 0;
    }

    /* Clamp to available bytes */
    if (offset + size > fself->file_size) {
        size = (size_t)(fself->file_size - offset);
    }

    if (sa_fseek64(fself->fp, (int64_t)offset, SEEK_SET) != 0) {
        fself->base.last_error = SACD_INPUT_ERR_SEEK_FAILED;
        snprintf(fself->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                 "seek failed at offset %llu", (unsigned long long)offset);
        return 0;
    }

    {
        size_t bytes_read = fread(buffer, 1, size, fself->fp);
        if (bytes_read == 0 && ferror(fself->fp)) {
            fself->base.last_error = SACD_INPUT_ERR_READ_FAILED;
            snprintf(fself->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                     "read failed at offset %llu", (unsigned long long)offset);
            return 0;
        }

        return bytes_read;
    }
}


/* ============================================================================
 * Sector Format Support
 * ============================================================================ */

/**
 * @brief Sector format properties lookup table.
 */
typedef struct {
    uint32_t sector_size;  /**< Total sector size in bytes */
    int16_t  header_size;  /**< Header size in bytes */
    int16_t  trailer_size; /**< Trailer size in bytes */
} sector_format_info_t;

/**
 * @brief Lookup table for sector format properties.
 * Indexed by sacd_sector_format_t enum values.
 */
static const sector_format_info_t _sector_format_table[] = {
    { FS_SECTOR_SIZE_48, FS_HEADER_48, FS_TRAILER_48 }, /* SACD_SECTOR_2048 */
    { FS_SECTOR_SIZE_54, FS_HEADER_54, FS_TRAILER_54 }, /* SACD_SECTOR_2054 */
    { FS_SECTOR_SIZE_64, FS_HEADER_64, FS_TRAILER_64 }  /* SACD_SECTOR_2064 */
};

/**
 * @brief Check for SACD signature at a specific format offset.
 *
 * Reads from sector 510 (Master TOC location) and checks for the signature.
 *
 * @param[in]  fself   File input structure
 * @param[in]  format  Sector format to test
 * @return true if SACD signature found, false otherwise
 */
static bool _file_check_sacd_signature(sacd_input_file_t *fself,
                                        sacd_sector_format_t format)
{
    uint8_t buffer[20];
    uint64_t offset;
    size_t bytes_to_read;
    size_t signature_offset;
    size_t bytes_read;
    uint32_t sector_size;
    int16_t header_size;

    if (format > SACD_SECTOR_2064) {
        return false;
    }

    sector_size = _sector_format_table[format].sector_size;
    header_size = _sector_format_table[format].header_size;

    /* Calculate offset to sector 510 for this format */
    offset = (uint64_t)MASTER_TOC1_START * sector_size;

    /* Determine how much to read and where signature is */
    if (header_size > 0) {
        bytes_to_read = (size_t)header_size + 8;  /* header + signature */
        signature_offset = (size_t)header_size;
    } else {
        bytes_to_read = 8;  /* Just the signature */
        signature_offset = 0;
    }

    /* Read using the existing read_bytes function */
    bytes_read = _file_read_bytes((sacd_input_t *)fself, offset,
                                   bytes_to_read, buffer);
    if (bytes_read != bytes_to_read) {
        return false;
    }

    /* Check for SACD Master TOC signature */
    if (*(uint64_t *)(buffer + signature_offset) == MASTER_TOC_SIGN) {
        return true;
    }

    return false;
}

/**
 * @brief Detect the SACD sector format of the file.
 *
 * Tries each format in order (2064, 2054, 2048) until signature is found.
 *
 * @param[in]  fself  File input structure
 * @return true if a valid format was detected, false otherwise
 */
static bool _file_detect_sector_format(sacd_input_file_t *fself)
{
    /* Try formats in order: 2064, 2054, 2048 */
    static const sacd_sector_format_t formats[] = {
        SACD_SECTOR_2064,
        SACD_SECTOR_2054,
        SACD_SECTOR_2048
    };

    for (size_t i = 0; i < sizeof(formats) / sizeof(formats[0]); i++) {
        if (_file_check_sacd_signature(fself, formats[i])) {
            fself->sector_format = formats[i];
            fself->format_detected = true;
            return true;
        }
    }

    /* Default to 2048 if no signature found (might not be SACD) */
    fself->sector_format = SACD_SECTOR_2048;
    fself->format_detected = false;
    return false;
}

/**
 * @brief Get the sector format of the file.
 */
static int _file_get_sector_format(sacd_input_t *self,
                                    sacd_sector_format_t *format)
{
    sacd_input_file_t *fself = (sacd_input_file_t *)self;

    if (!fself || !format) {
        return SACD_INPUT_ERR_NULL_PTR;
    }

    /* Detect format on first call if not already done */
    if (!fself->format_detected) {
        _file_detect_sector_format(fself);
    }

    *format = fself->sector_format;
    return SACD_INPUT_OK;
}

/**
 * @brief Get the raw sector size for the detected format.
 */
static int _file_get_sector_size(sacd_input_t *self, uint32_t *size)
{
    sacd_input_file_t *fself = (sacd_input_file_t *)self;

    if (!fself || !size) {
        return SACD_INPUT_ERR_NULL_PTR;
    }

    /* Detect format on first call if not already done */
    if (!fself->format_detected) {
        _file_detect_sector_format(fself);
    }

    *size = _sector_format_table[fself->sector_format].sector_size;
    return SACD_INPUT_OK;
}

/**
 * @brief Get the header size for the detected format.
 */
static int _file_get_header_size(sacd_input_t *self, int16_t *size)
{
    sacd_input_file_t *fself = (sacd_input_file_t *)self;

    if (!fself || !size) {
        return SACD_INPUT_ERR_NULL_PTR;
    }

    /* Detect format on first call if not already done */
    if (!fself->format_detected) {
        _file_detect_sector_format(fself);
    }

    *size = _sector_format_table[fself->sector_format].header_size;
    return SACD_INPUT_OK;
}

/**
 * @brief Get the trailer size for the detected format.
 */
static int _file_get_trailer_size(sacd_input_t *self, int16_t *size)
{
    sacd_input_file_t *fself = (sacd_input_file_t *)self;

    if (!fself || !size) {
        return SACD_INPUT_ERR_NULL_PTR;
    }

    /* Detect format on first call if not already done */
    if (!fself->format_detected) {
        _file_detect_sector_format(fself);
    }

    *size = _sector_format_table[fself->sector_format].trailer_size;
    return SACD_INPUT_OK;
}

/**
 * @brief Read sectors in the native format (with headers/trailers).
 */
static int _file_read_sectors(sacd_input_t *self, uint32_t sector_pos,
                               uint32_t sector_count, void *buffer,
                               uint32_t *sectors_read)
{
    sacd_input_file_t *fself = (sacd_input_file_t *)self;
    uint32_t sector_size;
    uint64_t offset;
    size_t bytes_to_read;
    size_t bytes_read;

    if (!fself || !buffer || !sectors_read) {
        if (sectors_read) *sectors_read = 0;
        return SACD_INPUT_ERR_NULL_PTR;
    }

    if (sector_count == 0) {
        *sectors_read = 0;
        return SACD_INPUT_OK;
    }

    /* Detect format on first call if not already done */
    if (!fself->format_detected) {
        _file_detect_sector_format(fself);
    }

    sector_size = _sector_format_table[fself->sector_format].sector_size;
    offset = (uint64_t)sector_pos * sector_size;
    bytes_to_read = (size_t)sector_count * sector_size;

    /* Check bounds */
    if (offset >= fself->file_size) {
        *sectors_read = 0;
        fself->base.last_error = SACD_INPUT_ERR_EOF;
        return SACD_INPUT_ERR_EOF;
    }

    /* Clamp to available bytes */
    if (offset + bytes_to_read > fself->file_size) {
        bytes_to_read = (size_t)(fself->file_size - offset);
    }

    /* Read using the existing read_bytes function */
    bytes_read = _file_read_bytes(self, offset, bytes_to_read, buffer);
    *sectors_read = (uint32_t)(bytes_read / sector_size);

    if (bytes_read != bytes_to_read) {
        return SACD_INPUT_ERR_READ_FAILED;
    }

    return SACD_INPUT_OK;
}
