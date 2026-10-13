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

#include "sa_path.h"
#include "bprint.h"
#include "mem.h"
#include "sastring.h"

#include <errno.h>
#include <stdio.h>
#include <string.h>

#ifdef _WIN32
#include "win_utf8_io.h"
#include <direct.h>
#include <io.h>
#else
#include <unistd.h>
#endif

/**
 * Trim leading and trailing dots from a string in place.
 */
static void trim_dots(char *s)
{
    char *p;
    size_t len;

    if (!s || !*s)
        return;

    len = strlen(s);
    p = s;

    /* Trim trailing dots */
    while (len > 0 && p[len - 1] == '.')
        p[--len] = '\0';

    /* Trim leading dots */
    while (*p == '.') {
        p++;
        len--;
    }

    if (p != s)
        memmove(s, p, len + 1);
}

/**
 * Trim leading and trailing whitespace from a string in place.
 */
static void trim_whitespace(char *s)
{
    char *start, *end;
    size_t len;

    if (!s || !*s)
        return;

    /* Find first non-whitespace */
    start = s;
    while (*start && sa_isspace((unsigned char)*start))
        start++;

    /* All whitespace */
    if (!*start) {
        *s = '\0';
        return;
    }

    /* Find last non-whitespace */
    end = start + strlen(start) - 1;
    while (end > start && sa_isspace((unsigned char)*end))
        end--;

    len = (size_t)(end - start + 1);
    if (start != s)
        memmove(s, start, len);
    s[len] = '\0';
}

int sa_stat(const char *path, struct stat *buf)
{
    if (!path || !buf)
        return -1;

#ifdef _WIN32
    struct __stat64 wbuf;
    int ret;

    ret = stat64_utf8(path, &wbuf);
    if (ret == 0) {
        /* Copy relevant fields to struct stat */
        buf->st_dev = wbuf.st_dev;
        buf->st_ino = wbuf.st_ino;
        buf->st_mode = wbuf.st_mode;
        buf->st_nlink = wbuf.st_nlink;
        buf->st_uid = wbuf.st_uid;
        buf->st_gid = wbuf.st_gid;
        buf->st_rdev = wbuf.st_rdev;
        buf->st_size = (off_t)wbuf.st_size;
        buf->st_atime = wbuf.st_atime;
        buf->st_mtime = wbuf.st_mtime;
        buf->st_ctime = wbuf.st_ctime;
    }

    return ret;
#else
    return stat(path, buf);
#endif
}

int sa_path_exists(const char *path)
{
    struct stat st;
    return sa_stat(path, &st) == 0;
}

int sa_dir_exists(const char *path)
{
    struct stat st;

    if (sa_stat(path, &st) != 0)
        return 0;

#ifdef _WIN32
    return (st.st_mode & _S_IFMT) == _S_IFDIR;
#else
    return S_ISDIR(st.st_mode);
#endif
}

int sa_file_exists(const char *path)
{
    struct stat st;

    if (sa_stat(path, &st) != 0)
        return 0;

#ifdef _WIN32
    return (st.st_mode & _S_IFMT) == _S_IFREG;
#else
    return S_ISREG(st.st_mode);
#endif
}

char *sa_make_path(const char *base, const char *subdir,
                   const char *filename, const char *extension)
{
    AVBPrint bp;
    char *result = NULL;
    char sep = sa_path_separator();

    sa_bprint_init(&bp, SA_PATH_MAX, SA_BPRINT_SIZE_UNLIMITED);

    /* Append base path */
    if (base && *base) {
        sa_bprintf(&bp, "%s", base);
        /* Ensure trailing separator */
        if (bp.len > 0 && !sa_is_path_separator(bp.str[bp.len - 1]))
            sa_bprintf(&bp, "%c", sep);
    }

    /* Append subdirectory */
    if (subdir && *subdir) {
        sa_bprintf(&bp, "%s", subdir);
        if (bp.len > 0 && !sa_is_path_separator(bp.str[bp.len - 1]))
            sa_bprintf(&bp, "%c", sep);
    }

    /* Sanitize the path portion built so far */
    if (bp.len > 0)
        sa_sanitize_filepath(bp.str, bp.size);

    /* Append filename (sanitized) */
    if (filename && *filename) {
        char sanitized[SA_FILENAME_MAX];
        sa_strlcpy(sanitized, filename, sizeof(sanitized));
        sa_sanitize_filename(sanitized, sizeof(sanitized));
        sa_bprintf(&bp, "%s", sanitized);
    }

    /* Append extension */
    if (extension && *extension) {
        sa_bprintf(&bp, ".%s", extension);
    }

    if (!sa_bprint_is_complete(&bp)) {
        sa_bprint_finalize(&bp, NULL);
        return NULL;
    }

    if (sa_bprint_finalize(&bp, &result) < 0)
        return NULL;

    return result;
}

