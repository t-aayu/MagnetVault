package com.example

import com.example.data.network.CustomAppDns
import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun customDnsWireBuilderAndParser_detectsValidIpsSinkholesAndNxdomain() {
    val query = CustomAppDns.buildDnsQueryPacket("example.com", qtype = 1)
    assertNotNull(query)
    assertTrue(query!!.size > 12)

    val validResponse = byteArrayOf(
      0x00, 0x00,
      0x81.toByte(), 0x80.toByte(),
      0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
      7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
      3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
      0, 0x00, 0x01, 0x00, 0x01,
      0xC0.toByte(), 0x0C, 0x00, 0x01, 0x00, 0x01,
      0x00, 0x00, 0x00, 0x3C, 0x00, 0x04,
      93, 184.toByte(), 216.toByte(), 34
    )
    val parsedValid = CustomAppDns.parseDnsResponsePacket(validResponse)
    assertNotNull(parsedValid)
    assertFalse(parsedValid!!.isBlocked)
    assertEquals("93.184.216.34", parsedValid.addresses.first().hostAddress)

    val sinkholeResponse = validResponse.copyOf().apply {
      this[size - 4] = 0
      this[size - 3] = 0
      this[size - 2] = 0
      this[size - 1] = 0
    }
    val parsedSinkhole = CustomAppDns.parseDnsResponsePacket(sinkholeResponse)
    assertNotNull(parsedSinkhole)
    assertTrue(parsedSinkhole!!.isBlocked)
    assertTrue(parsedSinkhole.isSinkholed)

    val nxdomainResponse = byteArrayOf(
      0x00, 0x00, 0x81.toByte(), 0x83.toByte(),
      0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )
    val parsedNx = CustomAppDns.parseDnsResponsePacket(nxdomainResponse)
    assertNotNull(parsedNx)
    assertTrue(parsedNx!!.isBlocked)
  }
}
