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

#ifndef SAUTIL_INTREADWRITE_H
#define SAUTIL_INTREADWRITE_H

#include <stdint.h>
#include "libsautil/saconfig.h"
#include "attributes.h"
#include "bswap.h"

typedef union {
    uint64_t u64;
    uint32_t u32[2];
    uint16_t u16[4];
    uint8_t  u8 [8];
    double   f64;
    float    f32[2];
} sa_alias sa_alias64;

typedef union {
    uint32_t u32;
    uint16_t u16[2];
    uint8_t  u8 [4];
    float    f32;
} sa_alias sa_alias32;

typedef union {
    uint16_t u16;
    uint8_t  u8 [2];
} sa_alias sa_alias16;

/*
 * Arch-specific headers can provide any combination of
 * SA_[RW][BLN](16|24|32|48|64) and SA_(COPY|SWAP|ZERO)(64|128) macros.
 * Preprocessor symbols must be defined, even if these are implemented
 * as inline functions.
 *
 * R/W means read/write, B/L/N means big/little/native endianness.
 * The following macros require aligned access, compared to their
 * unaligned variants: SA_(COPY|SWAP|ZERO)(64|128), SA_[RW]N[8-64]A.
 * Incorrect usage may range from abysmal performance to crash
 * depending on the platform.
 *
 * The unaligned variants are SA_[RW][BLN][8-64] and SA_COPY*U.
 */

#ifdef HAVE_SA_CONFIG_H

#include "config.h"

#if ARCH_AARCH64
#   include "aarch64/intreadwrite.h"
#elif ARCH_MIPS
#   include "mips/intreadwrite.h"
#elif ARCH_PPC
#   include "ppc/intreadwrite.h"
#elif ARCH_X86
#   include "x86/intreadwrite.h"
#endif

#endif /* HAVE_SA_CONFIG_H */

/*
 * Map SA_RNXX <-> SA_R[BL]XX for all variants provided by per-arch headers.
 */

#if SA_HAVE_BIGENDIAN

#   if    defined(SA_RN16) && !defined(SA_RB16)
#       define SA_RB16(p) SA_RN16(p)
#   elif !defined(SA_RN16) &&  defined(SA_RB16)
#       define SA_RN16(p) SA_RB16(p)
#   endif

#   if    defined(SA_WN16) && !defined(SA_WB16)
#       define SA_WB16(p, v) SA_WN16(p, v)
#   elif !defined(SA_WN16) &&  defined(SA_WB16)
#       define SA_WN16(p, v) SA_WB16(p, v)
#   endif

#   if    defined(SA_RN24) && !defined(SA_RB24)
#       define SA_RB24(p) SA_RN24(p)
#   elif !defined(SA_RN24) &&  defined(SA_RB24)
#       define SA_RN24(p) SA_RB24(p)
#   endif

#   if    defined(SA_WN24) && !defined(SA_WB24)
#       define SA_WB24(p, v) SA_WN24(p, v)
#   elif !defined(SA_WN24) &&  defined(SA_WB24)
#       define SA_WN24(p, v) SA_WB24(p, v)
#   endif

#   if    defined(SA_RN32) && !defined(SA_RB32)
#       define SA_RB32(p) SA_RN32(p)
#   elif !defined(SA_RN32) &&  defined(SA_RB32)
#       define SA_RN32(p) SA_RB32(p)
#   endif

#   if    defined(SA_WN32) && !defined(SA_WB32)
#       define SA_WB32(p, v) SA_WN32(p, v)
#   elif !defined(SA_WN32) &&  defined(SA_WB32)
#       define SA_WN32(p, v) SA_WB32(p, v)
#   endif

#   if    defined(SA_RN48) && !defined(SA_RB48)
#       define SA_RB48(p) SA_RN48(p)
#   elif !defined(SA_RN48) &&  defined(SA_RB48)
#       define SA_RN48(p) SA_RB48(p)
#   endif

#   if    defined(SA_WN48) && !defined(SA_WB48)
#       define SA_WB48(p, v) SA_WN48(p, v)
#   elif !defined(SA_WN48) &&  defined(SA_WB48)
#       define SA_WN48(p, v) SA_WB48(p, v)
#   endif

#   if    defined(SA_RN64) && !defined(SA_RB64)
#       define SA_RB64(p) SA_RN64(p)
#   elif !defined(SA_RN64) &&  defined(SA_RB64)
#       define SA_RN64(p) SA_RB64(p)
#   endif

