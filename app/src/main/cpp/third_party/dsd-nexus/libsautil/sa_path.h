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

/**
 * @file
 * @brief Path manipulation utilities
 *
 * Cross-platform path manipulation functions with UTF-8 support.
 */

#ifndef SAUTIL_SA_PATH_H
#define SAUTIL_SA_PATH_H

#include <stddef.h>
#include "export.h"

#ifdef _WIN32
#include <sys/types.h>
#include <sys/stat.h>
typedef int sa_mode_t;
#else
#include <sys/stat.h>
typedef mode_t sa_mode_t;
#endif

#include "attributes.h"

/**
 * @defgroup sa_path Path Utilities
 * @{
 */

/** Maximum path length for internal buffers */
#define SA_PATH_MAX 1024

/** Maximum filename length (excluding path) */
#define SA_FILENAME_MAX 255

/**
 * Cross-platform stat() wrapper with UTF-8 support.
 *
 * On Windows, converts the UTF-8 path to wide characters and uses _wstat().
 * On POSIX systems, calls stat() directly.
 *
 * @param[in]  path UTF-8 encoded path
 * @param[out] buf  Pointer to stat structure to fill
 * @return 0 on success, -1 on error (errno is set)
 */
SACD_API int sa_stat(const char *path, struct stat *buf);

/**
 * Check if a path exists (file or directory).
 *
 * @param path UTF-8 encoded path to check
 * @return 1 if path exists, 0 otherwise
 */
SACD_API int sa_path_exists(const char *path);

/**
 * Check if a path exists and is a directory.
 *
 * @param path UTF-8 encoded path to check
 * @return 1 if path exists and is a directory, 0 otherwise
 */
SACD_API int sa_dir_exists(const char *path);

/**
 * Check if a path exists and is a regular file.
 *
 * @param path UTF-8 encoded path to check
 * @return 1 if path exists and is a regular file, 0 otherwise
 */
SACD_API int sa_file_exists(const char *path);

/**
 * Construct a file path from components.
 *
 * Builds a path by joining the provided components with the appropriate
 * path separator. All components are optional and may be NULL.
 *
 * @param base      Base directory path (without trailing separator)
 * @param subdir    Subdirectory name (without separators)
 * @param filename  File name (will be sanitized)
 * @param extension File extension (without leading dot)
 * @return Newly allocated path string, or NULL on error.
 *         Caller must free with sa_free().
 *
 * @note The filename is automatically sanitized for filesystem safety.
 */
sa_warn_unused_result
SACD_API char * sa_make_path(const char *base, const char *subdir,
                   const char *filename, const char *extension);

/**
 * Recursively create directories.
 *
 * Creates all directories in the given path that do not exist.
 * Similar to `mkdir -p` on Unix systems.
 *
 * @param path     UTF-8 encoded path to create
 * @param base_dir Optional base directory (already exists, don't try to create).
 *                 May be NULL.
 * @param mode     Permission mode for created directories (ignored on Windows)
 * @return 0 on success, -1 on error
 */
SACD_API int sa_mkdir_p(const char *path, const char *base_dir, sa_mode_t mode);

/**
 * Generate a unique file path that doesn't exist.
 *
 * If the specified path already exists, appends " (1)", " (2)", etc.
 * until a non-existing path is found.
 *
 * @param dir       Directory path
 * @param filename  Base filename (without extension)
 * @param extension File extension (without leading dot)
 * @return Newly allocated unique path, or NULL on error or if no unique
 *         name found within 64 attempts. Caller must free with sa_free().
 */
sa_warn_unused_result
SACD_API char * sa_unique_path(const char *dir, const char *filename, const char *extension);

/**
 * Sanitize a file path by replacing invalid characters.
 *
 * Replaces characters that are invalid in file paths on common filesystems
 * (Windows NTFS, macOS HFS+, Linux ext4) with underscores. Unlike
 * sa_sanitize_filename(), this preserves path separators (/ and \) and
 * drive letters (e.g., C:).
 *
 * Invalid characters replaced: control chars (0x00-0x1F, 0x7F), " * < > ? |
 *
 * @param[in,out] path UTF-8 string to sanitize (modified in place)
 * @param[in]     size Size of the path buffer
 *
 * @note Leading/trailing spaces and dots in path components are trimmed.
 */
SACD_API void sa_sanitize_filepath(char *path, size_t size);

/**
 * Get the platform-specific path separator character.
 *
 * @return '\\' on Windows, '/' on POSIX systems
 */
static inline char sa_path_separator(void)
{
#ifdef _WIN32
    return '\\';
#else
    return '/';
#endif
}

/**
 * Check if a character is a path separator.
 *
 * @param c Character to check
 * @return Non-zero if c is a path separator, 0 otherwise
 *
 * @note On Windows, both '/' and '\\' are considered separators.
 */
static inline int sa_is_path_separator(char c)
{
#ifdef _WIN32
    return c == '/' || c == '\\';
#else
    return c == '/';
#endif
}

/** @} */

#endif /* SAUTIL_SA_PATH_H */
