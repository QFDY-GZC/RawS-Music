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

#ifndef LIBSACD_SACD_SPECIFICATION_H
#define LIBSACD_SACD_SPECIFICATION_H

#include <libsacd/sacd.h>
#include <libsautil/bswap.h>
#include <libsautil/export.h>

#ifdef __cplusplus
extern "C" {
#endif

#undef ATTRIBUTE_PACKED
#undef PRAGMA_PACK_BEGIN
#undef PRAGMA_PACK_END

#if defined(__GNUC__)
#if __GNUC__ > 2 || (__GNUC__ == 2 && __GNUC_MINOR__ >= 95)
#define ATTRIBUTE_PACKED __attribute__((packed))
#define PRAGMA_PACK 0
#endif
#endif

#if !defined(ATTRIBUTE_PACKED)
#define ATTRIBUTE_PACKED
#define PRAGMA_PACK 1
#endif

#define MASTER_TOC_SIGN    (MAKE_MARKER64('S', 'A', 'C', 'D', 'M', 'T', 'O', 'C'))
#define MASTER_TEXT_SIGN   (MAKE_MARKER64('S', 'A', 'C', 'D', 'T', 'e', 'x', 't'))
#define MANUF_INFO_SIGN    (MAKE_MARKER64('S', 'A', 'C', 'D', '_', 'M', 'a', 'n'))
#define AREA_2CH_TOC_SIGN  (MAKE_MARKER64('T', 'W', 'O', 'C', 'H', 'T', 'O', 'C'))
#define AREA_MCH_TOC_SIGN  (MAKE_MARKER64('M', 'U', 'L', 'C', 'H', 'T', 'O', 'C'))
#define TRACK_LIST1_SIGN   (MAKE_MARKER64('S', 'A', 'C', 'D', 'T', 'R', 'L', '1'))
#define TRACK_LIST2_SIGN   (MAKE_MARKER64('S', 'A', 'C', 'D', 'T', 'R', 'L', '2'))
#define ISRC_GENRE_SIGN    (MAKE_MARKER64('S', 'A', 'C', 'D', '_', 'I', 'G', 'L'))
#define ACCESS_LIST_SIGN   (MAKE_MARKER64('S', 'A', 'C', 'D', '_', 'A', 'C', 'C'))
#define TRACK_TEXT_SIGN    (MAKE_MARKER64('S', 'A', 'C', 'D', 'T', 'T', 'x', 't'))
#define INDEX_LIST_SIGN    (MAKE_MARKER64('S', 'A', 'C', 'D', '_', 'I', 'n', 'd'))

#define MAX_ACCESS_LIST_COUNT 6550
#define MAX_MANUFACTURER_INFO 2040
#define MAX_DISC_WEB_LINK_INFO 128

#define ISRC_FIRST_SECTOR_COUNT 170
#define ISRC_SECOND_SECTOR_COUNT 85

#define SACD_LSN_SIZE 2048

#define MASTER_TOC1_START 510
#define MASTER_TOC2_START 520
#define MASTER_TOC3_START 530

#define FRAME_START_USE_CURRENT 0xFFFFFFFF

#define FS_HEADER_48 0
#define FS_TRAILER_48 0
#define FS_SECTOR_SIZE_48 (SACD_LSN_SIZE + FS_HEADER_48 + FS_TRAILER_48)
#define FS_HEADER_54 6
#define FS_TRAILER_54 0
#define FS_SECTOR_SIZE_54 (SACD_LSN_SIZE + FS_HEADER_54 + FS_TRAILER_54)
#define FS_HEADER_64 12
#define FS_TRAILER_64 4
#define FS_SECTOR_SIZE_64 (SACD_LSN_SIZE + FS_HEADER_64 + FS_TRAILER_64)

typedef enum
{
      CHAR_SET_UNKNOWN       = 0
    , CHAR_SET_ISO646        = 1    // ISO 646 (IRV), no escape sequences allowed
    , CHAR_SET_ISO8859_1     = 2    // ISO 8859-1, no escape sequences allowed
    , CHAR_SET_RIS506        = 3    // MusicShiftJIS, per RIS-506 (RIAJ), Music Shift-JIS Kanji
    , CHAR_SET_KSC5601       = 4    // Korean KSC 5601-1987
    , CHAR_SET_GB2312        = 5    // Chinese GB 2312-80
    , CHAR_SET_BIG5          = 6    // Big5
    , CHAR_SET_ISO8859_1_ESC = 7    // ISO 8859-1, single byte set escape sequences allowed
} character_set_t;

#if PRAGMA_PACK
#pragma pack(1)
#endif

//* Structs
/**
 * @brief Represents a genre Code, consisting of a table identifier and an index within that table.
 * * The total size is 4 bytes (1 + 1 + 2).
 * * If a Genre_Code structure is not used, both genre_table and genre_index must be set to zero.
 */
typedef struct
{
    /**
     * @brief Identifies the specific genre table used by the 'genre_index' field.
     * * The definition of the values for genre_table is as follows:
     * - **0**: Not used. Must be 0 if genre_index is 0.
     * - **1**: General genre Table (See Annex B).
     * - **2**: Japanese genre Table (See RIS504).
     * - **3..255**: Reserved for future standardization.
     */
    uint8_t genre_table;

    /**
     * @brief Reserved field.
     * * This field is not currently used and should be ignored or set to 0.
     */
    uint8_t reserved;

    /**
     * @brief The index within the selected 'genre_table' that provides the actual genre definition.
     * * If 'genre_table' is equal to zero, 'genre_index' must also be set to zero.
     */
    uint16_t genre_index;
} ATTRIBUTE_PACKED genre_code_t;

/**
 * @struct sacd_version_t
 * @brief Stores the major and minor version numbers of a specification.
 *
 * This structure is used to identify the version of the relevant specification
 * (e.g., Super Audio CD) that a component or disc adheres to.
 * It is a compact 2-byte structure.
 */
typedef struct
{
    /**
     * @brief The major version number of this specification.
     * @note For discs according to this specification, this value must be **2**.
     */
    uint8_t major;

    /**
     * @brief The minor version number of this specification.
     * @note For discs according to this specification, this value must be **0**.
     */
    uint8_t minor;
} ATTRIBUTE_PACKED sacd_version_t;

/**
 * @brief Represents a date with year, month, and day fields.
 * * The total size is 4 bytes (2 + 1 + 1).
 */
typedef struct
{
    /**
     * @brief The year.
     * * Range: 0 to 65535.
     * A value of 0 is only allowed if a valid date is not available.
     */
    uint16_t year;

    /**
     * @brief The month.
     * * Range: 0 to 12.
     * Values 1..12 mean January..December, respectively.
     * A value of 0 is only allowed if a valid date is not available.
     */
    uint8_t month;

    /**
     * @brief The day of the month.
     * * Range: 0 to 31.
     * A value of 0 is only allowed if a valid date is not available.
     */
    uint8_t day;
} ATTRIBUTE_PACKED date_sacd_t;

/**
 * @brief Contains general information about the Album associated with the disc.
 * * The total size is 48 bytes (2 + 2 + 4 + 16 + (4 * 4) + 8).
 * * It is recommended that all discs in one Album have the same Album_Genre.
 */
typedef struct
{
    /**
     * @brief The total number of discs in this album set.
     * * Minimum allowed value is **1**.
     * * All discs in one Album must have the same value.
     */
    uint16_t album_set_size;

    /**
     * @brief The sequence number of this disc within the album.
     * * Numbering is consecutive, starting with **1** for the first disc.
     * * Range: **1** to the value of album_set_size.
     */
    uint16_t album_sequence_number;

    /**
     * @brief Reserved field.
     * * Should be ignored or set to 0.
     */
    uint8_t reserved_0[4];

    /**
     * @brief The catalog number of this album (e.g., UPC/EAN).
     * * All discs in one Album must have the same number.
     * * If not used, all bytes MUST be set to **zero (0x00)**.
     * * If used, the field MUST be padded at the end with **space characters (0x20)**.
     */
    char album_catalog_number[MAX_CATALOG_LENGTH];

    /**
     * @brief Minimum zero and maximum four genres associated with this album.
     * * The genre codes are defined by the genre_code_t structure (Table and index).
     * * Maximum number of entries is 4.
     */
    genre_code_t album_genre[MAX_GENRE_COUNT];

    /**
     * @brief Reserved field.
     * * Should be ignored or set to 0.
     */
    uint8_t reserved_1[8];
} ATTRIBUTE_PACKED album_info_t;

/**
 * @brief Contains general information about the Super Audio CD disc, including TOC addresses and disc-specific metadata.
 * * The total size is 64 bytes (4+4+4+4+1+3+2+2+16+(4*4)+4+4).
 */
typedef struct
{
    /**
     * @brief Logical sector Number (LSN) of the first sector of Area TOC-1 in the **2-Channel Stereo Area**.
     * * If the 2-Channel Area is present, this MUST be **544**. If not present, this MUST be **0**.
     */
    uint32_t stereo_toc_1_lsn;

    /**
     * @brief LSN of the first sector of Area TOC-2 in the **2-Channel Stereo Area**.
     * * If the 2-Channel Area is not present, this MUST be **0**.
     */
    uint32_t stereo_toc_2_lsn;

    /**
     * @brief LSN of the first sector of Area TOC-1 in the **Multi Channel Area**.
     * * If the Multi Channel Area is not present, this MUST be **0**.
     */
    uint32_t mc_toc_1_lsn;

    /**
     * @brief LSN of the first sector of Area TOC-2 in the **Multi Channel Area**.
     * * If the Multi Channel Area is not present, this MUST be **0**.
     */
    uint32_t mc_toc_2_lsn;

    /**
     * @brief Flags defining the disc type.
     * * **Bit 7 (Hybr bit):** Set to **1** on a **hybrid Disc**; set to **0** on a non-hybrid Disc.
     * * **Bits 0-6 (Reserved):** MUST be set to **0**.
     */
#if defined(__BIG_ENDIAN__)
    uint8_t disc_type_hybrid : 1;
    uint8_t disc_type_reserved : 7;
#else
    uint8_t disc_type_reserved : 7;
    uint8_t disc_type_hybrid : 1;
#endif

    /**
     * @brief Reserved field.
     * * Should be ignored or set to 0.
     */
    uint8_t reserved_0[3];

    /**
     * @brief The length in Sectors of Area TOC-A in the **2-Channel Stereo Area**.
     * * If the 2-Channel Area is not present, this MUST be **0**.
     */
    uint16_t stereo_toc_length;

    /**
     * @brief The length in Sectors of Area TOC-A in the **Multi Channel Area**.
     * * If the Multi Channel Area is not present, this MUST be **0**.
     */
    uint16_t mc_toc_length;

    /**
     * @brief The 16-byte string that uniquely identifies this specific disc in an Album.
     * * If not used, all bytes MUST be set to **zero (0x00)**.
     * * If used, the string MUST be padded at the end with **space characters (0x20)**.
     */
    char disc_catalog_number[MAX_CATALOG_LENGTH];

    /**
     * @brief Minimum zero and maximum four genres associated with this Super Audio CD disc.
     * * The genre code syntax is defined by the genre_code_t structure.
     */
    genre_code_t disc_genre[MAX_GENRE_COUNT];

    /**
     * @brief The creation date of the disc.
     * * If disc_date is not used, all fields (year, month, day) MUST be set to **zero**.
     */
    date_sacd_t disc_date;

    /**
     * @brief Reserved field.
     * * Should be ignored or set to 0.
     */
    uint8_t reserved_1[4];
} ATTRIBUTE_PACKED disc_info_t;

/**
 * @brief Contains a WebLink_String pointing to a web page with information about the disc.
 * * The total size is 128 bytes.
 */
typedef struct
{
    /**
     * @brief A WebLink_String that points to a web page.
     * * This page contains information specific to this disc.
     */
    char disc_weblink[128];
} ATTRIBUTE_PACKED disc_weblink_info_t;

/**
 * @brief Defines the language and character set for a single Text Channel.
 * * The total size is 4 bytes (2 + 1 + 1).
 */
typedef struct
{
    /**
     * @brief The ISO 639 Language Code for the text in this channel.
     * * All text in the corresponding Text Channel must be according to this code.
     * * The value 0x0000 is **not** allowed.
     */
    uint16_t language_code;

    /**
     * @brief Defines the character set used for the text in this channel.
     * * All text in the corresponding Text Channel must be encoded using this Character Set.
     * * The value 0 (Not used) is **not** allowed.
     * * See the 'Character Set Code' table in the text_channels structure for definitions.
     */
    uint8_t character_set_code;

    /**
     * @brief Reserved field.
     * * Should be ignored or set to 0.
     */
    uint8_t reserved_0;
} ATTRIBUTE_PACKED chan_info_t;

/**
 * @brief Container for all Text Channel information, including language and character set codes.
 * * The maximum allowed value for N_Text_Channels is 8.
 * * The total size is 40 bytes (1 + 7 + (8 * 4)).
 */
typedef struct
{
    /**
     * @brief The number of Text Channels currently used.
     * * Must be equal to the number of used Text Channels.
     * * Range: 0 to 8. A value of zero is allowed.
     * * If not zero, at least one text item must be present in **each** used Text Channel.
     */
    uint8_t text_channel_count;

    /**
     * @brief Reserved fields to ensure 8-byte alignment before the array.
     * * Should be ignored or set to 0.
     */
    uint8_t reserved_0[7];

    /**
     * @brief Array of structures defining the Language Code and Character Set Code for each text channel.
     * * The number of used channels is defined by text_channel_count.
     * * Used channels must be encoded starting with index 0 (Text Channel Number 1).
     * * Unused channels must follow all used channels.
     * * **Recommendation:** Text Channel 1 (info[0]) should be used as the default by an SACD player.
     * * **Recommendation:** Use the same Text Channel Number order for Master TOC and Area TOC if data combination is the same.
     *
     * ### Character Set Code Definitions
     * | Code | Bytes/Char | Description |
     * | :--- | :--- | :--- |
     * | 1 | 1 | ISO 646 International Reference Version (IRV), no escape sequences |
     * | 2 | 1 | ISO 8859-1, no escape sequences |
     * | 3 | 2 | RIS 506 |
     * | 4 | 2 | Korean KSC 5601-1989 |
     * | 5 | 2 | Chinese GB 2312-80 |
     * | 6 | 2 | Big5 |
     * | 7 | 1 | ISO 8859-1, escape sequences to single byte character sets are allowed |
     * | 0, 8..255 | - | Not used or Reserved for future standardization |
     */
    chan_info_t info[MAX_TEXT_CHANNEL_COUNT];
} ATTRIBUTE_PACKED text_channels_t;

/**
 * @brief The Master Table of Contents, sector 0 (Master_TOC_0).
 * * Contains general metadata for the entire Super Audio CD disc and album.
 * * This structure has a fixed size of exactly **one sector** (2048 bytes).
 */
typedef struct
{
    /**
     * @brief An 8-byte string identifying the Master TOC.
     * * Value MUST be "SACDMTOC" (0x53 0x41 0x43 0x44 0x4D 0x54 0x4F 0x43).
     */
    uint64_t signature;

    /**
     * @brief The specification version of the Master TOC structure.
     */
    sacd_version_t version;

    /**
     * @brief Reserved field.
     * * Should be ignored or set to 0.
     */
    uint8_t reserved_1[6];

    /**
     * @brief Album-specific information (48 bytes).
     * * Includes album_set_size, album_sequence_number, album_catalog_number, and album_genre.
     * * All discs in the album must have the same album_set_size and album_catalog_number.
     */
    album_info_t album;

    /**
     * @brief Disc-specific information (64 bytes).
     * * Includes TOC addresses, disc_flags, disc_catalog_number, disc_genre, and disc_date.
     */
    disc_info_t disc;

    /**
     * @brief Defines the properties (Language, Character Set) of the Text Channels used (40 bytes).
     * * Used to interpret the text strings found in subsequent Master TOC sectors.
     */
    text_channels_t text_channels;

    /**
     * @brief Contains a WebLink_String pointing to a web page with disc information (128 bytes).
     */
    disc_weblink_info_t disc_weblink_info;

    /**
     * @brief Reserved field to fill the structure to the full sector size (2048 bytes).
     * * Should be ignored or set to 0.
     */
    uint8_t reserved_2[1752];
} ATTRIBUTE_PACKED master_toc_0_t;

/**
 * @brief Contains all general text information related to the Album and Disc for a specific Text Channel (c).
 * * The structure has a fixed size of exactly **one sector** (2048 bytes).
 * * Every Text Channel has an associated Master_Text structure (Master_Text[c]).
 * * For unused Text Channels (c > N_Text_Channels), all fields except the Signature MUST be set to zero.
 * * **Recommendation:** All discs in one Album should contain the same Album- related text.
 * * **Recommendation:** If Album_Set_Size = 1, the Album-related text should equal the Disc-related text.
 */
typedef struct
{
    /**
     * @brief An 8-byte string identifying the text Sectors of the Master TOC.
     * * Value MUST be "SACDText" (0x53 0x41 0x43 0x44 0x54 0x65 0x78 0x74).
     * * All eight Master_Text Sectors must contain this signature.
     */
    uint64_t signature;

    /**
     * @brief Reserved field.
     * * Should be ignored or set to 0.
     */
    uint8_t reserved_1[8];

    // --- Album Text Pointers (The pointer value is the byte position in this sector) ---

    /**
     * @brief Pointer to the start of the album_title text field.
     * * If album_title is used, this MUST be set to **64**. If not used, set to **0**.
     */
    uint16_t album_title_ptr;

    /**
     * @brief Pointer to the start of the album_artist text field.
     * * If album_artist is not used, set to **0**.
     */
    uint16_t album_artist_ptr;

    /**
     * @brief Pointer to the start of the album_publisher text field.
     * * If album_publisher is not used, set to **0**.
     */
    uint16_t album_publisher_ptr;

    /**
     * @brief Pointer to the start of the album_copyright text field.
     * * If album_copyright is not used, set to **0**.
     */
    uint16_t album_copyright_ptr;

    /**
     * @brief Pointer to the start of the album_title_phonetic text field.
     * * If album_title_phonetic is not used, set to **0**.
     */
    uint16_t album_title_phonetic_ptr;

    /**
     * @brief Pointer to the start of the album_artist_phonetic text field.
     * * If album_artist_phonetic is not used, set to **0**.
     */
    uint16_t album_artist_phonetic_ptr;

    /**
     * @brief Pointer to the start of the album_publisher_phonetic text field.
     * * If album_publisher_phonetic is not used, set to **0**.
     */
    uint16_t album_publisher_phonetic_ptr;

    /**
     * @brief Pointer to the start of the album_copyright_phonetic text field.
     * * If album_copyright_phonetic is not used, set to **0**.
     */
    uint16_t album_copyright_phonetic_ptr;

    // --- Disc Text Pointers (The pointer value is the byte position in this sector) ---

    /**
     * @brief Pointer to the start of the disc_title text field.
     * * If disc_title is not used, set to **0**.
     */
    uint16_t disc_title_ptr;

    /**
     * @brief Pointer to the start of the disc_artist text field.
     * * If disc_artist is not used, set to **0**.
     */
    uint16_t disc_artist_ptr;

    /**
     * @brief Pointer to the start of the disc_publisher text field.
     * * If disc_publisher is not used, set to **0**. (Corrected from duplicate disc_artist_ptr)
     */
    uint16_t disc_publisher_ptr;

    /**
     * @brief Pointer to the start of the disc_copyright text field.
     * * If disc_copyright is not used, set to **0**.
     */
    uint16_t disc_copyright_ptr;

    /**
     * @brief Pointer to the start of the disc_title_phonetic text field.
     * * If disc_title_phonetic is not used, set to **0**.
     */
    uint16_t disc_title_phonetic_ptr;

    /**
     * @brief Pointer to the start of the disc_artist_phonetic text field.
     * * If disc_artist_phonetic is not used, set to **0**.
     */
    uint16_t disc_artist_phonetic_ptr;

    /**
     * @brief Pointer to the start of the disc_publisher_phonetic text field.
     * * If disc_publisher_phonetic is not used, set to **0**.
     */
    uint16_t disc_publisher_phonetic_ptr;

    /**
     * @brief Pointer to the start of the disc_copyright_phonetic text field.
     * * If disc_copyright_phonetic is not used, set to **0**.
     */
    uint16_t disc_copyright_phonetic_ptr;

    /**
     * @brief Reserved field.
     * * Should be ignored or set to 0.
     */
    uint8_t reserved_2[16];

    /**
     * @brief Text area containing the actual variable-length strings (album_title, album_artist, etc.) referenced by the pointers above.
     * * The string content must be coded with the character set specified by character_set_code[c] for this Text Channel.
     * * All text strings occupy this space. The end of a string is implied by the start of the next string or the end of the 2048-byte sector.
     * * The start of the first text string (album_title) is at byte position 64.
     */
    uint8_t album_title[1984];
} ATTRIBUTE_PACKED master_text_t;

/**
 * @brief Contains information stored by the disc manufacturer.
 * * This structure has a fixed size of exactly **one sector** (2048 bytes).
 * * If manufacturer information is not stored, all bytes in the Info field MUST be set to **zero**.
 */
typedef struct
{
    /**
     * @brief An 8-byte string identifying the sector with the manufacturer information in the Master TOC.
     * * Value MUST be "SACD_Man" (0x53 0x41 0x43 0x44 0x5F 0x4D 0x61 0x6E).
     */
    uint64_t signature;

    /**
     * @brief Contains the manufacturer-specific information.
     * * The content and format of this data is entirely **decided by the disc manufacturer**.
     * * If no information is stored, all bytes MUST be set to **zero (0x00)**.
     */
    char info[MAX_MANUFACTURER_INFO];
} ATTRIBUTE_PACKED manuf_info_t;

/**
 * @brief Contains general text information for the current Track Area, specifically for a given Text Channel (c).
 * * This structure must ALWAYS be present in Area_TOC_0.
 * * All unused fields MUST be set to zero.
 * * The total size is 8 bytes (2 + 2 + 2 + 2).
 */
typedef struct
{
    /**
     * @brief Pointer to the byte position of the first character of the area_description text.
     * * If the area_description text is not used, this pointer MUST be set to **0**.
     */
    uint16_t area_description_ptr;

    /**
     * @brief Pointer to the byte position of the first character of the area_copyright text.
     * * If the area_copyright text is not used, this pointer MUST be set to **0**.
     */
    uint16_t area_copyright_ptr;

    /**
     * @brief Pointer to the byte position of the first character of the area_description_phonetic text.
     * * If the area_description_phonetic text is not used, this pointer MUST be set to **0**.
     */
    uint16_t area_description_phonetic_ptr;

    /**
     * @brief Pointer to the byte position of the first character of the area_copyright_phonetic text.
     * * If the area_copyright_phonetic text is not used, this pointer MUST be set to **0**.
     */
    uint16_t area_copyright_phonetic_ptr;
} ATTRIBUTE_PACKED area_text_channel_t;

/**
 * @brief Represents a single entry in the Access_List (formerly Main_Acc_List).
 * * This structure defines the start address and either an access margin or a pointer to a detailed list.
 * * The total size is 5 bytes (2 + 3).
 */
typedef struct
{
    /**
     * @brief Contains either a pointer to a detailed access list or an access margin value.
     * * **Bit 15 (Most Significant Bit):** Detailed_Access flag.
     * * **Bits 0-14:** Access Margin value (used for estimating intermediate Start Addresses).
     * * The value stored here is for Interval[N], used to estimate the Start Address for a given time Code.
     */
    uint16_t access_flags;

    /**
     * @brief Contains the Start Address of the Multiplexed frame at the start of the interval.
     * * Specifically, this is the Start Address of Multiplexed frame [N * main_step_size].
     * * The Start Address is the Logical sector Number.
     * * This is a 3-byte big-endian field stored as a byte array.
     * * To convert to a 32-bit value: entry[0] << 16 | entry[1] << 8 | entry[2]
     */
    uint8_t entry[3];
} ATTRIBUTE_PACKED main_acc_list_t;

/**
 * @brief The first sector of the Area Table of Contents (Area_TOC_0) for a Track Area (2-Channel or Multi Channel).
 * * Contains all essential metadata and pointers for the audio area and its tracks.
 * * This structure has a fixed size of exactly **one sector** (2048 bytes).
 */
typedef struct
{
    /**
     * @brief An 8-byte string identifying the first sector of the Area TOC.
     * * **2-Channel Stereo Area:** "TWOCHTOC" (0x54 0x57 0x4F 0x43 0x48 0x54 0x4F 0x43)
     * * **Multi Channel Area:** "MULCHTOC" (0x4D 0x55 0x4C 0x43 0x48 0x54 0x4F 0x43)
     */
    uint64_t signature;

    /**
     * @brief The specification version of the Area TOC structure.
     */
    sacd_version_t version;

    /**
     * @brief The length of the area_toc in Sectors.
     * * Includes the length of all additional Lists defined in Super Audio CD Part 3.
     */
    uint16_t area_toc_length;

    /**
     * @brief Reserved field.
     */
    uint8_t reserved_1[4];

    /**
     * @brief The highest Average Byte Rate (in bytes/second) of the Multiplexed Frames in this Track Area (calculated over 15 Frames).
     * * **DST/Flexible Format (frame_format=0):** Max Byte Rate is calculated based on start addresses. Max value is 839680 (2Ch) or 1873920 (5/6Ch).
     * * **Fixed Format 2 (frame_format=2):** MUST be 716800.
     * * **Fixed Format 3 (frame_format=3):** MUST be 819200.
     */
    uint32_t max_byte_rate;

    /**
     * @brief Code for the sampling frequency used for the current Audio Area.
     * * **Value 4:** 64 * 44100 Hz.
     * * Other values (0-3, 5-255) are reserved.
     */
    uint8_t fs_code;

    /**
     * @brief Flags defining the Audio Area's format.
     * * **Bits 0-3 (frame_format):** Defines the frame structure of the multiplexed audio signal (e.g., 0=DST/Flexible, 2=Fixed DSD 2Ch/14Sect, 3=Fixed DSD 2Ch/16Sect).
     * * **Bits 4-7:** Reserved (MUST be 0).
     */
#if defined(__BIG_ENDIAN__)
        uint8_t reserved_2a : 4;
        uint8_t frame_format : 4;
#else
    uint8_t frame_format : 4;
    uint8_t reserved_2 : 4;
#endif

    /**
     * @brief Reserved field.
     */
    uint8_t reserved_3[10];

    /**
     * @brief The number of audio channels encoded in each frame (maximum number available).
     * * **2-Channel Stereo Area:** MUST be **2**.
     * * **Multi Channel Area:** Allowed values are **5** and **6**.
     * * Defines Channel Mapping.
     */
    uint8_t channel_count;

    /**
     * @brief Audio Area configuration.
     * * **Bits 0-4 (loudspeaker_config):** Global loudspeaker setup (e.g., 0=2Ch Stereo, 3=5Ch ITU-R, 4=6Ch/5.1).
     * * **Bits 5-7 (extra_settings):** Usage of Audio Channel 4 if channel_count=6 (0=LFE loudspeaker). MUST be 0 if channel_count=2 or 5.
     */
#if defined(__BIG_ENDIAN__)
    uint8_t loudspeaker_config : 5;
    uint8_t extra_settings : 3;
#else
    uint8_t extra_settings : 3;
    uint8_t loudspeaker_config : 5;
#endif

    /**
     * @brief Maximum number of Audio Channels available per Track (min 0, max 6).
     * * **0:** Number of available channels equals channel_count.
     * * **1-6:** Number of available channels equals max_available_channels.
     */
    uint8_t max_available_channels;

    /**
     * @brief Mute flags indicating which Audio Channels are not available (Silence Pattern used).
     * * **Bits 3-6 (AMF1-AMF4):** Calculated as the logical AND of the corresponding TMF flags for all tracks.
     * * If channel_count=2, all AMF bits MUST be 0. If channel_count=5, AMF4 MUST be 0.
     */
    uint8_t area_mute_flags;

    /**
     * @brief Reserved field.
     */
    uint8_t reserved_4[12];

    /**
     * @brief Defines copy management attributes for the Track Area.
     * * **Bits 0-6 (track_attribute):** Copy management information.
     * * **Bit 7:** Reserved.
     */
#if defined(__BIG_ENDIAN__)
        uint8_t reserved_4a : 1;
        uint8_t track_attribute : 7;
#else
    uint8_t track_attribute : 7;
    uint8_t reserved_5 : 1;
#endif

    /**
     * @brief Reserved field.
     */
    uint8_t reserved_6[15];

    /**
     * @brief The total playing time of the current Track Area (max 255:59:74).
     * * MUST satisfy the condition: $\text{total\_area\_play\_time} \ge 0.8 \times \text{roundup}(\text{total\_area\_play\_time in Area TOC-B})$
     * * Calculated as: $\text{track\_start\_time\_code}[\text{n\_tracks}] + \text{track\_time\_length}[\text{n\_tracks}]$.
     */
    time_sacd_t total_area_play_time;

    /**
     * @brief Reserved field.
     */
    uint8_t reserved_7[1];

    /**
     * @brief Track Number offset for display purposes.
     * * $\text{Display Track Number} = \text{Physical Track Number} + \text{track\_offset}$ (max 510).
     * * For the first disc in an Album ($\text{album\_sequence\_number}=1$), this MUST be **0**.
     */
    uint8_t track_offset;

    /**
     * @brief The number of Tracks in the current Track Area (min 1, max 255).
     * * In Area TOC-A, this value **excludes** Bonus Tracks.
     */
    uint8_t track_count;

    /**
     * @brief Reserved field.
     */
    uint8_t reserved_8[2];

    /**
     * @brief Logical sector Number (LSN) of the first sector in the Track Area (contains the Audio_Frame with time_code 0:0:0).
     */
    uint32_t track_area_start_address;

    /**
     * @brief Logical sector Number (LSN) of the last sector in the Track Area.
     * * Calculated as: $\text{track\_start\_address}[\text{n\_tracks}] + \text{track\_length}[\text{n\_tracks}] - 1$.
     */
    uint32_t track_area_end_address;

    /**
     * @brief Defines the properties (Language, Character Set) of the Text Channels used in this Area TOC (40 bytes).
     * * Recommended to be the same as in Area TOC-B.
     */
    text_channels_t text_channels;

    /**
     * @brief Reserved field.
     */
    uint8_t reserved_9[8];

    // --- Relative Pointers (offset in Sectors from the start of the current area_toc) ---

    /**
     * @brief Relative pointer to the sector position of track_text.
     * * If track_text is not present (e.g., text_channel_count is zero), this MUST be **0**. Allowed values are 0, 5, and 37.
     */
    uint16_t track_text_ptr;

    /**
     * @brief Relative pointer to the sector position of index_list.
     * * Only present if the Track Area contains $\ge 1$ Track with two or more Indexes. If not present, this MUST be **0**.
     */
    uint16_t index_list_ptr;

    /**
     * @brief Relative pointer to the sector position of access_list.
     * * MUST be present if the Audio Area is **DST coded**. MUST NOT be present if Plain DSD.
     * * If not present, this MUST be **0**. Allowed values are 0 and 5.
     */
    uint16_t access_list_ptr;

    /**
     * @brief Relative pointer to the sector position of track_weblink_list.
     * * Only present if the Track Area contains $\ge 1$ Track with a WebLink. If not present, this MUST be **0**.
     */
    uint16_t track_weblink_list_ptr;

    /**
     * @brief Reserved field.
     */
    uint8_t reserved_10[10];

    /**
     * @brief Array of pointers to general text information for the current Track Area, one for each text channel (8 * 8 = 64 bytes).
     * * area_toc_0 must always contain area_text. Unused fields MUST be zero.
     */
    area_text_channel_t area_text[MAX_TEXT_CHANNEL_COUNT];

    /**
     * @brief Area containing the actual variable-length strings (area_description, area_copyright, etc.) referenced by the area_text pointers.
     */
    uint8_t data[1840];
} ATTRIBUTE_PACKED area_data_t;

/**
 * @brief Contains the start sector address (LSN) and length in Sectors for all Tracks in the current Track Area.
 * * This structure **must always be present** in an Area TOC.
 * * The length of Track_List_1 is always **one sector** (2048 bytes).
 */
typedef struct
{
    /**
     * @brief An 8-byte string identifying the sector with track_list_1.
     * * Value MUST be "SACDTRL1" (0x53 0x41 0x43 0x44 0x54 0x52 0x4C 0x31).
     */
    uint64_t signature;

    /**
     * @brief Array of Logical sector Numbers (LSN) for the first sector of each Track.
     * * track_start_lsn[tno] is the LSN for the first sector of Track[tno].
     * * The first sector contains the first byte of the Track's first Multiplexed frame.
     * * The address of a Track must be within the current Track Area.
     * * **Constraint:** For $1 \le tno < n\_tracks$, the following must be true:
     * $$ \text{track\_start\_address}[tno+1] \ge \text{track\_start\_address}[tno] + \text{track\_length}[tno] - 1 $$
     */
    uint32_t track_start_lsn[MAX_TRACK_COUNT];

    /**
     * @brief Array containing the length, in Sectors, of each Track.
     * * A track\_length of zero is **not allowed**.
     * * **Calculation:**
     * $$\text{track\_length}[tno] = \text{LSN}(\text{Last sector}[tno]) - \text{track\_start\_address}[tno] + 1$$
     * * **Note:** The sum of all track\_lengths does not have to equal the size of the Track Area because one sector can be shared (last sector of one track, first of next) or one or more tracks can have a preceding pause.
     */
    uint32_t track_length[MAX_TRACK_COUNT];
} ATTRIBUTE_PACKED track_list_1_t;

typedef struct
{
  uint64_t signature;
  /**
   * @brief Contains the start time code and mode for a single Track.
   * * This structure is typically part of a larger Track List.
   * * The total size is 4 bytes (3 + 1).
   */
  struct
  {
      /**
       * @brief The start time Code of the current Track (Track[tno]).
       * * This time Code must be encoded in the Audio_Header of the sector addressed by track_start_lsn[tno].
       * * **Constraint:** For 1 <= tno < track_count, the following must be true:
       * $$ \text{track\_start\_time\_code}[tno+1] \ge \text{track\_start\_time\_code}[tno] + \text{track\_time\_length}[tno] $$
       */
      time_sacd_t track_start_time_code;

      /**
       * @brief Defines the mode and usage flags for the Track.
       * * **Bits 0-6:** Reserved (must be zero).
       * * **Bit 7 (extra_use):** Defines the usage of Audio Channel 4 if channel_count = 6.
       *
       * ### extra_use Bit (Bit 7) Definition
       * * If channel_count = 2 or channel_count = 5, the value of extra_use MUST be **0**.
       * * If channel_count = 6:
       * * **Value 0:** Audio Channel 4 is used for an **LFE (Low-Frequency Effects) loudspeaker**.
       * * **Value 1..7:** Reserved for future use.
       */
      uint8_t track_mode;
  } ATTRIBUTE_PACKED info_1[MAX_TRACK_COUNT];

  /**
   * @brief Contains the playing time and flag information for a single Track.
   * * This structure is typically part of a larger Track List.
   * * The total size is 4 bytes (3 + 1).
   */
  struct
  {
      /**
       * @brief The playing time (length) of the current Track (Track[tno]).
       * * The minimum allowed value for track_time_length is **1 second**.
       * * The format is a time Code (minutes, seconds, frames).
       */
      time_sacd_t track_time_length;

      /**
       * @brief Defines various flags related to the Track's usage and management.
       * * The bit format is as follows:
       * * **Bit 7:** ILP[tno] (index List Present)
       * * **Bit 6:** TMF4[tno] (Track Management Flag 4)
       * * **Bit 5:** TMF3[tno] (Track Management Flag 3)
       * * **Bit 4:** TMF2[tno] (Track Management Flag 2)
       * * **Bit 3:** TMF1[tno] (Track Management Flag 1)
       * * **Bits 0-2:** Reserved (implied, should be ignored or set to 0)
       */
#if defined(__BIG_ENDIAN__)
        uint8_t track_flag_ilp : 1;
        uint8_t track_flag_tmf4 : 1;
        uint8_t track_flag_tmf3 : 1;
        uint8_t track_flag_tmf2 : 1;
        uint8_t track_flag_tmf1 : 1;
        uint8_t reserved : 3;
#else
        uint8_t reserved : 3;
        uint8_t track_flag_tmf1 : 1;
        uint8_t track_flag_tmf2 : 1;
        uint8_t track_flag_tmf3 : 1;
        uint8_t track_flag_tmf4 : 1;
        uint8_t track_flag_ilp : 1;
#endif
  } ATTRIBUTE_PACKED info_2[MAX_TRACK_COUNT];
} ATTRIBUTE_PACKED track_list_2_t;

/**
 * @brief Represents the first sector of the ISRC and genre List (ISRC_and_Genre_List), containing ISRC codes for the first tracks.
 * * This structure has a fixed size of exactly **one sector** (2048 bytes).
 */
typedef struct
{
    /**
     * @brief An 8-byte string identifying the first sector of the isrc_and_genre_list.
     * * Value MUST be "SACD_IGL" (0x53 0x41 0x43 0x44 0x5F 0x49 0x47 0x4C).
     */
    uint64_t signature;

    /**
     * @brief Array of International Standard Recording Codes (ISRCs) for the initial set of tracks.
     * * **Recommendation:** Should contain the ISRC code for Track[tno].
     * * **Mandate:** If the ISRC code is **not available** for a track, all fields in that track's area_isrc_t entry MUST be set to **NUL characters (0x00)**.
     * * If used, the ISRC code must comply with **ISO 3901**.
     */
    area_isrc_t isrc_1[ISRC_FIRST_SECTOR_COUNT];
} ATTRIBUTE_PACKED isrc_genre_list_1_t;

/**
 * @brief Represents the second sector of the ISRC and genre List (ISRC_and_Genre_List), containing the remaining ISRC codes and all Track genre Codes.
 * * This structure has a fixed size of exactly **one sector** (2048 bytes).
 */
typedef struct
{
    /**
     * @brief Array of International Standard Recording Codes (ISRCs) for tracks 171 through 255 (if present).
     * * The size is 85 entries (1020 bytes).
     * * **Mandate:** If the ISRC code is **not available**, all fields in the area_isrc_t entry MUST be set to **NUL characters (0x00)**.
     * * If used, the ISRC code must comply with ISO 3901.
     */
    area_isrc_t isrc_2[ISRC_SECOND_SECTOR_COUNT];

    /**
     * @brief Reserved field.
     */
    uint8_t reserved_3[4];

    /**
     * @brief Array containing the genre Code for every track on the disc.
     * * The size is 255 entries (1020 bytes).
     * * **Recommendation:** Should contain the genre Code for Track[tno].
     * * **Mandate:** If the genre Code is **not available**, all fields in the genre_code_t entry MUST be set to **zero (0x00)**.
     */
    genre_code_t genre[MAX_TRACK_COUNT];

    /**
     * @brief Reserved field.
     */
    uint8_t reserved_4[4];
} ATTRIBUTE_PACKED isrc_genre_list_2_t;

/**
 * @brief Contains a table of Start Addresses associated with time Codes for a DST coded Track Area.
 * * This list MUST be present if the Audio Area is DST coded, and is NOT allowed for Plain DSD Audio Areas.
 * * The Access_List has a fixed size of 65536 Bytes (32 Sectors).
 */
typedef struct
{
    /**
     * @brief An 8-byte string identifying the first sector of the access_list.
     * * Value MUST be "SACD_ACC" (0x53 0x41 0x43 0x44 0x5F 0x41 0x43 0x43).
     */
    uint64_t signature;

    /**
     * @brief The number of Entries in the main_acc_list.
     * * Range: 1 to 6550.
     * * The value is calculated by the formula:
     * $$n_{\text{entries}} = \text{trunc}\left(\frac{\text{total\_area\_play\_time} - 1}{\text{main\_step\_size}}\right) + 1$$
     * Where $\text{total\_area\_play\_time}$ is expressed in Frames.
     */
    uint16_t entry_count;

    /**
     * @brief The size of the time interval in Frames between successive entries in the main_acc_list.
     * * The Track Area is divided into Intervals of main_step_size Frames.
     * * main_step_size must be a multiple of 10.
     * * Range: 10 to 180.
     */
    uint8_t main_step_size;

    /**
     * @brief Reserved field.
     * * The value must be set to 0.
     */
    uint8_t reserved_1[5];

    /**
     * @brief Contains the Start Addresses of the Multiplexed Frames at intervals of main_step_size Frames.
     * * For each encoded Start Address, it contains either a correction factor for intermediate addresses, or a pointer to a detailed access list.
     * * The number of active entries is given by entry_count.
     * * Size: 5 bytes per entry * 6550 entries = 32750 bytes.
     */
    main_acc_list_t main_acc_list[MAX_ACCESS_LIST_COUNT];

    /**
     * @brief Reserved field to fill the space up to byte 32768 (sector 16).
     * * The value must be set to 0.
     */
    uint8_t reserved_2[2];

    /**
     * @brief Detailed access data pointed to by entries in main_acc_list.
     * * Provides finer granularity for address lookup.
     * * Size: 27 * 1213 = 32751 bytes.
     */
    uint8_t detailed_access[27][1213];

    /**
     * @brief Reserved field to fill the space up to the end of the 65536-byte structure.
     * * The value must be set to 0.
     */
    uint8_t reserved_3[17];
} ATTRIBUTE_PACKED access_list_t;

/**
 * @brief Contains a variable-length list of start time Codes for Indexes within Tracks that have more than one index.
 * * This list is only present if the Track Area contains at least one Track with two or more Indexes.
 * * The size of this list can vary (indicated by '?' sector in original comment).
 */
typedef struct
{
    /**
     * @brief An 8-byte string identifying the first sector of the index_list.
     * * Value MUST be "SACD_Ind" (0x53 0x41 0x43 0x44 0x5F 0x49 0x6E 0x64).
     */
    uint64_t signature;

    /**
     * @brief Byte offset from the first byte of index_list to the start of the index data (stored_index_count) for Track[tno].
     * * If no indexes (other than the first one) are stored for Track[tno], this MUST be **0**.
     * * The first non-zero pointer MUST have the value **524** (byte position 524).
     */
    uint16_t index_ptr[MAX_TRACK_COUNT];

    /**
     * @brief Reserved field.
     */
    uint8_t reserved_1[6];

    /**
     * @brief Structure containing the index data for a specific Track.
     * * This array is variable length, and its contents are pointed to by index_ptr.
     * * Note: The size calculation in the original code implies this array is packed immediately following the reserved field.
     */
    struct
    {
        /**
         * @brief The number of Indexes stored in the index_list for this Track.
         * * This value is equal to the **total number of Indexes minus one**.
         * * Range: **1** to **254**.
         */
        uint8_t stored_index_count;

        /**
         * @brief Array of start time Codes for Indexes 2 through stored_index_count + 1.
         * * index_start_tc[x] contains the start time Code for index $x+2$.
         * * The start position of index 1 is the same as the start of the Track and is **not stored**.
         * * The time Code is relative to the start of the Track Area.
         */
        time_sacd_t index_start_tc[MAX_INDEX_COUNT - 1];
    } ATTRIBUTE_PACKED index_start[MAX_TRACK_COUNT];
} ATTRIBUTE_PACKED index_list_t;

/**
 * @brief Main Track_Text structure representing the first sector.
 * @details The total length of the Track_Text area can span up to 32 sectors.
 */
typedef struct {
    /** @brief Identification string. Must be "SACDTTxt". */
    uint64_t signature;

    /**
     * @brief Multi-dimensional array of byte offsets.
     * @details Offsets are from the start of this structure to the 
     * N_Items field of the target track. A value of 0 indicates no text.
     * Dimensions are [N_Text_Channels][N_Tracks].
     */
    uint16_t track_text_item_ptr[MAX_TEXT_CHANNEL_COUNT * MAX_TRACK_COUNT];

    /* * Note: The space between the pointer table and offset 4096 
     * must be filled with zeros (Reserved until 4096).
     */
} ATTRIBUTE_PACKED track_text_header_t;

/**
 * @brief TOC_Text structure containing categorized text information.
 * @details This structure is variable length and must always be aligned to a 4-byte boundary.
 */
typedef struct {
    /** @brief Type of text encoded (See @ref track_type_t). */
    uint8_t type;

    /** @brief Padding field. Must contain exactly one space character (0x20). */
    uint8_t padding1;

    /** * @brief Special String data. 
     * @details Contains the actual text. This is a variable-length field.
     * Use this pointer to access the text payload.
     */
    char text[255]; 

    /* Note: Padding2 is not represented as a member because it is a 
       variable number of zero bytes added at the end of sp_string 
       to ensure the next TOC_Text starts on a 4-byte boundary. */
} ATTRIBUTE_PACKED toc_text_t;

/**
 * @brief Header for an individual Track's text data block.
 * @details Located at the offset specified by the corresponding Item Pointer.
 */
typedef struct {
    /** @brief Number of text items for this track (Range: 1 to 10). */
    uint8_t num_items;

    /** @brief Reserved bytes. Must be set to zero. */
    uint8_t reserved[3];

    /** * @brief Array of text items.
     * @details Each item follows the TOC_Text format. Because TOC_Text 
     * is variable-length, these must be accessed sequentially.
     */
    toc_text_t text;
} ATTRIBUTE_PACKED text_item_t;

/**
 * @defgroup audio_sector_format Audio Sector Format
 * @{
 *
 * @brief SACD audio sector and packet structure definitions.
 *
 * @par Packet Types
 * Each packet contains exactly one type of data, identified by audio_packet_data_type_t:
 * - DATA_TYPE_AUDIO (2): Audio data packet
 * - DATA_TYPE_SUPPLEMENTARY (3): Supplementary data packet
 * - DATA_TYPE_PADDING (7): Padding data packet
 *
 * A packet belongs to exactly one audio_sector_t. Each sector contains 1-7 packets.
 *
 * @par Multiplexed Frame Format
 * A Multiplexed Frame consists of an integer number of packets with the following rules:
 * - Must contain at least one audio packet
 * - May contain zero or more supplementary and padding packets
 * - The first packet must always be an audio packet (for both DSD and DST)
 * - Spans a maximum of 16 sectors (MAX_DST_SECTORS)
 * - Has a fixed time length of 1/75 second
 *
 * @par Elementary Frames
 * The concatenation of packets by type within a Multiplexed Frame forms Elementary Frames:
 * - Audio Frame: all audio packets concatenated
 * - Supplementary Data Frame: all supplementary packets concatenated
 * - Padding Frame: all padding packets concatenated
 *
 * @par Time Code
 * Each Multiplexed Frame has a Time Code in the format Minutes:Seconds:Frames
 * (0-255:0-59:0-74). Time codes start at 0:0:0 at the first frame in the Track Area
 * and increment contiguously throughout.
 *
 * @par Sector Structure (audio_sector_t)
 * @code
 * audio_sector_t {
 *     audio_packet_info_t packet_info[packet_count];  // 2 bytes each
 *     frame_info_t        frame_info[frame_start_count]; // 3 or 4 bytes each
 *     uint8_t             data[];  // packet data until end of sector
 *     uint8_t             stuffing[]; // zero padding to sector boundary
 * }
 * @endcode
 *
 * @see audio_sector_t for the sector structure definition
 * @see audio_packet_info_t for packet metadata
 * @see frame_info_t for frame time code and span information
 * @see audio_packet_data_type_t for packet type enumeration
 * @}
 */

/**
 * @brief Frame Information Header.
 *
 * Contains the time code, the number of sectors used by the frame, and
 * the channel configuration. Present for each frame that starts in the Audio Sector.
 */
typedef struct
{
    /** @brief Time Code. The Time Code for this specific frame. */
    time_sacd_t time_code;

    /**
     * @brief Channel and Sector Count Header (1 Byte).
     *
     * This byte defines the number of sectors the frame spans and the
     * audio channel configuration.
     *
     * * **Bit [7] (channel_bit_1):** Used in combination with channel_bit_2 and channel_bit_3 to
     * define channel_count.
     * * **Bits [6:2] (sector_count):** The number of Sectors used by the Multiplexed Frame.
     * Calculated as Y - X + 1, where X is the start Sector and Y is the end Sector.
     * - channel_count=2: sector_count must be between 1 and 7.
     * - channel_count=5: sector_count must be between 1 and 14.
     * - channel_count=6: sector_count must be between 1 and 16.
     * * **Bit [1] (channel_bit_2):** Used in combination with channel_bit_1 and channel_bit_3.
     * * **Bit [0] (channel_bit_3):** Used in combination with channel_bit_1 and channel_bit_2.
     *
     * **Channel Definition:**
     * | Ch_Bit_1 | Ch_Bit_2 | Ch_Bit_3 | Meaning |
     * | :------: | :------: | :------: | :------: |
     * | 0        | 0        | 0        | channel_count = 2 (Stereo) |
     * | 0        | 0        | 1        | channel_count = 5 |
     * | 0        | 1        | 0        | channel_count = 6 |
     * | All others | Reserved for future use |
     */
#if defined(__BIG_ENDIAN__)
    uint8_t channel_bit_1 : 1;
    uint8_t sector_count  : 5;
    uint8_t channel_bit_2 : 1;
    uint8_t channel_bit_3 : 1;
#else
    uint8_t channel_bit_3 : 1;
    uint8_t channel_bit_2 : 1;
    uint8_t sector_count  : 5;
    uint8_t channel_bit_1 : 1;
#endif
} ATTRIBUTE_PACKED frame_info_t;

/**
 * @brief Audio Packet Info (16-bit).
 *
 * This union holds the header information for a single audio packet, allowing
 * access as a raw 16-bit word or as individual bitfields.
 */
typedef struct
{
/**
 * @brief Packet Information Header (16 Bits).
 *
 * This word contains critical information about the packet's contents,
 * length, and position relative to audio frames.
 *
 * * **Bits [15] (frame_start):** Indicates that an audio Frame starts in
 * this Packet. Set to **1** if a Frame starts here, **0** otherwise.
 * * **Bit [14] (Reserved):** Must be set to zero.
 * * **Bits [13:11] (data_type):** Defines the content of the Packet (e.g., Audio, Supplementary, Padding).
 * * **Bits [10:0] (packet_length):** Contains the length in bytes of the
 * Packet. Minimum length is 1 byte, maximum is 2045 bytes.
 */
#if defined(__BIG_ENDIAN__)
    uint16_t frame_start   : 1;
    uint16_t reserved      : 1;
    uint16_t data_type     : 3;
    uint16_t packet_length : 11;
#else
    uint16_t packet_length : 11;
    uint16_t data_type     : 3;
    uint16_t reserved      : 1;
    uint16_t frame_start   : 1;
#endif
} ATTRIBUTE_PACKED audio_packet_info_t;

typedef struct
{
    /**
     * @brief Sector Header (1 Byte).
     *
     * This byte contains critical information about the sector's contents
     * and framing.
     *
     * * **Bits [7:5] (packet_count):** Contains the number of Packets in the
     * Audio_Sector. The minimum allowed value for packet_count is one.
     * * **Bits [4:2] (frame_start_count):** Contains the number of Frames that start
     * in this Audio_Sector.
     * * **Bit [1] (Reserved):** Must be set to zero.
     * * **Bit [0] (dst_coded):** Defines whether the Track Area is DST Coded or not.
     * - If frame_format is zero, dst_coded must be set to one.
     * - If frame_format is equal to 2 or 3, dst_coded must be set to zero.
     */
#if defined(__BIG_ENDIAN__)
    uint8_t packet_count : 3;
    uint8_t frame_start_count  : 3;
    uint8_t reserved          : 1;
    uint8_t dst_coded       : 1;
#else
    uint8_t dst_coded       : 1;
    uint8_t reserved          : 1;
    uint8_t frame_start_count  : 3;
    uint8_t packet_count : 3;
#endif
  audio_packet_info_t packet_info[7];
  uint8_t data[SACD_LSN_SIZE - 1 - (2 * 7)];
} ATTRIBUTE_PACKED audio_sector_t;

#if PRAGMA_PACK
#pragma pack()
#endif

#ifdef __cplusplus
}
#endif

#endif /* LIBSACD_SACD_SPECIFICATION_H */
