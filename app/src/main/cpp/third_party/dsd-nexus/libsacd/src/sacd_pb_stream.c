/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
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

#include <stdint.h>
#include <stddef.h>
#include <pb_encode.h>
#include <pb_decode.h>

/* Include tinycsocket implementation in this translation unit */
#define TINYCSOCKET_IMPLEMENTATION

/* Suppress warnings from tinycsocket header-only library */
#ifdef _MSC_VER
#pragma warning(push)
#pragma warning(disable: 4100)  /* unreferenced formal parameter */
#pragma warning(disable: 4996)  /* deprecated functions (sprintf) */
#pragma warning(disable: 4702)  /* unreachable code */
#endif

#include <tinycsocket.h>

#ifdef _MSC_VER
#pragma warning(pop)
#endif

#include "sacd_pb_stream.h"

/**
 * Callback for nanopb output stream to write data to a tinycsocket socket.
 */
static bool _write_callback(pb_ostream_t *stream, const uint8_t *buf, size_t count)
{
    TcsSocket socket = (TcsSocket)(uintptr_t)stream->state;
    size_t total_sent = 0;

    while (total_sent < count)
    {
        size_t sent = 0;
        TcsResult result = tcs_send(socket, buf + total_sent,
                                    count - total_sent, 0, &sent);

        if (result != TCS_SUCCESS || sent == 0)
        {
            return false;
        }
        total_sent += sent;
    }

    return true;
}

/**
 * Callback for nanopb input stream to read data from a tinycsocket socket.
 */
static bool _read_callback(pb_istream_t *stream, uint8_t *buf, size_t count)
{
    TcsSocket socket = (TcsSocket)(uintptr_t)stream->state;
    size_t total_received = 0;

    if (buf == NULL)
    {
        /* Skip input - used when there are unknown fields */
        uint8_t dummy;
        while (count > 0)
        {
            size_t received = 0;
            TcsResult result = tcs_receive(socket, &dummy, 1, 0, &received);
            if (result != TCS_SUCCESS || received == 0)
            {
                return false;
            }
            count--;
        }
        return true;
    }

    while (total_received < count)
    {
        size_t received = 0;
        TcsResult result = tcs_receive(socket, buf + total_received,
                                       count - total_received, 0, &received);

        if (result != TCS_SUCCESS)
        {
            stream->bytes_left = 0; /* EOF */
            return false;
        }

        if (received == 0)
        {
            /* Connection closed */
            stream->bytes_left = 0;
            return false;
        }

        total_received += received;
    }

    return true;
}

pb_ostream_t pb_ostream_from_tcs_socket(TcsSocket socket)
{
    pb_ostream_t stream = {&_write_callback, (void *)(uintptr_t)socket, SIZE_MAX, 0};
    return stream;
}

pb_istream_t pb_istream_from_tcs_socket(TcsSocket socket)
{
    pb_istream_t stream = {&_read_callback, (void *)(uintptr_t)socket, SIZE_MAX};
    return stream;
}
