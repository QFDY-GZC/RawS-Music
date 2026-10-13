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

#ifndef LIBSACD_SACD_PB_STREAM_H
#define LIBSACD_SACD_PB_STREAM_H

#include <pb.h>
#include <tinycsocket.h>

/**
 * Create a nanopb output stream from a tinycsocket socket.
 *
 * @param socket The connected TcsSocket to write to
 * @return A pb_ostream_t configured for socket output
 */
pb_ostream_t pb_ostream_from_tcs_socket(TcsSocket socket);

/**
 * Create a nanopb input stream from a tinycsocket socket.
 *
 * @param socket The connected TcsSocket to read from
 * @return A pb_istream_t configured for socket input
 */
pb_istream_t pb_istream_from_tcs_socket(TcsSocket socket);

#endif /* LIBSACD_SACD_PB_STREAM_H */
