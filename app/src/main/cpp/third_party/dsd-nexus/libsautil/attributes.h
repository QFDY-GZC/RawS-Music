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
 * Macro definitions for various function/variable attributes
 */

#ifndef SAUTIL_ATTRIBUTES_H
#define SAUTIL_ATTRIBUTES_H

#ifdef __GNUC__
#    define SA_GCC_VERSION_AT_LEAST(x,y) (__GNUC__ > (x) || __GNUC__ == (x) && __GNUC_MINOR__ >= (y))
#    define SA_GCC_VERSION_AT_MOST(x,y)  (__GNUC__ < (x) || __GNUC__ == (x) && __GNUC_MINOR__ <= (y))
#else
#    define SA_GCC_VERSION_AT_LEAST(x,y) 0
#    define SA_GCC_VERSION_AT_MOST(x,y)  0
#endif

#ifdef __has_builtin
#    define SA_HAS_BUILTIN(x) __has_builtin(x)
#else
#    define SA_HAS_BUILTIN(x) 0
#endif

#ifdef __has_attribute
#    define SA_HAS_ATTRIBUTE(x) __has_attribute(x)
#else
#    define SA_HAS_ATTRIBUTE(x) 0
#endif

#if defined(__cplusplus) && defined(__has_cpp_attribute)
#    define SA_HAS_STD_ATTRIBUTE(x) __has_cpp_attribute(x)
#elif !defined(__cplusplus) && defined(__has_c_attribute)
#    define SA_HAS_STD_ATTRIBUTE(x) __has_c_attribute(x)
#else
#    define SA_HAS_STD_ATTRIBUTE(x) 0
#endif

#ifndef sa_always_inline
#if SA_GCC_VERSION_AT_LEAST(3,1) || defined(__clang__)
#    define sa_always_inline __attribute__((always_inline)) inline
#elif defined(_MSC_VER)
#    define sa_always_inline __forceinline
#else
#    define sa_always_inline inline
#endif
#endif

#ifndef sa_extern_inline
#if defined(__ICL) && __ICL >= 1210 || defined(__GNUC_STDC_INLINE__)
#    define sa_extern_inline extern inline
#else
#    define sa_extern_inline inline
#endif
#endif

#if SA_HAS_STD_ATTRIBUTE(nodiscard)
#    define sa_warn_unused_result [[nodiscard]]
#elif SA_GCC_VERSION_AT_LEAST(3,4) || defined(__clang__)
#    define sa_warn_unused_result __attribute__((warn_unused_result))
#else
#    define sa_warn_unused_result
#endif

#if SA_GCC_VERSION_AT_LEAST(3,1) || defined(__clang__)
#    define sa_noinline __attribute__((noinline))
#elif defined(_MSC_VER)
#    define sa_noinline __declspec(noinline)
#else
#    define sa_noinline
#endif

#if SA_GCC_VERSION_AT_LEAST(3,1) || defined(__clang__)
#    define sa_pure __attribute__((pure))
#else
#    define sa_pure
#endif

#if SA_GCC_VERSION_AT_LEAST(2,6) || defined(__clang__)
#    define sa_const __attribute__((const))
#else
#    define sa_const
#endif

#if SA_GCC_VERSION_AT_LEAST(4,3) || defined(__clang__)
#    define sa_cold __attribute__((cold))
#else
#    define sa_cold
#endif

#if SA_GCC_VERSION_AT_LEAST(4,1) && !defined(__llvm__)
#    define sa_flatten __attribute__((flatten))
#else
#    define sa_flatten
#endif

#if SA_HAS_STD_ATTRIBUTE(deprecated)
#    define attribute_deprecated [[deprecated]]
#elif SA_GCC_VERSION_AT_LEAST(3,1) || defined(__clang__)
#    define attribute_deprecated __attribute__((deprecated))
#elif defined(_MSC_VER)
#    define attribute_deprecated __declspec(deprecated)
#else
#    define attribute_deprecated
#endif

/**
 * Disable warnings about deprecated features
 * This is useful for sections of code kept for backward compatibility and
 * scheduled for removal.
 */
#ifndef SA_NOWARN_DEPRECATED
#if SA_GCC_VERSION_AT_LEAST(4,6) || defined(__clang__)
#    define SA_NOWARN_DEPRECATED(code) \
        _Pragma("GCC diagnostic push") \
        _Pragma("GCC diagnostic ignored \"-Wdeprecated-declarations\"") \
        code \
        _Pragma("GCC diagnostic pop")
#elif defined(_MSC_VER)
#    define SA_NOWARN_DEPRECATED(code) \
        __pragma(warning(push)) \
        __pragma(warning(disable : 4996)) \
        code; \
        __pragma(warning(pop))
#else
#    define SA_NOWARN_DEPRECATED(code) code
#endif
#endif

#if SA_HAS_STD_ATTRIBUTE(maybe_unused)
#    define sa_unused [[maybe_unused]]
#elif defined(__GNUC__) || defined(__clang__)
#    define sa_unused __attribute__((unused))
#else
#    define sa_unused
#endif

/**
 * Mark a variable as used and prevent the compiler from optimizing it
 * away.  This is useful for variables accessed only from inline
 * assembler without the compiler being aware.
 */
#if SA_GCC_VERSION_AT_LEAST(3,1) || defined(__clang__)
#    define sa_used __attribute__((used))
#else
#    define sa_used
#endif

#if SA_GCC_VERSION_AT_LEAST(3,3) || defined(__clang__)
#   define sa_alias __attribute__((may_alias))
#else
#   define sa_alias
#endif

#if (defined(__GNUC__) || defined(__clang__)) && !defined(__INTEL_COMPILER)
#    define sa_uninit(x) x=x
#else
#    define sa_uninit(x) x
#endif

#if defined(__GNUC__) || defined(__clang__)
#    define sa_builtin_constant_p __builtin_constant_p
#else
#    define sa_builtin_constant_p(x) 0
#endif

// for __MINGW_PRINTF_FORMAT and __MINGW_SCANF_FORMAT
#ifdef __MINGW32__
#    include <stdio.h>
#endif

#ifdef __MINGW_PRINTF_FORMAT
#    define SA_PRINTF_FMT __MINGW_PRINTF_FORMAT
#elif SA_HAS_ATTRIBUTE(format)
#    define SA_PRINTF_FMT __printf__
#endif

#ifdef __MINGW_SCANF_FORMAT
#    define SA_SCANF_FMT __MINGW_SCANF_FORMAT
#elif SA_HAS_ATTRIBUTE(format)
#    define SA_SCANF_FMT __scanf__
#endif

#ifdef SA_PRINTF_FMT
#    define sa_printf_format(fmtpos, attrpos) __attribute__((format(SA_PRINTF_FMT, fmtpos, attrpos)))
#else
#    define sa_printf_format(fmtpos, attrpos)
#endif

#ifdef SA_SCANF_FMT
#    define sa_scanf_format(fmtpos, attrpos) __attribute__((format(SA_SCANF_FMT, fmtpos, attrpos)))
#else
#    define sa_scanf_format(fmtpos, attrpos)
#endif

#if SA_HAS_STD_ATTRIBUTE(noreturn)
#    define sa_noreturn [[noreturn]]
#elif SA_GCC_VERSION_AT_LEAST(2,5) || defined(__clang__)
#    define sa_noreturn __attribute__((noreturn))
#else
#    define sa_noreturn
#endif

#endif /* SAUTIL_ATTRIBUTES_H */
