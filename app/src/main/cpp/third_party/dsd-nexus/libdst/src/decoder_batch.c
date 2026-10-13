/**
 * @file decoder_batch.c
 * @brief Batch parallel DST decoder implementation using sa_tpool
 *
 * This implementation uses the sa_tpool thread pool to decode multiple
 * DST frames in parallel. Each worker thread has its own dst_decoder_t
 * instance to avoid sharing state.
 *
 * SPDX-License-Identifier: MIT
 */


#include <libdst/decoder_batch.h>
#include <libdst/decoder.h>
#include <libsautil/sa_tpool.h>
#include <libsautil/cpu.h>
#include <libsautil/mem.h>

#include <stdlib.h>
#include <string.h>
#ifdef __APPLE__
#include <libsautil/c11threads.h>
#else
#include <threads.h>
#endif

/*============================================================================
 * Constants
 *============================================================================*/

/** Default sample rate for DSD64 */
#define DST_SAMPLE_RATE 2822400

/** Maximum threads to use (sanity limit) */
#define DST_MAX_THREADS 64

/** Default persistent queue size */
#define DST_QUEUE_SIZE 128

/*============================================================================
 * Job Structure (forward declaration for struct)
 *============================================================================*/

typedef struct dst_decode_job_s dst_decode_job_t;

/*============================================================================
 * Batch Decoder Structure
 *============================================================================*/

struct dst_batch_decoder_s {
    int channel_count;          /**< Audio channels (2 or 6) */
    int thread_count;           /**< Number of decoder instances */

    /* Thread pool */
    sa_tpool *pool;             /**< Worker thread pool */
    int owns_pool;              /**< True if we created the pool */

    /* Persistent process queue for reduced overhead */
    sa_tpool_process *queue;    /**< Reusable process queue */
    int queue_size;             /**< Size of persistent queue */

    /* Pre-allocated job array for single-frame fast path */
    dst_decode_job_t *single_job;

    /* Per-thread decoder instances */
    dst_decoder_t **decoders;   /**< Array of decoder pointers */

    /* Decoder pool for exclusive acquisition at runtime */
    mtx_t pool_mutex;        /**< Protects decoder pool state */
    cnd_t pool_cond;         /**< Signal when decoder becomes available */
    int *decoder_available;     /**< Array of availability flags (1=available) */
    int available_count;        /**< Number of currently available decoders */
};

/*============================================================================
 * Job Structure
 *============================================================================*/

/**
 * @brief Job passed to worker thread
 */
struct dst_decode_job_s {
    dst_batch_decoder_t *batch_decoder; /**< Parent batch decoder for pool access */
    dst_decoder_t *decoder;     /**< Acquired decoder instance */
    int decoder_index;          /**< Index in decoder array */
    const uint8_t *input;       /**< Input DST frame data */
    size_t input_size;          /**< Input frame size */
    uint8_t *output;            /**< Output DSD buffer */
    size_t output_size;         /**< Output size (filled by worker) */
    int error;                  /**< Error code (0 = success) */
};

/*============================================================================
 * Decoder Pool Management
 *============================================================================*/

/**
 * @brief Acquire a decoder from the pool (blocks if none available)
 *
 * Returns the decoder and sets *index to the decoder's index for release.
 */
static dst_decoder_t *acquire_decoder(dst_batch_decoder_t *dec, int *index)
{
    int i;
    dst_decoder_t *decoder = NULL;

    mtx_lock(&dec->pool_mutex);

    /* Wait until a decoder is available */
    while (dec->available_count == 0) {
        cnd_wait(&dec->pool_cond, &dec->pool_mutex);
    }

    /* Find and acquire an available decoder */
    for (i = 0; i < dec->thread_count; i++) {
        if (dec->decoder_available[i]) {
            dec->decoder_available[i] = 0;
            dec->available_count--;
            decoder = dec->decoders[i];
            *index = i;
            break;
        }
    }

    mtx_unlock(&dec->pool_mutex);

    return decoder;
}

/**
 * @brief Release a decoder back to the pool
 */
static void release_decoder(dst_batch_decoder_t *dec, int index)
{
    mtx_lock(&dec->pool_mutex);
    dec->decoder_available[index] = 1;
    dec->available_count++;
    cnd_signal(&dec->pool_cond);
    mtx_unlock(&dec->pool_mutex);
}

/*============================================================================
 * Worker Function
 *============================================================================*/

/**
 * @brief Worker function executed by sa_tpool threads
 *
 * Acquires a decoder from the pool, decodes the frame, then releases it.
 * The job structure is returned as the result data.
 */
