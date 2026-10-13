/*
 * This file is part of FFmpeg.
 *
 * FFmpeg is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * FFmpeg is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with FFmpeg; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

#ifndef SAUTIL_DYNARRAY_H
#define SAUTIL_DYNARRAY_H

#include "log.h"
#include "mem.h"

/**
 * Add an element to a dynamic array.
 *
 * The array is reallocated when its number of elements reaches powers of 2.
 * Therefore, the amortized cost of adding an element is constant.
 *
 * In case of success, the pointer to the array is updated in order to
 * point to the new grown array, and the size is incremented.
 *
 * @param sa_size_max  maximum size of the array, usually the MAX macro of
 *                     the type of the size
 * @param sa_elt_size  size of the elements in the array, in bytes
 * @param sa_array     pointer to the array, must be a lvalue
 * @param sa_size      size of the array, must be an integer lvalue
 * @param sa_success   statement to execute on success; at this point, the
 *                     size variable is not yet incremented
 * @param sa_failure   statement to execute on failure; if this happens, the
 *                     array and size are not changed; the statement can end
 *                     with a return or a goto
 */
#define FF_DYNARRAY_ADD(sa_size_max, sa_elt_size, sa_array, sa_size, \
                        sa_success, sa_failure) \
    do { \
        size_t sa_size_new = (sa_size); \
        if (!((sa_size) & ((sa_size) - 1))) { \
            sa_size_new = (sa_size) ? (sa_size) << 1 : 1; \
            if (sa_size_new > (sa_size_max) / (sa_elt_size)) { \
                sa_size_new = 0; \
            } else { \
                void *sa_array_new = \
                    sa_realloc((sa_array), sa_size_new * (sa_elt_size)); \
                if (!sa_array_new) \
                    sa_size_new = 0; \
                else \
                    (sa_array) = sa_array_new; \
            } \
        } \
        if (sa_size_new) { \
            { sa_success } \
            (sa_size)++; \
        } else { \
            sa_failure \
        } \
    } while (0)

#endif /* SAUTIL_DYNARRAY_H */
