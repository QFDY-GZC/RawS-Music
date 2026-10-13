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

#include "sacd_specification.h"

const char *album_category[] = 
{
    "Not used", "General", "Japanese"
};

const char *album_genre_general[] = 
{
    "Reserved",
    "Not defined",
    "Adult Contemporary",
    "Alternative Rock",
    "Children's Music",
    "Classical",
    "Contemporary Christian",
    "Country",
    "Dance",
    "Easy Listening",
    "Erotic",
    "Folk",
    "Gospel",
    "Hip Hop",
    "Jazz",
    "Latin",
    "Musical",
    "New Age",
    "Opera",
    "Operetta",
    "Pop Music",
    "RAP",
    "Reggae",
    "Rock Music",
    "Rhythm & Blues",
    "Sound Effects",
    "Sound Track",
    "Spoken Word",
    "World Music",
    "Blues"
};

const char *album_genre_japanese[] = 
{
    "音楽：演歌",
    "音楽：ポップス歌謡曲",
    "音楽：ニューミュージック",
    "音楽：ロック／ディスコ",
    "音楽：ポピュラーソング／フォークソング",
    "音楽：ジャズ／フュージョン",
    "音楽：軽音楽／映画音楽",
    "音楽：クラシック",
    "音楽：その他",
    "学芸：民謡／純邦楽",
    "学芸：民族音楽",
    "学芸：教育／教材／童謡／童話",
    "学芸：アニメ",
    "学芸：その他",
    "カラオケ：カラオケ",
    "カラオケ：映像カラオケ",
    "劇映画：邦画",
    "劇映画：洋画",
    "劇映画：アダルト",
    "劇映画：アニメ",
    "劇映画：その他",
    "その他：ＢＧＭ",
    "その他：スポーツ／ドキュメンタリー",
    "その他：娯楽／教養",
    "その他：その他"
};

const char *album_genre_japanese_en[] = 
{
    "Enka",
    "J-Pop / Ballads",
    "New Music (J-Pop)",
    "Rock / Disco",
    "Pop / Folk",
    "Jazz / Fusion",
    "Soundtrack",
    "Classical",
    "Other Music",
    "Traditional Japanese",
    "World Music",
    "Kids / Educational",
    "Anime Music",
    "Arts & Culture",
    "Karaoke",
    "Video Karaoke",
    "Japanese Cinema",
    "Western Cinema",
    "Adult Content",
    "Animated Films",
    "Other Cinema",
    "Background Music",
    "Sports / Documentary",
    "Entertainment",
    "Miscellaneous"
};
