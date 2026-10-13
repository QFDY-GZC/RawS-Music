#ifndef SACD_EXPORT_H
#define SACD_EXPORT_H

/** \file include/SACD/export.h
 *
 *  \brief
 *  This module contains \#defines and symbols for exporting function
 *  calls, and providing version information and compiled-in features.
 *
 *  See the \link sacd_export export \endlink module.
 */

/** \defgroup sacd_export SACD/export.h: export symbols
 *  \ingroup sacd
 *
 *  \brief
 *  This module contains \#defines and symbols for exporting function
 *  calls, and providing version information and compiled-in features.
 *
 *  If you are compiling for Windows (with Visual Studio or MinGW for
 *  example) and will link to the static library (libSACD++.lib) you
 *  should define SACD_NO_DLL in your project to make sure the symbols
 *  are exported properly.
 *
 * \{
 */

/** This \#define is used internally in libSACD and its headers to make
 * sure the correct symbols are exported when working with shared
 * libraries. On Windows, this \#define is set to __declspec(dllexport)
 * when compiling libSACD into a library and to __declspec(dllimport)
 * when the headers are used to link to that DLL. On non-Windows systems
 * it is used to set symbol visibility.
 *
 * Because of this, the define SACD_NO_DLL must be defined when linking
 * to libSACD statically or linking will fail.
 */
/* This has grown quite complicated. SACD_NO_DLL is used by MSVC sln
 * files and CMake, which build either static or shared. autotools can
 * build static, shared or **both**. Therefore, DLL_EXPORT, which is set
 * by libtool, must override SACD_NO_DLL on building shared components
 */
#if defined(_WIN32)

/* SACD_NO_DLL is defined by CMake when building static libraries.
 * When building shared, SACD_API_EXPORTS is defined for the building library,
 * and consumers get __declspec(dllimport) automatically.
 */
#if defined(SACD_NO_DLL) && !(defined(DLL_EXPORT))
#define SACD_API
#else
#ifdef SACD_API_EXPORTS
#define	SACD_API __declspec(dllexport)
#else
#define SACD_API __declspec(dllimport)
#endif
#endif

#elif defined(SACD_USE_VISIBILITY_ATTR)
#define SACD_API __attribute__ ((visibility ("default")))

#else
#define SACD_API

#endif

#define SACD_API_VERSION_CURRENT 1
#define SACD_API_VERSION_REVISION 0 /**< see above */
#define SACD_API_VERSION_AGE 0 /**< see above */

/* \} */

#endif
