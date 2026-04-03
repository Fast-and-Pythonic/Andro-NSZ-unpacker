#pragma once
#include <stdint.h>

/*
 * Verify NCA headers inside an NSP using AES-XTS decryption + SHA-256.
 *
 * nsp_path      : path to the output NSP file
 * header_key_32 : 32-byte key from prod.keys (key1[16] || key2[16])
 * err_out       : buffer for error description on failure
 * err_len       : size of err_out
 *
 * Returns 0 on success (all verified), negative on failure.
 */
int nca_verify_nsp(const char    *nsp_path,
                   const uint8_t *header_key_32,
                   char          *err_out,
                   int            err_len);
