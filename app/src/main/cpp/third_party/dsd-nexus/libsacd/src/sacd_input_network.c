/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief Network-based input implementation for SACD reading using nanopb + tinycsocket.
 * This implementation reads sector data from a remote server over a TCP socket
 * using the Protocol Buffers format defined in sacd_ripper.proto.
 * Protocol Overview:
 * - Uses nanopb for Protocol Buffer encoding/decoding
 * - Request/response messages as defined in sacd_ripper.proto
 * - A zero byte signals end of request
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
#include "sacd_pb_stream.h"

#include <libsautil/mem.h>
#include <libsautil/sastring.h>

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdbool.h>

#include <tinycsocket.h>
#include <pb_encode.h>
#include <pb_decode.h>
#include "sacd_ripper.pb.h"

/* Maximum sectors to read in a single request */
#define MAX_PROCESSING_BLOCK_SIZE 256

/**
 * @struct sacd_input_network_t
 * @brief Extended structure for network-based input.
 *
 * The base struct MUST be the first member for safe casting.
 */
typedef struct sacd_input_network {
    sacd_input_t    base;           /**< Base structure (must be first!) */
    TcsSocket       sock;           /**< tinycsocket socket descriptor */
    uint32_t        total_sectors;  /**< Cached total sector count */
    uint8_t        *input_buffer;   /**< Buffer for protobuf responses */
    char            host[256];      /**< Server hostname (for error messages) */
    uint16_t        port;           /**< Server port */
    bool            connected;      /**< Connection status */
} sacd_input_network_t;

/* Forward declarations of vtable functions */
static int          _network_close(sacd_input_t *self);
static uint32_t     _network_total_sectors(sacd_input_t *self);
static const char  *_network_get_error(sacd_input_t *self);

/* Sector format methods - network always provides 2048-byte sectors */
static int          _network_get_sector_format(sacd_input_t *self,
                                                sacd_sector_format_t *format);
static int          _network_get_sector_size(sacd_input_t *self, uint32_t *size);
static int          _network_get_header_size(sacd_input_t *self, int16_t *size);
static int          _network_get_trailer_size(sacd_input_t *self, int16_t *size);
static int          _network_read_sectors(sacd_input_t *self, uint32_t sector_pos,
                                           uint32_t sector_count, void *buffer,
                                           uint32_t *sectors_read);

/**
 * @brief Static vtable for network input instances.
 *
 * @note Network protocol always provides 2048-byte sectors (server handles format).
 */
static const sacd_input_ops_t _network_input_ops = {
    .close             = _network_close,
    .read_sectors      = _network_read_sectors,
    .total_sectors     = _network_total_sectors,
    .authenticate      = NULL,  /* Could be added for authenticated connections */
    .decrypt           = NULL,  /* Decryption handled server-side */
    .get_error         = _network_get_error,
    /* Sector format methods */
    .get_sector_format = _network_get_sector_format,
    .get_sector_size   = _network_get_sector_size,
    .get_header_size   = _network_get_header_size,
    .get_trailer_size  = _network_get_trailer_size,
};

/* Track library initialization state */
static bool _tcs_initialized = false;

/**
 * @brief Initialize tinycsocket library (once).
 */
static bool _tcs_lib_init(void)
{
    if (!_tcs_initialized)
    {
        if (tcs_lib_init() != TCS_SUCCESS)
        {
            return false;
        }
        _tcs_initialized = true;
    }
    return true;
}

/**
 * @brief Send data over socket ensuring all bytes are sent.
 */
static bool _send_all(TcsSocket sock, const void *buf, size_t len)
{
    const uint8_t *ptr = (const uint8_t *)buf;
    size_t remaining = len;

    while (remaining > 0)
    {
        size_t sent = 0;
        TcsResult result = tcs_send(sock, ptr, remaining, 0, &sent);

        if (result != TCS_SUCCESS || sent == 0)
        {
            return false;
        }
        ptr += sent;
        remaining -= sent;
    }
    return true;
}

/**
 * @brief Open a network socket input.
 */
