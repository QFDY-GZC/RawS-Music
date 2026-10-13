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

#include <stdatomic.h>
#include <stdint.h>
#include <string.h>

#include "sa_assert.h"
#include "buffer_internal.h"
#include "error.h"
#include "mem.h"

static sa_buffer_ref_t *buffer_create(sa_buffer_t *buf, uint8_t *data, size_t size,
                                  void (*free)(void *opaque, uint8_t *data),
                                  void *opaque, int flags)
{
    sa_buffer_ref_t *ref = NULL;

    buf->data     = data;
    buf->size     = size;
    buf->free     = free ? free : sa_buffer_default_free;
    buf->opaque   = opaque;

    atomic_init(&buf->refcount, 1);

    buf->flags = flags;

    ref = sa_mallocz(sizeof(*ref));
    if (!ref)
        return NULL;

    ref->buffer = buf;
    ref->data   = data;
    ref->size   = size;

    return ref;
}

sa_buffer_ref_t *sa_buffer_create(uint8_t *data, size_t size,
                              void (*free)(void *opaque, uint8_t *data),
                              void *opaque, int flags)
{
    sa_buffer_ref_t *ret;
    sa_buffer_t *buf = sa_mallocz(sizeof(*buf));
    if (!buf)
        return NULL;

    ret = buffer_create(buf, data, size, free, opaque, flags);
    if (!ret) {
        sa_free(buf);
        return NULL;
    }
    return ret;
}

void sa_buffer_default_free(void *opaque, uint8_t *data)
{
    sa_free(data);
}

sa_buffer_ref_t *sa_buffer_alloc(size_t size)
{
    sa_buffer_ref_t *ret = NULL;
    uint8_t    *data = NULL;

    data = sa_malloc(size);
    if (!data)
        return NULL;

    ret = sa_buffer_create(data, size, sa_buffer_default_free, NULL, 0);
    if (!ret)
        sa_freep(&data);

    return ret;
}

sa_buffer_ref_t *sa_buffer_allocz(size_t size)
{
    sa_buffer_ref_t *ret = sa_buffer_alloc(size);
    if (!ret)
        return NULL;

    memset(ret->data, 0, size);
    return ret;
}

sa_buffer_ref_t *sa_buffer_ref(const sa_buffer_ref_t *buf)
{
    sa_buffer_ref_t *ret = sa_mallocz(sizeof(*ret));

    if (!ret)
        return NULL;

    *ret = *buf;

    atomic_fetch_add_explicit(&buf->buffer->refcount, 1, memory_order_relaxed);

    return ret;
}

static void buffer_replace(sa_buffer_ref_t **dst, sa_buffer_ref_t **src)
{
    sa_buffer_t *b;

    b = (*dst)->buffer;

    if (src) {
        **dst = **src;
        sa_freep(src);
    } else
        sa_freep(dst);

    if (atomic_fetch_sub_explicit(&b->refcount, 1, memory_order_acq_rel) == 1) {
        /* b->free below might already free the structure containing *b,
         * so we have to read the flag now to avoid use-after-free. */
        int free_avbuffer = !(b->flags_internal & BUFFER_FLAG_NO_FREE);
        b->free(b->opaque, b->data);
        if (free_avbuffer)
            sa_free(b);
    }
}

void sa_buffer_unref(sa_buffer_ref_t **buf)
{
    if (!buf || !*buf)
        return;

    buffer_replace(buf, NULL);
}

int sa_buffer_is_writable(const sa_buffer_ref_t *buf)
{
    if (buf->buffer->flags & SA_BUFFER_FLAG_READONLY)
        return 0;

    return atomic_load(&buf->buffer->refcount) == 1;
}

void *sa_buffer_get_opaque(const sa_buffer_ref_t *buf)
{
    return buf->buffer->opaque;
}

int sa_buffer_get_ref_count(const sa_buffer_ref_t *buf)
{
    return atomic_load(&buf->buffer->refcount);
}

