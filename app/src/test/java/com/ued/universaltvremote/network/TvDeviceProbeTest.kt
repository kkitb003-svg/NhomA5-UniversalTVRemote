package com.ued.universaltvremote.network

import com.ued.universaltvremote.model.TvBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvDeviceProbeTest {

    @Test
    fun `parseSsdpHeaders reads canonical headers case-insensitively`() {
        val rawResponse = """
            HTTP/1.1 200 OK
            location: http://192.168.1.20:8060/
            ST: roku:ecp
            usn: uuid:roku:ecp:ABC123

        """.trimIndent()

        val headers = parseSsdpHeaders(rawResponse)

        assertEquals("http://192.168.1.20:8060/", headers["LOCATION"])
        assertEquals("roku:ecp", headers["ST"])
        assertEquals("uuid:roku:ecp:ABC123", headers["USN"])
    }

    @Test
    fun `detectBrandFromText recognizes supported TV families`() {
        assertEquals(TvBrand.SAMSUNG, detectBrandFromText("Samsung Smart TV Tizen"))
        assertEquals(TvBrand.LG, detectBrandFromText("LG webOS TV"))
        assertEquals(TvBrand.SONY, detectBrandFromText("Sony BRAVIA XR"))
        assertEquals(TvBrand.ROKU, detectBrandFromText("roku:ecp"))
        assertEquals(TvBrand.ANDROID_TV, detectBrandFromText("Google TV living room"))
        assertEquals(TvBrand.FIRE_TV, detectBrandFromText("Amazon Fire TV Stick"))
        assertEquals(TvBrand.VIDAA, detectBrandFromText("Hisense VIDAA"))
    }

    @Test
    fun `detectBrandFromText returns null for unrelated devices`() {
        assertNull(detectBrandFromText("HP LaserJet Printer"))
    }
}
