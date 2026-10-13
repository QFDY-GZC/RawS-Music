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

#include <stdlib.h>
#include <inttypes.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <assert.h>
#include <limits.h>
#ifdef __APPLE__
#include "c11threads.h"
#else
#include <threads.h>
#endif

#include <libsautil/mem.h>

#include "sa_tpool_internal.h"

/* Forward declarations for static functions */
static void sa_tpool_process_detach_locked(sa_tpool *p, sa_tpool_process *q);
static void sa_tpool_process_shutdown_locked(sa_tpool_process *q);
static void wake_next_worker(sa_tpool_process *q, int locked);

/* ============================================================================
 * Platform-specific helpers
 * ========================================================================== */

/**
 * Initialize a recursive mutex.
 */
static int sa_tpool_mtx_init_recursive(mtx_t *mutex)
{
    /* C11 threads: mtx_plain | mtx_recursive */
    return mtx_init(mutex, mtx_plain | mtx_recursive);
}

/* ============================================================================
 * Process-queue results management
 * ========================================================================== */

/*
 * Adds a result to the end of the process result queue.
 * Called from worker thread context after job execution.
 *
 * Returns 0 on success, -1 on failure.
 */
static int sa_tpool_add_result(sa_tpool_job *j, void *data)
{
    sa_tpool_process *q = j->q;
    sa_tpool_result *r;

    mtx_lock(&q->p->pool_m);

    if (--q->n_processing == 0)
        cnd_signal(&q->none_processing_c);

    /* No results queue is fine if we don't want any results back */
    if (q->in_only) {
        mtx_unlock(&q->p->pool_m);
        return 0;
    }

    r = sa_malloc(sizeof(*r));
    if (!r) {
        mtx_unlock(&q->p->pool_m);
        sa_tpool_process_shutdown(q);
        return -1;
    }

    r->next = NULL;
    r->data = data;
    r->result_cleanup = j->result_cleanup;
    r->serial = j->serial;

    q->n_output++;
    if (q->output_tail) {
        q->output_tail->next = r;
        q->output_tail = r;
    } else {
        q->output_head = q->output_tail = r;
    }

    assert(r->serial >= q->next_serial
           || q->next_serial == (uint64_t)INT_MAX);
    if (r->serial == q->next_serial) {
        cnd_broadcast(&q->output_avail_c);
    }

    mtx_unlock(&q->p->pool_m);

    return 0;
}

/* Core of sa_tpool_next_result() -- must be called with pool_m held */
static sa_tpool_result *sa_tpool_next_result_locked(sa_tpool_process *q)
{
    sa_tpool_result *r, *last;

    if (q->shutdown)
        return NULL;

    for (last = NULL, r = q->output_head; r; last = r, r = r->next) {
        if (r->serial == q->next_serial)
            break;
    }

    if (r) {
        /* Remove r from linked list */
        if (q->output_head == r)
            q->output_head = r->next;
        else
            last->next = r->next;

        if (q->output_tail == r)
            q->output_tail = last;

        if (!q->output_head)
            q->output_tail = NULL;

        q->next_serial++;
        q->n_output--;

        if (q->qsize && q->n_output < q->qsize) {
            if (q->n_input < q->qsize)
                cnd_signal(&q->input_not_full_c);
            if (!q->shutdown)
                wake_next_worker(q, 1);
        }
    }

    return r;
}

/*
 * Pulls the next item off the process result queue (non-blocking).
 * Results will be returned in strict dispatch order.
 */
sa_tpool_result *sa_tpool_next_result(sa_tpool_process *q)
{
    sa_tpool_result *r;

    mtx_lock(&q->p->pool_m);
    r = sa_tpool_next_result_locked(q);
    mtx_unlock(&q->p->pool_m);

    return r;
}

/*
 * Pulls the next item off the process result queue (blocking).
 * Waits until a result matching the next expected serial is available.
 */
sa_tpool_result *sa_tpool_next_result_wait(sa_tpool_process *q)
{
    sa_tpool_result *r;

    mtx_lock(&q->p->pool_m);
    while (!(r = sa_tpool_next_result_locked(q))) {
        q->ref_count++;
        if (q->shutdown) {
            int rc = --q->ref_count;
            mtx_unlock(&q->p->pool_m);
            if (rc == 0)
                sa_tpool_process_destroy(q);
            return NULL;
        }
        cnd_wait(&q->output_avail_c, &q->p->pool_m);
        q->ref_count--;
    }
    mtx_unlock(&q->p->pool_m);

    return r;
}

/*
 * Returns true if there are no items pending or in-progress.
 */
