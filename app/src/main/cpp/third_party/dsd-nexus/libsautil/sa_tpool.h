/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * DSD-Nexus is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * DSD-Nexus is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with DSD-Nexus; if not, see <https://www.gnu.org/licenses/>.
 */

#ifndef SAUTIL_SA_TPOOL_H
#define SAUTIL_SA_TPOOL_H

#include <stdint.h>
#include "export.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * A generic thread pool consisting of worker threads and process queues.
 *
 * Multiple process queues can share the same pool of worker threads,
 * enabling heterogeneous workloads on the same set of threads.
 *
 * Results are returned in dispatch order (serial-number ordered).
 */

/* Opaque types */
typedef struct sa_tpool sa_tpool;
typedef struct sa_tpool_process sa_tpool_process;
typedef struct sa_tpool_result sa_tpool_result;

/* =========================================================================
 * Pool lifecycle
 * ========================================================================= */

/**
 * Create a worker pool with n worker threads.
 *
 * @param n_threads  Number of worker threads to create
 * @return Pool pointer on success, NULL on failure
 */
SACD_API sa_tpool * sa_tpool_init(int n_threads);

/**
 * Destroy a thread pool. Worker threads are joined (they will
 * finish their current work before returning).
 *
 * @param p  Pool to destroy
 */
SACD_API void sa_tpool_destroy(sa_tpool *p);

/**
 * Return the number of threads in the pool.
 *
 * @param p  Pool
 * @return Number of threads
 */
SACD_API int sa_tpool_size(sa_tpool *p);

/* =========================================================================
 * Job dispatch
 * ========================================================================= */

/**
 * Add a job to the work pool (blocking if queue is full).
 *
 * @param p     Pool
 * @param q     Process queue to add job to
 * @param func  Function to execute (return value becomes the result)
 * @param arg   Argument passed to func
 * @return 0 on success, -1 on failure
 */
SACD_API int sa_tpool_dispatch(sa_tpool *p, sa_tpool_process *q,
                      void *(*func)(void *arg), void *arg);

/**
 * Add a job with cleanup callbacks and non-blocking option.
 *
 * @param p               Pool
 * @param q               Process queue
 * @param exec_func       Function to execute
 * @param arg             Argument passed to exec_func
 * @param job_cleanup     Called if job is discarded before execution (may be NULL)
 * @param result_cleanup  Called if result is discarded (may be NULL)
 * @param nonblock        0: block if full, +1: return -1/EAGAIN if full,
 *                        -1: add regardless of queue size
 * @return 0 on success, -1 on failure
 */
SACD_API int sa_tpool_dispatch3(sa_tpool *p, sa_tpool_process *q,
                       void *(*exec_func)(void *arg), void *arg,
                       void (*job_cleanup)(void *arg),
                       void (*result_cleanup)(void *data),
                       int nonblock);

/**
 * Wake up a dispatcher blocked on a full queue.
 * Used during seek/close to unblock a reader thread.
 *
 * @param q  Process queue
 */
SACD_API void sa_tpool_wake_dispatch(sa_tpool_process *q);

/* =========================================================================
 * Results
 * ========================================================================= */

/**
 * Pull the next result off the process queue (non-blocking).
 * Results are returned in strict dispatch order (serial numbers).
 *
 * @param q  Process queue
 * @return Result pointer, or NULL if not ready
 */
SACD_API sa_tpool_result * sa_tpool_next_result(sa_tpool_process *q);

/**
 * Pull the next result, blocking until one is available.
 * Returns NULL on error or during shutdown.
 *
 * @param q  Process queue
 * @return Result pointer, or NULL
 */
SACD_API sa_tpool_result * sa_tpool_next_result_wait(sa_tpool_process *q);

/**
 * Free a result. If free_data is true, also frees result->data.
 *
 * @param r          Result to free
 * @param free_data  If true, free the data pointer too
 */
SACD_API void sa_tpool_delete_result(sa_tpool_result *r, int free_data);

/**
 * Extract the data pointer from a result.
 *
 * @param r  Result
 * @return The data returned by the job function
 */
SACD_API void * sa_tpool_result_data(sa_tpool_result *r);

/* =========================================================================
 * Process queue management
 * ========================================================================= */

/**
 * Create a process queue attached to a pool.
 *
 * @param p        Pool to attach to
 * @param qsize    Maximum queue depth (input + output)
 * @param in_only  If true, don't store results (fire-and-forget jobs)
 * @return Process queue, or NULL on failure
 */
SACD_API sa_tpool_process * sa_tpool_process_init(sa_tpool *p, int qsize, int in_only);

/**
 * Destroy a process queue. Drains remaining jobs first.
 *
 * @param q  Process queue
 */
SACD_API void sa_tpool_process_destroy(sa_tpool_process *q);

/**
 * Reset a process queue to initial state.
 * Discards queued input, waits for in-progress jobs, discards output.
 * Resets serial numbers to 0. Used for seek operations.
 *
 * @param q             Process queue
 * @param free_results  If true, free result data pointers
 * @return 0 on success, -1 on failure
 */
SACD_API int sa_tpool_process_reset(sa_tpool_process *q, int free_results);

/**
 * Flush a process queue -- wait for all queued and in-progress
 * jobs to complete. Does not destroy the queue.
 *
 * @param q  Process queue
 * @return 0 on success, -1 on failure
 */
SACD_API int sa_tpool_process_flush(sa_tpool_process *q);

/**
 * Signal shutdown of a process queue.
 * Wakes any threads waiting on this queue's condition variables.
 *
 * @param q  Process queue
 */
SACD_API void sa_tpool_process_shutdown(sa_tpool_process *q);

/**
 * Check if a process queue is in shutdown state.
 *
 * @param q  Process queue
 * @return Non-zero if shutdown
 */
SACD_API int sa_tpool_process_is_shutdown(sa_tpool_process *q);

/**
 * Check if a process queue is empty (no input, processing, or output).
 *
 * @param q  Process queue
 * @return Non-zero if empty
 */
SACD_API int sa_tpool_process_empty(sa_tpool_process *q);

/**
 * Return total items in the queue (input + processing + output).
 *
 * @param q  Process queue
 * @return Total item count
 */
SACD_API int sa_tpool_process_sz(sa_tpool_process *q);

/**
 * Return the maximum queue size.
 *
 * @param q  Process queue
 * @return Queue size limit
 */
SACD_API int sa_tpool_process_qsize(sa_tpool_process *q);

/**
 * Increment reference count on a process queue.
 *
 * @param q  Process queue
 */
SACD_API void sa_tpool_process_ref_incr(sa_tpool_process *q);

/**
 * Decrement reference count. Destroys queue if count reaches 0.
 *
 * @param q  Process queue
 */
SACD_API void sa_tpool_process_ref_decr(sa_tpool_process *q);

/**
 * Attach a process queue to a pool's scheduler.
 *
 * @param p  Pool
 * @param q  Process queue
 */
SACD_API void sa_tpool_process_attach(sa_tpool *p, sa_tpool_process *q);

/**
 * Detach a process queue from a pool's scheduler.
 *
 * @param p  Pool
 * @param q  Process queue
 */
SACD_API void sa_tpool_process_detach(sa_tpool *p, sa_tpool_process *q);

#ifdef __cplusplus
}
#endif

#endif /* SAUTIL_SA_TPOOL_H */