#   if    defined(SA_WN64) && !defined(SA_WB64)
#       define SA_WB64(p, v) SA_WN64(p, v)
#   elif !defined(SA_WN64) &&  defined(SA_WB64)
#       define SA_WN64(p, v) SA_WB64(p, v)
#   endif

#else /* SA_HAVE_BIGENDIAN */

#   if    defined(SA_RN16) && !defined(SA_RL16)
#       define SA_RL16(p) SA_RN16(p)
#   elif !defined(SA_RN16) &&  defined(SA_RL16)
#       define SA_RN16(p) SA_RL16(p)
#   endif

#   if    defined(SA_WN16) && !defined(SA_WL16)
#       define SA_WL16(p, v) SA_WN16(p, v)
#   elif !defined(SA_WN16) &&  defined(SA_WL16)
#       define SA_WN16(p, v) SA_WL16(p, v)
#   endif

#   if    defined(SA_RN24) && !defined(SA_RL24)
#       define SA_RL24(p) SA_RN24(p)
#   elif !defined(SA_RN24) &&  defined(SA_RL24)
#       define SA_RN24(p) SA_RL24(p)
#   endif

#   if    defined(SA_WN24) && !defined(SA_WL24)
#       define SA_WL24(p, v) SA_WN24(p, v)
#   elif !defined(SA_WN24) &&  defined(SA_WL24)
#       define SA_WN24(p, v) SA_WL24(p, v)
#   endif

#   if    defined(SA_RN32) && !defined(SA_RL32)
#       define SA_RL32(p) SA_RN32(p)
#   elif !defined(SA_RN32) &&  defined(SA_RL32)
#       define SA_RN32(p) SA_RL32(p)
#   endif

#   if    defined(SA_WN32) && !defined(SA_WL32)
#       define SA_WL32(p, v) SA_WN32(p, v)
#   elif !defined(SA_WN32) &&  defined(SA_WL32)
#       define SA_WN32(p, v) SA_WL32(p, v)
#   endif

#   if    defined(SA_RN48) && !defined(SA_RL48)
#       define SA_RL48(p) SA_RN48(p)
#   elif !defined(SA_RN48) &&  defined(SA_RL48)
#       define SA_RN48(p) SA_RL48(p)
#   endif

#   if    defined(SA_WN48) && !defined(SA_WL48)
#       define SA_WL48(p, v) SA_WN48(p, v)
#   elif !defined(SA_WN48) &&  defined(SA_WL48)
#       define SA_WN48(p, v) SA_WL48(p, v)
#   endif

#   if    defined(SA_RN64) && !defined(SA_RL64)
#       define SA_RL64(p) SA_RN64(p)
#   elif !defined(SA_RN64) &&  defined(SA_RL64)
#       define SA_RN64(p) SA_RL64(p)
#   endif

#   if    defined(SA_WN64) && !defined(SA_WL64)
#       define SA_WL64(p, v) SA_WN64(p, v)
#   elif !defined(SA_WN64) &&  defined(SA_WL64)
#       define SA_WN64(p, v) SA_WL64(p, v)
#   endif

#endif /* !SA_HAVE_BIGENDIAN */

/*
 * Define SA_[RW]N helper macros to simplify definitions not provided
 * by per-arch headers.
 */

#if defined(__GNUC__) || defined(__clang__)

union unaligned_64 { uint64_t l; } __attribute__((packed)) sa_alias;
union unaligned_32 { uint32_t l; } __attribute__((packed)) sa_alias;
union unaligned_16 { uint16_t l; } __attribute__((packed)) sa_alias;

#   define SA_RN(s, p) (((const union unaligned_##s *) (p))->l)
#   define SA_WN(s, p, v) ((((union unaligned_##s *) (p))->l) = (v))

#elif defined(_MSC_VER) && (defined(_M_ARM) || defined(_M_X64) || defined(_M_ARM64)) && SA_HAVE_FAST_UNALIGNED

#   define SA_RN(s, p) (*((const __unaligned uint##s##_t*)(p)))
#   define SA_WN(s, p, v) (*((__unaligned uint##s##_t*)(p)) = (v))

#elif SA_HAVE_FAST_UNALIGNED

#   define SA_RN(s, p) (((const sa_alias##s*)(p))->u##s)
#   define SA_WN(s, p, v) (((sa_alias##s*)(p))->u##s = (v))

