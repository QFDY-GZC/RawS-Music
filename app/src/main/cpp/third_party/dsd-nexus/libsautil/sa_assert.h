/*
 * copyright (c) 2010 Michael Niedermayer <michaelni@gmx.at>
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
 * simple assert() macros that are a bit more flexible than ISO C assert().
 * @author Michael Niedermayer <michaelni@gmx.at>
 */

#ifndef SAUTIL_AVASSERT_H
#define SAUTIL_AVASSERT_H

#include <stdlib.h>
#ifdef HAVE_SA_CONFIG_H
#   include "config.h"
#endif
#include "attributes.h"
#include "log.h"
#include "macros.h"

/**
 * assert() equivalent, that is always enabled.
 */
#define sa_assert0(cond) do {                                           \
    if (!(cond)) {                                                      \
        sa_log(NULL, SA_LOG_PANIC, "Assertion %s failed at %s:%d\n",    \
               SA_STRINGIFY(cond), __FILE__, __LINE__);                 \
        abort();                                                        \
    }                                                                   \
} while (0)


/**
 * assert() equivalent, that does not lie in speed critical code.
 * These asserts() thus can be enabled without fearing speed loss.
 */
#if defined(ASSERT_LEVEL) && ASSERT_LEVEL > 0
#define sa_assert1(cond) sa_assert0(cond)
#else
#define sa_assert1(cond) ((void)0)
#endif


/**
 * assert() equivalent, that does lie in speed critical code.
 */
#if defined(ASSERT_LEVEL) && ASSERT_LEVEL > 1
#define sa_assert2(cond) sa_assert0(cond)
#define sa_assert2_fpu() sa_assert0_fpu()
#else
#define sa_assert2(cond) ((void)0)
#define sa_assert2_fpu() ((void)0)
#endif

/**
 * Assert that floating point operations can be executed.
 *
 * This will sa_assert0() that the cpu is not in MMX state on X86
 */
void sa_assert0_fpu(void);

/**
 * Asserts that are used as compiler optimization hints depending
 * upon ASSERT_LEVEL and NBDEBUG.
 *
 * Undefined behaviour occurs if execution reaches a point marked
 * with sa_unreachable() or if a condition used with sa_assume()
 * is false.
 *
 * The condition used with sa_assume() should not have side-effects
 * and should be visible to the compiler.
 */
#if defined(ASSERT_LEVEL) ? ASSERT_LEVEL > 0 : !defined(HAVE_SA_CONFIG_H) && !defined(NDEBUG)
#define sa_unreachable(msg)                                             \
do {                                                                    \
    sa_log(NULL, SA_LOG_PANIC,                                          \
           "Reached supposedly unreachable code at %s:%d: %s\n",        \
           __FILE__, __LINE__, msg);                                    \
    abort();                                                            \
} while (0)
#define sa_assume(cond) sa_assert0(cond)
#else
#if SA_GCC_VERSION_AT_LEAST(4, 5) || SA_HAS_BUILTIN(__builtin_unreachable)
#define sa_unreachable(msg) __builtin_unreachable()
#elif  defined(_MSC_VER)
#define sa_unreachable(msg) __assume(0)
#elif __STDC_VERSION__ >= 202311L
#include <stddef.h>
#define sa_unreachable(msg) unreachable()
#else
#define sa_unreachable(msg) ((void)0)
#endif

#define sa_assume(cond) do { \
    if (!(cond))             \
        sa_unreachable();    \
} while (0)
#endif

#endif /* SAUTIL_AVASSERT_H */
