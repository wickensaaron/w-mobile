#ifndef W_IMPORTED_XMLTV_SHIM_H
#define W_IMPORTED_XMLTV_SHIM_H

/* Opaque handles keep system XML/zlib implementation types out of Kotlin bindings. */
typedef void *wm_xml_handle;
typedef void *wm_gzip_handle;
typedef int (*wm_xml_start_callback)(void *, const char *, const char *, const char *, const char *);
typedef int (*wm_xml_end_callback)(void *, const char *);
typedef int (*wm_xml_text_callback)(void *, const char *, int);
typedef int (*wm_gzip_bytes_callback)(void *, const unsigned char *, int);

wm_xml_handle wm_xml_create(void *, wm_xml_start_callback, wm_xml_end_callback, wm_xml_text_callback);
int wm_xml_feed(wm_xml_handle, const unsigned char *, int, int);
void wm_xml_destroy(wm_xml_handle);
wm_gzip_handle wm_gzip_create(void *, wm_gzip_bytes_callback);
int wm_gzip_feed(wm_gzip_handle, const unsigned char *, int);
int wm_gzip_finish(wm_gzip_handle);
void wm_gzip_destroy(wm_gzip_handle);
#endif
