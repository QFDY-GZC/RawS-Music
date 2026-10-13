/* RawSMusic Android adaptation. The reader calls the app's charset converter;
 * no network, locale switching or private Android ICU ABI is required. */
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <stdatomic.h>
#include <libsautil/mem.h>

typedef char* (*raw_sacd_charset_converter)(const char*, uint8_t);
static _Atomic(raw_sacd_charset_converter) converter;
__attribute__((visibility("default")))
void raw_sacd_set_charset_converter(raw_sacd_charset_converter callback) {
    atomic_store(&converter, callback);
}
uint16_t sacd_special_string_len(const char* text, uint8_t codepage) {
    if (!text) return 0;
    (void)codepage;
    return (uint16_t)strnlen(text, 2047);
}
char* sacd_special_string_to_utf8(const char* text, uint8_t codepage) {
    if (!text) return NULL;
    raw_sacd_charset_converter callback = atomic_load(&converter);
    if (callback) return callback(text, codepage);
    /* ASCII fixtures/CLI work without a JVM. Regional encodings are registered
       by the Android bridge before a real metadata scan. */
    size_t size = strlen(text);
    char* result = sa_malloc(size + 1);
    if (result) memcpy(result, text, size + 1);
    return result;
}
