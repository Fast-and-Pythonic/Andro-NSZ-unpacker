package com.androNSZ

import com.androNSZ.nut.KeysParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class KeysParserTest {

   private fun tempKeys(content: String): File {
      val f = File.createTempFile("prod", ".keys")
      f.writeText(content)
      f.deleteOnExit()
      return f
   }

   @Test
   fun parsesSingleApplicationKey() {
      val key = "0123456789abcdef0123456789abcdef"   // 16 bytes / 32 hex
      val f = tempKeys("key_area_key_application_00 = $key\n")
      val out = KeysParser.parseKeyAreaKeys(f)
      assertNotNull(out)
      assertEquals(17, out!!.size)          // one 17-byte record
      assertEquals(0x00.toByte(), out[0])   // generation 0x00
      assertEquals(0x01.toByte(), out[1])   // first key byte
      assertEquals(0xef.toByte(), out[16])  // last key byte
   }

   @Test
   fun parsesMultipleGenerationsAndIgnoresOthers() {
      val f = tempKeys(
         """
         header_key = 00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff
         key_area_key_application_00 = 000102030405060708090a0b0c0d0e0f
         key_area_key_application_0a = 101112131415161718191a1b1c1d1e1f
         key_area_key_ocean_00 = ffffffffffffffffffffffffffffffff
         """.trimIndent()
      )
      val out = KeysParser.parseKeyAreaKeys(f)
      assertNotNull(out)
      assertEquals(34, out!!.size)                 // exactly two records, ocean ignored
      assertEquals(0x00.toByte(), out[0])
      assertEquals(0x0a.toByte(), out[17])         // second record's generation
   }

   @Test
   fun mixedCaseKeyIsAccepted() {
      val f = tempKeys("KEY_AREA_KEY_APPLICATION_0F = ABCDEF0123456789ABCDEF0123456789\n")
      val out = KeysParser.parseKeyAreaKeys(f)
      assertNotNull(out)
      assertEquals(17, out!!.size)
      assertEquals(0x0f.toByte(), out[0])
      assertEquals(0xab.toByte(), out[1])
   }

   @Test
   fun malformedHexLengthIsRejected() {
      val f = tempKeys("key_area_key_application_00 = 0123\n")   // too short
      assertNull(KeysParser.parseKeyAreaKeys(f))
   }

   @Test
   fun nonHexCharOfCorrectLengthIsRejectedNotThrown() {
      // 32 chars but with a non-hex 'z': must be skipped, not crash hexToBytes.
      val f = tempKeys("key_area_key_application_00 = 0123456789abcdef0123456789abcdez\n")
      assertNull(KeysParser.parseKeyAreaKeys(f))
   }

   @Test
   fun oneBadKeyDoesNotDropAValidOne() {
      val f = tempKeys(
         """
         key_area_key_application_00 = 0123456789abcdef0123456789abcdez
         key_area_key_application_01 = 000102030405060708090a0b0c0d0e0f
         """.trimIndent()
      )
      val out = KeysParser.parseKeyAreaKeys(f)
      assertNotNull(out)
      assertEquals(17, out!!.size)            // only the valid record survives
      assertEquals(0x01.toByte(), out[0])
   }

   @Test
   fun noApplicationKeysReturnsNull() {
      val f = tempKeys("header_key = 00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff\n")
      assertNull(KeysParser.parseKeyAreaKeys(f))
   }
}