#else

#ifndef SA_RB16
#   define SA_RB16(x)                           \
    ((((const uint8_t*)(x))[0] << 8) |          \
      ((const uint8_t*)(x))[1])
#endif
#ifndef SA_WB16
#   define SA_WB16(p, val) do {                 \
        uint16_t d = (val);                     \
        ((uint8_t*)(p))[1] = (d);               \
        ((uint8_t*)(p))[0] = (d)>>8;            \
    } while(0)
#endif

#ifndef SA_RL16
#   define SA_RL16(x)                           \
    ((((const uint8_t*)(x))[1] << 8) |          \
      ((const uint8_t*)(x))[0])
#endif
#ifndef SA_WL16
#   define SA_WL16(p, val) do {                 \
        uint16_t d = (val);                     \
        ((uint8_t*)(p))[0] = (d);               \
        ((uint8_t*)(p))[1] = (d)>>8;            \
    } while(0)
#endif

#ifndef SA_RB32
#   define SA_RB32(x)                                \
    (((uint32_t)((const uint8_t*)(x))[0] << 24) |    \
               (((const uint8_t*)(x))[1] << 16) |    \
               (((const uint8_t*)(x))[2] <<  8) |    \
                ((const uint8_t*)(x))[3])
#endif
#ifndef SA_WB32
#   define SA_WB32(p, val) do {                 \
        uint32_t d = (val);                     \
        ((uint8_t*)(p))[3] = (d);               \
        ((uint8_t*)(p))[2] = (d)>>8;            \
        ((uint8_t*)(p))[1] = (d)>>16;           \
        ((uint8_t*)(p))[0] = (d)>>24;           \
    } while(0)
#endif

#ifndef SA_RL32
#   define SA_RL32(x)                                \
    (((uint32_t)((const uint8_t*)(x))[3] << 24) |    \
               (((const uint8_t*)(x))[2] << 16) |    \
               (((const uint8_t*)(x))[1] <<  8) |    \
                ((const uint8_t*)(x))[0])
#endif
#ifndef SA_WL32
#   define SA_WL32(p, val) do {                 \
        uint32_t d = (val);                     \
        ((uint8_t*)(p))[0] = (d);               \
        ((uint8_t*)(p))[1] = (d)>>8;            \
        ((uint8_t*)(p))[2] = (d)>>16;           \
        ((uint8_t*)(p))[3] = (d)>>24;           \
    } while(0)
#endif

#ifndef SA_RB64
#   define SA_RB64(x)                                   \
    (((uint64_t)((const uint8_t*)(x))[0] << 56) |       \
     ((uint64_t)((const uint8_t*)(x))[1] << 48) |       \
     ((uint64_t)((const uint8_t*)(x))[2] << 40) |       \
     ((uint64_t)((const uint8_t*)(x))[3] << 32) |       \
     ((uint64_t)((const uint8_t*)(x))[4] << 24) |       \
     ((uint64_t)((const uint8_t*)(x))[5] << 16) |       \
     ((uint64_t)((const uint8_t*)(x))[6] <<  8) |       \
      (uint64_t)((const uint8_t*)(x))[7])
#endif
#ifndef SA_WB64
#   define SA_WB64(p, val) do {                 \
        uint64_t d = (val);                     \
        ((uint8_t*)(p))[7] = (d);               \
        ((uint8_t*)(p))[6] = (d)>>8;            \
        ((uint8_t*)(p))[5] = (d)>>16;           \
        ((uint8_t*)(p))[4] = (d)>>24;           \
        ((uint8_t*)(p))[3] = (d)>>32;           \
        ((uint8_t*)(p))[2] = (d)>>40;           \
        ((uint8_t*)(p))[1] = (d)>>48;           \
        ((uint8_t*)(p))[0] = (d)>>56;           \
    } while(0)
#endif

#ifndef SA_RL64
#   define SA_RL64(x)                                   \
    (((uint64_t)((const uint8_t*)(x))[7] << 56) |       \
     ((uint64_t)((const uint8_t*)(x))[6] << 48) |       \
     ((uint64_t)((const uint8_t*)(x))[5] << 40) |       \
     ((uint64_t)((const uint8_t*)(x))[4] << 32) |       \
     ((uint64_t)((const uint8_t*)(x))[3] << 24) |       \
     ((uint64_t)((const uint8_t*)(x))[2] << 16) |       \
     ((uint64_t)((const uint8_t*)(x))[1] <<  8) |       \
      (uint64_t)((const uint8_t*)(x))[0])