int sacd_input_open_network(const char *host, uint16_t port, sacd_input_t **out)
{
    sacd_input_network_t *self;
    ServerRequest request;
    ServerResponse response = ServerResponse_init_zero;
    pb_istream_t input;
    pb_ostream_t output;
    uint8_t zero = 0;

    if (!host || !out)
    {
        return SACD_INPUT_ERR_INVALID_ARG;
    }

    *out = NULL;

    /* Initialize tinycsocket library */
    if (!_tcs_lib_init())
    {
        return SACD_INPUT_ERR_NETWORK;
    }

    /* Allocate structure */
    self = (sacd_input_network_t *)sa_calloc(1, sizeof(*self));
    if (!self)
    {
        return SACD_INPUT_ERR_OUT_OF_MEMORY;
    }

    /* Initialize base */
    self->base.ops  = &_network_input_ops;
    self->base.type = SACD_INPUT_TYPE_NETWORK;
    self->base.last_error = SACD_INPUT_OK;

    self->sock = TCS_SOCKET_INVALID;
    self->connected = false;
    self->port = port;
    self->total_sectors = 0;
    sa_strlcpy(self->host, host, sizeof(self->host));

    /* Allocate input buffer for responses - must be large enough for ServerResponse struct */
    self->input_buffer = (uint8_t *)sa_malloc(sizeof(ServerResponse));
    if (!self->input_buffer)
    {
        sa_free(self);
        return SACD_INPUT_ERR_OUT_OF_MEMORY;
    }

    /* Create socket */
    if (tcs_socket_preset(&self->sock, TCS_PRESET_TCP_IP4) != TCS_SUCCESS)
    {
        snprintf(self->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                 "failed to create socket");
        self->base.last_error = SACD_INPUT_ERR_NETWORK;
        sa_free(self->input_buffer);
        sa_free(self);
        return SACD_INPUT_ERR_NETWORK;
    }

    /* Connect to server */
    if (tcs_connect_str(self->sock, host, port) != TCS_SUCCESS)
    {
        snprintf(self->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                 "failed to connect to %s:%u", host, port);
        self->base.last_error = SACD_INPUT_ERR_NETWORK;
        tcs_close(&self->sock);
        sa_free(self->input_buffer);
        sa_free(self);
        return SACD_INPUT_ERR_NETWORK;
    }

    /* Create nanopb streams */
    input = pb_istream_from_tcs_socket(self->sock);
    output = pb_ostream_from_tcs_socket(self->sock);

    /* Send DISC_OPEN request */
    request.type = ServerRequest_Type_DISC_OPEN;
    request.sector_offset = 0;
    request.sector_count = 0;

    if (!pb_encode(&output, ServerRequest_fields, &request))
    {
        snprintf(self->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                 "failed to encode OPEN request");
        self->base.last_error = SACD_INPUT_ERR_NETWORK;
        tcs_close(&self->sock);
        sa_free(self->input_buffer);
        sa_free(self);
        return SACD_INPUT_ERR_NETWORK;
    }

    /* Signal end of request with zero byte */
    pb_write(&output, &zero, 1);

    /* Read response */
    if (!pb_decode(&input, ServerResponse_fields, &response))
    {
        snprintf(self->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                 "failed to decode OPEN response");
        self->base.last_error = SACD_INPUT_ERR_NETWORK;
        tcs_close(&self->sock);
        sa_free(self->input_buffer);
        sa_free(self);
        return SACD_INPUT_ERR_NETWORK;
    }

    if (response.result != 0 || response.type != ServerResponse_Type_DISC_OPENED)
    {
        snprintf(self->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                 "server returned error on OPEN");
        self->base.last_error = SACD_INPUT_ERR_OPEN_FAILED;
        tcs_close(&self->sock);
        sa_free(self->input_buffer);
        sa_free(self);
        return SACD_INPUT_ERR_OPEN_FAILED;
    }

    /* Query total sectors */
    {
        ServerRequest size_request;
        ServerResponse size_response = ServerResponse_init_zero;

        input = pb_istream_from_tcs_socket(self->sock);
        output = pb_ostream_from_tcs_socket(self->sock);

        size_request.type = ServerRequest_Type_DISC_SIZE;
        size_request.sector_offset = 0;
        size_request.sector_count = 0;

        if (!pb_encode(&output, ServerRequest_fields, &size_request))
        {
            snprintf(self->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                     "failed to encode SIZE request");
            self->base.last_error = SACD_INPUT_ERR_NETWORK;
            tcs_close(&self->sock);
            sa_free(self->input_buffer);
            sa_free(self);
            return SACD_INPUT_ERR_NETWORK;
        }

        pb_write(&output, &zero, 1);

        if (!pb_decode(&input, ServerResponse_fields, &size_response))
        {
            snprintf(self->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                     "failed to decode SIZE response");
            self->base.last_error = SACD_INPUT_ERR_NETWORK;
            tcs_close(&self->sock);
            sa_free(self->input_buffer);
            sa_free(self);
            return SACD_INPUT_ERR_NETWORK;
        }

        if (size_response.type != ServerResponse_Type_DISC_SIZE)
        {
            snprintf(self->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                     "unexpected response type for SIZE");
            self->base.last_error = SACD_INPUT_ERR_NETWORK;
            tcs_close(&self->sock);
            sa_free(self->input_buffer);
            sa_free(self);
            return SACD_INPUT_ERR_NETWORK;
        }

        self->total_sectors = (uint32_t)size_response.result;
    }

    self->connected = true;
    *out = (sacd_input_t *)self;
    return SACD_INPUT_OK;
}

