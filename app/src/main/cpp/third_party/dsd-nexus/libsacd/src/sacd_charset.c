/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief Character set conversion utilities for SACD text metadata.
 * SACD discs store text in various character encodings depending on region.
 * This module converts these encodings to UTF-8 for consistent handling.
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

#include "sacd_charset.h"

#include <stdint.h>
#include <string.h>
#include <libsautil/mem.h>

/* Maximum number of supported codepage indices (0-7) */
#define CODEPAGE_INDEX_MASK 0x07
#define CODEPAGE_COUNT      8

/* ============================================================================
 * Codepage Definitions
 *
 * SACD character set codes (from SACD specification):
 *   0 = ISO 646 (US-ASCII equivalent)
 *   1 = ISO 646 (US-ASCII)
 *   2 = ISO 8859-1 (Latin-1, Western European)
 *   3 = Shift-JIS (Japanese)
 *   4 = KSC 5601 (Korean)
 *   5 = GB 2312 (Simplified Chinese)
 *   6 = Big5 (Traditional Chinese)
 *   7 = ISO 8859-1 (fallback)
 * ============================================================================ */

#ifdef _WIN32
/* ============================================================================
 * Windows Implementation (MultiByteToWideChar / WideCharToMultiByte)
 * ============================================================================ */

#include <windows.h>

/**
 * Windows codepage identifiers corresponding to SACD character set codes.
 */
static const uint32_t codepage_ids[CODEPAGE_COUNT] = {
    CP_ACP,     /* 0: System default (ISO 646 approximation) */
    20127,      /* 1: US-ASCII */
    28591,      /* 2: ISO 8859-1 */
    932,        /* 3: Shift-JIS (Japanese) */
    949,        /* 4: KSC 5601 / EUC-KR (Korean) */
    936,        /* 5: GB 2312 / GBK (Simplified Chinese) */
    950,        /* 6: Big5 (Traditional Chinese) */
    28591,      /* 7: ISO 8859-1 (fallback) */
};

char *sacd_special_string_to_utf8(const char *str, uint8_t codepage_index)
{
    uint32_t codepage;
    wchar_t *widestr = NULL;
    char *utf8str = NULL;
    int widelen, utf8len;

    if (!str) {
        return NULL;
    }

    codepage = codepage_ids[codepage_index & CODEPAGE_INDEX_MASK];

    /* Convert source encoding to UTF-16 */
    widelen = MultiByteToWideChar(codepage, 0, str, -1, NULL, 0);
    if (widelen == 0) {
        return NULL;
    }

    widestr = (wchar_t *)sa_malloc((size_t)widelen * sizeof(wchar_t));
    if (!widestr) {
        return NULL;
    }

    if (MultiByteToWideChar(codepage, 0, str, -1, widestr, widelen) == 0) {
        sa_free(widestr);
        return NULL;
    }

    /* Convert UTF-16 to UTF-8 */
    utf8len = WideCharToMultiByte(CP_UTF8, 0, widestr, -1, NULL, 0, NULL, NULL);
    if (utf8len == 0) {
        sa_free(widestr);
        return NULL;
    }

    utf8str = (char *)sa_malloc((size_t)utf8len);
    if (!utf8str) {
        sa_free(widestr);
        return NULL;
    }

    if (WideCharToMultiByte(CP_UTF8, 0, widestr, -1, utf8str, utf8len,
                            NULL, NULL) == 0) {
        sa_free(widestr);
        sa_free(utf8str);
        return NULL;
    }

    sa_free(widestr);
    return utf8str;
}

uint16_t sacd_special_string_len(const char *str, uint8_t codepage_index)
{
    uint32_t codepage;
    uint16_t len;

    if (!str) {
        return 0;
    }

    codepage = codepage_ids[codepage_index & CODEPAGE_INDEX_MASK];
    len = (uint16_t) MultiByteToWideChar(codepage, 0, str, -1, NULL, 0);
    if (len == 0) {
        return 0;
    }
    return len;
}

#else
/* ============================================================================
 * POSIX Implementation (iconv)
 * ============================================================================ */

#include <stdlib.h>
#include <stdio.h>
#include <iconv.h>
#include <errno.h>

/**
 * iconv charset names corresponding to SACD character set codes.
 */
static const char *iconv_charsets[CODEPAGE_COUNT] = {
    "US-ASCII",     /* 0: ISO 646 */
    "US-ASCII",     /* 1: ISO 646 */
    "ISO-8859-1",   /* 2: Latin-1 */
    "SJIS",         /* 3: Shift-JIS (Japanese) */
    "KSC_5601",     /* 4: Korean */
    "GB_2312-80",   /* 5: Simplified Chinese */
    "BIG5",         /* 6: Traditional Chinese */
    "ISO-8859-1",   /* 7: Latin-1 (fallback) */
};

