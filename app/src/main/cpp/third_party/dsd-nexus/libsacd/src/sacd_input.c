/*
 * This file is part of DSD-Nexus.
 * Copyright (c) 2026 Alexander Wichers
 *
 * @brief Common utilities and auto-detection for SACD input devices.
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

#include "sacd_input.h"

#include <string.h>
#include <stdlib.h>
#include <ctype.h>

const char *sacd_input_error_string(sacd_input_error_t error)
{
    switch (error) {
    case SACD_INPUT_OK:
        return "success";
    case SACD_INPUT_ERR_NULL_PTR:
        return "null pointer";
    case SACD_INPUT_ERR_OPEN_FAILED:
        return "open failed";
    case SACD_INPUT_ERR_READ_FAILED:
        return "read failed";
    case SACD_INPUT_ERR_SEEK_FAILED:
        return "seek failed";
    case SACD_INPUT_ERR_AUTH_FAILED:
        return "authentication failed";
    case SACD_INPUT_ERR_DECRYPT_FAILED:
        return "decryption failed";
    case SACD_INPUT_ERR_NOT_SUPPORTED:
        return "operation not supported";
    case SACD_INPUT_ERR_OUT_OF_MEMORY:
        return "out of memory";
    case SACD_INPUT_ERR_NETWORK:
        return "network error";
    case SACD_INPUT_ERR_TIMEOUT:
        return "timeout";
    case SACD_INPUT_ERR_INVALID_ARG:
        return "invalid argument";
    case SACD_INPUT_ERR_EOF:
        return "end of file";
    case SACD_INPUT_ERR_CLOSED:
        return "device closed";
    default:
        return "unknown error";
    }
}

const char *sacd_input_type_string(sacd_input_type_t type)
{
    switch (type) {
    case SACD_INPUT_TYPE_FILE:
        return "file";
    case SACD_INPUT_TYPE_MEMORY:
        return "memory";
    case SACD_INPUT_TYPE_NETWORK:
        return "network";
    case SACD_INPUT_TYPE_DEVICE:
        return "device";
    case SACD_INPUT_TYPE_UNKNOWN:
    default:
        return "unknown";
    }
}

/**
 * @brief Check if a string looks like a network address (host:port).
 *
 * Simple heuristic: contains at least one dot and ends with :digits
 */
static bool _is_network_path(const char *path)
{
    const char *colon;
    const char *p;
    int dot_count = 0;

    if (!path || !*path) {
        return false;
    }

    /* Find the last colon */
    colon = strrchr(path, ':');
    if (!colon || colon == path) {
        return false;
    }

    /* Check if everything after colon is digits (port number) */
    for (p = colon + 1; *p; p++) {
        if (!isdigit((unsigned char)*p)) {
            return false;
        }
    }

    /* Must have at least one digit after colon */
    if (p == colon + 1) {
        return false;
    }

    /* Count dots before colon (IP address check) */
    for (p = path; p < colon; p++) {
        if (*p == '.') {
            dot_count++;
        }
    }

    /* IPv4 addresses have 3 dots, hostnames have at least one */
    return dot_count >= 1;
}

/**
 * @brief Check if a path refers to a physical device.
 */
static bool _is_device_path(const char *path)
{
    if (!path || !*path) {
        return false;
    }

#ifdef _WIN32
    /* Windows: Check for drive letter patterns like "D:" or "\\.\D:" */
    if (strlen(path) >= 2) {
        /* Simple drive letter */
        if (isalpha((unsigned char)path[0]) && path[1] == ':' &&
            (path[2] == '\0' || path[2] == '\\' || path[2] == '/')) {
            /* This is a drive root - could be file or device */
            /* Treat as device only if it's just the drive letter */
            return path[2] == '\0';
        }
        /* Device path like \\.\D: */
        if (strncmp(path, "\\\\.\\", 4) == 0) {
            return true;
        }
        if (strncmp(path, "//./", 4) == 0) {
            return true;
        }
    }
#else
    /* Unix: Check for /dev/ prefix */
    if (strncmp(path, "/dev/", 5) == 0) {
        return true;
    }
#endif

    return false;
}

int sacd_input_open(const char *path, sacd_input_t **out)
{
    if (!path || !out) {
        return SACD_INPUT_ERR_INVALID_ARG;
    }

    /* Check for network path first (host:port pattern) */
    if (_is_network_path(path)) {
#ifdef SACD_NO_PS3DRIVE
        return SACD_INPUT_ERR_NOT_SUPPORTED;
#else
        char host[256];
        const char *colon = strrchr(path, ':');
        size_t host_len = (size_t)(colon - path);
        uint16_t port;

        if (host_len >= sizeof(host)) {
            host_len = sizeof(host) - 1;
        }
        memcpy(host, path, host_len);
        host[host_len] = '\0';

        port = (uint16_t)atoi(colon + 1);
        return sacd_input_open_network(host, port, out);
#endif
    }

    /* Check for device path */
    if (_is_device_path(path)) {
#ifdef SACD_NO_PS3DRIVE
        return SACD_INPUT_ERR_NOT_SUPPORTED;
#else
        return sacd_input_open_device(path, out);
#endif
    }

    /* Default to file */
    return sacd_input_open_file(path, out);
}