int sa_tpool_process_empty(sa_tpool_process *q)
{
    int empty;

    mtx_lock(&q->p->pool_m);
    empty = q->n_input == 0 && q->n_processing == 0 && q->n_output == 0;
    mtx_unlock(&q->p->pool_m);

    return empty;
}

void sa_tpool_process_ref_incr(sa_tpool_process *q)
{
    mtx_lock(&q->p->pool_m);
    q->ref_count++;
    mtx_unlock(&q->p->pool_m);
}

void sa_tpool_process_ref_decr(sa_tpool_process *q)
{
    mtx_lock(&q->p->pool_m);
    if (--q->ref_count <= 0) {
        mtx_unlock(&q->p->pool_m);
        sa_tpool_process_destroy(q);
        return;
    }
    mtx_unlock(&q->p->pool_m);
}

int sa_tpool_process_sz(sa_tpool_process *q)
{
    int len;

    mtx_lock(&q->p->pool_m);
    len = q->n_output + q->n_input + q->n_processing;
    mtx_unlock(&q->p->pool_m);

    return len;
}

static void sa_tpool_process_shutdown_locked(sa_tpool_process *q)
{
    q->shutdown = 1;
    cnd_broadcast(&q->output_avail_c);
    cnd_broadcast(&q->input_not_full_c);
    cnd_broadcast(&q->input_empty_c);
    cnd_broadcast(&q->none_processing_c);
}

void sa_tpool_process_shutdown(sa_tpool_process *q)
{
    mtx_lock(&q->p->pool_m);
    sa_tpool_process_shutdown_locked(q);
    mtx_unlock(&q->p->pool_m);
}

int sa_tpool_process_is_shutdown(sa_tpool_process *q)
{
    mtx_lock(&q->p->pool_m);
    int r = q->shutdown;
    mtx_unlock(&q->p->pool_m);
    return r;
}

void sa_tpool_delete_result(sa_tpool_result *r, int free_data)
{
    if (!r)
        return;

    if (free_data && r->data)
        sa_free(r->data);

    sa_free(r);
}

void *sa_tpool_result_data(sa_tpool_result *r)
{
    return r->data;
}

/*
 * Initializes a thread process-queue.
 */
sa_tpool_process *sa_tpool_process_init(sa_tpool *p, int qsize, int in_only)
{
    sa_tpool_process *q = sa_malloc(sizeof(*q));
    if (!q)
        return NULL;

    cnd_init(&q->output_avail_c);
    cnd_init(&q->input_not_full_c);
    cnd_init(&q->input_empty_c);
    cnd_init(&q->none_processing_c);

    q->p           = p;
    q->input_head  = NULL;
    q->input_tail  = NULL;
    q->output_head = NULL;
    q->output_tail = NULL;
    q->next_serial = 0;
    q->curr_serial = 0;
    q->no_more_input = 0;
    q->n_input     = 0;
    q->n_output    = 0;
    q->n_processing = 0;
    q->qsize       = qsize;
    q->in_only     = in_only;
    q->shutdown    = 0;
    q->wake_dispatch = 0;
    q->ref_count   = 1;

    q->next        = NULL;
    q->prev        = NULL;

    sa_tpool_process_attach(p, q);

    return q;
}

/* Deallocates memory for a thread process-queue. */
void sa_tpool_process_destroy(sa_tpool_process *q)
{
    if (!q)
        return;

    /* Prevent dispatch from queuing up any more jobs. */
    mtx_lock(&q->p->pool_m);
    q->no_more_input = 1;
    mtx_unlock(&q->p->pool_m);

    /* Ensure it's fully drained before destroying the queue */
    sa_tpool_process_reset(q, 0);

    mtx_lock(&q->p->pool_m);
    sa_tpool_process_detach_locked(q->p, q);
    sa_tpool_process_shutdown_locked(q);

    /* Maybe a worker is scanning this queue, so delay destruction */
    if (--q->ref_count > 0) {
        mtx_unlock(&q->p->pool_m);
        return;
    }

    cnd_destroy(&q->output_avail_c);
    cnd_destroy(&q->input_not_full_c);
    cnd_destroy(&q->input_empty_c);
    cnd_destroy(&q->none_processing_c);
    mtx_unlock(&q->p->pool_m);

    sa_free(q);
}

void sa_tpool_process_attach(sa_tpool *p, sa_tpool_process *q)
{
    mtx_lock(&p->pool_m);
    if (p->q_head) {
        q->next = p->q_head;
        q->prev = p->q_head->prev;
        p->q_head->prev->next = q;
        p->q_head->prev = q;
    } else {
        q->next = q;
        q->prev = q;
    }
    p->q_head = q;
    assert(p->q_head && p->q_head->prev && p->q_head->next);
    mtx_unlock(&p->pool_m);
}

