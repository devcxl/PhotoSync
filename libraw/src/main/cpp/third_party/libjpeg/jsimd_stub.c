/*
 * jsimd_stub.c - Stub implementations of libjpeg-turbo SIMD detector / helper
 * functions.  All can-…() return 0 (not-available), action functions are empty.
 */
#include <stddef.h>
#include <stdio.h>
#include "jpeglib.h"

/* -- Colour conversion -- */
int jsimd_can_rgb_ycc(void)                            { return 0; }
int jsimd_can_rgb_gray(void)                           { return 0; }
int jsimd_can_ycc_rgb(void)                            { return 0; }
int jsimd_can_ycc_rgb565(void)                         { return 0; }
int jsimd_c_can_null_convert(void)                     { return 0; }

void jsimd_rgb_ycc_convert(j_compress_ptr c, JSAMPARRAY in,
                           JSAMPIMAGE out, JDIMENSION row, int n) {}
void jsimd_rgb_gray_convert(j_compress_ptr c, JSAMPARRAY in,
                            JSAMPIMAGE out, JDIMENSION row, int n) {}
void jsimd_ycc_rgb_convert(j_decompress_ptr c, JSAMPIMAGE in,
                           JDIMENSION row, JSAMPARRAY out, int n) {}
void jsimd_ycc_rgb565_convert(j_decompress_ptr c, JSAMPIMAGE in,
                              JDIMENSION row, JSAMPARRAY out, int n) {}
void jsimd_c_null_convert(j_compress_ptr c, JSAMPARRAY in,
                          JSAMPIMAGE out, JDIMENSION row, int n) {}

/* -- Down-sampling -- */
int  jsimd_can_h2v2_downsample(void)        { return 0; }
int  jsimd_can_h2v1_downsample(void)        { return 0; }
int  jsimd_can_h2v2_smooth_downsample(void) { return 0; }
void jsimd_h2v2_downsample(j_compress_ptr c, jpeg_component_info *ci,
                           JSAMPARRAY in, JSAMPARRAY out) {}
void jsimd_h2v2_smooth_downsample(j_compress_ptr c, jpeg_component_info *ci,
                                  JSAMPARRAY in, JSAMPARRAY out) {}
void jsimd_h2v1_downsample(j_compress_ptr c, jpeg_component_info *ci,
                           JSAMPARRAY in, JSAMPARRAY out) {}

/* -- Up-sampling -- */
int  jsimd_can_h2v2_upsample(void) { return 0; }
int  jsimd_can_h2v1_upsample(void) { return 0; }
int  jsimd_can_int_upsample(void)  { return 0; }
void jsimd_h2v2_upsample(j_decompress_ptr c, jpeg_component_info *ci,
                         JSAMPARRAY in, JSAMPARRAY *out) {}
void jsimd_h2v1_upsample(j_decompress_ptr c, jpeg_component_info *ci,
                         JSAMPARRAY in, JSAMPARRAY *out) {}
void jsimd_int_upsample(j_decompress_ptr c, jpeg_component_info *ci,
                        JSAMPARRAY in, JSAMPARRAY *out) {}

/* -- Fancy up-sampling -- */
int  jsimd_can_h2v2_fancy_upsample(void) { return 0; }
int  jsimd_can_h2v1_fancy_upsample(void) { return 0; }
int  jsimd_can_h1v2_fancy_upsample(void) { return 0; }
void jsimd_h2v2_fancy_upsample(j_decompress_ptr c, jpeg_component_info *ci,
                               JSAMPARRAY in, JSAMPARRAY *out, int row) {}
void jsimd_h2v1_fancy_upsample(j_decompress_ptr c, jpeg_component_info *ci,
                               JSAMPARRAY in, JSAMPARRAY *out, int row) {}
void jsimd_h1v2_fancy_upsample(j_decompress_ptr c, jpeg_component_info *ci,
                               JSAMPARRAY in, JSAMPARRAY *out, int row) {}

