/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief Abstract input device interface for SACD sector reading.
 * This module provides a polymorphic interface for reading raw sectors from
 * various input sources. It uses a vtable pattern to support multiple backends:
 * - File-based input (ISO disc images)
 * - Memory-based input (virtual device from buffer)
 * - Network input (remote server via socket)
 * - Physical device input (Bluray/DVD via ioctl)
 * Each backend implements the sacd_input_ops_t interface. The base sacd_input_t
 * structure must be embedded as the first member of each implementation struct
 * to allow safe casting between base and derived types.
 * @example
 * @code
 * sacd_input_t *input = NULL;
 * int ret = sacd_input_open("disc.iso", &input);
 * if (ret == SACD_INPUT_OK) {
 *     uint8_t buffer[2048];
 *     uint32_t sectors_read;
 *     sacd_input_read_sectors(input, 0, 1, buffer, &sectors_read);
 *     sacd_input_close(input);
 * }
 * @endcode
 * @see sacd_sector_reader.h for higher-level sector reading with format handling
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

#ifndef LIBSACD_SACD_INPUT_H
#define LIBSACD_SACD_INPUT_H

#include <libsautil/export.h>

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief Standard SACD logical sector size in bytes.
 */
#define SACD_LSN_SIZE 2048

/**
 * @brief Maximum error message length.
 */
#define SACD_INPUT_ERROR_MSG_SIZE 256

/**
 * @enum sacd_sector_format_t
 * @brief SACD sector format types.
 *
 * Disc image files can have different sector formats depending on how
 * they were created. Physical devices and network sources always use
 * 2048-byte sectors (SECTOR_2048).
 */
typedef enum {
    SACD_SECTOR_2048 = 0,  /**< Plain 2048-byte sectors, no header/trailer */
    SACD_SECTOR_2054 = 1,  /**< 6-byte header + 2048 data, no trailer */
    SACD_SECTOR_2064 = 2   /**< 12-byte header + 2048 data + 4-byte trailer */
} sacd_sector_format_t;

/**
 * @enum sacd_input_type_t
 * @brief Input device type identifiers.
 */
typedef enum sacd_input_type {
    SACD_INPUT_TYPE_UNKNOWN = 0,    /**< Unknown or invalid type */
    SACD_INPUT_TYPE_FILE,           /**< Regular filesystem file (ISO image) */
    SACD_INPUT_TYPE_MEMORY,         /**< Virtual device from memory buffer */
    SACD_INPUT_TYPE_NETWORK,        /**< Network socket connection */
    SACD_INPUT_TYPE_DEVICE          /**< Physical device (Bluray/DVD via ioctl) */
} sacd_input_type_t;

/**
 * @enum sacd_input_error_t
 * @brief Error codes for input operations.
 *
 * Functions return 0 (SACD_INPUT_OK) on success, negative values on error.
 */
typedef enum sacd_input_error {
    SACD_INPUT_OK               =  0,   /**< Operation completed successfully */
    SACD_INPUT_ERR_NULL_PTR     = -1,   /**< NULL pointer argument */
    SACD_INPUT_ERR_OPEN_FAILED  = -2,   /**< Failed to open input source */
    SACD_INPUT_ERR_READ_FAILED  = -3,   /**< Read operation failed */
    SACD_INPUT_ERR_SEEK_FAILED  = -4,   /**< Seek operation failed */
    SACD_INPUT_ERR_AUTH_FAILED  = -5,   /**< Authentication failed */
    SACD_INPUT_ERR_DECRYPT_FAILED = -6, /**< Decryption failed */
    SACD_INPUT_ERR_NOT_SUPPORTED = -7,  /**< Operation not supported by backend */
    SACD_INPUT_ERR_OUT_OF_MEMORY = -8,  /**< Memory allocation failed */
    SACD_INPUT_ERR_NETWORK      = -9,   /**< Network communication error */
    SACD_INPUT_ERR_TIMEOUT      = -10,  /**< Operation timed out */
    SACD_INPUT_ERR_INVALID_ARG  = -11,  /**< Invalid argument value */
    SACD_INPUT_ERR_EOF          = -12,  /**< End of file/device reached */
    SACD_INPUT_ERR_CLOSED       = -13   /**< Device already closed */
} sacd_input_error_t;

