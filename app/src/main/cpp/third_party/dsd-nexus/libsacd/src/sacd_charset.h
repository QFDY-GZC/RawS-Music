/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief Character set conversion utilities for SACD text metadata.
 * SACD discs store text metadata (album titles, artist names, track titles)
 * in various character encodings depending on the disc's region. The encoding
 * is identified by a codepage index stored in the disc's Text Channel
 * information (see text_channels_t::character_set_code).
 * This module converts SACD text from its native encoding to UTF-8 for
 * consistent handling throughout the application.
 * ### Supported Codepage Indices
 * | Index | Encoding       | Description                        |
 * |:------|:---------------|:-----------------------------------|
 * | 0     | ISO 646 / ASCII| System default (ASCII approximation)|
 * | 1     | US-ASCII       | ISO 646 International Reference    |
 * | 2     | ISO 8859-1     | Latin-1 (Western European)         |
 * | 3     | Shift-JIS      | Japanese (RIS-506 Music Shift-JIS) |
 * | 4     | KSC 5601       | Korean                             |
 * | 5     | GB 2312        | Simplified Chinese                 |
 * | 6     | Big5           | Traditional Chinese                |
 * | 7     | ISO 8859-1     | Latin-1 (fallback)                 |
 * @note On Windows, conversion uses MultiByteToWideChar/WideCharToMultiByte.
 *       On POSIX, conversion uses iconv.
 * @see text_channels_t for character set code definitions in the disc TOC
 * @see character_set_t for the codepage index enumeration
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

#ifndef LIBSACD_SACD_CHARSET_H
#define LIBSACD_SACD_CHARSET_H

#include <stdint.h>

/**
 * @brief Convert an SACD-encoded string to UTF-8.
 *
 * Converts a null-terminated string from the character encoding specified
 * by @p codepage_index to a newly allocated UTF-8 string.
 *
 * For multi-byte encodings (Shift-JIS, KSC 5601, GB 2312, Big5), the
 * input string uses a double-null terminator (0x00 0x00).
 *
 * @param[in] str             Null-terminated input string in the source encoding
 * @param[in] codepage_index  SACD character set code (0-7, masked to 3 bits)
 *
 * @return Newly allocated UTF-8 string on success (caller must free with sa_free()),
 *         or NULL on error (NULL input, conversion failure, or allocation failure)
 *
 * @note The caller is responsible for freeing the returned string.
 */
char *sacd_special_string_to_utf8(const char* str, uint8_t codepage_index);

/**
 * @brief Get the character length of an SACD-encoded string.
 *
 * Returns the length of the string in its source encoding. For single-byte
 * encodings this is the byte count (equivalent to strlen). For multi-byte
 * encodings (Shift-JIS, KSC 5601, GB 2312, Big5), this is the byte count
 * up to (but not including) the double-null terminator.
 *
 * On Windows, returns the number of wide characters needed to represent
 * the string (as reported by MultiByteToWideChar).
 *
 * @param[in] str             Null-terminated input string in the source encoding
 * @param[in] codepage_index  SACD character set code (0-7, masked to 3 bits)
 *
 * @return String length in bytes (POSIX) or wide characters (Windows),
 *         or 0 if @p str is NULL
 */
uint16_t sacd_special_string_len(const char *str, uint8_t codepage_index);

#endif /* LIBSACD_SACD_CHARSET_H */