/* Internal: detach while pool_m is already held */
static void sa_tpool_process_detach_locked(sa_tpool *p, sa_tpool_process *q)
{
    if (!p->q_head || !q->prev || !q->next)
        return;

    sa_tpool_process *curr = p->q_head, *first = curr;
    do {
        if (curr == q) {
            q->next->prev = q->prev;
            q->prev->next = q->next;
            p->q_head = q->next;
            q->next = q->prev = NULL;

            /* Last one */
            if (p->q_head == q)
                p->q_head = NULL;
            break;
        }
        curr = curr->next;
    } while (curr != first);
}

void sa_tpool_process_detach(sa_tpool *p, sa_tpool_process *q)
{
    mtx_lock(&p->pool_m);
    sa_tpool_process_detach_locked(p, q);
    mtx_unlock(&p->pool_m);
}


/* ============================================================================
 * The thread pool
 * ========================================================================== */

/*
 * A worker thread.
 *
 * Each thread checks each process-queue in the pool in turn, looking for
 * input jobs that also have room for output. If found, we execute and repeat.
 * If nothing found, we wait on our per-worker condition variable.
 */
static int tpool_worker(void *arg)
{
    sa_tpool_worker *w = (sa_tpool_worker *)arg;
    sa_tpool *p = w->p;
    sa_tpool_job *j;

    mtx_lock(&p->pool_m);
    while (!p->shutdown) {
        assert(p->q_head == NULL || (p->q_head->prev && p->q_head->next));

        int work_to_do = 0;
        sa_tpool_process *first = p->q_head, *q = first;
        do {
            /* Iterate over queues, finding one with jobs and room for output */
            if (q && q->input_head
                && q->qsize - q->n_output > q->n_processing
                && !q->shutdown) {
                work_to_do = 1;
                break;
            }

            if (q) q = q->next;
        } while (q && q != first);

        if (!work_to_do) {
            /* No work available -- wait */
            p->nwaiting++;

            if (p->t_stack_top == -1 || p->t_stack_top > w->idx)
                p->t_stack_top = w->idx;

            p->t_stack[w->idx] = 1;
            cnd_wait(&w->pending_c, &p->pool_m);
            p->t_stack[w->idx] = 0;

            /* Find new t_stack_top */
            int i;
            p->t_stack_top = -1;
            for (i = 0; i < p->tsize; i++) {
                if (p->t_stack[i]) {
                    p->t_stack_top = i;
                    break;
                }
            }

            p->nwaiting--;
            continue;
        }

        /* Process as many items in this queue as possible */
        q->ref_count++;
        while (q->input_head && q->qsize - q->n_output > q->n_processing) {
            if (p->shutdown)
                goto shutdown;

            if (q->shutdown)
                break;

            j = q->input_head;
            assert(j->p == p);

            if (!(q->input_head = j->next))
                q->input_tail = NULL;

            q->n_processing++;
            if (q->n_input-- >= q->qsize)
                cnd_broadcast(&q->input_not_full_c);

            if (q->n_input == 0)
                cnd_signal(&q->input_empty_c);

            p->njobs--;

            mtx_unlock(&p->pool_m);

            if (sa_tpool_add_result(j, j->func(j->arg)) < 0)
                goto err;
            sa_free(j);

            mtx_lock(&p->pool_m);
        }
        if (--q->ref_count == 0) {
            sa_tpool_process_destroy(q);
        } else {
            /* Out of jobs on this queue, restart search from next one */
            if (p->q_head)
                p->q_head = p->q_head->next;
        }
    }

shutdown:
    mtx_unlock(&p->pool_m);
    return 0;

err:
    /* Hard failure, shutdown all queues */
    mtx_lock(&p->pool_m);
    {
        sa_tpool_process *first2 = p->q_head, *q2 = first2;
        if (q2) {
            do {
                sa_tpool_process_shutdown_locked(q2);
                q2->shutdown = 2; /* signify error */
                q2 = q2->next;
            } while (q2 != first2);
        }
    }
    mtx_unlock(&p->pool_m);
    return 0;
}