/* Forward declaration */
typedef struct sacd_input_s sacd_input_t;

/**
 * @struct sacd_input_ops_t
 * @brief Virtual function table for input device operations.
 *
 * All backend implementations must provide this vtable. Function pointers
 * marked as optional may be NULL if the operation is not supported.
 */
typedef struct sacd_input_ops {
    /**
     * @brief Close the device and free all associated resources.
     * @param[in] self  Pointer to the input device
     * @return SACD_INPUT_OK on success, negative error code on failure
     *
     * @note After this call, the `self` pointer is invalid.
     */
    int (*close)(sacd_input_t *self);

    /**
     * @brief Read sectors in the native format (with headers/trailers if present).
     * @param[in]  self         Pointer to the input device
     * @param[in]  sector_pos   Starting sector number (0-based)
     * @param[in]  sector_count Number of sectors to read
     * @param[out] buffer       Buffer to receive data (must be large enough
     *                          for sector_count * raw_sector_size bytes)
     * @param[out] sectors_read Receives number of sectors actually read
     * @return SACD_INPUT_OK on success, negative error code on failure
     *
     * @note For file inputs with headers/trailers, returns the raw sector data.
     *       For device/network, sectors are always 2048 bytes.
     */
    int (*read_sectors)(sacd_input_t *self, uint32_t sector_pos,
                        uint32_t sector_count, void *buffer,
                        uint32_t *sectors_read);

    /**
     * @brief Get the total number of sectors on the device.
     * @param[in] self  Pointer to the input device
     * @return Total sector count, or 0 on error
     */
    uint32_t (*total_sectors)(sacd_input_t *self);

    /**
     * @brief Authenticate with the device for encrypted disc access.
     * @param[in] self  Pointer to the input device
     * @return SACD_INPUT_OK on success, negative error code on failure
     *
     * @note Optional. May be NULL if authentication is not supported/needed.
     */
    int (*authenticate)(sacd_input_t *self);

    /**
     * @brief Decrypt data read from an encrypted disc.
     * @param[in]     self        Pointer to the input device
     * @param[in,out] buffer      Data to decrypt (modified in-place)
     * @param[in]     block_count Number of sectors in the buffer
     * @return SACD_INPUT_OK on success, negative error code on failure
     *
     * @note Optional. May be NULL if decryption is not supported/needed.
     */
    int (*decrypt)(sacd_input_t *self, uint8_t *buffer, uint32_t block_count);

    /**
     * @brief Get a human-readable error message for the last error.
     * @param[in] self  Pointer to the input device
     * @return Pointer to error message string (internal, do not free)
     */
    const char *(*get_error)(sacd_input_t *self);

    /* ========================================================================
     * Sector Format Methods (for unified sector reading)
     * ======================================================================== */

    /**
     * @brief Get the sector format of the input source.
     * @param[in]  self    Pointer to the input device
     * @param[out] format  Receives the sector format
     * @return SACD_INPUT_OK on success, negative error code on failure
     *
     * @note For file inputs, this returns the detected format.
     *       For device/network/memory, always returns SACD_SECTOR_2048.
     */
    int (*get_sector_format)(sacd_input_t *self, sacd_sector_format_t *format);

    /**
     * @brief Get the raw sector size in bytes.
     * @param[in]  self  Pointer to the input device
     * @param[out] size  Receives the sector size (2048, 2054, or 2064)
     * @return SACD_INPUT_OK on success, negative error code on failure
     */
    int (*get_sector_size)(sacd_input_t *self, uint32_t *size);

    /**
     * @brief Get the header size for this sector format.
     * @param[in]  self  Pointer to the input device
     * @param[out] size  Receives the header size (0, 6, or 12)
     * @return SACD_INPUT_OK on success, negative error code on failure
     */
    int (*get_header_size)(sacd_input_t *self, int16_t *size);

    /**
     * @brief Get the trailer size for this sector format.
     * @param[in]  self  Pointer to the input device
     * @param[out] size  Receives the trailer size (0 or 4)
     * @return SACD_INPUT_OK on success, negative error code on failure
     */
    int (*get_trailer_size)(sacd_input_t *self, int16_t *size);

} sacd_input_ops_t;

