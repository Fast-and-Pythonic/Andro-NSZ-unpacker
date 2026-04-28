#ifndef HFS0_H
#define HFS0_H

#include <stdint.h>
#include <stdio.h>

#define HFS0_MAGIC       0x30534648u  /* "HFS0" little-endian */
#define HFS0_MAX_FILES   512

/* HFS0 header (16 bytes) - IDENTICAL to PFS0 header structure */
typedef struct {
    uint32_t magic;              // "HFS0" (0x30534648)
    uint32_t file_count;
    uint32_t string_table_size;
    uint32_t _pad;
} Hfs0Header;

/* HFS0 file entry (64 bytes) - CRITICAL DIFFERENCE from PFS0 (24 bytes)
 * This structure must be exactly 64 bytes to match the HFS0 specification.
 * Note: Hash fields are always set to zeros in output (nsz reference behavior).
 */
typedef struct {
    uint64_t offset;                  // Relative to end of header
    uint64_t size;
    uint32_t string_offset;
    uint32_t hashed_region_size;      // Set to 0 (not used by nsz reference)
    uint64_t _pad1;                   // Reserved (8 bytes)
    uint8_t  sha256_hash[32];         // Set to zeros (not verified)
} __attribute__((packed)) Hfs0FileEntry;

/* In-memory file representation */
typedef struct {
    char     name[256];
    uint64_t data_offset;             // Absolute offset in file
    uint64_t size;
    int      is_ncz;                  // 1 if extension is .ncz
} Hfs0File;

/* Container structure */
typedef struct {
    int      file_count;
    Hfs0File files[HFS0_MAX_FILES];
    uint64_t data_area_offset;        // Offset where file data starts
} Hfs0Container;

/* API functions */
int  hfs0_parse(const char *input_path, Hfs0Container *out);
int  hfs0_write_header(FILE *out_fp, Hfs0Container *container,
                       const uint64_t *new_file_sizes);
const char *hfs0_last_error(void);

#endif /* HFS0_H */
