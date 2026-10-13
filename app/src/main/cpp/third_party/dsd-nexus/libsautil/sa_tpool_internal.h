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

/*
 * This file implements a thread pool for multi-threading applications.
 * It consists of two distinct interfaces: thread pools and thread job queues.
 *
 * The pool of threads is given a function pointer and void* data to pass in.
 * This means the pool can run jobs of multiple types, albeit first come
 * first served with no job scheduling except to pick tasks from
 * queues that have room to store the result.
 *
 * Upon completion, the return value from the function pointer is
 * added back to the queue if the result is required. We may have
 * multiple queues in use for the one pool.
 */

#ifndef SA_TPOOL_INTERNAL_H
#define SA_TPOOL_INTERNAL_H

#include <stdint.h>
#ifdef __APPLE__
#include "c11threads.h"
#else
#include <threads.h>
#endif

#include "sa_tpool.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * An input job, before execution.
 */
typedef struct sa_tpool_job {
    void *(*func)(void *arg);
    void *arg;
    void (*job_cleanup)(void *arg);
    void (*result_cleanup)(void *data);
    struct sa_tpool_job *next;

    struct sa_tpool *p;
    struct sa_tpool_process *q;
    uint64_t serial;
} sa_tpool_job;

/*
 * An output result, after job has executed.
 */
struct sa_tpool_result {
    struct sa_tpool_result *next;
    void (*result_cleanup)(void *data);
    uint64_t serial;    /* sequential number for ordering */
    void *data;         /* result itself */
};

/*
 * A per-thread worker struct.
 */
typedef struct {
    struct sa_tpool *p;
    int idx;
    thrd_t tid;
    cnd_t pending_c;   /* signaled when job is available */
} sa_tpool_worker;

/*
 * An IO queue consists of a queue of jobs to execute
 * (the "input" side) and a queue of job results post-
 * execution (the "output" side).
 *
 * We have size limits to prevent either queue from
 * growing too large and serial numbers to ensure
 * sequential consumption of the output.
 *
 * The thread pool may have many heterogeneous tasks, each
 * using its own process queue mixed into the same thread pool.
 */
struct sa_tpool_process {
    struct sa_tpool *p;             /* thread pool */
    sa_tpool_job    *input_head;    /* input list */
    sa_tpool_job    *input_tail;
    sa_tpool_result *output_head;   /* output list */
    sa_tpool_result *output_tail;
    int qsize;                      /* max size of i/o queues */
    uint64_t next_serial;           /* next serial for output */
    uint64_t curr_serial;           /* current serial (next input) */

    int no_more_input;              /* disable dispatching of more jobs */
    int n_input;                    /* no. items in input queue */
    int n_output;                   /* no. items in output queue */
    int n_processing;               /* no. items being processed */

    int shutdown;                   /* true if pool is being destroyed */
    int in_only;                    /* if true, don't queue result up */
    int wake_dispatch;              /* unblocks waiting dispatchers */

    int ref_count;                  /* used to track safe destruction */

    cnd_t output_avail_c;     /* signaled on each new output */
    cnd_t input_not_full_c;   /* input queue is no longer full */
    cnd_t input_empty_c;      /* input queue has become empty */
    cnd_t none_processing_c;  /* n_processing has hit zero */

    struct sa_tpool_process *next, *prev;  /* circular linked list */
};

/*
 * The single pool structure itself.
 *
 * This knows nothing about the nature of the jobs or where their
 * output is going, but it maintains a list of queues associated with
 * this pool from which the jobs are taken.
 */
struct sa_tpool {
    int nwaiting;   /* how many workers waiting for new jobs */
    int njobs;      /* how many total jobs are waiting in all queues */
    int shutdown;   /* true if pool is being destroyed */

    /* I/O queues to check for jobs in and to put results.
     * Forms a circular linked list. (q_head may be amended
     * to point to the most recently updated.) */
    sa_tpool_process *q_head;

    /* threads */
    int tsize;              /* number of worker threads */
    sa_tpool_worker *t;     /* worker thread array */
    int *t_stack;           /* stack of waiting worker IDs */
    int t_stack_top;        /* top of waiting stack (-1 if empty) */

    /* A single mutex used when updating this and any associated structure. */
    mtx_t pool_m;

    /* Tracking of average number of running jobs. */
    int n_count, n_running;
};

#ifdef __cplusplus
}
#endif

#endif /* SA_TPOOL_INTERNAL_H */