static void *dst_decode_worker(void *arg)
{
    dst_decode_job_t *job = (dst_decode_job_t *)arg;
    dst_batch_decoder_t *dec = job->batch_decoder;
    int out_len = 0;
    int decoder_index;

    /* Acquire a decoder from the pool */
    dst_decoder_t *decoder = acquire_decoder(dec, &decoder_index);
    if (!decoder) {
        job->error = -1;
        job->output_size = 0;
        return job;
    }

    job->decoder = decoder;
    job->decoder_index = decoder_index;

    /* Decode the frame */
    job->error = dst_decoder_decode(
        decoder,
        (uint8_t *)job->input,
        (int)job->input_size,
        job->output,
        &out_len
    );

    job->output_size = (size_t)out_len;

    /* Release the decoder back to the pool */
    release_decoder(dec, decoder_index);

    return job;  /* Return job as result data */
}

/*============================================================================
 * Public API
 *============================================================================*/

dst_batch_decoder_t *dst_batch_decoder_create(int channel_count, int thread_count)
{
    dst_batch_decoder_t *dec;
    sa_tpool *pool;
    int actual_threads;

    /* Determine thread count */
    if (thread_count <= 0) {
        actual_threads = sa_cpu_count();
        if (actual_threads <= 0) {
            actual_threads = 4;  /* Fallback */
        }
    } else {
        actual_threads = thread_count;
    }

    if (actual_threads > DST_MAX_THREADS) {
        actual_threads = DST_MAX_THREADS;
    }

    /* Create thread pool */
    pool = sa_tpool_init(actual_threads);
    if (!pool) {
        return NULL;
    }

    /* Create decoder with this pool */
    dec = dst_batch_decoder_create_with_pool(channel_count, pool);
    if (!dec) {
        sa_tpool_destroy(pool);
        return NULL;
    }

    dec->owns_pool = 1;
    return dec;
}

dst_batch_decoder_t *dst_batch_decoder_create_with_pool(int channel_count,
                                                         sa_tpool *pool)
{
    dst_batch_decoder_t *dec;
    int i;
    int pool_threads;

    if (!pool || (channel_count != 2 && channel_count != 6)) {
        return NULL;
    }

    pool_threads = sa_tpool_size(pool);
    if (pool_threads <= 0) {
        return NULL;
    }

    /* Allocate decoder structure */
    dec = (dst_batch_decoder_t *)sa_calloc(1, sizeof(*dec));
    if (!dec) {
        return NULL;
    }

    dec->channel_count = channel_count;
    dec->thread_count = pool_threads;
    dec->pool = pool;
    dec->owns_pool = 0;

    /* Initialize pool mutex and condition */
    if (mtx_init(&dec->pool_mutex, mtx_plain) != thrd_success) {
        sa_free(dec);
        return NULL;
    }

    if (cnd_init(&dec->pool_cond) != thrd_success) {
        mtx_destroy(&dec->pool_mutex);
        sa_free(dec);
        return NULL;
    }

    /* Allocate decoder availability array */
    dec->decoder_available = (int *)sa_calloc((size_t)pool_threads, sizeof(int));
    if (!dec->decoder_available) {
        cnd_destroy(&dec->pool_cond);
        mtx_destroy(&dec->pool_mutex);
        sa_free(dec);
        return NULL;
    }

    /* Allocate decoder array */
    dec->decoders = (dst_decoder_t **)sa_calloc((size_t)pool_threads,
                                                  sizeof(dst_decoder_t *));
    if (!dec->decoders) {
        sa_free(dec->decoder_available);
        cnd_destroy(&dec->pool_cond);
        mtx_destroy(&dec->pool_mutex);
        sa_free(dec);
        return NULL;
    }

    /* Create per-thread decoder instances */
    for (i = 0; i < pool_threads; i++) {
        if (dst_decoder_init(&dec->decoders[i], channel_count,
                             DST_SAMPLE_RATE) != 0) {
            /* Cleanup on failure */
            while (--i >= 0) {
                dst_decoder_close(dec->decoders[i]);
            }
            sa_free(dec->decoders);
            sa_free(dec->decoder_available);
            cnd_destroy(&dec->pool_cond);
            mtx_destroy(&dec->pool_mutex);
            sa_free(dec);
            return NULL;
        }
        /* Mark decoder as available */
        dec->decoder_available[i] = 1;
    }
    dec->available_count = pool_threads;

    /* Create persistent process queue */
    dec->queue_size = DST_QUEUE_SIZE;
    dec->queue = sa_tpool_process_init(pool, dec->queue_size, 0);
    if (!dec->queue) {
        for (i = 0; i < pool_threads; i++) {
            dst_decoder_close(dec->decoders[i]);
        }
        sa_free(dec->decoders);
        sa_free(dec->decoder_available);
        cnd_destroy(&dec->pool_cond);
        mtx_destroy(&dec->pool_mutex);
        sa_free(dec);
        return NULL;
    }

    /* Pre-allocate single job for fast path */
    dec->single_job = (dst_decode_job_t *)sa_calloc(1, sizeof(dst_decode_job_t));
    if (!dec->single_job) {
        sa_tpool_process_destroy(dec->queue);
        for (i = 0; i < pool_threads; i++) {
            dst_decoder_close(dec->decoders[i]);
        }
        sa_free(dec->decoders);
        sa_free(dec->decoder_available);
        cnd_destroy(&dec->pool_cond);
        mtx_destroy(&dec->pool_mutex);
        sa_free(dec);
        return NULL;
    }

    /* Set batch decoder reference for single job */
    dec->single_job->batch_decoder = dec;

    return dec;
}

