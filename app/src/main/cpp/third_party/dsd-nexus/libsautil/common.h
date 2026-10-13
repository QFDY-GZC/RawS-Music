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
 * common internal and external API header
 */

#ifndef SAUTIL_COMMON_H
#define SAUTIL_COMMON_H

#include <stdint.h>

#include "attributes.h"

#ifndef sa_clip
#   define sa_clip          sa_clip_c
#endif

#ifndef sa_popcount
#   define sa_popcount      sa_popcount_c
#endif

#ifndef sa_popcount64
#   define sa_popcount64    sa_popcount64_c
#endif

#ifndef sa_log2
sa_const int sa_log2(unsigned v);
#endif

#ifndef sa_log2_16bit
sa_const int sa_log2_16bit(unsigned v);
#endif

/**
 * Clip a signed integer value into the amin-amax range.
 * @param a value to clip
 * @param amin minimum value of the clip range
 * @param amax maximum value of the clip range
 * @return clipped value
 */
static sa_always_inline sa_const int sa_clip_c(int a, int amin, int amax)
{
#if defined(HAVE_SA_CONFIG_H) && defined(ASSERT_LEVEL) && ASSERT_LEVEL >= 2
    if (amin > amax) abort();
#endif
    if      (a < amin) return amin;
    else if (a > amax) return amax;
    else               return a;
}

/**
 * Count number of bits set to one in x
 * @param x value to count bits of
 * @return the number of bits set to one in x
 */
static sa_always_inline sa_const int sa_popcount_c(uint32_t x)
{
    x -= (x >> 1) & 0x55555555;
    x = (x & 0x33333333) + ((x >> 2) & 0x33333333);
    x = (x + (x >> 4)) & 0x0F0F0F0F;
    x += x >> 8;
    return (x + (x >> 16)) & 0x3F;
}

/**
 * Count number of bits set to one in x
 * @param x value to count bits of
 * @return the number of bits set to one in x
 */
static sa_always_inline sa_const int sa_popcount64_c(uint64_t x)
{
    return sa_popcount((uint32_t)x) + sa_popcount((uint32_t)(x >> 32));
}

#endif /* SAUTIL_COMMON_H */
