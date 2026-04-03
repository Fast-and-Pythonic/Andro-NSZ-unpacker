#pragma once
#include <stdint.h>
#include <stdio.h>

#define PFS0_MAGIC       0x30534650u  /* "PFS0" little-endian */
#define PFS0_MAX_FILES   512

typedef struct {
    uint32_t magic;
    uint32_t file_count;
    uint32_t string_table_size;
    uint32_t _pad;
} Pfs0Header;

typedef struct {
    uint64_t offset;
    uint64_t size;
    uint32_t string_offset;
    uint32_t _pad;
} Pfs0FileEntry;

typedef struct {
    char     name[256];
    uint64_t data_offset;   /* absolute offset inside the container file */
    uint64_t size;
    int      is_ncz;        /* 1 if extension is .ncz */
} Pfs0File;

typedef struct {
    int      file_count;
    Pfs0File files[PFS0_MAX_FILES];
    uint64_t data_area_offset;
} Pfs0Container;

/* Returns 0 on success, negative on error. */
int  pfs0_parse(const char *input_path, Pfs0Container *out);

/* Write new PFS0 header with updated file sizes.
   new_file_sizes[i] corresponds to container->files[i].
   Returns 0 on success. */
int  pfs0_write_header(FILE *out_fp, Pfs0Container *container,
                       const uint64_t *new_file_sizes);

const char *pfs0_last_error(void);
