package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WifiManagerUnitTest {

    private class FakeShellExecutor(
        var rootAvailable: Boolean = true,
        val handler: (String) -> ShellResult = { ShellResult(true, "", 0) }
    ) : ShellExecutor {
        val executedCommands = mutableListOf<String>()

        override suspend fun isRootAvailable(): Boolean = rootAvailable

        override suspend fun executeCommand(command: String): ShellResult {
            executedCommands.add(command)
            return handler(command)
        }
    }

    @Test
    fun `shellQuote safely escapes special characters`() {
        assertEquals("'SimpleSSID'", RootShellExecutor.shellQuote("SimpleSSID"))
        assertEquals("'My Home WiFi'", RootShellExecutor.shellQuote("My Home WiFi"))
        assertEquals("'pass\$123`cmd`\"!'", RootShellExecutor.shellQuote("pass\$123`cmd`\"!"))
        assertEquals("'O'\\''Reilly'", RootShellExecutor.shellQuote("O'Reilly"))
    }

    @Test
    fun `parseRootScanResults parses column-aligned output including frequency`() {
        val output = """
              BSSID              Frequency      RSSI           Flags                                          SSID
              aa:bb:cc:dd:ee:01  5180           -48            [WPA2-PSK-CCMP][ESS]                           Home_5G
              aa:bb:cc:dd:ee:02  2437           -72            [ESS]                                          Cafe Free WiFi
        """.trimIndent()

        val results = WifiRepository.parseRootScanResults(output)
        assertEquals(2, results.size)

        assertEquals("Home_5G", results[0].ssid)
        assertEquals("aa:bb:cc:dd:ee:01", results[0].bssid)
        assertEquals(5180, results[0].frequencyMhz)
        assertEquals(-48, results[0].level)
        assertEquals("[WPA2-PSK-CCMP][ESS]", results[0].capabilities)

        assertEquals("Cafe Free WiFi", results[1].ssid)
        assertEquals(2437, results[1].frequencyMhz)
        assertEquals(-72, results[1].level)
    }

    @Test
    fun `parseSavedNetworks preserves spaces in unquoted and quoted SSIDs`() {
        val output = """
            Network Id      SSID                         Security type
            0               My Home Network              wpa2
            1               "Office Guest"               open
            2               5GHz_Lab                     wpa3
        """.trimIndent()

        val saved = WifiRepository.parseSavedNetworks(output)
        assertEquals(3, saved.size)
        assertEquals(SavedNetworkInfo(0, "My Home Network", SecurityProtocol.WPA2), saved[0])
        assertEquals(SavedNetworkInfo(1, "Office Guest", SecurityProtocol.OPEN), saved[1])
        assertEquals(SavedNetworkInfo(2, "5GHz_Lab", SecurityProtocol.WPA3), saved[2])
    }

    @Test
    fun `parseRootWifiStatus extracts connected SSID and telemetry`() {
        val output = """
            Wifi is enabled
            Wifi is connected to "PixelRootLab"
            WifiInfo: SSID: "PixelRootLab", BSSID: 12:34:56:78:9a:bc, RSSI: -54, Link speed: 866Mbps, Frequency: 5220MHz, Security type: WPA3-SAE
        """.trimIndent()

        val status = WifiRepository.parseRootWifiStatus(output)
        assertNotNull(status)
        assertTrue(status!!.isConnected)
        assertEquals("PixelRootLab", status.ssid)
        assertEquals("12:34:56:78:9a:bc", status.bssid)
        assertEquals(-54, status.rssi)
        assertEquals(866, status.linkSpeedMbps)
        assertEquals(5220, status.frequencyMhz)
        assertEquals(SecurityProtocol.WPA3, status.securityProtocol)
    }

    @Test
    fun `frequency band and channel calculation are accurate`() {
        assertEquals(WifiBand.BAND_2_4_GHZ, WifiBand.fromFrequency(2437))
        assertEquals(6, WifiBand.frequencyToChannel(2437))
        assertEquals(WifiBand.BAND_5_GHZ, WifiBand.fromFrequency(5180))
        assertEquals(36, WifiBand.frequencyToChannel(5180))
        assertEquals(WifiBand.BAND_6_GHZ, WifiBand.fromFrequency(5955))
        assertEquals(1, WifiBand.frequencyToChannel(5955))
        assertEquals(WifiBand.UNKNOWN, WifiBand.fromFrequency(0))
        assertNull(WifiBand.frequencyToChannel(0))
    }

    @Test
    fun `security protocol detection matches capabilities`() {
        assertEquals(SecurityProtocol.WPA3, SecurityProtocol.fromCapabilities("[WPA3-SAE-CCMP][ESS]"))
        assertEquals(SecurityProtocol.WPA2, SecurityProtocol.fromCapabilities("[WPA2-PSK-CCMP][ESS]"))
        assertEquals(SecurityProtocol.WEP, SecurityProtocol.fromCapabilities("[WEP][ESS]"))
        assertEquals(SecurityProtocol.OPEN, SecurityProtocol.fromCapabilities("[ESS]"))
        assertFalse(SecurityProtocol.OPEN.isSecured)
        assertTrue(SecurityProtocol.WPA2.isSecured)
    }

    @Test
    fun `connectToNetwork quotes SSID and password and detects incorrect password`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeShell = FakeShellExecutor { cmd ->
            if (cmd.contains("wrongPass")) {
                ShellResult(false, "Authentication failure: wrong password", 1)
            } else {
                ShellResult(true, "Connection initiated", 0)
            }
        }
        val repo = WifiRepository(context, fakeShell)

        val failResult = repo.connectToNetwork("Test\$Net", "wpa2", "wrongPass'1")
        assertTrue(failResult is ConnectionResult.IncorrectPassword)
        val cmdUsed = fakeShell.executedCommands.last()
        assertTrue(cmdUsed.contains("'Test\$Net'"))
        assertTrue(cmdUsed.contains("'wrongPass'\\''1'"))

        val openResult = repo.connectToNetwork("Cafe Net", "open", null)
        assertTrue(openResult is ConnectionResult.Success)
        assertTrue(fakeShell.executedCommands.last().endsWith("'Cafe Net' open"))
    }
}
