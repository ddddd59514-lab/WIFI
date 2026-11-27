package com.example

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class WifiRepository(
    private val context: Context,
    private val shellExecutor: ShellExecutor = RootShellExecutor
) {
    private val appContext = context.applicationContext
    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    @SuppressLint("MissingPermission")
    fun scanWifiNetworks(): Flow<List<CustomScanResult>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    try {
                        val standardResults = wifiManager?.scanResults?.map {
                            @Suppress("DEPRECATION")
                            val ssid = it.SSID ?: ""
                            CustomScanResult(
                                ssid = ssid,
                                bssid = it.BSSID ?: "",
                                level = it.level,
                                frequencyMhz = it.frequency,
                                capabilities = it.capabilities ?: ""
                            )
                        }.orEmpty()
                        if (standardResults.isNotEmpty()) {
                            trySend(standardResults)
                        }
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Standard scan results require location permission; relying on root")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading standard scan results", e)
                    }
                }
            }
        }

        try {
            val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, intentFilter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, intentFilter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering scan receiver", e)
        }

        // Launch scan trigger & root poller inside ProducerScope so they cancel cleanly on awaitClose
        launch(Dispatchers.IO) {
            triggerScan()
        }

        val pollerJob = launch(Dispatchers.IO) {
            while (true) {
                try {
                    val rootResults = fetchScanResultsViaRoot()
                    if (rootResults.isNotEmpty()) {
                        trySend(rootResults)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Poller error", e)
                }
                delay(3000L)
            }
        }

        awaitClose {
            pollerJob.cancel()
            try {
                appContext.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering scan receiver", e)
            }
        }
    }

    suspend fun fetchScanResultsViaRoot(): List<CustomScanResult> {
        val result = shellExecutor.executeCommand("cmd wifi list-scan-results")
        if (!result.isSuccess) return emptyList()
        return parseRootScanResults(result.output)
    }

    @SuppressLint("MissingPermission")
    suspend fun triggerScan(): List<CustomScanResult> = withContext(Dispatchers.IO) {
        try {
            @Suppress("DEPRECATION")
            wifiManager?.startScan()
        } catch (e: Exception) {
            Log.w(TAG, "Standard startScan failed or throttled", e)
        }
        shellExecutor.executeCommand("cmd wifi start-scan")
        fetchScanResultsViaRoot()
    }

    fun observeConnectionState(): Flow<ConnectionStateInfo> = callbackFlow {
        val cm = connectivityManager
        val networkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) {
                    launch(Dispatchers.IO) {
                        val info = resolveConnectedState(null)
                        trySend(info)
                    }
                }

                override fun onLost(network: Network) {
                    trySend(ConnectionStateInfo(isConnected = false, ssid = ""))
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        val transportInfo = networkCapabilities.transportInfo as? WifiInfo
                        launch(Dispatchers.IO) {
                            val info = resolveConnectedState(transportInfo)
                            if (info.isConnected) {
                                trySend(info)
                            }
                        }
                    }
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    launch(Dispatchers.IO) {
                        trySend(resolveConnectedState(null))
                    }
                }

                override fun onLost(network: Network) {
                    trySend(ConnectionStateInfo(isConnected = false, ssid = ""))
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        launch(Dispatchers.IO) {
                            val info = resolveConnectedState(null)
                            if (info.isConnected) {
                                trySend(info)
                            }
                        }
                    }
                }
            }
        }

        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm?.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering network callback", e)
        }

        // Initial emission
        launch(Dispatchers.IO) {
            trySend(resolveConnectedState(null))
        }

        awaitClose {
            try {
                cm?.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback", e)
            }
        }
    }

    private suspend fun resolveConnectedState(wifiInfoFromCallback: WifiInfo?): ConnectionStateInfo {
        val info = wifiInfoFromCallback ?: getCurrentWifiInfo()
        val stdSsid = info?.ssid
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID }

        if (stdSsid != null && (info.networkId != -1 || wifiInfoFromCallback != null)) {
            return ConnectionStateInfo(
                isConnected = true,
                ssid = stdSsid,
                bssid = info.bssid
            )
        }

        // Root fallback when Android redacts SSID (<unknown ssid>) due to Location being off
        val rootStatus = fetchRootWifiStatus()
        if (rootStatus != null && rootStatus.isConnected && rootStatus.ssid.isNotBlank()) {
            return ConnectionStateInfo(
                isConnected = true,
                ssid = rootStatus.ssid,
                bssid = rootStatus.bssid
            )
        }

        return ConnectionStateInfo(isConnected = false, ssid = "")
    }

    fun observeWifiPowerState(): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                    val state = intent.getIntExtra(
                        WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN
                    )
                    if (state == WifiManager.WIFI_STATE_ENABLED || state == WifiManager.WIFI_STATE_DISABLED) {
                        trySend(state == WifiManager.WIFI_STATE_ENABLED)
                    }
                }
            }
        }

        try {
            val intentFilter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, intentFilter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, intentFilter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering Wi-Fi state receiver", e)
        }

        try {
            trySend(wifiManager?.isWifiEnabled ?: true)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading initial Wi-Fi enabled state", e)
        }

        awaitClose {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering Wi-Fi state receiver", e)
            }
        }
    }

    suspend fun setWifiPower(enabled: Boolean): Boolean {
        val action = if (enabled) "enabled" else "disabled"
        val result = shellExecutor.executeCommand("cmd wifi set-wifi-enabled $action")
        return result.isSuccess
    }

    @SuppressLint("MissingPermission")
    suspend fun getConnectedWifiDetails(): WifiConnectionDetails? = withContext(Dispatchers.IO) {
        val info = getCurrentWifiInfo()
        val rootStatus = fetchRootWifiStatus()

        val rawSsid = info?.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID }
        val ssid = rawSsid ?: rootStatus?.ssid?.takeIf { it.isNotBlank() } ?: return@withContext null

        val bssid = info?.bssid?.takeIf { it.isNotBlank() && it != DEFAULT_MAC }
            ?: rootStatus?.bssid
            ?: DEFAULT_MAC

        val rssi = info?.rssi?.takeIf { it != -127 } ?: rootStatus?.rssi ?: -70
        val linkSpeed = info?.linkSpeed?.takeIf { it > 0 } ?: rootStatus?.linkSpeedMbps ?: 0
        val freq = info?.frequency?.takeIf { it > 0 } ?: rootStatus?.frequencyMhz ?: 0

        var security = rootStatus?.securityProtocol ?: SecurityProtocol.WPA2
        try {
            val scanMatch = wifiManager?.scanResults?.firstOrNull { it.BSSID == bssid }
            if (scanMatch != null) {
                security = SecurityProtocol.fromCapabilities(scanMatch.capabilities ?: "")
            } else {
                val rootMatch = fetchScanResultsViaRoot().firstOrNull {
                    it.bssid.equals(bssid, ignoreCase = true) || it.ssid == ssid
                }
                if (rootMatch != null) {
                    security = SecurityProtocol.fromCapabilities(rootMatch.capabilities)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not match security from scan results", e)
        }

        val linkInfo = getActiveWifiLinkDetails()

        WifiConnectionDetails(
            ssid = ssid,
            bssid = bssid,
            securityProtocol = security,
            signalStrengthDbm = rssi,
            signalQuality = SignalQuality.fromRssi(rssi),
            linkSpeedMbps = linkSpeed,
            ipAddress = linkInfo.ipAddress,
            gatewayIp = linkInfo.gatewayIp,
            dnsServers = linkInfo.dnsServers,
            frequencyMhz = freq,
            band = WifiBand.fromFrequency(freq),
            channel = WifiBand.frequencyToChannel(freq)
        )
    }

    @Suppress("DEPRECATION")
    private fun getCurrentWifiInfo(): WifiInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val active = connectivityManager?.activeNetwork
                val caps = active?.let { connectivityManager.getNetworkCapabilities(it) }
                val transportInfo = caps?.transportInfo as? WifiInfo
                if (transportInfo != null) return transportInfo
            } catch (e: SecurityException) {
                Log.w(TAG, "ConnectivityManager access denied, falling back to WifiManager", e)
            } catch (e: Exception) {
                Log.w(TAG, "Error querying ConnectivityManager WifiInfo", e)
            }
        }
        return try {
            wifiManager?.connectionInfo
        } catch (e: Exception) {
            Log.e(TAG, "Error getting WifiInfo", e)
            null
        }
    }

    private data class ActiveLinkInfo(
        val ipAddress: String,
        val gatewayIp: String?,
        val dnsServers: List<String>
    )

    private fun getActiveWifiLinkDetails(): ActiveLinkInfo {
        try {
            val cm = connectivityManager
            if (cm != null) {
                @Suppress("DEPRECATION")
                val wifiNetwork = cm.allNetworks.firstOrNull { net ->
                    cm.getNetworkCapabilities(net)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }
                val lp: LinkProperties? = wifiNetwork?.let { cm.getLinkProperties(it) }
                if (lp != null) {
                    val ipv4 = lp.linkAddresses
                        .mapNotNull { it.address as? Inet4Address }
                        .firstOrNull { !it.isLoopbackAddress }
                        ?.hostAddress
                    val gateway = lp.routes
                        .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                        ?.gateway?.hostAddress
                        ?: lp.routes.mapNotNull { it.gateway as? Inet4Address }.firstOrNull()?.hostAddress
                    val dns = lp.dnsServers
                        .mapNotNull { it.hostAddress }
                        .distinct()
                    if (ipv4 != null) {
                        return ActiveLinkInfo(ipv4, gateway, dns)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "LinkProperties lookup failed, falling back to NetworkInterface", e)
        }
        return ActiveLinkInfo(getWifiIpAddressFallback(), null, emptyList())
    }

    fun getWifiIpAddressFallback(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val name = intf.name.lowercase()
                if (name.contains("wlan") || name.contains("p2p")) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            addr.hostAddress?.let { return it }
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "getWifiIpAddressFallback error", ex)
        }
        return "0.0.0.0"
    }

    suspend fun connectToNetwork(
        ssid: String,
        securityType: String,
        password: String? = null
    ): ConnectionResult {
        val wasEnabled = try {
            wifiManager?.isWifiEnabled == true
        } catch (_: Exception) {
            true
        }
        if (!wasEnabled) {
            setWifiPower(true)
            delay(1000L)
        }

        val quotedSsid = RootShellExecutor.shellQuote(ssid)
        val normalizedSecurity = securityType.trim().lowercase()
        val command = if (normalizedSecurity == "open" || normalizedSecurity == "owe") {
            "cmd wifi connect-network $quotedSsid $normalizedSecurity"
        } else {
            val quotedPass = RootShellExecutor.shellQuote(password.orEmpty())
            "cmd wifi connect-network $quotedSsid $normalizedSecurity $quotedPass"
        }

        val result = shellExecutor.executeCommand(command)
        if (!result.isSuccess) {
            val out = result.output.lowercase()
            if (out.contains("password") || out.contains("auth") || out.contains("key")) {
                return ConnectionResult.IncorrectPassword
            }
            return ConnectionResult.Failure(result.output.ifBlank { "Exit code ${result.code}" })
        }
        return ConnectionResult.Success
    }

    suspend fun forgetNetwork(ssid: String): Boolean {
        val saved = getSavedNetworksDetailed()
        val match = saved.firstOrNull { it.ssid.equals(ssid, ignoreCase = true) }
        if (match != null) {
            val res = shellExecutor.executeCommand("cmd wifi forget-network ${match.networkId}")
            if (res.isSuccess) return true
        }

        val quotedSsid = RootShellExecutor.shellQuote(ssid)
        val fb = shellExecutor.executeCommand("cmd wifi forget-network $quotedSsid")
        return fb.isSuccess
    }

    suspend fun forgetNetworkById(networkId: Int): Boolean {
        return shellExecutor.executeCommand("cmd wifi forget-network $networkId").isSuccess
    }

    suspend fun getSavedNetworksDetailed(): List<SavedNetworkInfo> {
        val result = shellExecutor.executeCommand("cmd wifi list-networks")
        if (!result.isSuccess) return emptyList()
        return parseSavedNetworks(result.output)
    }

    suspend fun getSavedNetworks(): List<String> {
        return getSavedNetworksDetailed().map { it.ssid }
    }

    private suspend fun fetchRootWifiStatus(): RootWifiStatus? {
        val result = shellExecutor.executeCommand("cmd wifi status")
        if (!result.isSuccess) return null
        return parseRootWifiStatus(result.output)
    }

    companion object {
        private const val TAG = "WifiRepository"
        private const val UNKNOWN_SSID = "<unknown ssid>"
        private const val DEFAULT_MAC = "00:00:00:00:00:00"

        internal fun parseRootScanResults(output: String): List<CustomScanResult> {
            val lines = output.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return emptyList()

            val header = lines.first()
            val bssidIdx = Regex("\\bBSSID\\b", RegexOption.IGNORE_CASE).find(header)?.range?.first ?: -1
            val freqIdx = Regex("\\b(Frequency|freq)\\b", RegexOption.IGNORE_CASE).find(header)?.range?.first ?: -1
            val rssiIdx = Regex("\\b(RSSI|signal)\\b", RegexOption.IGNORE_CASE).find(header)?.range?.first ?: -1
            val flagsIdx = Regex("\\b(Flags|flags)\\b", RegexOption.IGNORE_CASE).find(header)?.range?.first ?: -1
            val ssidIdx = Regex("\\bSSID\\b", RegexOption.IGNORE_CASE).find(header)?.range?.first ?: -1

            if (bssidIdx == -1 || ssidIdx == -1) {
                return parseRootScanResultsFallback(lines)
            }

            val results = mutableListOf<CustomScanResult>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                try {
                    if (line.length <= bssidIdx) continue
                    val bssidEnd = when {
                        freqIdx > bssidIdx -> freqIdx
                        rssiIdx > bssidIdx -> rssiIdx
                        ssidIdx > bssidIdx -> ssidIdx
                        else -> line.length
                    }
                    val bssid = line.substring(bssidIdx, minOf(line.length, bssidEnd)).trim()
                    if (!bssid.contains(":")) continue

                    val freqStr = if (freqIdx != -1 && freqIdx < line.length) {
                        val endIdx = listOf(rssiIdx, flagsIdx, ssidIdx)
                            .filter { it > freqIdx }
                            .minOrNull() ?: line.length
                        line.substring(freqIdx, minOf(line.length, endIdx)).trim()
                    } else ""

                    val rssiStr = if (rssiIdx != -1 && rssiIdx < line.length) {
                        val endIdx = listOf(flagsIdx, ssidIdx)
                            .filter { it > rssiIdx }
                            .minOrNull() ?: line.length
                        line.substring(rssiIdx, minOf(line.length, endIdx)).trim()
                    } else ""

                    val flagsStr = if (flagsIdx != -1 && flagsIdx < line.length) {
                        val endIdx = if (ssidIdx > flagsIdx) ssidIdx else line.length
                        line.substring(flagsIdx, minOf(line.length, endIdx)).trim()
                    } else ""

                    val ssidStr = if (ssidIdx < line.length) {
                        line.substring(ssidIdx).trim().removeSurrounding("\"")
                    } else ""

                    results.add(
                        CustomScanResult(
                            ssid = ssidStr,
                            bssid = bssid,
                            level = rssiStr.toIntOrNull() ?: -75,
                            frequencyMhz = freqStr.toIntOrNull() ?: 0,
                            capabilities = flagsStr
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing scan line: $line", e)
                }
            }
            return results
        }

        private fun parseRootScanResultsFallback(lines: List<String>): List<CustomScanResult> {
            val results = mutableListOf<CustomScanResult>()
            val macRegex = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")
            for (line in lines) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 4 && macRegex.matches(parts[0])) {
                    val bssid = parts[0]
                    val freq = parts.drop(1).firstOrNull {
                        val n = it.toIntOrNull()
                        n != null && n in 2400..7200
                    }?.toIntOrNull() ?: 0
                    val rssi = parts.drop(1).firstOrNull {
                        it.startsWith("-") && it.toIntOrNull() != null
                    }?.toIntOrNull() ?: -70
                    val flags = parts.firstOrNull { it.startsWith("[") && it.endsWith("]") } ?: ""
                    val flagsIdx = if (flags.isNotEmpty()) parts.indexOf(flags) else -1
                    val ssid = if (flagsIdx != -1 && flagsIdx + 1 < parts.size) {
                        parts.drop(flagsIdx + 1).joinToString(" ").removeSurrounding("\"")
                    } else {
                        parts.last().removeSurrounding("\"")
                    }
                    results.add(CustomScanResult(ssid, bssid, rssi, freq, flags))
                }
            }
            return results
        }

        internal fun parseSavedNetworks(output: String): List<SavedNetworkInfo> {
            val lines = output.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return emptyList()

            val header = lines.first()
            val ssidColIdx = header.indexOf("SSID")
            val secColIdx = header.indexOf("Security")

            val savedList = mutableListOf<SavedNetworkInfo>()
            for (line in lines) {
                if (line.contains("Network Id", ignoreCase = true) ||
                    (line.contains("SSID") && line.contains("Security"))
                ) {
                    continue
                }

                // Prefer quoted SSID if present
                val quoteParts = line.split("\"")
                if (quoteParts.size >= 3) {
                    val netId = quoteParts[0].trim().split("\\s+".toRegex()).firstOrNull()?.toIntOrNull()
                    val ssid = quoteParts[1]
                    val secRaw = quoteParts[2].trim()
                    if (netId != null && ssid.isNotBlank()) {
                        savedList.add(
                            SavedNetworkInfo(
                                networkId = netId,
                                ssid = ssid,
                                securityProtocol = SecurityProtocol.fromCmdArg(secRaw)
                            )
                        )
                        continue
                    }
                }

                // Column-offset parsing preserves spaces inside unquoted SSIDs
                if (ssidColIdx > 0 && secColIdx > ssidColIdx && line.length > ssidColIdx) {
                    val idStr = line.substring(0, minOf(ssidColIdx, line.length)).trim()
                    val netId = idStr.toIntOrNull()
                    if (netId != null) {
                        val ssidEnd = minOf(secColIdx, line.length)
                        val ssid = line.substring(ssidColIdx, ssidEnd).trim().removeSurrounding("\"")
                        val secRaw = if (line.length > secColIdx) line.substring(secColIdx).trim() else "wpa2"
                        if (ssid.isNotBlank()) {
                            savedList.add(
                                SavedNetworkInfo(
                                    networkId = netId,
                                    ssid = ssid,
                                    securityProtocol = SecurityProtocol.fromCmdArg(secRaw)
                                )
                            )
                            continue
                        }
                    }
                }

                // Whitespace fallback
                val cols = line.trim().split("\\s+".toRegex())
                if (cols.size >= 2) {
                    val netId = cols[0].toIntOrNull() ?: continue
                    val secRaw = if (cols.size >= 3) cols.last() else "wpa2"
                    val ssid = if (cols.size >= 3) {
                        cols.subList(1, cols.size - 1).joinToString(" ").removeSurrounding("\"")
                    } else {
                        cols[1].removeSurrounding("\"")
                    }
                    if (ssid.isNotBlank()) {
                        savedList.add(
                            SavedNetworkInfo(
                                networkId = netId,
                                ssid = ssid,
                                securityProtocol = SecurityProtocol.fromCmdArg(secRaw)
                            )
                        )
                    }
                }
            }
            return savedList
        }

        internal data class RootWifiStatus(
            val isConnected: Boolean,
            val ssid: String,
            val bssid: String?,
            val rssi: Int?,
            val linkSpeedMbps: Int?,
            val frequencyMhz: Int?,
            val securityProtocol: SecurityProtocol?
        )

        internal fun parseRootWifiStatus(output: String): RootWifiStatus? {
            if (output.isBlank()) return null
            val isConnected = output.contains("Wifi is connected to", ignoreCase = true) ||
                output.contains("SSID:", ignoreCase = true) && !output.contains("not connected", ignoreCase = true)
            if (!isConnected) return null

            val ssidMatch = Regex("SSID:\\s*\"?([^\",\\n]+)\"?").find(output)
            val bssidMatch = Regex("BSSID:\\s*([0-9a-fA-F:]{17})").find(output)
            val rssiMatch = Regex("RSSI:\\s*(-?\\d+)").find(output)
            val linkSpeedMatch = Regex("Link speed:\\s*(\\d+)").find(output)
            val freqMatch = Regex("Frequency:\\s*(\\d+)").find(output)
            val securityMatch = Regex("Security type:\\s*([^,\\n]+)", RegexOption.IGNORE_CASE).find(output)

            val ssid = ssidMatch?.groupValues?.getOrNull(1)?.trim() ?: ""
            if (ssid.isBlank() || ssid == UNKNOWN_SSID) return null

            val secStr = securityMatch?.groupValues?.getOrNull(1)?.trim()
            val secProto = secStr?.let { SecurityProtocol.fromCapabilities(it) }

            return RootWifiStatus(
                isConnected = true,
                ssid = ssid,
                bssid = bssidMatch?.groupValues?.getOrNull(1),
                rssi = rssiMatch?.groupValues?.getOrNull(1)?.toIntOrNull(),
                linkSpeedMbps = linkSpeedMatch?.groupValues?.getOrNull(1)?.toIntOrNull(),
                frequencyMhz = freqMatch?.groupValues?.getOrNull(1)?.toIntOrNull(),
                securityProtocol = secProto
            )
        }
    }
}
