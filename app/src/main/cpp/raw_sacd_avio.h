#pragma once
struct AVFormatContext;
struct AVInputFormat;
struct AVDictionary;
int raw_sacd_avio_open(AVFormatContext** format, const char* path, AVDictionary** options);
bool raw_sacd_avio_close(AVFormatContext** format);
int raw_audio_format_open(AVFormatContext** format, const char* path,
    const AVInputFormat* input_format, AVDictionary** options);
void raw_audio_format_close(AVFormatContext** format);