/**
 * @struct sacd_input
 * @brief Base input device structure.
 *
 * All backend implementations must embed this structure as their first member.
 * This allows safe casting between the base type and derived types.
 *
 * @code
 * typedef struct {
 *     sacd_input_t base;  // Must be first!
 *     // Implementation-specific fields...
 * } my_input_impl_t;
 * @endcode
 */
struct sacd_input_s {
    const sacd_input_ops_t *ops;    /**< Pointer to vtable */
    sacd_input_type_t       type;   /**< Device type identifier */
    sacd_input_error_t      last_error; /**< Most recent error code */
    char error_msg[SACD_INPUT_ERROR_MSG_SIZE]; /**< Error message buffer */
};

/* ============================================================================
 * Factory Functions - Create Input Devices
 * ============================================================================ */

/**
 * @brief Open a file-based input (ISO image or disc image file).
 *
 * @param[in]  path  Path to the file (UTF-8 encoded on Windows)
 * @param[out] out   Receives pointer to created device on success
 *
 * @return SACD_INPUT_OK on success, or:
 *         - SACD_INPUT_ERR_INVALID_ARG: NULL path or out pointer
 *         - SACD_INPUT_ERR_OUT_OF_MEMORY: Allocation failed
 *         - SACD_INPUT_ERR_OPEN_FAILED: Could not open file
 */
SACD_API int sacd_input_open_file(const char *path, sacd_input_t **out);

/**
 * @brief Open a network socket input.
 *
 * Connects to a remote SACD server that provides sector data over a network
 * protocol.
 *
 * @param[in]  host  Hostname or IP address of the server
 * @param[in]  port  Port number to connect to
 * @param[out] out   Receives pointer to created device on success
 *
 * @return SACD_INPUT_OK on success, or:
 *         - SACD_INPUT_ERR_INVALID_ARG: NULL host or out pointer
 *         - SACD_INPUT_ERR_OUT_OF_MEMORY: Allocation failed
 *         - SACD_INPUT_ERR_NETWORK: Connection failed
 */
#ifndef SACD_NO_PS3DRIVE
SACD_API int sacd_input_open_network(const char *host, uint16_t port,
                                     sacd_input_t **out);
#endif

#ifndef SACD_NO_PS3DRIVE
/**
 * @brief Open a physical device (Bluray/DVD drive).
 *
 * Opens a physical optical drive for reading SACD discs. On Linux, this is
 * typically "/dev/sr0" or similar. On Windows, use drive letters like "D:".
 *
 * @param[in]  device  Device path or identifier
 * @param[out] out     Receives pointer to created device on success
 *
 * @return SACD_INPUT_OK on success, or:
 *         - SACD_INPUT_ERR_INVALID_ARG: NULL device or out pointer
 *         - SACD_INPUT_ERR_OUT_OF_MEMORY: Allocation failed
 *         - SACD_INPUT_ERR_OPEN_FAILED: Could not open device
 *         - SACD_INPUT_ERR_NOT_SUPPORTED: Platform does not support device access
 */
SACD_API int sacd_input_open_device(const char *device, sacd_input_t **out);
#endif

/**
 * @brief Auto-detect and open the appropriate input type.
 *
 * Examines the path to determine the input type:
 * - Paths starting with "memory://" use registered memory buffers
 * - Paths containing ":" followed by digits are treated as network (host:port)
 * - Paths starting with "/dev/" (Linux) or drive letters (Windows) as devices
 * - Everything else as files
 *
 * @param[in]  path  Path, URL, or device identifier
 * @param[out] out   Receives pointer to created device on success
 *
 * @return SACD_INPUT_OK on success, negative error code on failure
 *
 * @see sacd_input_open_file
 * @see sacd_input_open_network
 * @see sacd_input_open_device
 * @see sacd_input_register_memory
 */