/**
 * @brief Close network connection and free resources.
 */
static int _network_close(sacd_input_t *self)
{
    sacd_input_network_t *nself = (sacd_input_network_t *)self;

    if (!nself)
    {
        return SACD_INPUT_ERR_NULL_PTR;
    }

    if (nself->connected && nself->sock != TCS_SOCKET_INVALID)
    {
        /* Send CLOSE command (best effort) */
        ServerRequest request;
        pb_ostream_t output = pb_ostream_from_tcs_socket(nself->sock);
        uint8_t zero = 0;

        request.type = ServerRequest_Type_DISC_CLOSE;
        request.sector_offset = 0;
        request.sector_count = 0;

        if (pb_encode(&output, ServerRequest_fields, &request))
        {
            pb_write(&output, &zero, 1);
        }
        /* We don't wait for response on close */
    }

    if (nself->sock != TCS_SOCKET_INVALID)
    {
        tcs_close(&nself->sock);
        nself->sock = TCS_SOCKET_INVALID;
    }

    if (nself->input_buffer)
    {
        sa_free(nself->input_buffer);
        nself->input_buffer = NULL;
    }

    nself->connected = false;
    sa_free(nself);
    return SACD_INPUT_OK;
}


/**
 * @brief Get total number of sectors.
 */
static uint32_t _network_total_sectors(sacd_input_t *self)
{
    sacd_input_network_t *nself = (sacd_input_network_t *)self;

    if (!nself)
    {
        return 0;
    }

    return nself->total_sectors;
}

/**
 * @brief Get error message.
 */
static const char *_network_get_error(sacd_input_t *self)
{
    if (!self)
    {
        return "null pointer";
    }

    if (self->error_msg[0])
    {
        return self->error_msg;
    }

    return sacd_input_error_string(self->last_error);
}

/* ============================================================================
 * Sector Format Methods
 *
 * Network protocol always provides 2048-byte sectors with no headers/trailers.
 * The server handles any format conversion transparently.
 * ============================================================================ */

/**
 * @brief Get sector format - always 2048 for network.
 */
static int _network_get_sector_format(sacd_input_t *self,
                                       sacd_sector_format_t *format)
{
    if (!self || !format)
    {
        return SACD_INPUT_ERR_NULL_PTR;
    }
    *format = SACD_SECTOR_2048;
    return SACD_INPUT_OK;
}

/**
 * @brief Get sector size - always 2048 for network.
 */
static int _network_get_sector_size(sacd_input_t *self, uint32_t *size)
{
    if (!self || !size)
    {
        return SACD_INPUT_ERR_NULL_PTR;
    }
    *size = SACD_LSN_SIZE;
    return SACD_INPUT_OK;
}

/**
 * @brief Get header size - always 0 for network.
 */
