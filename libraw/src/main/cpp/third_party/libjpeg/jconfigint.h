#define BUILD  "20250101"
#define HIDDEN  __attribute__((visibility("hidden")))
#undef inline
#define INLINE  inline __attribute__((always_inline))
#define THREAD_LOCAL  __thread
#define PACKAGE_NAME  "libjpeg-turbo"
#define VERSION  "3.0.4"

#ifdef __LP64__
#define SIZEOF_SIZE_T  8
#else
#define SIZEOF_SIZE_T  4
#endif

#define HAVE_BUILTIN_CTZL  1

#if defined(__has_attribute)
#if __has_attribute(fallthrough)
#define FALLTHROUGH  __attribute__((fallthrough));
#else
#define FALLTHROUGH
#endif
#else
#define FALLTHROUGH
#endif

#ifndef BITS_IN_JSAMPLE
#define BITS_IN_JSAMPLE  8
#endif

#undef C_ARITH_CODING_SUPPORTED
#undef D_ARITH_CODING_SUPPORTED
#undef WITH_SIMD

#if BITS_IN_JSAMPLE == 8
#define C_ARITH_CODING_SUPPORTED  1
#define D_ARITH_CODING_SUPPORTED  1
#define WITH_SIMD  0
#endif
