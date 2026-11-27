package com.example

data class CustomScanResult(
    val ssid: String,
    val bssid: String,
    val level: Int,
    val frequencyMhz: Int,
    val capabilities: String
)

enum class SecurityProtocol(val cmdArg: String, val isSecured: Boolean) {
    OPEN("open", false),
    OWE("owe", true),
    WEP("wep", true),
    WPA2("wpa2", true),
    WPA3("wpa3", true),
    WPA2_ENTERPRISE("wpa2-enterprise", true),
    WPA3_ENTERPRISE("wpa3-enterprise", true);

    companion object {
        fun fromCapabilities(capabilities: String): SecurityProtocol {
            val caps = capabilities.uppercase()
            return when {
                caps.contains("WPA3") && caps.contains("EAP") -> WPA3_ENTERPRISE
                caps.contains("EAP") -> WPA2_ENTERPRISE
                caps.contains("WPA3") || caps.contains("SAE") -> WPA3
                caps.contains("WPA2") || caps.contains("WPA") || caps.contains("PSK") -> WPA2
                caps.contains("WEP") -> WEP
                caps.contains("OWE") -> OWE
                else -> OPEN
            }
        }

        fun fromCmdArg(arg: String): SecurityProtocol {
            return entries.firstOrNull { it.cmdArg.equals(arg.trim(), ignoreCase = true) } ?: WPA2
        }
    }
}

enum class SignalQuality(val bars: Int) {
    EXCELLENT(4),
    VERY_GOOD(3),
    GOOD(2),
    FAIR(1),
    WEAK(0);

    companion object {
        fun fromRssi(rssiDbm: Int): SignalQuality {
            return when {
                rssiDbm >= -55 -> EXCELLENT
                rssiDbm >= -65 -> VERY_GOOD
                rssiDbm >= -75 -> GOOD
                rssiDbm >= -85 -> FAIR
                else -> WEAK
            }
        }
    }
}

enum class WifiBand {
    BAND_2_4_GHZ,
    BAND_5_GHZ,
    BAND_6_GHZ,
    UNKNOWN;

    companion object {
        fun fromFrequency(freqMhz: Int): WifiBand {
            return when (freqMhz) {
                in 2400..2500 -> BAND_2_4_GHZ
                in 4900..5900 -> BAND_5_GHZ
                in 5925..7125 -> BAND_6_GHZ
                else -> UNKNOWN
            }
        }

        fun frequencyToChannel(freqMhz: Int): Int? {
            return when {
                freqMhz == 2484 -> 14
                freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
                freqMhz in 5160..5885 -> (freqMhz - 5000) / 5
                freqMhz in 5955..7115 -> (freqMhz - 5950) / 5
                else -> null
            }
        }
    }
}

data class WifiConnectionDetails(
    val ssid: String,
    val bssid: String,
    val securityProtocol: SecurityProtocol,
    val signalStrengthDbm: Int,
    val signalQuality: SignalQuality,
    val linkSpeedMbps: Int,
    val ipAddress: String,
    val gatewayIp: String?,
    val dnsServers: List<String>,
    val frequencyMhz: Int,
    val band: WifiBand,
    val channel: Int?
)

data class SavedNetworkInfo(
    val networkId: Int,
    val ssid: String,
    val securityProtocol: SecurityProtocol
)

data class ConnectionStateInfo(
    val isConnected: Boolean,
    val ssid: String,
    val bssid: String? = null
)

sealed class ConnectionResult {
    object Success : ConnectionResult()
    object IncorrectPassword : ConnectionResult()
    object Timeout : ConnectionResult()
    data class Failure(val message: String) : ConnectionResult()
}

enum class NetworkFilter {
    ALL,
    SAVED,
    BAND_5_GHZ,
    OPEN
}

data class WifiUiState(
    val hasRoot: Boolean = true,
    val isCheckingRoot: Boolean = true,
    val networks: List<WifiNetworkUIModel> = emptyList(),
    val savedNetworks: List<SavedNetworkInfo> = emptyList(),
    val connectedNetwork: WifiNetworkUIModel? = null,
    val connectedSsid: String? = null,
    val connectingSsid: String? = null,
    val isScanning: Boolean = false,
    val isWifiEnabled: Boolean = true,
    val searchQuery: String = "",
    val activeFilter: NetworkFilter = NetworkFilter.ALL,
    val errorMsg: String? = null,
    val connectionDetails: WifiConnectionDetails? = null
)

data class WifiNetworkUIModel(
    val ssid: String,
    val bssid: String,
    val level: Int,
    val frequencyMhz: Int,
    val band: WifiBand,
    val signalQuality: SignalQuality,
    val isSecured: Boolean,
    val securityProtocol: SecurityProtocol,
    val isSaved: Boolean,
    val isConnected: Boolean,
    val isConnecting: Boolean,
    val apCount: Int = 1
)
