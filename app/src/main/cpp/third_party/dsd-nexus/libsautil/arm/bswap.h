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

#ifndef SAUTIL_ARM_BSWAP_H
#define SAUTIL_ARM_BSWAP_H

#include <stdint.h>
#include "config.h"
#include "libsautil/attributes.h"

#ifdef __ARMCC_VERSION

#if HAVE_ARMV6
#define sa_bswap32 sa_bswap32
static sa_always_inline sa_const uint32_t sa_bswap32(uint32_t x)
{
    return __rev(x);
}
#endif /* HAVE_ARMV6 */

#elif HAVE_INLINE_ASM

#if HAVE_ARMV6_INLINE
#define sa_bswap16 sa_bswap16
static sa_always_inline sa_const unsigned sa_bswap16(unsigned x)
{
    unsigned y;

    __asm__("rev16 %0, %1" : "=r"(y) : "r"(x));
    return y;
}
#endif
#endif /* __ARMCC_VERSION */

#endif /* SAUTIL_ARM_BSWAP_H */
