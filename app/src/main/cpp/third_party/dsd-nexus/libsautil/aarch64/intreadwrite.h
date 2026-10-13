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

#ifndef SAUTIL_AARCH64_INTREADWRITE_H
#define SAUTIL_AARCH64_INTREADWRITE_H

#if HAVE_INTRINSICS_NEON

#include <arm_neon.h>

#define SA_COPY128 SA_COPY128
static sa_always_inline void SA_COPY128(void *d, const void *s)
{
    uint8x16_t tmp = vld1q_u8((const uint8_t *)s);
    vst1q_u8((uint8_t *)d, tmp);
}

#define SA_ZERO128 SA_ZERO128
static sa_always_inline void SA_ZERO128(void *d)
{
    uint8x16_t zero = vdupq_n_u8(0);
    vst1q_u8((uint8_t *)d, zero);
}

#endif /* HAVE_INTRINSICS_NEON */

#endif /* SAUTIL_AARCH64_INTREADWRITE_H */
