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

#ifndef SAUTIL_BUFFER_INTERNAL_H
#define SAUTIL_BUFFER_INTERNAL_H

#include <stdatomic.h>
#include <stdint.h>
#ifdef __APPLE__
#include "c11threads.h"
#else
#include <threads.h>
#endif

#include "buffer.h"

/**
 * The buffer was sa_realloc()ed, so it is reallocatable.
 */
#define BUFFER_FLAG_REALLOCATABLE (1 << 0)
/**
 * The sa_buffer_t structure is part of a larger structure
 * and should not be freed.
 */
#define BUFFER_FLAG_NO_FREE       (1 << 1)

struct sa_buffer_s {
    uint8_t *data; /**< data described by this buffer */
    size_t size; /**< size of data in bytes */

    /**
     *  number of existing sa_buffer_ref_t instances referring to this buffer
     */
    atomic_uint refcount;

    /**
     * a callback for freeing the data
     */
    void (*free)(void *opaque, uint8_t *data);

    /**
     * an opaque pointer, to be used by the freeing callback
     */
    void *opaque;

    /**
     * A combination of SA_BUFFER_FLAG_*
     */
    int flags;

    /**
     * A combination of BUFFER_FLAG_*
     */
    int flags_internal;
};

typedef struct sa_buffer_pool_entry_t {
    uint8_t *data;

    /*
     * Backups of the original opaque/free of the sa_buffer_t corresponding to
     * data. They will be used to free the buffer when the pool is freed.
     */
    void *opaque;
    void (*free)(void *opaque, uint8_t *data);

    sa_buffer_pool_t *pool;
    struct sa_buffer_pool_entry_t *next;

    /*
     * An sa_buffer_t structure to (re)use as sa_buffer_t for subsequent uses
     * of this sa_buffer_pool_entry_t.
     */
    sa_buffer_t buffer;
} sa_buffer_pool_entry_t;

struct sa_buffer_pool_s {
    mtx_t mutex;
    sa_buffer_pool_entry_t *pool;

    /*
     * This is used to track when the pool is to be freed.
     * The pointer to the pool itself held by the caller is considered to
     * be one reference. Each buffer requested by the caller increases refcount
     * by one, returning the buffer to the pool decreases it by one.
     * refcount reaches zero when the buffer has been uninited AND all the
     * buffers have been released, then it's safe to free the pool and all
     * the buffers in it.
     */
    atomic_uint refcount;

    size_t size;
    void *opaque;
    sa_buffer_ref_t* (*alloc)(size_t size);
    sa_buffer_ref_t* (*alloc2)(void *opaque, size_t size);
    void         (*pool_free)(void *opaque);
};

#endif /* SAUTIL_BUFFER_INTERNAL_H */
