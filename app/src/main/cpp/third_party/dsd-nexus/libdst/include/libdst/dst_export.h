#ifndef DST_EXPORT_H
#define DST_EXPORT_H

/*
 * Auto-generated export header for libdst.
 * Controls symbol visibility for shared library builds.
 *
 * When building the library:  DST_API = __declspec(dllexport)  [Windows]
 * When consuming the library: DST_API = __declspec(dllimport)  [Windows]
 * On non-Windows with visibility: DST_API = __attribute__((visibility("default")))
 * Otherwise (static builds):  DST_API = (empty)
 */

#if defined(_WIN32) || defined(__CYGWIN__)
    #ifdef DST_BUILDING_DLL
        #define DST_API __declspec(dllexport)
    #elif defined(DST_USING_DLL)
        #define DST_API __declspec(dllimport)
    #else
        #define DST_API
    #endif
#else
    #if defined(__GNUC__) && __GNUC__ >= 4
        #define DST_API __attribute__((visibility("default")))
    #else
        #define DST_API
    #endif
#endif

#endif /* DST_EXPORT_H */