/* -- Merged up-sampling -- */
int  jsimd_can_h2v2_merged_upsample(void) { return 0; }
int  jsimd_can_h2v1_merged_upsample(void) { return 0; }
void jsimd_h2v2_merged_upsample(j_decompress_ptr c, JSAMPIMAGE in,
                                JDIMENSION row, JSAMPARRAY out) {}
void jsimd_h2v1_merged_upsample(j_decompress_ptr c, JSAMPIMAGE in,
                                JDIMENSION row, JSAMPARRAY out) {}

/* -- Quantisation -- */
int  jsimd_can_quantize(void)       { return 0; }
int  jsimd_can_quantize_float(void) { return 0; }
void jsimd_quantize(JCOEFPTR coef, int *div, int *ws) {}
void jsimd_quantize_float(JCOEFPTR coef, float *div, float *ws) {}

/* -- Forward DCT -- */
int  jsimd_can_convsamp(void)       { return 0; }
int  jsimd_can_convsamp_float(void) { return 0; }
void jsimd_convsamp(JSAMPARRAY s, JDIMENSION col, int *ws) {}
void jsimd_convsamp_float(JSAMPARRAY s, JDIMENSION col, float *ws) {}
int  jsimd_can_fdct_islow(void) { return 0; }
int  jsimd_can_fdct_ifast(void) { return 0; }
int  jsimd_can_fdct_float(void) { return 0; }
void jsimd_fdct_islow(int *data) {}
void jsimd_fdct_ifast(int *data) {}
void jsimd_fdct_float(float *data) {}

/* -- Inverse DCT -- */
int  jsimd_can_idct_2x2(void)   { return 0; }
int  jsimd_can_idct_4x4(void)   { return 0; }
int  jsimd_can_idct_islow(void) { return 0; }
int  jsimd_can_idct_ifast(void) { return 0; }
int  jsimd_can_idct_float(void) { return 0; }
void jsimd_idct_2x2(j_decompress_ptr c, jpeg_component_info *ci,
                    JCOEFPTR coef, JSAMPARRAY out, JDIMENSION col) {}
void jsimd_idct_4x4(j_decompress_ptr c, jpeg_component_info *ci,
                    JCOEFPTR coef, JSAMPARRAY out, JDIMENSION col) {}
void jsimd_idct_islow(j_decompress_ptr c, jpeg_component_info *ci,
                      JCOEFPTR coef, JSAMPARRAY out, JDIMENSION col) {}
void jsimd_idct_ifast(j_decompress_ptr c, jpeg_component_info *ci,
                      JCOEFPTR coef, JSAMPARRAY out, JDIMENSION col) {}
void jsimd_idct_float(j_decompress_ptr c, jpeg_component_info *ci,
                      JCOEFPTR coef, JSAMPARRAY out, JDIMENSION col) {}

/* -- 12/16-bit precision entry points (referenced but never used with 8-bit) -- */
void j12init_1pass_quantizer(j_decompress_ptr cinfo) {}
void j12init_2pass_quantizer(j_decompress_ptr cinfo) {}
void j12init_color_deconverter(j_decompress_ptr cinfo) {}
void j12init_merged_upsampler(j_decompress_ptr cinfo) {}
void j12init_upsampler(j_decompress_ptr cinfo) {}
void j16init_color_deconverter(j_decompress_ptr cinfo) {}
void j16init_upsampler(j_decompress_ptr cinfo) {}
void j16init_merged_upsampler(j_decompress_ptr cinfo) {}

void j12init_d_coef_controller(j_decompress_ptr cinfo) {}
void j12init_d_diff_controller(j_decompress_ptr cinfo) {}
void j12init_d_main_controller(j_decompress_ptr cinfo) {}
void j12init_d_post_controller(j_decompress_ptr cinfo) {}
void j12init_inverse_dct(j_decompress_ptr cinfo) {}
void j12init_lossless_decompressor(j_decompress_ptr cinfo) {}
void j16init_d_diff_controller(j_decompress_ptr cinfo) {}
void j16init_d_main_controller(j_decompress_ptr cinfo) {}
void j16init_d_post_controller(j_decompress_ptr cinfo) {}
void j16init_lossless_decompressor(j_decompress_ptr cinfo) {}