static void wake_next_worker(sa_tpool_process *q, int locked)
{
    if (!q) return;
    sa_tpool *p = q->p;
    if (!locked)
        mtx_lock(&p->pool_m);

    /* Update q_head so workers start processing this queue */
    assert(q->prev && q->next); /* attached */
    p->q_head = q;

    /* Wake up a worker if we have more jobs than running threads */
    int sig = p->t_stack_top >= 0 && p->njobs > p->tsize - p->nwaiting
        && (q->n_processing < q->qsize - q->n_output);

    if (sig)
        cnd_signal(&p->t[p->t_stack_top].pending_c);

    if (!locked)
        mtx_unlock(&p->pool_m);
}

/*
 * Creates a worker pool with n worker threads.
 */
sa_tpool *sa_tpool_init(int n)
{
    int t_idx = 0;
    sa_tpool *p = sa_calloc(1, sizeof(*p));
    if (!p)
        return NULL;

    p->tsize = n;
    p->njobs = 0;
    p->nwaiting = 0;
    p->shutdown = 0;
    p->q_head = NULL;
    p->t_stack = NULL;
    p->n_count = 0;
    p->n_running = 0;

    p->t = sa_malloc((size_t)n * sizeof(p->t[0]));
    if (!p->t) {
        sa_free(p);
        return NULL;
    }

    p->t_stack = sa_malloc((size_t)n * sizeof(*p->t_stack));
    if (!p->t_stack) {
        sa_free(p->t);
        sa_free(p);
        return NULL;
    }
    p->t_stack_top = -1;

    if (sa_tpool_mtx_init_recursive(&p->pool_m) != thrd_success) {
        sa_free(p->t_stack);
        sa_free(p->t);
        sa_free(p);
        return NULL;
    }

    mtx_lock(&p->pool_m);

    for (t_idx = 0; t_idx < n; t_idx++) {
        sa_tpool_worker *w = &p->t[t_idx];
        p->t_stack[t_idx] = 0;
        w->p = p;
        w->idx = t_idx;
        cnd_init(&w->pending_c);
        if (thrd_create(&w->tid, tpool_worker, w) != thrd_success)
            goto cleanup;
    }

    mtx_unlock(&p->pool_m);

    return p;

cleanup:
    {
        int j_idx;
        fprintf(stderr, "sa_tpool: failed to start worker thread\n");
        p->shutdown = 1;
        mtx_unlock(&p->pool_m);
        for (j_idx = 0; j_idx < t_idx; j_idx++) {
            thrd_join(p->t[j_idx].tid, NULL);
            cnd_destroy(&p->t[j_idx].pending_c);
        }
        mtx_destroy(&p->pool_m);
        sa_free(p->t_stack);
        sa_free(p->t);
        sa_free(p);
        return NULL;
    }
}

int sa_tpool_size(sa_tpool *p)
{
    return p->tsize;
}

/*
 * Adds a job to the work pool (simple blocking dispatch).
 */
int sa_tpool_dispatch(sa_tpool *p, sa_tpool_process *q,
                      void *(*func)(void *arg), void *arg)
{
    return sa_tpool_dispatch3(p, q, func, arg, NULL, NULL, 0);
}

/*
 * Full-featured dispatch with cleanup callbacks and non-block option.
 */
int sa_tpool_dispatch3(sa_tpool *p, sa_tpool_process *q,
                       void *(*exec_func)(void *arg), void *arg,
                       void (*job_cleanup)(void *arg),
                       void (*result_cleanup)(void *data),
                       int nonblock)
{
    sa_tpool_job *j;

    mtx_lock(&p->pool_m);

    if ((q->no_more_input || q->n_input >= q->qsize) && nonblock == 1) {
        mtx_unlock(&p->pool_m);
        errno = EAGAIN;
        return -1;
    }

    j = sa_malloc(sizeof(*j));
    if (!j) {
        mtx_unlock(&p->pool_m);
        return -1;
    }
    j->func = exec_func;
    j->arg = arg;
    j->job_cleanup = job_cleanup;
    j->result_cleanup = result_cleanup;
    j->next = NULL;
    j->p = p;
    j->q = q;
    j->serial = q->curr_serial++;

    if (nonblock == 0) {
        while ((q->no_more_input || q->n_input >= q->qsize) &&
               !q->shutdown && !q->wake_dispatch) {
            cnd_wait(&q->input_not_full_c, &q->p->pool_m);
        }
        if (q->no_more_input || q->shutdown) {
            sa_free(j);
            mtx_unlock(&p->pool_m);
            return -1;
        }
        if (q->wake_dispatch) {
            q->wake_dispatch = 0;
        }
    }

    p->njobs++;
    q->n_input++;

    if (q->input_tail) {
        q->input_tail->next = j;
        q->input_tail = j;
    } else {
        q->input_head = q->input_tail = j;
    }

    if (!q->shutdown)
        wake_next_worker(q, 1);

    mtx_unlock(&p->pool_m);

    return 0;
}