/**
 * Create a single directory with UTF-8 path support.
 */
static int mkdir_single(const char *path, sa_mode_t mode)
{
    int ret;

#ifdef _WIN32
    wchar_t *wpath;
    (void)mode;  /* Mode is ignored on Windows */

    wpath = wchar_from_utf8(path);
    if (!wpath) {
        errno = EINVAL;
        return -1;
    }

    ret = _wmkdir(wpath);
    sa_free(wpath);  /* wchar_from_utf8 uses malloc, not sa_malloc */
#else
    ret = mkdir(path, mode);
#endif

    return ret;
}

int sa_mkdir_p(const char *path, const char *base_dir, sa_mode_t mode)
{
    char *path_copy = NULL;
    char *pos;
    size_t base_len = 0;
    int ret = 0;

    if (!path || !*path)
        return -1;

    path_copy = sa_strdup(path);
    if (!path_copy)
        return -1;

    if (base_dir && *base_dir) {
        base_len = strlen(base_dir);
        /* Skip past base_dir in the path */
        if (base_len < strlen(path_copy))
            base_len++;  /* Skip separator after base_dir */
    }

    pos = path_copy + base_len;

    /* Skip leading separators for absolute paths */
    while (*pos && sa_is_path_separator(*pos))
        pos++;

    /* Iterate through path components */
    while (*pos) {
        /* Find next separator */
        while (*pos && !sa_is_path_separator(*pos))
            pos++;

        if (*pos) {
            char saved = *pos;
            *pos = '\0';

            ret = mkdir_single(path_copy, mode);
            if (ret != 0 && errno != EEXIST && errno != EISDIR &&
                errno != EACCES && errno != EROFS) {
                sa_free(path_copy);
                return -1;
            }

#ifndef _WIN32
            /* Set permissions on Unix */
            chmod(path_copy, mode);
#endif

            *pos = saved;
            pos++;
        }
    }

    /* Create final directory (in case path doesn't end with separator) */
    ret = mkdir_single(path_copy, mode);
    if (ret != 0 && errno != EEXIST && errno != EISDIR &&
        errno != EACCES && errno != EROFS) {
        sa_free(path_copy);
        return -1;
    }

#ifndef _WIN32
    chmod(path_copy, mode);
#endif

    sa_free(path_copy);
    return 0;
}

char *sa_unique_path(const char *dir, const char *filename, const char *extension)
{
    char *path = NULL;
    char numbered_name[SA_FILENAME_MAX];
    int i;

    if (!filename || !*filename)
        return NULL;

    /* Try original name first */
    path = sa_make_path(dir, NULL, filename, extension);
    if (!path)
        return NULL;

    if (!sa_path_exists(path))
        return path;

    sa_free(path);

    /* Try numbered variants */
    for (i = 1; i <= 64; i++) {
        int n = snprintf(numbered_name, sizeof(numbered_name),
                         "%s (%d)", filename, i);
        if (n < 0 || (size_t)n >= sizeof(numbered_name))
            continue;

        path = sa_make_path(dir, NULL, numbered_name, extension);
        if (!path)
            return NULL;

        if (!sa_path_exists(path))
            return path;

        sa_free(path);
    }

    return NULL;
}

void sa_sanitize_filepath(char *path, size_t size)
{
    /*
     * Invalid characters for file paths:
     * - Control characters (0x00-0x1F, 0x7F)
     * - NTFS/Windows: " * < > ? |
     * - We preserve : for drive letters and / \ for separators
     *
     * Note: macOS HFS+ only disallows :, Unix only disallows / and null
     */
    static const char unsafe_chars[] = {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a,
        0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12, 0x13, 0x14,
        0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e,
        0x1f,
        '"',  /* 0x22 */
        '*',  /* 0x2a */
        '<',  /* 0x3c */
        '>',  /* 0x3e */
        '?',  /* 0x3f */
        '|',  /* 0x7c */
        0x7f, /* DEL */
        '\0'
    };
    char *c;

    if (!path || !*path || size == 0)
        return;

    for (c = path; *c; c++) {
        if (strchr(unsafe_chars, *c))
            *c = '_';
    }

    /* Trim dots and whitespace from path components */
    trim_dots(path);
    trim_whitespace(path);
}