int sa_buffer_make_writable(sa_buffer_ref_t **pbuf)
{
    sa_buffer_ref_t *newbuf, *buf = *pbuf;

    if (sa_buffer_is_writable(buf))
        return 0;

    newbuf = sa_buffer_alloc(buf->size);
    if (!newbuf)
        return AVERROR(ENOMEM);

    memcpy(newbuf->data, buf->data, buf->size);

    buffer_replace(pbuf, &newbuf);

    return 0;
}

int sa_buffer_realloc(sa_buffer_ref_t **pbuf, size_t size)
{
    sa_buffer_ref_t *buf = *pbuf;
    uint8_t *tmp;
    int ret;

    if (!buf) {
        /* allocate a new buffer with sa_realloc(), so it will be reallocatable
         * later */
        uint8_t *data = sa_realloc(NULL, size);
        if (!data)
            return AVERROR(ENOMEM);

        buf = sa_buffer_create(data, size, sa_buffer_default_free, NULL, 0);
        if (!buf) {
            sa_freep(&data);
            return AVERROR(ENOMEM);
        }

        buf->buffer->flags_internal |= BUFFER_FLAG_REALLOCATABLE;
        *pbuf = buf;

        return 0;
    } else if (buf->size == size)
        return 0;

    if (!(buf->buffer->flags_internal & BUFFER_FLAG_REALLOCATABLE) ||
        !sa_buffer_is_writable(buf) || buf->data != buf->buffer->data) {
        /* cannot realloc, allocate a new reallocable buffer and copy data */
        sa_buffer_ref_t *new = NULL;

        ret = sa_buffer_realloc(&new, size);
        if (ret < 0)
            return ret;

        memcpy(new->data, buf->data, SAMIN(size, buf->size));

        buffer_replace(pbuf, &new);
        return 0;
    }

    tmp = sa_realloc(buf->buffer->data, size);
    if (!tmp)
        return AVERROR(ENOMEM);

    buf->buffer->data = buf->data = tmp;
    buf->buffer->size = buf->size = size;
    return 0;
}

int sa_buffer_replace(sa_buffer_ref_t **pdst, const sa_buffer_ref_t *src)
{
    sa_buffer_ref_t *dst = *pdst;
    sa_buffer_ref_t *tmp;

    if (!src) {
        sa_buffer_unref(pdst);
        return 0;
    }

    if (dst && dst->buffer == src->buffer) {
        /* make sure the data pointers match */
        dst->data = src->data;
        dst->size = src->size;
        return 0;
    }

    tmp = sa_buffer_ref(src);
    if (!tmp)
        return AVERROR(ENOMEM);

    sa_buffer_unref(pdst);
    *pdst = tmp;
    return 0;
}

sa_buffer_pool_t *sa_buffer_pool_init2(size_t size, void *opaque,
                                   sa_buffer_ref_t* (*alloc)(void *opaque, size_t size),
                                   void (*pool_free)(void *opaque))
{
    sa_buffer_pool_t *pool = sa_mallocz(sizeof(*pool));
    if (!pool)
        return NULL;

    if (mtx_init(&pool->mutex, mtx_plain) != thrd_success) {
        sa_free(pool);
        return NULL;
    }

    pool->size      = size;
    pool->opaque    = opaque;
    pool->alloc2    = alloc;
    pool->alloc     = sa_buffer_alloc; // fallback
    pool->pool_free = pool_free;

    atomic_init(&pool->refcount, 1);

    return pool;
}

sa_buffer_pool_t *sa_buffer_pool_init(size_t size, sa_buffer_ref_t* (*alloc)(size_t size))
{
    sa_buffer_pool_t *pool = sa_mallocz(sizeof(*pool));
    if (!pool)
        return NULL;

    if (mtx_init(&pool->mutex, mtx_plain) != thrd_success) {
        sa_free(pool);
        return NULL;
    }

    pool->size     = size;
    pool->alloc    = alloc ? alloc : sa_buffer_alloc;

    atomic_init(&pool->refcount, 1);

    return pool;
}