SACD_API int sacd_input_open(const char *path, sacd_input_t **out);

/* ============================================================================
 * Inline Wrapper Functions - Call Through vtable
 * ============================================================================ */

/**
 * @brief Close an input device and free resources.
 * @param[in] input  Device to close (freed after this call)
 * @return SACD_INPUT_OK on success, negative error code on failure
 */
static inline int sacd_input_close(sacd_input_t *input)
{
    if (!input || !input->ops || !input->ops->close) {
        return SACD_INPUT_ERR_NULL_PTR;
    }
    return input->ops->close(input);
}

/**
 * @brief Get total number of sectors on the device.
 * @param[in] input  Device to query
 * @return Total sector count, or 0 on error
 */
static inline uint32_t sacd_input_total_sectors(sacd_input_t *input)
{
    if (!input || !input->ops || !input->ops->total_sectors) {
        return 0;
    }
    return input->ops->total_sectors(input);
}

/**
 * @brief Authenticate with the device.
 * @param[in] input  Device to authenticate
 * @return SACD_INPUT_OK on success, negative error code on failure
 */
static inline int sacd_input_authenticate(sacd_input_t *input)
{
    if (!input || !input->ops) {
        return SACD_INPUT_ERR_NULL_PTR;
    }
    if (!input->ops->authenticate) {
        return SACD_INPUT_ERR_NOT_SUPPORTED;
    }
    return input->ops->authenticate(input);
}

/**
 * @brief Decrypt data from an encrypted disc.
 * @param[in]     input       Device that provided the data
 * @param[in,out] buffer      Data to decrypt (in-place)
 * @param[in]     block_count Number of sectors in buffer
 * @return SACD_INPUT_OK on success, negative error code on failure
 */
static inline int sacd_input_decrypt(sacd_input_t *input, uint8_t *buffer,
                                     uint32_t block_count)
{
    if (!input || !input->ops) {
        return SACD_INPUT_ERR_NULL_PTR;
    }
    if (!input->ops->decrypt) {
        return SACD_INPUT_ERR_NOT_SUPPORTED;
    }
    return input->ops->decrypt(input, buffer, block_count);
}

/**
 * @brief Get error message for last operation.
 * @param[in] input  Device to query
 * @return Error message string (internal, do not free)
 */
static inline const char *sacd_input_get_error(sacd_input_t *input)
{
    if (!input || !input->ops || !input->ops->get_error) {
        return "null input";
    }
    return input->ops->get_error(input);
}

/**
 * @brief Get the type of an input device.
 * @param[in] input  Device to query
 * @return Device type, or SACD_INPUT_TYPE_UNKNOWN if input is NULL
 */
static inline sacd_input_type_t sacd_input_get_type(sacd_input_t *input)
{
    return input ? input->type : SACD_INPUT_TYPE_UNKNOWN;
}

/**
 * @brief Get the last error code.
 * @param[in] input  Device to query
 * @return Last error code, or SACD_INPUT_ERR_NULL_PTR if input is NULL
 */
static inline sacd_input_error_t sacd_input_get_last_error(sacd_input_t *input)
{
    return input ? input->last_error : SACD_INPUT_ERR_NULL_PTR;
}

/* ============================================================================
 * Sector Format Inline Wrappers
 * ============================================================================ */

/**
 * @brief Check if the input device supports sector format queries.
 * @param[in] input  Device to query
 * @return true if sector format methods are available, false otherwise
 */
static inline bool sacd_input_supports_sector_format(sacd_input_t *input)
{
    return input && input->ops && input->ops->get_sector_format;
}

/**
 * @brief Get the sector format of the input source.
 * @param[in]  input   Device to query
 * @param[out] format  Receives the sector format
 * @return SACD_INPUT_OK on success, negative error code on failure
 */