#endif
#ifndef SA_WL64
#   define SA_WL64(p, val) do {                 \
        uint64_t d = (val);                     \
        ((uint8_t*)(p))[0] = (d);               \
        ((uint8_t*)(p))[1] = (d)>>8;            \
        ((uint8_t*)(p))[2] = (d)>>16;           \
        ((uint8_t*)(p))[3] = (d)>>24;           \
        ((uint8_t*)(p))[4] = (d)>>32;           \
        ((uint8_t*)(p))[5] = (d)>>40;           \
        ((uint8_t*)(p))[6] = (d)>>48;           \
        ((uint8_t*)(p))[7] = (d)>>56;           \
    } while(0)
#endif

#if SA_HAVE_BIGENDIAN
#   define SA_RN(s, p)    SA_RB##s(p)
#   define SA_WN(s, p, v) SA_WB##s(p, v)
#else
#   define SA_RN(s, p)    SA_RL##s(p)
#   define SA_WN(s, p, v) SA_WL##s(p, v)
#endif

#endif /* HAVE_FAST_UNALIGNED */

#ifndef SA_RN16
#   define SA_RN16(p) SA_RN(16, p)
#endif

#ifndef SA_RN32
#   define SA_RN32(p) SA_RN(32, p)
#endif

#ifndef SA_RN64
#   define SA_RN64(p) SA_RN(64, p)
#endif

#ifndef SA_WN16
#   define SA_WN16(p, v) SA_WN(16, p, v)
#endif

#ifndef SA_WN32
#   define SA_WN32(p, v) SA_WN(32, p, v)
#endif

#ifndef SA_WN64
#   define SA_WN64(p, v) SA_WN(64, p, v)
#endif

#if SA_HAVE_BIGENDIAN
#   define SA_RB(s, p)    SA_RN##s(p)
#   define SA_WB(s, p, v) SA_WN##s(p, v)
#   define SA_RL(s, p)    sa_bswap##s(SA_RN##s(p))
#   define SA_WL(s, p, v) SA_WN##s(p, sa_bswap##s(v))
#else
#   define SA_RB(s, p)    sa_bswap##s(SA_RN##s(p))
#   define SA_WB(s, p, v) SA_WN##s(p, sa_bswap##s(v))
#   define SA_RL(s, p)    SA_RN##s(p)
#   define SA_WL(s, p, v) SA_WN##s(p, v)
#endif

#define SA_RB8(x)     (((const uint8_t*)(x))[0])
#define SA_WB8(p, d)  do { ((uint8_t*)(p))[0] = (d); } while(0)

#define SA_RL8(x)     SA_RB8(x)
#define SA_WL8(p, d)  SA_WB8(p, d)

#ifndef SA_RB16
#   define SA_RB16(p)    SA_RB(16, p)
#endif
#ifndef SA_WB16
#   define SA_WB16(p, v) SA_WB(16, p, v)
#endif

#ifndef SA_RL16
#   define SA_RL16(p)    SA_RL(16, p)
#endif
#ifndef SA_WL16
#   define SA_WL16(p, v) SA_WL(16, p, v)
#endif

#ifndef SA_RB32
#   define SA_RB32(p)    SA_RB(32, p)
#endif
#ifndef SA_WB32
#   define SA_WB32(p, v) SA_WB(32, p, v)
#endif

#ifndef SA_RL32
#   define SA_RL32(p)    SA_RL(32, p)
#endif
#ifndef SA_WL32
#   define SA_WL32(p, v) SA_WL(32, p, v)
#endif

#ifndef SA_RB64
#   define SA_RB64(p)    SA_RB(64, p)
#endif
#ifndef SA_WB64
#   define SA_WB64(p, v) SA_WB(64, p, v)
#endif

#ifndef SA_RL64
#   define SA_RL64(p)    SA_RL(64, p)
#endif
#ifndef SA_WL64
#   define SA_WL64(p, v) SA_WL(64, p, v)
#endif

#ifndef SA_RB24
#   define SA_RB24(x)                           \
    ((((const uint8_t*)(x))[0] << 16) |         \
     (((const uint8_t*)(x))[1] <<  8) |         \
      ((const uint8_t*)(x))[2])
