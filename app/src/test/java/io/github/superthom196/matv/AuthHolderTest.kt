package io.github.superthom196.matv

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthHolderTest {

    @Before
    fun home() { AuthHolder.baseUrl = "http://192.168.0.150:8095" }

    @After
    fun forget() { AuthHolder.baseUrl = null }

    @Test
    fun isOurs_theServerItself() {
        assertTrue(AuthHolder.isOurs("http://192.168.0.150:8095/imageproxy/abc123?size=256".toHttpUrl()))
    }

    @Test
    fun isOurs_notAThirdPartyImageHost() {
        assertFalse(AuthHolder.isOurs("https://assets.fanart.tv/fanart/music/x/artistthumb/y.jpg".toHttpUrl()))
        assertFalse(AuthHolder.isOurs("https://r2.theaudiodb.com/images/media/artist/thumb/z.jpg".toHttpUrl()))
    }

    @Test
    fun isOurs_sameHostOtherPortIsNotOurs() {
        assertFalse(AuthHolder.isOurs("http://192.168.0.150:8096/x.jpg".toHttpUrl()))
    }

    @Test
    fun isOurs_defaultPortMatchesTheExplicitOne() {
        AuthHolder.baseUrl = "http://serverpi"
        assertTrue(AuthHolder.isOurs("http://serverpi:80/imageproxy/abc".toHttpUrl()))
        assertFalse(AuthHolder.isOurs("https://serverpi/imageproxy/abc".toHttpUrl()))
    }

    @Test
    fun isOurs_nothingWhileNoServerIsKnown() {
        AuthHolder.baseUrl = null
        assertFalse(AuthHolder.isOurs("http://192.168.0.150:8095/imageproxy/abc".toHttpUrl()))
    }
}
