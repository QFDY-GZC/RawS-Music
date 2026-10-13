/**
 * @file decoder_batch.h
 * @brief Batch parallel DST decoder using sa_tpool
 *
 * This decoder processes multiple DST frames in parallel using a thread pool,
 * returning results in the same order as inputs. Each frame is decoded
 * independently (DST is stateless), enabling efficient parallelization.
 *
 * SPDX-License-Identifier: MIT
 */

#ifndef DST_DECODER_BATCH_H
#define DST_DECODER_BATCH_H

#include <stdint.h>
#include <stddef.h>
#include <libdst/dst_export.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Forward declaration */
struct sa_tpool;

/**
 * @brief Opaque batch decoder handle
 */
typedef struct dst_batch_decoder_s dst_batch_decoder_t;

/**
 * @brief Create a batch decoder with its own thread pool
 *
 * @param channel_count  Audio channels (2 for stereo, 6 for multichannel)
 * @param thread_count   Number of worker threads (0 = auto-detect CPU cores)
 * @return Decoder handle, or NULL on failure
 */
DST_API dst_batch_decoder_t *dst_batch_decoder_create(int channel_count, int thread_count);

/**
 * @brief Create a batch decoder using an existing thread pool
 *
 * Allows sharing a thread pool across multiple decoders/converters.
 * The caller retains ownership of the pool.
 *
 * @param channel_count  Audio channels (2 for stereo, 6 for multichannel)
 * @param pool           Existing thread pool to use
 * @return Decoder handle, or NULL on failure
 */
DST_API dst_batch_decoder_t *dst_batch_decoder_create_with_pool(int channel_count,
                                                                struct sa_tpool *pool);

/**
 * @brief Destroy batch decoder and free resources
 *
 * If the decoder owns the thread pool (created via dst_batch_decoder_create),
 * the pool is also destroyed. If using a shared pool, only the decoder is freed.
 *
 * @param decoder  Decoder to destroy (may be NULL)
 */
void DST_API dst_batch_decoder_destroy(dst_batch_decoder_t *decoder);

/**
 * @brief Decode multiple DST frames in parallel
 *
 * Decodes up to 'count' DST frames in parallel using the thread pool.
 * Results are guaranteed to be in the same order as inputs.
 *
 * @param decoder       Batch decoder instance
 * @param inputs        Array of pointers to input DST frame data
 * @param input_sizes   Array of input frame sizes in bytes
 * @param outputs       Array of pointers to output DSD buffers (pre-allocated)
 * @param output_sizes  Array to receive output sizes (in bytes)
 * @param count         Number of frames to decode
 * @return 0 on success, first error code on failure
 *
 * @note Output buffers must be pre-allocated with sufficient size.
 *       For stereo: 4704 bytes per channel = 9408 bytes
 *       For 6-channel: 4704 bytes per channel = 28224 bytes
 */
int DST_API dst_batch_decode(dst_batch_decoder_t *decoder,
                     const uint8_t *inputs[], const size_t input_sizes[],
                     uint8_t *outputs[], size_t output_sizes[],
                     size_t count);

/**
 * @brief Get the number of worker threads in the decoder's pool
 *
 * @param decoder  Batch decoder instance
 * @return Number of threads, or 0 if decoder is NULL
 */
int DST_API dst_batch_decoder_thread_count(const dst_batch_decoder_t *decoder);

#ifdef __cplusplus
}
#endif

#endif /* DST_DECODER_BATCH_H */
