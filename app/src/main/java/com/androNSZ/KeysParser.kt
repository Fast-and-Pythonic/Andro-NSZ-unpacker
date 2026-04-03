package com.androNSZ

import java.io.File

object KeysParser {

   /**
    * Parses prod.keys and returns the 32-byte header_key, or null if not found.
    * Expected line format: "header_key = <64 hex chars>"
    */
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

   private fun hexToBytes(hex: String): ByteArray {
      val result = ByteArray(hex.length / 2)
      for (i in result.indices) {
         result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
      }
      return result
   }
}