void dst_batch_decoder_destroy(dst_batch_decoder_t *decoder)
{
    int i;

    if (!decoder) {
        return;
    }

    /* Free pre-allocated single job */
    if (decoder->single_job) {
        sa_free(decoder->single_job);
    }

    /* Destroy persistent queue */
    if (decoder->queue) {
        sa_tpool_process_destroy(decoder->queue);
    }

    /* Destroy decoder instances */
    if (decoder->decoders) {
        for (i = 0; i < decoder->thread_count; i++) {
            if (decoder->decoders[i]) {
                dst_decoder_close(decoder->decoders[i]);
            }
        }
        sa_free(decoder->decoders);
    }

    /* Free decoder availability array */
    if (decoder->decoder_available) {
        sa_free(decoder->decoder_available);
    }

    /* Destroy pool sync primitives */
    cnd_destroy(&decoder->pool_cond);
    mtx_destroy(&decoder->pool_mutex);

    /* Destroy pool if we own it */
    if (decoder->owns_pool && decoder->pool) {
        sa_tpool_destroy(decoder->pool);
    }

    sa_free(decoder);
}

int dst_batch_decode(dst_batch_decoder_t *decoder,
                     const uint8_t *inputs[], const size_t input_sizes[],
                     uint8_t *outputs[], size_t output_sizes[],
                     size_t count)
{
    dst_decode_job_t *jobs = NULL;
    size_t i;
    int first_error = 0;

    if (!decoder || !inputs || !input_sizes || !outputs || !output_sizes) {
        return -1;
    }

    if (count == 0) {
        return 0;
    }

    /*
     * Fast path for single-frame decoding:
     * Use pre-allocated job and persistent queue to minimize overhead.
     */
    if (count == 1) {
        dst_decode_job_t *job = decoder->single_job;
        sa_tpool_result *result;

        job->batch_decoder = decoder;
        job->input = inputs[0];
        job->input_size = input_sizes[0];
        job->output = outputs[0];
        job->output_size = 0;
        job->error = 0;

        if (sa_tpool_dispatch(decoder->pool, decoder->queue,
                              dst_decode_worker, job) != 0) {
            return -1;
        }

        result = sa_tpool_next_result_wait(decoder->queue);
        if (result) {
            dst_decode_job_t *done = (dst_decode_job_t *)sa_tpool_result_data(result);
            if (done) {
                output_sizes[0] = done->output_size;
                first_error = done->error;
            }
            sa_tpool_delete_result(result, 0);
        } else {
            return -1;
        }

        return first_error;
    }

    /*
     * Multi-frame batch path:
     * Dispatch all jobs immediately - workers acquire/release decoders at runtime.
     * This allows full pipelining without synchronization barriers.
     */

    /* Allocate job structures for entire batch */
    jobs = (dst_decode_job_t *)sa_calloc(count, sizeof(*jobs));
    if (!jobs) {
        return -1;
    }

    /* Dispatch all jobs to the pool */
    for (i = 0; i < count; i++) {
        jobs[i].batch_decoder = decoder;
        jobs[i].input = inputs[i];
        jobs[i].input_size = input_sizes[i];
        jobs[i].output = outputs[i];
        jobs[i].output_size = 0;
        jobs[i].error = 0;

        if (sa_tpool_dispatch(decoder->pool, decoder->queue,
                              dst_decode_worker, &jobs[i]) != 0) {
            first_error = -1;
            break;
        }
    }

    /* Collect results in order (sa_tpool guarantees serial ordering) */
    for (i = 0; i < count && first_error == 0; i++) {
        sa_tpool_result *result = sa_tpool_next_result_wait(decoder->queue);
        if (result) {
            dst_decode_job_t *job = (dst_decode_job_t *)sa_tpool_result_data(result);
            if (job) {
                output_sizes[i] = job->output_size;
                if (job->error != 0 && first_error == 0) {
                    first_error = job->error;
                }
            }
            sa_tpool_delete_result(result, 0);
        } else {
            first_error = -1;
            break;
        }
    }

    sa_free(jobs);

    return first_error;
}

int dst_batch_decoder_thread_count(const dst_batch_decoder_t *decoder)
{
    if (!decoder) {
        return 0;
    }
    return decoder->thread_count;
}
