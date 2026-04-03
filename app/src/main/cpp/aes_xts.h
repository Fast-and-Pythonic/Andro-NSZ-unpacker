#pragma once
#include <stdint.h>
#include <stddef.h>

/*
 * AES-128-XTS decryption for NCA headers.
 *
 * key1 : 16-byte data key  (header_key[0..15])
 * key2 : 16-byte tweak key (header_key[16..31])
 * in/out: sector_count * sector_size bytes
 * first_sector: logical sector number of the first input sector
 */
void aes_xts_decrypt(const uint8_t *in,
                     uint8_t       *out,
                     size_t         sector_count,
                     size_t         sector_size,
                     uint64_t       first_sector,
                     const uint8_t *key1,
                     const uint8_t *key2);
