package com.androNSZ.nut

import java.io.File

object KeysParser {

   fun parseHeaderKey(keysFile: File): ByteArray? {
      if (!keysFile.exists()) return null
      keysFile.readLines().forEach { line ->
         val trimmed = line.trim()
         val eq = trimmed.indexOf('=')
         if (eq < 0) return@forEach
         val keyName = trimmed.substring(0, eq).trim()
         if (keyName == "header_key") {
            val hex = trimmed.substring(eq + 1).trim()
            if (hex.length == 64 && hex.all { it.isLetterOrDigit() }) {
               return hexToBytes(hex)
            }
         }
      }
      return null
   }

   /*
    * Collect every key_area_key_application_XX (XX = key generation) into a
    * flat byte array of 17-byte records: [generation][16-byte key]. Native code
    * selects the record matching an NCA's key generation to unwrap its key area
    * for CNMT reading. Returns null if none are present.
    */
   fun parseKeyAreaKeys(keysFile: File): ByteArray? {
      if (!keysFile.exists()) return null
      val records = ArrayList<ByteArray>()
      keysFile.readLines().forEach { line ->
         val trimmed = line.trim()
         val eq = trimmed.indexOf('=')
         if (eq < 0) return@forEach
         val keyName = trimmed.substring(0, eq).trim().lowercase()
         if (!keyName.startsWith("key_area_key_application_")) return@forEach
         val genHex = keyName.removePrefix("key_area_key_application_")
         if (genHex.length != 2) return@forEach
         val gen = genHex.toIntOrNull(16) ?: return@forEach
         val hex = trimmed.substring(eq + 1).trim()
         if (hex.length == 32 && hex.all { it.isLetterOrDigit() }) {
            records.add(byteArrayOf(gen.toByte()) + hexToBytes(hex))
         }
      }
      if (records.isEmpty()) return null
      val out = ByteArray(records.size * 17)
      records.forEachIndexed { i, rec -> rec.copyInto(out, i * 17) }
      return out
   }

   private fun hexToBytes(hex: String): ByteArray {
      val result = ByteArray(hex.length / 2)
      for (i in result.indices) {
         result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
      }
      return result
   }
}