#endif
#ifndef SA_WB24
#   define SA_WB24(p, d) do {                   \
        ((uint8_t*)(p))[2] = (d);               \
        ((uint8_t*)(p))[1] = (d)>>8;            \
        ((uint8_t*)(p))[0] = (d)>>16;           \
    } while(0)
#endif

#ifndef SA_RL24
#   define SA_RL24(x)                           \
    ((((const uint8_t*)(x))[2] << 16) |         \
     (((const uint8_t*)(x))[1] <<  8) |         \
      ((const uint8_t*)(x))[0])
#endif
#ifndef SA_WL24
#   define SA_WL24(p, d) do {                   \
        ((uint8_t*)(p))[0] = (d);               \
        ((uint8_t*)(p))[1] = (d)>>8;            \
        ((uint8_t*)(p))[2] = (d)>>16;           \
    } while(0)
#endif

#ifndef SA_RB48
#   define SA_RB48(x)                                     \
    (((uint64_t)((const uint8_t*)(x))[0] << 40) |         \
     ((uint64_t)((const uint8_t*)(x))[1] << 32) |         \
     ((uint64_t)((const uint8_t*)(x))[2] << 24) |         \
     ((uint64_t)((const uint8_t*)(x))[3] << 16) |         \
     ((uint64_t)((const uint8_t*)(x))[4] <<  8) |         \
      (uint64_t)((const uint8_t*)(x))[5])
#endif
#ifndef SA_WB48
#   define SA_WB48(p, darg) do {                \
        uint64_t d = (darg);                    \
        ((uint8_t*)(p))[5] = (d);               \
        ((uint8_t*)(p))[4] = (d)>>8;            \
        ((uint8_t*)(p))[3] = (d)>>16;           \
        ((uint8_t*)(p))[2] = (d)>>24;           \
        ((uint8_t*)(p))[1] = (d)>>32;           \
        ((uint8_t*)(p))[0] = (d)>>40;           \
    } while(0)
#endif

#ifndef SA_RL48
#   define SA_RL48(x)                                     \
    (((uint64_t)((const uint8_t*)(x))[5] << 40) |         \
     ((uint64_t)((const uint8_t*)(x))[4] << 32) |         \
     ((uint64_t)((const uint8_t*)(x))[3] << 24) |         \
     ((uint64_t)((const uint8_t*)(x))[2] << 16) |         \
     ((uint64_t)((const uint8_t*)(x))[1] <<  8) |         \
      (uint64_t)((const uint8_t*)(x))[0])
#endif
#ifndef SA_WL48
#   define SA_WL48(p, darg) do {                \
        uint64_t d = (darg);                    \
        ((uint8_t*)(p))[0] = (d);               \
        ((uint8_t*)(p))[1] = (d)>>8;            \
        ((uint8_t*)(p))[2] = (d)>>16;           \
        ((uint8_t*)(p))[3] = (d)>>24;           \
        ((uint8_t*)(p))[4] = (d)>>32;           \
        ((uint8_t*)(p))[5] = (d)>>40;           \
    } while(0)
#endif

/*
 * The SA_[RW]NA macros access naturally aligned data
 * in a type-safe way.
 */

#define SA_RNA(s, p)    (((const sa_alias##s*)(p))->u##s)
#define SA_WNA(s, p, v) (((sa_alias##s*)(p))->u##s = (v))

#ifndef SA_RN16A
#   define SA_RN16A(p) SA_RNA(16, p)
#endif

#ifndef SA_RN32A
#   define SA_RN32A(p) SA_RNA(32, p)
#endif

#ifndef SA_RN64A
#   define SA_RN64A(p) SA_RNA(64, p)
#endif

#ifndef SA_WN16A
#   define SA_WN16A(p, v) SA_WNA(16, p, v)
#endif

#ifndef SA_WN32A
#   define SA_WN32A(p, v) SA_WNA(32, p, v)
#endif

#ifndef SA_WN64A
#   define SA_WN64A(p, v) SA_WNA(64, p, v)
#endif

#if SA_HAVE_BIGENDIAN
#   define SA_RLA(s, p)    sa_bswap##s(SA_RN##s##A(p))
#   define SA_WLA(s, p, v) SA_WN##s##A(p, sa_bswap##s(v))
#   define SA_RBA(s, p)    SA_RN##s##A(p)
#   define SA_WBA(s, p, v) SA_WN##s##A(p, v)
#else
#   define SA_RLA(s, p)    SA_RN##s##A(p)
#   define SA_WLA(s, p, v) SA_WN##s##A(p, v)
#   define SA_RBA(s, p)    sa_bswap##s(SA_RN##s##A(p))
#   define SA_WBA(s, p, v) SA_WN##s##A(p, sa_bswap##s(v))
#endif

