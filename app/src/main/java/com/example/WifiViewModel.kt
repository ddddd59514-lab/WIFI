package com.example

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WifiViewModel(
    private val repository: WifiRepository,
    private val shellExecutor: ShellExecutor = RootShellExecutor
) : ViewModel() {

    private val _scanResults = MutableStateFlow<List<CustomScanResult>>(emptyList())
    private val _savedNetworks = MutableStateFlow<List<SavedNetworkInfo>>(emptyList())
    private val _connectionStateInfo = MutableStateFlow(ConnectionStateInfo(false, ""))
    private val _isWifiEnabled = MutableStateFlow(true)
    private val _uiState = MutableStateFlow(WifiUiState())

    val uiState: StateFlow<WifiUiState> = combine(
        _scanResults,
        _savedNetworks,
        _connectionStateInfo,
        _isWifiEnabled,
        _uiState
    ) { scan, saved, connState, wifiEnabled, state ->
        val savedSsids = saved.map { it.ssid }.toSet()

        // Sort strongest first BEFORE grouping by SSID so we always pick the strongest AP per SSID
        val groupedBySsid = scan
            .filter { it.ssid.isNotBlank() }
            .sortedByDescending { it.level }
            .groupBy { it.ssid }

        val allModels = groupedBySsid.map { (ssid, aps) ->
            val strongest = aps.first()
            val protocol = SecurityProtocol.fromCapabilities(strongest.capabilities)
            WifiNetworkUIModel(
                ssid = ssid,
                bssid = strongest.bssid,
                level = strongest.level,
                frequencyMhz = strongest.frequencyMhz,
                band = WifiBand.fromFrequency(strongest.frequencyMhz),
                signalQuality = SignalQuality.fromRssi(strongest.level),
                isSecured = protocol.isSecured,
                securityProtocol = protocol,
                isSaved = savedSsids.contains(ssid),
                isConnected = connState.isConnected && connState.ssid == ssid,
                isConnecting = state.connectingSsid == ssid,
                apCount = aps.size
            )
        }

        val connectedModel = if (connState.isConnected && connState.ssid.isNotBlank()) {
            allModels.firstOrNull { it.isConnected } ?: WifiNetworkUIModel(
                ssid = connState.ssid,
                bssid = connState.bssid ?: "00:00:00:00:00:00",
                level = -60,
                frequencyMhz = 0,
                band = WifiBand.UNKNOWN,
                signalQuality = SignalQuality.VERY_GOOD,
                isSecured = true,
                securityProtocol = SecurityProtocol.WPA2,
                isSaved = savedSsids.contains(connState.ssid),
                isConnected = true,
                isConnecting = false,
                apCount = 1
            )
        } else {
            null
        }

        val query = state.searchQuery.trim()
        val filteredNetworks = allModels
            .filter { !it.isConnected }
            .filter { model ->
                query.isEmpty() || model.ssid.contains(query, ignoreCase = true)
            }
            .filter { model ->
                when (state.activeFilter) {
                    NetworkFilter.ALL -> true
                    NetworkFilter.SAVED -> model.isSaved
                    NetworkFilter.BAND_5_GHZ -> model.band == WifiBand.BAND_5_GHZ || model.band == WifiBand.BAND_6_GHZ
                    NetworkFilter.OPEN -> !model.isSecured
                }
            }

        state.copy(
            networks = filteredNetworks,
            savedNetworks = saved,
            connectedNetwork = connectedModel,
            connectedSsid = if (connState.isConnected) connState.ssid else null,
            isWifiEnabled = wifiEnabled
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WifiUiState())

    private var connectJob: Job? = null

    init {
        checkRootAndLoad()

        viewModelScope.launch {
            repository.observeWifiPowerState().collect { enabled ->
                _isWifiEnabled.value = enabled
                if (!enabled) {
                    _scanResults.value = emptyList()
                    _uiState.update { it.copy(connectingSsid = null, isScanning = false) }
                }
            }
        }

        viewModelScope.launch {
            repository.scanWifiNetworks().collect { results ->
                _scanResults.value = results
            }
        }

        viewModelScope.launch {
            repository.observeConnectionState().collect { connInfo ->
                _connectionStateInfo.value = connInfo
                if (connInfo.isConnected) {
                    val target = _uiState.value.connectingSsid
                    if (target == null || target == connInfo.ssid) {
                        connectJob?.cancel()
                        _uiState.update { it.copy(connectingSsid = null, errorMsg = null) }
                    }
                    refreshSavedNetworks()
                }
            }
        }
    }

    fun retryRootCheck() {
        checkRootAndLoad()
    }

    private fun checkRootAndLoad() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingRoot = true) }
            val hasRoot = shellExecutor.isRootAvailable()
            _uiState.update { it.copy(hasRoot = hasRoot, isCheckingRoot = false) }
            if (hasRoot) {
                refreshSavedNetworks()
                triggerScan()
            }
        }
    }

    fun refreshSavedNetworks() {
        viewModelScope.launch {
            val saved = repository.getSavedNetworksDetailed()
            _savedNetworks.value = saved
        }
    }

    fun toggleWifi(enabled: Boolean) {
        viewModelScope.launch {
            val success = repository.setWifiPower(enabled)
            if (success) {
                _isWifiEnabled.value = enabled
                if (enabled) {
                    delay(1000L)
                    triggerScan()
                } else {
                    _scanResults.value = emptyList()
                    _uiState.update { it.copy(connectingSsid = null, connectionDetails = null) }
                }
            }
        }
    }

    fun triggerScan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true) }
            try {
                val rootResults = repository.triggerScan()
                if (rootResults.isNotEmpty()) {
                    _scanResults.value = rootResults
                }
            } finally {
                delay(400L)
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setFilter(filter: NetworkFilter) {
        _uiState.update { it.copy(activeFilter = filter) }
    }

    fun clearFilters() {
        _uiState.update { it.copy(searchQuery = "", activeFilter = NetworkFilter.ALL) }
    }

    fun showConnectedDetails() {
        viewModelScope.launch {
            val details = repository.getConnectedWifiDetails()
            _uiState.update { it.copy(connectionDetails = details) }
        }
    }

    fun clearConnectedDetails() {
        _uiState.update { it.copy(connectionDetails = null) }
    }

    fun connectToNetwork(ssid: String, securityType: String, password: String?) {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            _uiState.update { it.copy(connectingSsid = ssid, errorMsg = null) }
            try {
                val result = repository.connectToNetwork(ssid, securityType, password)
                when (result) {
                    is ConnectionResult.Success -> {
                        refreshSavedNetworks()
                        // Wait up to 15s for ConnectivityManager/Root status to confirm connection
                        var elapsed = 0L
                        while (elapsed < CONNECTION_TIMEOUT_MS) {
                            val conn = _connectionStateInfo.value
                            if (conn.isConnected && conn.ssid == ssid) {
                                _uiState.update { it.copy(connectingSsid = null, errorMsg = null) }
                                return@launch
                            }
                            delay(500L)
                            elapsed += 500L
                        }
                        // Timed out waiting for confirmation
                        if (_uiState.value.connectingSsid == ssid) {
                            _uiState.update { it.copy(connectingSsid = null, errorMsg = ERROR_TIMEOUT) }
                        }
                    }
                    is ConnectionResult.IncorrectPassword -> {
                        _uiState.update { it.copy(connectingSsid = null, errorMsg = ERROR_INCORRECT_PASSWORD) }
                    }
                    is ConnectionResult.Failure -> {
                        _uiState.update {
                            it.copy(
                                connectingSsid = null,
                                errorMsg = "$ERROR_ROOT_PREFIX${result.message}"
                            )
                        }
                    }
                    is ConnectionResult.Timeout -> {
                        _uiState.update { it.copy(connectingSsid = null, errorMsg = ERROR_TIMEOUT) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        connectingSsid = null,
                        errorMsg = "$ERROR_ROOT_PREFIX${e.message ?: ""}"
                    )
                }
            }
        }
    }

    fun disconnectOrForget(ssid: String) {
        viewModelScope.launch {
            val result = repository.forgetNetwork(ssid)
            if (result) {
                refreshSavedNetworks()
                if (_uiState.value.connectionDetails?.ssid == ssid) {
                    _uiState.update { it.copy(connectionDetails = null) }
                }
            }
        }
    }

    fun forgetNetworkById(networkId: Int, ssid: String) {
        viewModelScope.launch {
            val result = repository.forgetNetworkById(networkId)
            if (result) {
                refreshSavedNetworks()
                if (_uiState.value.connectionDetails?.ssid == ssid) {
                    _uiState.update { it.copy(connectionDetails = null) }
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMsg = null) }
    }

    companion object {
        const val CONNECTION_TIMEOUT_MS = 15000L
        const val ERROR_INCORRECT_PASSWORD = "error_incorrect_password"
        const val ERROR_TIMEOUT = "error_timeout"
        const val ERROR_ROOT_PREFIX = "error_root:"
    }
}