/**
 * Character width for each encoding (1 = single-byte, 2 = multi-byte).
 */
static const uint8_t charset_widths[CODEPAGE_COUNT] = {
    1,  /* US-ASCII */
    1,  /* US-ASCII */
    1,  /* ISO-8859-1 */
    2,  /* SJIS */
    2,  /* KSC_5601 */
    2,  /* GB_2312 */
    2,  /* BIG5 */
    1,  /* ISO-8859-1 */
};

/**
 * @brief Calculate length of a double-byte null-terminated string.
 *
 * Multi-byte Asian encodings use two-byte characters with a double-null
 * terminator. This function finds the length up to (but not including)
 * the terminating double-null.
 *
 * @param[in] str  Pointer to double-byte string
 * @return         Length in bytes (not including terminator)
 */
static uint16_t _multibyte_strlen(const uint8_t *str)
{
    uint16_t len = 0;

    if (!str) {
        return 0;
    }

    /* Scan for double-null terminator (0x00 0x00) */
    while (str[len] != 0 || str[len + 1] != 0) {
        len += 2;
    }

    return len;
}

/**
 * @brief Convert string between character sets using iconv.
 *
 * @param[in] input   Input string
 * @param[in] insize  Input string length in bytes
 * @param[in] from    Source charset name
 * @param[in] to      Destination charset name
 * @return            Newly allocated converted string, or NULL on error
 */
static char *_charset_convert(const char *input, size_t insize,
                              const char *from, const char *to)
{
    iconv_t cd;
    char *out = NULL;
    char *outptr;
    size_t outsize, outleft;
    size_t result;
    const char *inptr;
    size_t inleft;

    if (!input || !from || !to) {
        return NULL;
    }

    cd = iconv_open(to, from);
    if (cd == (iconv_t)-1) {
        /* Conversion not supported, return copy of input */
        return sa_strdup(input);
    }

    /*
     * Allocate output buffer.
     * Round up to multiple of 4 due to GLIBC quirks, plus 4 for null terminator.
     */
    outsize = ((insize + 3) & ~3) + 4;
    out = (char *)sa_malloc(outsize);
    if (!out) {
        iconv_close(cd);
        return NULL;
    }

    inptr = input;
    inleft = insize;
    outptr = out;
    outleft = outsize - 4;  /* Reserve space for null terminator */

    /* Convert with automatic buffer expansion */
    while (inleft > 0) {
        result = iconv(cd, (char **)&inptr, &inleft, &outptr, &outleft);

        if (result == (size_t)-1) {
            switch (errno) {
            case E2BIG:
                /* Output buffer full - expand and continue */
                {
                    size_t used = (size_t)(outptr - out);
                    size_t newsize = ((outsize - 4) * 2) + 4;
                    char *newbuf = (char *)sa_realloc(out, newsize);
                    if (!newbuf) {
                        sa_free(out);
                        iconv_close(cd);
                        return NULL;
                    }
                    out = newbuf;
                    outptr = out + used;
                    outleft = newsize - 4 - used;
                    outsize = newsize;
                }
                break;

            case EILSEQ:
                /* Invalid input sequence - skip and continue */
                if (inleft > 0) {
                    inptr++;
                    inleft--;
                }
                break;

            case EINVAL:
                /* Incomplete input sequence - stop processing */
                inleft = 0;
                break;

            default:
                /* Other error - stop processing */
                inleft = 0;
                break;
            }
        }
    }

    /* Null-terminate (up to 4 bytes for UTF-32 safety) */
    memset(outptr, 0, 4);

    iconv_close(cd);
    return out;
}

char *sacd_special_string_to_utf8(const char *str, uint8_t codepage_index)
{
    uint8_t index;
    size_t len;
    const char *charset;

    if (!str) {
        return NULL;
    }

    index = codepage_index & CODEPAGE_INDEX_MASK;
    charset = iconv_charsets[index];

    /* Determine string length based on encoding width */
    if (charset_widths[index] == 2) {
        len = _multibyte_strlen((const uint8_t *)str);
    } else {
        len = strlen(str);
    }

    return _charset_convert(str, len, charset, "UTF-8");
}

uint16_t sacd_special_string_len(const char *str, uint8_t codepage_index)
{
    uint8_t index;
    uint16_t len;

    if (!str) {
        return 0;
    }

    index = codepage_index & CODEPAGE_INDEX_MASK;

    /* Determine string length based on encoding width */
    if (charset_widths[index] == 2) {
        len = _multibyte_strlen((const uint8_t *)str);
    } else {
        len = strlen(str);
    }

    return len;
}

#endif /* _WIN32 */
