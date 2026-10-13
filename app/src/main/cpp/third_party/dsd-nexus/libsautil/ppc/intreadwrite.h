/*
 * Copyright (c) 2008 Mans Rullgard <mans@mansr.com>
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

#ifndef SAUTIL_PPC_INTREADWRITE_H
#define SAUTIL_PPC_INTREADWRITE_H

#include <stdint.h>
#include "config.h"

#if HAVE_XFORM_ASM

#if HAVE_BIGENDIAN
#define SA_RL16 sa_read_bswap16
#define SA_WL16 sa_write_bswap16
#define SA_RL32 sa_read_bswap32
#define SA_WL32 sa_write_bswap32
#define SA_RL64 sa_read_bswap64
#define SA_WL64 sa_write_bswap64

#else
#define SA_RB16 sa_read_bswap16
#define SA_WB16 sa_write_bswap16
#define SA_RB32 sa_read_bswap32
#define SA_WB32 sa_write_bswap32
#define SA_RB64 sa_read_bswap64
#define SA_WB64 sa_write_bswap64

#endif

static sa_always_inline uint16_t sa_read_bswap16(const void *p)
{
    uint16_t v;
    __asm__ ("lhbrx   %0, %y1" : "=r"(v) : "Z"(*(const uint16_t*)p));
    return v;
}

static sa_always_inline void sa_write_bswap16(void *p, uint16_t v)
{
    __asm__ ("sthbrx  %1, %y0" : "=Z"(*(uint16_t*)p) : "r"(v));
}

static sa_always_inline uint32_t sa_read_bswap32(const void *p)
{
    uint32_t v;
    __asm__ ("lwbrx   %0, %y1" : "=r"(v) : "Z"(*(const uint32_t*)p));
    return v;
}

static sa_always_inline void sa_write_bswap32(void *p, uint32_t v)
{
    __asm__ ("stwbrx  %1, %y0" : "=Z"(*(uint32_t*)p) : "r"(v));
}

#if HAVE_LDBRX

static sa_always_inline uint64_t sa_read_bswap64(const void *p)
{
    uint64_t v;
    __asm__ ("ldbrx   %0, %y1" : "=r"(v) : "Z"(*(const uint64_t*)p));
    return v;
}

static sa_always_inline void sa_write_bswap64(void *p, uint64_t v)
{
    __asm__ ("stdbrx  %1, %y0" : "=Z"(*(uint64_t*)p) : "r"(v));
}

#else

static sa_always_inline uint64_t sa_read_bswap64(const void *p)
{
    union { uint64_t v; uint32_t hl[2]; } v;
    __asm__ ("lwbrx   %0, %y2  \n\t"
             "lwbrx   %1, %y3  \n\t"
             : "=&r"(v.hl[1]), "=r"(v.hl[0])
             : "Z"(*(const uint32_t*)p), "Z"(*((const uint32_t*)p+1)));
    return v.v;
}

static sa_always_inline void sa_write_bswap64(void *p, uint64_t v)
{
    union { uint64_t v; uint32_t hl[2]; } vv = { v };
    __asm__ ("stwbrx  %2, %y0  \n\t"
             "stwbrx  %3, %y1  \n\t"
             : "=Z"(*(uint32_t*)p), "=Z"(*((uint32_t*)p+1))
             : "r"(vv.hl[1]), "r"(vv.hl[0]));
}

#endif /* HAVE_LDBRX */

#endif /* HAVE_XFORM_ASM */

#endif /* SAUTIL_PPC_INTREADWRITE_H */