/*
 * Wakes up a single thread stuck in dispatch.
 */
void sa_tpool_wake_dispatch(sa_tpool_process *q)
{
    mtx_lock(&q->p->pool_m);
    q->wake_dispatch = 1;
    cnd_signal(&q->input_not_full_c);
    mtx_unlock(&q->p->pool_m);
}

/*
 * Flushes the process-queue: drains input and waits for all
 * in-progress jobs to complete.
 */
int sa_tpool_process_flush(sa_tpool_process *q)
{
    int i;
    sa_tpool *p = q->p;

    mtx_lock(&p->pool_m);

    /* Wake up all workers for the final sprint */
    for (i = 0; i < p->tsize; i++)
        if (p->t_stack[i])
            cnd_signal(&p->t[i].pending_c);

    /* Ensure there is room for the final sprint */
    if (q->qsize < q->n_output + q->n_input + q->n_processing)
        q->qsize = q->n_output + q->n_input + q->n_processing;

    if (q->shutdown) {
        while (q->n_processing)
            cnd_wait(&q->none_processing_c, &p->pool_m);
    }

    /* Wait for n_input and n_processing to hit zero */
    while (!q->shutdown && (q->n_input || q->n_processing)) {
        while (q->n_input && !q->shutdown) {
            cnd_wait(&q->input_empty_c, &p->pool_m);
        }

        while (q->n_processing) {
            cnd_wait(&q->none_processing_c, &p->pool_m);
        }
        if (q->shutdown) break;
    }

    mtx_unlock(&p->pool_m);

    return 0;
}

/*
 * Resets a process to the initial state.
 * Removes queued input, flushes in-progress, discards output.
 * Resets serial numbers to 0.
 */
int sa_tpool_process_reset(sa_tpool_process *q, int free_results)
{
    sa_tpool_job *j, *jn, *j_head;
    sa_tpool_result *r, *rn, *r_head;

    mtx_lock(&q->p->pool_m);
    /* Prevent next_result from returning data during our flush */
    q->next_serial = (uint64_t)INT_MAX;

    /* Remove any queued input not yet being acted upon */
    j_head = q->input_head;
    q->input_head = q->input_tail = NULL;
    q->n_input = 0;

    /* Remove any queued output */
    r_head = q->output_head;
    q->output_head = q->output_tail = NULL;
    q->n_output = 0;
    mtx_unlock(&q->p->pool_m);

    /* Release memory (can be done unlocked now) */
    for (j = j_head; j; j = jn) {
        jn = j->next;
        if (j->job_cleanup) j->job_cleanup(j->arg);
        sa_free(j);
    }

    for (r = r_head; r; r = rn) {
        rn = r->next;
        if (r->result_cleanup) {
            r->result_cleanup(r->data);
            r->data = NULL;
        }
        sa_tpool_delete_result(r, free_results);
    }

    /* Wait for any jobs being processed to complete */
    if (sa_tpool_process_flush(q) != 0)
        return -1;

    /* Remove any new output produced during flush */
    mtx_lock(&q->p->pool_m);
    r_head = q->output_head;
    q->output_head = q->output_tail = NULL;
    q->n_output = 0;

    /* Reset serial back to starting point */
    q->next_serial = q->curr_serial = 0;
    cnd_signal(&q->input_not_full_c);
    mtx_unlock(&q->p->pool_m);

    /* Discard unwanted output */
    for (r = r_head; r; r = rn) {
        rn = r->next;
        if (r->result_cleanup) {
            r->result_cleanup(r->data);
            r->data = NULL;
        }
        sa_tpool_delete_result(r, free_results);
    }

    return 0;
}

int sa_tpool_process_qsize(sa_tpool_process *q)
{
    return q->qsize;
}

/*
 * Destroys a thread pool. Threads are joined (they finish current work).
 */
void sa_tpool_destroy(sa_tpool *p)
{
    int i;

    /* Send shutdown message to worker threads */
    mtx_lock(&p->pool_m);
    p->shutdown = 1;

    for (i = 0; i < p->tsize; i++)
        cnd_signal(&p->t[i].pending_c);

    mtx_unlock(&p->pool_m);

    for (i = 0; i < p->tsize; i++)
        thrd_join(p->t[i].tid, NULL);

    mtx_destroy(&p->pool_m);
    for (i = 0; i < p->tsize; i++)
        cnd_destroy(&p->t[i].pending_c);

    if (p->t_stack)
        sa_free(p->t_stack);

    sa_free(p->t);
    sa_free(p);
}
