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
 * @ingroup lavu_buffer
 * refcounted data buffer API
 */

#ifndef SAUTIL_BUFFER_H
#define SAUTIL_BUFFER_H

#include <stddef.h>
#include <stdint.h>
#include "export.h"

/**
 * @defgroup lavu_buffer sa_buffer_t
 * @ingroup lavu_data
 *
 * @{
 * sa_buffer_t is an API for reference-counted data buffers.
 *
 * There are two core objects in this API -- sa_buffer_t and sa_buffer_ref_t. sa_buffer_t
 * represents the data buffer itself; it is opaque and not meant to be accessed
 * by the caller directly, but only through sa_buffer_ref_t. However, the caller may
 * e.g. compare two sa_buffer_t pointers to check whether two different references
 * are describing the same data buffer. sa_buffer_ref_t represents a single
 * reference to an sa_buffer_t and it is the object that may be manipulated by the
 * caller directly.
 *
 * There are two functions provided for creating a new sa_buffer_t with a single
 * reference -- sa_buffer_alloc() to just allocate a new buffer, and
 * sa_buffer_create() to wrap an existing array in an sa_buffer_t. From an existing
 * reference, additional references may be created with sa_buffer_ref().
 * Use sa_buffer_unref() to free a reference (this will automatically free the
 * data once all the references are freed).
 *
 * The convention throughout this API and the rest of FFmpeg is such that the
 * buffer is considered writable if there exists only one reference to it (and
 * it has not been marked as read-only). The sa_buffer_is_writable() function is
 * provided to check whether this is true and sa_buffer_make_writable() will
 * automatically create a new writable buffer when necessary.
 * Of course nothing prevents the calling code from violating this convention,
 * however that is safe only when all the existing references are under its
 * control.
 *
 * @note Referencing and unreferencing the buffers is thread-safe and thus
 * may be done from multiple threads simultaneously without any need for
 * additional locking.
 *
 * @note Two different references to the same buffer can point to different
 * parts of the buffer (i.e. their sa_buffer_ref_t.data will not be equal).
 */

/**
 * A reference counted buffer type. It is opaque and is meant to be used through
 * references (sa_buffer_ref_t).
 */
typedef struct sa_buffer_s sa_buffer_t;

/**
 * A reference to a data buffer.
 *
 * The size of this struct is not a part of the public ABI and it is not meant
 * to be allocated directly.
 */
typedef struct sa_buffer_ref_t {
    sa_buffer_t *buffer;

    /**
     * The data buffer. It is considered writable if and only if
     * this is the only reference to the buffer, in which case
     * sa_buffer_is_writable() returns 1.
     */
    uint8_t *data;
    /**
     * Size of data in bytes.
     */
    size_t   size;
} sa_buffer_ref_t;

/**
 * Allocate an sa_buffer_t of the given size using sa_malloc().
 *
 * @return an sa_buffer_ref_t of given size or NULL when out of memory
 */
SACD_API sa_buffer_ref_t * sa_buffer_alloc(size_t size);

/**
 * Same as sa_buffer_alloc(), except the returned buffer will be initialized
 * to zero.
 */
SACD_API sa_buffer_ref_t * sa_buffer_allocz(size_t size);

/**
 * Always treat the buffer as read-only, even when it has only one
 * reference.
 */
#define SA_BUFFER_FLAG_READONLY (1 << 0)

/**
 * Create an sa_buffer_t from an existing array.
 *
 * If this function is successful, data is owned by the sa_buffer_t. The caller may
 * only access data through the returned sa_buffer_ref_t and references derived from
 * it.
 * If this function fails, data is left untouched.
 * @param data   data array
 * @param size   size of data in bytes
 * @param free   a callback for freeing this buffer's data
 * @param opaque parameter to be got for processing or passed to free
 * @param flags  a combination of SA_BUFFER_FLAG_*
 *
 * @return an sa_buffer_ref_t referring to data on success, NULL on failure.
 */
