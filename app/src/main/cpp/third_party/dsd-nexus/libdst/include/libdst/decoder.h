/*
 * Direct Stream Transfer (DST) decoder
 * Copyright (c) 2014 Peter Ross <pross@xvid.org>
 *
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
 * Direct Stream Transfer (DST) decoder
 * ISO/IEC 14496-3 Part 3 Subpart 10: Technical description of lossless coding of oversampled audio
 */

#ifndef LIBDST_DECODER_H
#define LIBDST_DECODER_H

#include <stdint.h>
#include <libdst/dst_export.h>

typedef struct dst_decoder_s dst_decoder_t;

int DST_API dst_decoder_init(dst_decoder_t **decoder, int channel_count, int sample_rate);
int DST_API dst_decoder_close(dst_decoder_t *decoder);

int DST_API dst_decoder_decode(dst_decoder_t *decoder,
                       uint8_t *dst_data, int frame_size,
                       uint8_t *dsd_output, int *dsd_output_len);

#endif /* LIBDST_DECODER_H */