static int _network_get_header_size(sacd_input_t *self, int16_t *size)
{
    if (!self || !size)
    {
        return SACD_INPUT_ERR_NULL_PTR;
    }
    *size = 0;
    return SACD_INPUT_OK;
}

/**
 * @brief Get trailer size - always 0 for network.
 */
static int _network_get_trailer_size(sacd_input_t *self, int16_t *size)
{
    if (!self || !size)
    {
        return SACD_INPUT_ERR_NULL_PTR;
    }
    *size = 0;
    return SACD_INPUT_OK;
}

/**
 * @brief Read sectors from network.
 *
 * Uses the pre-allocated input_buffer to hold the ServerResponse (which is 1MB+
 * due to the embedded data array). After decoding, copies data to user buffer.
 */
static int _network_read_sectors(sacd_input_t *self, uint32_t sector_pos,
                                  uint32_t sector_count, void *buffer,
                                  uint32_t *sectors_read)
{
    sacd_input_network_t *nself = (sacd_input_network_t *)self;
    uint8_t output_buf[32];
    ServerRequest request;
    ServerResponse *response;
    pb_ostream_t output;
    pb_istream_t input;
    size_t bytes_written;

    if (!nself || !buffer || !sectors_read)
    {
        if (sectors_read) *sectors_read = 0;
        return SACD_INPUT_ERR_NULL_PTR;
    }

    if (sector_count == 0)
    {
        *sectors_read = 0;
        return SACD_INPUT_OK;
    }

    if (!nself->connected || nself->sock == TCS_SOCKET_INVALID)
    {
        *sectors_read = 0;
        nself->base.last_error = SACD_INPUT_ERR_CLOSED;
        snprintf(nself->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                 "not connected");
        return SACD_INPUT_ERR_CLOSED;
    }

    /* Use the pre-allocated buffer for the large ServerResponse struct */
    response = (ServerResponse *)nself->input_buffer;
    memset(response, 0, sizeof(ServerResponse));

    /* Build and encode READ request to buffer first */
    output = pb_ostream_from_buffer(output_buf, sizeof(output_buf));

    request.type = ServerRequest_Type_DISC_READ;
    request.sector_offset = sector_pos;
    request.sector_count = sector_count;

    if (!pb_encode(&output, ServerRequest_fields, &request))
    {
        *sectors_read = 0;
        nself->base.last_error = SACD_INPUT_ERR_NETWORK;
        snprintf(nself->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                 "failed to encode READ request");
        return SACD_INPUT_ERR_NETWORK;
    }

    /* Add terminating zero byte */
    bytes_written = output.bytes_written;
    if (bytes_written < sizeof(output_buf))
    {
        output_buf[bytes_written] = 0;
        bytes_written++;
    }

    /* Send the encoded request */
    if (!_send_all(nself->sock, output_buf, bytes_written))
    {
        *sectors_read = 0;
        nself->base.last_error = SACD_INPUT_ERR_NETWORK;
        snprintf(nself->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                 "failed to send READ request");
        return SACD_INPUT_ERR_NETWORK;
    }

    /* Read and decode response from socket */
    input = pb_istream_from_tcs_socket(nself->sock);

    if (!pb_decode(&input, ServerResponse_fields, response))
    {
        *sectors_read = 0;
        nself->base.last_error = SACD_INPUT_ERR_NETWORK;
        snprintf(nself->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                 "failed to decode READ response");
        return SACD_INPUT_ERR_NETWORK;
    }

    if (response->type != ServerResponse_Type_DISC_READ)
    {
        *sectors_read = 0;
        nself->base.last_error = SACD_INPUT_ERR_READ_FAILED;
        snprintf(nself->base.error_msg, SACD_INPUT_ERROR_MSG_SIZE,
                 "unexpected response type for READ");
        return SACD_INPUT_ERR_READ_FAILED;
    }

    if (response->has_data && response->data.size > 0)
    {
        /* Copy decoded data to user buffer */
        memcpy(buffer, response->data.bytes, response->data.size);
        *sectors_read = (uint32_t)response->result;
        return (*sectors_read == sector_count) ? SACD_INPUT_OK : SACD_INPUT_ERR_READ_FAILED;
    }

    *sectors_read = 0;
    return SACD_INPUT_ERR_READ_FAILED;
}