SACD_API sa_buffer_ref_t * sa_buffer_create(uint8_t *data, size_t size,
                              void (*free)(void *opaque, uint8_t *data),
                              void *opaque, int flags);

/**
 * Default free callback, which calls sa_free() on the buffer data.
 * This function is meant to be passed to sa_buffer_create(), not called
 * directly.
 */
SACD_API void sa_buffer_default_free(void *opaque, uint8_t *data);

/**
 * Create a new reference to an sa_buffer_t.
 *
 * @return a new sa_buffer_ref_t referring to the same sa_buffer_t as buf or NULL on
 * failure.
 */
SACD_API sa_buffer_ref_t * sa_buffer_ref(const sa_buffer_ref_t *buf);

/**
 * Free a given reference and automatically free the buffer if there are no more
 * references to it.
 *
 * @param buf the reference to be freed. The pointer is set to NULL on return.
 */
SACD_API void sa_buffer_unref(sa_buffer_ref_t **buf);

/**
 * @return 1 if the caller may write to the data referred to by buf (which is
 * true if and only if buf is the only reference to the underlying sa_buffer_t).
 * Return 0 otherwise.
 * A positive answer is valid until sa_buffer_ref() is called on buf.
 */
SACD_API int sa_buffer_is_writable(const sa_buffer_ref_t *buf);

/**
 * @return the opaque parameter set by sa_buffer_create.
 */
SACD_API void * sa_buffer_get_opaque(const sa_buffer_ref_t *buf);

SACD_API int sa_buffer_get_ref_count(const sa_buffer_ref_t *buf);

/**
 * Create a writable reference from a given buffer reference, avoiding data copy
 * if possible.
 *
 * @param buf buffer reference to make writable. On success, buf is either left
 *            untouched, or it is unreferenced and a new writable sa_buffer_ref_t is
 *            written in its place. On failure, buf is left untouched.
 * @return 0 on success, a negative AVERROR on failure.
 */
SACD_API int sa_buffer_make_writable(sa_buffer_ref_t **buf);

/**
 * Reallocate a given buffer.
 *
 * @param buf  a buffer reference to reallocate. On success, buf will be
 *             unreferenced and a new reference with the required size will be
 *             written in its place. On failure buf will be left untouched. *buf
 *             may be NULL, then a new buffer is allocated.
 * @param size required new buffer size.
 * @return 0 on success, a negative AVERROR on failure.
 *
 * @note the buffer is actually reallocated with sa_realloc() only if it was
 * initially allocated through sa_buffer_realloc(NULL) and there is only one
 * reference to it (i.e. the one passed to this function). In all other cases
 * a new buffer is allocated and the data is copied.
 */
SACD_API int sa_buffer_realloc(sa_buffer_ref_t **buf, size_t size);

/**
 * Ensure dst refers to the same data as src.
 *
 * When *dst is already equivalent to src, do nothing. Otherwise unreference dst
 * and replace it with a new reference to src.
 *
 * @param dst Pointer to either a valid buffer reference or NULL. On success,
 *            this will point to a buffer reference equivalent to src. On
 *            failure, dst will be left untouched.
 * @param src A buffer reference to replace dst with. May be NULL, then this
 *            function is equivalent to sa_buffer_unref(dst).
 * @return 0 on success
 *         AVERROR(ENOMEM) on memory allocation failure.
 */
SACD_API int sa_buffer_replace(sa_buffer_ref_t **dst, const sa_buffer_ref_t *src);

/**
 * @}
 */

