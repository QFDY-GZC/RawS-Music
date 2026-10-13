/*
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

#ifndef SAUTIL_X86_BSWAP_H
#define SAUTIL_X86_BSWAP_H

#include <stdint.h>
#if defined(_MSC_VER)
#include <stdlib.h>
#include <intrin.h>
#endif
#include "config.h"
#include "libsautil/attributes.h"

#if defined(_MSC_VER)

#define sa_bswap16 sa_bswap16
static sa_always_inline sa_const uint16_t sa_bswap16(uint16_t x)
{
    return _rotr16(x, 8);
}

#define sa_bswap32 sa_bswap32
static sa_always_inline sa_const uint32_t sa_bswap32(uint32_t x)
{
    return _byteswap_ulong(x);
}

#if ARCH_X86_64
#define sa_bswap64 sa_bswap64
static inline uint64_t sa_const sa_bswap64(uint64_t x)
{
    return _byteswap_uint64(x);
}
#endif


#elif HAVE_INLINE_ASM

#ifdef __INTEL_COMPILER
#define sa_bswap32 sa_bswap32
static sa_always_inline sa_const uint32_t sa_bswap32(uint32_t x)
{
    __asm__("bswap   %0" : "+r" (x));
    return x;
}

#if ARCH_X86_64
#define sa_bswap64 sa_bswap64
static inline uint64_t sa_const sa_bswap64(uint64_t x)
{
    __asm__("bswap  %0": "=r" (x) : "0" (x));
    return x;
}
#endif
#endif /* __INTEL_COMPILER */

#endif /* HAVE_INLINE_ASM */
#endif /* SAUTIL_X86_BSWAP_H */
