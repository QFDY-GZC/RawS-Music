/*
 * copyright (c) 2006 Michael Niedermayer <michaelni@gmx.at>
 *
 * This file is part of FFmpeg.
 *
 * FFmpeg is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * FFmpeg is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with FFmpeg; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

/**
 * @file
 * byte swapping routines
 */

#ifndef SAUTIL_BSWAP_H
#define SAUTIL_BSWAP_H

#include <stdint.h>
#include "libsautil/saconfig.h"
#include "attributes.h"

#ifdef HAVE_SA_CONFIG_H

#include "config.h"

#if ARCH_ARM
#   include "arm/bswap.h"
#elif ARCH_RISCV
#   include "riscv/bswap.h"
#elif ARCH_X86
#   include "x86/bswap.h"
#endif

#endif /* HAVE_SA_CONFIG_H */

#define SA_BSWAP16C(x) (((x) << 8 & 0xff00)  | ((x) >> 8 & 0x00ff))
#define SA_BSWAP32C(x) (SA_BSWAP16C(x) << 16 | SA_BSWAP16C((x) >> 16))
#define SA_BSWAP64C(x) (SA_BSWAP32C(x) << 32 | SA_BSWAP32C((x) >> 32))

#define SA_BSWAPC(s, x) SA_BSWAP##s##C(x)

#ifndef sa_bswap16
static sa_always_inline sa_const uint16_t sa_bswap16(uint16_t x)
{
    x= (x>>8) | (x<<8);
    return x;
}
#endif

#ifndef sa_bswap32
static sa_always_inline sa_const uint32_t sa_bswap32(uint32_t x)
{
    return SA_BSWAP32C(x);
}
#endif

#ifndef sa_bswap64
static inline uint64_t sa_const sa_bswap64(uint64_t x)
{
    return (uint64_t)sa_bswap32((uint32_t)x) << 32 | sa_bswap32((uint32_t)(x >> 32));
}
#endif

// be2ne ... big-endian to native-endian
// le2ne ... little-endian to native-endian

#if SA_HAVE_BIGENDIAN
#define ntoh16(x) (x)
#define ntoh32(x) (x)
#define ntoh64(x) (x)
#define hton16(x) (x)
#define hton32(x) (x)
#define hton64(x) (x)
#undef htole16
#undef htole32
#undef htole64
#define htole16(x) sa_bswap16(x)
#define htole32(x) sa_bswap32(x)
#define htole64(x) sa_bswap64(x)
#define MAKE_MARKER(a, b, c, d)    (((a) << 24) | ((b) << 16) | ((c) << 8) | (d))
#define MAKE_MARKER64(a, b, c, d, e, f, g, h)    (((uint64_t)(a) << 56) | ((uint64_t)(b) << 48) | ((uint64_t)(c) << 40) | ((uint64_t)(d) << 32) | ((uint64_t)(e) << 24) | ((uint64_t)(f) << 16) | ((uint64_t)(g) << 8) | (uint64_t)(h))

#define sa_be2ne16(x) (x)
#define sa_be2ne32(x) (x)
#define sa_be2ne64(x) (x)
#define sa_le2ne16(x) sa_bswap16(x)
#define sa_le2ne32(x) sa_bswap32(x)
#define sa_le2ne64(x) sa_bswap64(x)
#define SA_BE2NEC(s, x) (x)
#define SA_LE2NEC(s, x) SA_BSWAPC(s, x)
#else
#define ntoh16(x) sa_bswap16(x)
#define ntoh32(x) sa_bswap32(x)
#define ntoh64(x) sa_bswap64(x)
#define hton16(x) sa_bswap16(x)
#define hton32(x) sa_bswap32(x)
#define hton64(x) sa_bswap64(x)
#undef htole16
#undef htole32
#undef htole64
#define htole16(x) (x)
#define htole32(x) (x)
#define htole64(x) (x)
#define MAKE_MARKER(a, b, c, d)    ((a) | ((b) << 8) | ((c) << 16) | ((d) << 24))
#define MAKE_MARKER64(a, b, c, d, e, f, g, h)    ((uint64_t)(a) | ((uint64_t)(b) << 8) | ((uint64_t)(c) << 16) | ((uint64_t)(d) << 24) | ((uint64_t)(e) << 32) | ((uint64_t)(f) << 40) | ((uint64_t)(g) << 48) | ((uint64_t)(h) << 56))

#define sa_be2ne16(x) sa_bswap16(x)
#define sa_be2ne32(x) sa_bswap32(x)
#define sa_be2ne64(x) sa_bswap64(x)
#define sa_le2ne16(x) (x)
#define sa_le2ne32(x) (x)
#define sa_le2ne64(x) (x)
#define SA_BE2NEC(s, x) SA_BSWAPC(s, x)
#define SA_LE2NEC(s, x) (x)
#endif

#define SA_BE2NE16C(x) SA_BE2NEC(16, x)
#define SA_BE2NE32C(x) SA_BE2NEC(32, x)
#define SA_BE2NE64C(x) SA_BE2NEC(64, x)
#define SA_LE2NE16C(x) SA_LE2NEC(16, x)
#define SA_LE2NE32C(x) SA_LE2NEC(32, x)
#define SA_LE2NE64C(x) SA_LE2NEC(64, x)

#endif /* SAUTIL_BSWAP_H */