static inline int sacd_input_get_sector_format(sacd_input_t *input,
                                                sacd_sector_format_t *format)
{
    if (!input || !input->ops || !format) {
        return SACD_INPUT_ERR_NULL_PTR;
    }
    if (!input->ops->get_sector_format) {
        /* Default to 2048 if not implemented */
        *format = SACD_SECTOR_2048;
        return SACD_INPUT_OK;
    }
    return input->ops->get_sector_format(input, format);
}

/**
 * @brief Get the raw sector size in bytes.
 * @param[in]  input  Device to query
 * @param[out] size   Receives the sector size (2048, 2054, or 2064)
 * @return SACD_INPUT_OK on success, negative error code on failure
 */
static inline int sacd_input_get_sector_size(sacd_input_t *input, uint32_t *size)
{
    if (!input || !input->ops || !size) {
        return SACD_INPUT_ERR_NULL_PTR;
    }
    if (!input->ops->get_sector_size) {
        /* Default to 2048 if not implemented */
        *size = SACD_LSN_SIZE;
        return SACD_INPUT_OK;
    }
    return input->ops->get_sector_size(input, size);
}

/**
 * @brief Get the header size for this sector format.
 * @param[in]  input  Device to query
 * @param[out] size   Receives the header size (0, 6, or 12)
 * @return SACD_INPUT_OK on success, negative error code on failure
 */
static inline int sacd_input_get_header_size(sacd_input_t *input, int16_t *size)
{
    if (!input || !input->ops || !size) {
        return SACD_INPUT_ERR_NULL_PTR;
    }
    if (!input->ops->get_header_size) {
        /* Default to 0 if not implemented */
        *size = 0;
        return SACD_INPUT_OK;
    }
    return input->ops->get_header_size(input, size);
}

/**
 * @brief Get the trailer size for this sector format.
 * @param[in]  input  Device to query
 * @param[out] size   Receives the trailer size (0 or 4)
 * @return SACD_INPUT_OK on success, negative error code on failure
 */
static inline int sacd_input_get_trailer_size(sacd_input_t *input, int16_t *size)
{
    if (!input || !input->ops || !size) {
        return SACD_INPUT_ERR_NULL_PTR;
    }
    if (!input->ops->get_trailer_size) {
        /* Default to 0 if not implemented */
        *size = 0;
        return SACD_INPUT_OK;
    }
    return input->ops->get_trailer_size(input, size);
}

/**
 * @brief Read sectors in the native format (with headers/trailers if present).
 * @param[in]  input        Device to read from
 * @param[in]  sector_pos   Starting sector number
 * @param[in]  sector_count Number of sectors to read
 * @param[out] buffer       Buffer to receive data
 * @param[out] sectors_read Receives number of sectors actually read (may be NULL)
 * @return SACD_INPUT_OK on success, negative error code on failure
 */
static inline int sacd_input_read_sectors(sacd_input_t *input,
                                          uint32_t sector_pos,
                                          uint32_t sector_count,
                                          void *buffer,
                                          uint32_t *sectors_read)
{
    uint32_t dummy_read;

    if (!input || !input->ops || !buffer) {
        if (sectors_read) *sectors_read = 0;
        return SACD_INPUT_ERR_NULL_PTR;
    }

    if (!sectors_read) {
        sectors_read = &dummy_read;
    }

    if (!input->ops->read_sectors) {
        *sectors_read = 0;
        return SACD_INPUT_ERR_NOT_SUPPORTED;
    }

    return input->ops->read_sectors(input, sector_pos, sector_count,
                                     buffer, sectors_read);
}

/* ============================================================================
 * Utility Functions
 * ============================================================================ */

/**
 * @brief Convert an error code to a human-readable string.
 * @param[in] error  Error code to convert
 * @return Static string describing the error
 */
SACD_API const char *sacd_input_error_string(sacd_input_error_t error);

/**
 * @brief Convert an input type to a human-readable string.
 * @param[in] type  Input type to convert
 * @return Static string naming the type
 */
SACD_API const char *sacd_input_type_string(sacd_input_type_t type);

#ifdef __cplusplus
}
#endif

#endif /* LIBSACD_SACD_INPUT_H */