static void buffer_pool_flush(sa_buffer_pool_t *pool)
{
    while (pool->pool) {
        sa_buffer_pool_entry_t *buf = pool->pool;
        pool->pool = buf->next;

        buf->free(buf->opaque, buf->data);
        sa_freep(&buf);
    }
}

/*
 * This function gets called when the pool has been uninited and
 * all the buffers returned to it.
 */
static void buffer_pool_free(sa_buffer_pool_t *pool)
{
    buffer_pool_flush(pool);
    mtx_destroy(&pool->mutex);

    if (pool->pool_free)
        pool->pool_free(pool->opaque);

    sa_freep(&pool);
}

void sa_buffer_pool_uninit(sa_buffer_pool_t **ppool)
{
    sa_buffer_pool_t *pool;

    if (!ppool || !*ppool)
        return;
    pool   = *ppool;
    *ppool = NULL;

    mtx_lock(&pool->mutex);
    buffer_pool_flush(pool);
    mtx_unlock(&pool->mutex);

    if (atomic_fetch_sub_explicit(&pool->refcount, 1, memory_order_acq_rel) == 1)
        buffer_pool_free(pool);
}

static void pool_release_buffer(void *opaque, uint8_t *data)
{
    sa_buffer_pool_entry_t *buf = opaque;
    sa_buffer_pool_t *pool = buf->pool;

    mtx_lock(&pool->mutex);
    buf->next = pool->pool;
    pool->pool = buf;
    mtx_unlock(&pool->mutex);

    if (atomic_fetch_sub_explicit(&pool->refcount, 1, memory_order_acq_rel) == 1)
        buffer_pool_free(pool);
}

/* allocate a new buffer and override its free() callback so that
 * it is returned to the pool on free */
static sa_buffer_ref_t *pool_alloc_buffer(sa_buffer_pool_t *pool)
{
    sa_buffer_pool_entry_t *buf;
    sa_buffer_ref_t     *ret;

    sa_assert0(pool->alloc || pool->alloc2);

    ret = pool->alloc2 ? pool->alloc2(pool->opaque, pool->size) :
                         pool->alloc(pool->size);
    if (!ret)
        return NULL;

    buf = sa_mallocz(sizeof(*buf));
    if (!buf) {
        sa_buffer_unref(&ret);
        return NULL;
    }

    buf->data   = ret->buffer->data;
    buf->opaque = ret->buffer->opaque;
    buf->free   = ret->buffer->free;
    buf->pool   = pool;

    ret->buffer->opaque = buf;
    ret->buffer->free   = pool_release_buffer;

    return ret;
}

sa_buffer_ref_t *sa_buffer_pool_get(sa_buffer_pool_t *pool)
{
    sa_buffer_ref_t *ret;
    sa_buffer_pool_entry_t *buf;

    mtx_lock(&pool->mutex);
    buf = pool->pool;
    if (buf) {
        memset(&buf->buffer, 0, sizeof(buf->buffer));
        ret = buffer_create(&buf->buffer, buf->data, pool->size,
                            pool_release_buffer, buf, 0);
        if (ret) {
            pool->pool = buf->next;
            buf->next = NULL;
            buf->buffer.flags_internal |= BUFFER_FLAG_NO_FREE;
        }
    } else {
        ret = pool_alloc_buffer(pool);
    }
    mtx_unlock(&pool->mutex);

    if (ret)
        atomic_fetch_add_explicit(&pool->refcount, 1, memory_order_relaxed);

    return ret;
}

void *sa_buffer_pool_buffer_get_opaque(const sa_buffer_ref_t *ref)
{
    sa_buffer_pool_entry_t *buf = ref->buffer->opaque;
    sa_assert0(buf);
    return buf->opaque;
}