/**
 * @defgroup lavu_bufferpool sa_buffer_pool_s
 * @ingroup lavu_data
 *
 * @{
 * sa_buffer_pool_t is an API for a lock-free thread-safe pool of AVBuffers.
 *
 * Frequently allocating and freeing large buffers may be slow. sa_buffer_pool_t is
 * meant to solve this in cases when the caller needs a set of buffers of the
 * same size (the most obvious use case being buffers for raw video or audio
 * frames).
 *
 * At the beginning, the user must call sa_buffer_pool_init() to create the
 * buffer pool. Then whenever a buffer is needed, call sa_buffer_pool_get() to
 * get a reference to a new buffer, similar to sa_buffer_alloc(). This new
 * reference works in all aspects the same way as the one created by
 * sa_buffer_alloc(). However, when the last reference to this buffer is
 * unreferenced, it is returned to the pool instead of being freed and will be
 * reused for subsequent sa_buffer_pool_get() calls.
 *
 * When the caller is done with the pool and no longer needs to allocate any new
 * buffers, sa_buffer_pool_uninit() must be called to mark the pool as freeable.
 * Once all the buffers are released, it will automatically be freed.
 *
 * Allocating and releasing buffers with this API is thread-safe as long as
 * either the default alloc callback is used, or the user-supplied one is
 * thread-safe.
 */

/**
 * The buffer pool. This structure is opaque and not meant to be accessed
 * directly. It is allocated with sa_buffer_pool_init() and freed with
 * sa_buffer_pool_uninit().
 */
typedef struct sa_buffer_pool_s sa_buffer_pool_t;

/**
 * Allocate and initialize a buffer pool.
 *
 * @param size size of each buffer in this pool
 * @param alloc a function that will be used to allocate new buffers when the
 * pool is empty. May be NULL, then the default allocator will be used
 * (sa_buffer_alloc()).
 * @return newly created buffer pool on success, NULL on error.
 */
SACD_API sa_buffer_pool_t * sa_buffer_pool_init(size_t size, sa_buffer_ref_t* (*alloc)(size_t size));

/**
 * Allocate and initialize a buffer pool with a more complex allocator.
 *
 * @param size size of each buffer in this pool
 * @param opaque arbitrary user data used by the allocator
 * @param alloc a function that will be used to allocate new buffers when the
 *              pool is empty. May be NULL, then the default allocator will be
 *              used (sa_buffer_alloc()).
 * @param pool_free a function that will be called immediately before the pool
 *                  is freed. I.e. after sa_buffer_pool_uninit() is called
 *                  by the caller and all the frames are returned to the pool
 *                  and freed. It is intended to uninitialize the user opaque
 *                  data. May be NULL.
 * @return newly created buffer pool on success, NULL on error.
 */
SACD_API sa_buffer_pool_t * sa_buffer_pool_init2(size_t size, void *opaque,
                                   sa_buffer_ref_t* (*alloc)(void *opaque, size_t size),
                                   void (*pool_free)(void *opaque));

/**
 * Mark the pool as being available for freeing. It will actually be freed only
 * once all the allocated buffers associated with the pool are released. Thus it
 * is safe to call this function while some of the allocated buffers are still
 * in use.
 *
 * @param pool pointer to the pool to be freed. It will be set to NULL.
 */
SACD_API void sa_buffer_pool_uninit(sa_buffer_pool_t **pool);

/**
 * Allocate a new sa_buffer_t, reusing an old buffer from the pool when available.
 * This function may be called simultaneously from multiple threads.
 *
 * @return a reference to the new buffer on success, NULL on error.
 */
SACD_API sa_buffer_ref_t * sa_buffer_pool_get(sa_buffer_pool_t *pool);

/**
 * Query the original opaque parameter of an allocated buffer in the pool.
 *
 * @param ref a buffer reference to a buffer returned by sa_buffer_pool_get.
 * @return the opaque parameter set by the buffer allocator function of the
 *         buffer pool.
 *
 * @note the opaque parameter of ref is used by the buffer pool implementation,
 * therefore you have to use this function to access the original opaque
 * parameter of an allocated buffer.
 */
SACD_API void * sa_buffer_pool_buffer_get_opaque(const sa_buffer_ref_t *ref);

/**
 * @}
 */

#endif /* SAUTIL_BUFFER_H */