#ifndef SA_RL16A
#   define SA_RL16A(p) SA_RLA(16, p)
#endif
#ifndef SA_WL16A
#   define SA_WL16A(p, v) SA_WLA(16, p, v)
#endif

#ifndef SA_RB16A
#   define SA_RB16A(p) SA_RBA(16, p)
#endif
#ifndef SA_WB16A
#   define SA_WB16A(p, v) SA_WBA(16, p, v)
#endif

#ifndef SA_RL32A
#   define SA_RL32A(p) SA_RLA(32, p)
#endif
#ifndef SA_WL32A
#   define SA_WL32A(p, v) SA_WLA(32, p, v)
#endif

#ifndef SA_RB32A
#   define SA_RB32A(p) SA_RBA(32, p)
#endif
#ifndef SA_WB32A
#   define SA_WB32A(p, v) SA_WBA(32, p, v)
#endif

#ifndef SA_RL64A
#   define SA_RL64A(p) SA_RLA(64, p)
#endif
#ifndef SA_WL64A
#   define SA_WL64A(p, v) SA_WLA(64, p, v)
#endif

#ifndef SA_RB64A
#   define SA_RB64A(p) SA_RBA(64, p)
#endif
#ifndef SA_WB64A
#   define SA_WB64A(p, v) SA_WBA(64, p, v)
#endif

/*
 * The SA_COPYxxU macros are suitable for copying data to/from unaligned
 * memory locations.
 */

#define SA_COPYU(n, d, s) SA_WN##n(d, SA_RN##n(s));

#ifndef SA_COPY16U
#   define SA_COPY16U(d, s) SA_COPYU(16, d, s)
#endif

#ifndef SA_COPY32U
#   define SA_COPY32U(d, s) SA_COPYU(32, d, s)
#endif

#ifndef SA_COPY64U
#   define SA_COPY64U(d, s) SA_COPYU(64, d, s)
#endif

#ifndef SA_COPY128U
#   define SA_COPY128U(d, s)                                    \
    do {                                                        \
        SA_COPY64U(d, s);                                       \
        SA_COPY64U((char *)(d) + 8, (const char *)(s) + 8);     \
    } while(0)
#endif

/* Parameters for SA_COPY*, SA_SWAP*, SA_ZERO* must be
 * naturally aligned.
 */

#define SA_COPY(n, d, s) \
    (((sa_alias##n*)(d))->u##n = ((const sa_alias##n*)(s))->u##n)

#ifndef SA_COPY16
#   define SA_COPY16(d, s) SA_COPY(16, d, s)
#endif

#ifndef SA_COPY32
#   define SA_COPY32(d, s) SA_COPY(32, d, s)
#endif

#ifndef SA_COPY64
#   define SA_COPY64(d, s) SA_COPY(64, d, s)
#endif

#ifndef SA_COPY128
#   define SA_COPY128(d, s)                    \
    do {                                       \
        SA_COPY64(d, s);                       \
        SA_COPY64((char*)(d)+8, (char*)(s)+8); \
    } while(0)
#endif

#define SA_SWAP(n, a, b) SASWAP(sa_alias##n, *(sa_alias##n*)(a), *(sa_alias##n*)(b))

#ifndef SA_SWAP64
#   define SA_SWAP64(a, b) SA_SWAP(64, a, b)
#endif

#define SA_ZERO(n, d) (((sa_alias##n*)(d))->u##n = 0)

#ifndef SA_ZERO16
#   define SA_ZERO16(d) SA_ZERO(16, d)
#endif

#ifndef SA_ZERO32
#   define SA_ZERO32(d) SA_ZERO(32, d)
#endif

#ifndef SA_ZERO64
#   define SA_ZERO64(d) SA_ZERO(64, d)
#endif

#ifndef SA_ZERO128
#   define SA_ZERO128(d)         \
    do {                         \
        SA_ZERO64(d);            \
        SA_ZERO64((char*)(d)+8); \
    } while(0)
#endif

#endif /* SAUTIL_INTREADWRITE_H */
