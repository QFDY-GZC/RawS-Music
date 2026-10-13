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
 * @ingroup lavu
 * Utility Preprocessor macros
 */

#ifndef SAUTIL_MACROS_H
#define SAUTIL_MACROS_H

#include "libsautil/saconfig.h"

#define SAMAX(a,b) ((a) > (b) ? (a) : (b))
#define SAMAX3(a,b,c) SAMAX(SAMAX(a,b),c)
#define SAMIN(a,b) ((a) > (b) ? (b) : (a))
#define SAMIN3(a,b,c) SAMIN(SAMIN(a,b),c)

#define SASWAP(type,a,b) do{type SWAP_tmp= b; b= a; a= SWAP_tmp;}while(0)

#define MKTAG(a,b,c,d)   ((a) | ((b) << 8) | ((c) << 16) | ((unsigned)(d) << 24))
#define MKBETAG(a,b,c,d) ((d) | ((c) << 8) | ((b) << 16) | ((unsigned)(a) << 24))

/**
 * @addtogroup preproc_misc Preprocessor String Macros
 *
 * String manipulation macros
 *
 * @{
 */

#define SA_STRINGIFY(s)         SA_TOSTRING(s)
#define SA_TOSTRING(s) #s

#define SA_GLUE(a, b) a ## b
#define SA_JOIN(a, b) SA_GLUE(a, b)

#endif /* SAUTIL_MACROS_H */
