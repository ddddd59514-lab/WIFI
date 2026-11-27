package com.example

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.AddManualNetworkSheet
import com.example.ui.ConnectedHeroCard
import com.example.ui.ConnectionDetailsDialog
import com.example.ui.EmptyScanState
import com.example.ui.ErrorAlertDialog
import com.example.ui.ForgetConfirmDialog
import com.example.ui.LocationPermissionBanner
import com.example.ui.NetworkItem
import com.example.ui.NoRootState
import com.example.ui.PasswordSheet
import com.example.ui.SavedNetworksSheet
import com.example.ui.WifiDisabledState
import com.example.ui.WifiPowerCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent(
    viewModel: WifiViewModel,
    hasLocationPermission: Boolean = true,
    onRequestLocationPermission: () -> Unit = {}
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        var showPasswordDialogFor by remember { mutableStateOf<WifiNetworkUIModel?>(null) }
        var showAddManualNetwork by remember { mutableStateOf(false) }
        var showSavedNetworksSheet by remember { mutableStateOf(false) }
        var pendingForgetSsid by remember { mutableStateOf<String?>(null) }

        val infiniteTransition = rememberInfiniteTransition(label = "scanSpin")
        val spinAngle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "spinAngle"
        )

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    modifier = Modifier.testTag("top_app_bar"),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.wifi_settings),
                                fontWeight = FontWeight.Bold
                            )
                            if (state.hasRoot && !state.isCheckingRoot) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.VerifiedUser,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = stringResource(R.string.root_badge_active),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    },
                    actions = {
                        if (state.hasRoot) {
                            IconButton(
                                onClick = {
                                    viewModel.refreshSavedNetworks()
                                    showSavedNetworksSheet = true
                                },
                                modifier = Modifier.testTag("saved_networks_button")
                            ) {
                                BadgedBox(
                                    badge = {
                                        if (state.savedNetworks.isNotEmpty()) {
                                            Badge {
                                                Text(state.savedNetworks.size.toString())
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Bookmarks,
                                        contentDescription = stringResource(R.string.saved_networks_title)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { viewModel.triggerScan() },
                                enabled = state.isWifiEnabled,
                                modifier = Modifier.testTag("refresh_scan_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.refresh),
                                    modifier = if (state.isScanning) {
                                        Modifier.rotate(spinAngle)
                                    } else {
                                        Modifier
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            floatingActionButton = {
                if (state.hasRoot && state.isWifiEnabled) {
                    ExtendedFloatingActionButton(
                        onClick = { showAddManualNetwork = true },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.add_network_manually)) },
                        modifier = Modifier.testTag("add_manual_network_fab")
                    )
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.TopCenter
            ) {
                if (!state.hasRoot || state.isCheckingRoot) {
                    NoRootState(
                        isChecking = state.isCheckingRoot,
                        onRetry = { viewModel.retryRootCheck() },
                        modifier = Modifier.widthIn(max = 680.dp)
                    )
                    return@Box
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 680.dp)
                        .padding(horizontal = 16.dp)
                ) {
                    Spacer(modifier = Modifier.height(6.dp))

                    WifiPowerCard(
                        isEnabled = state.isWifiEnabled,
                        onToggle = { checked -> viewModel.toggleWifi(checked) }
                    )

                    if (!hasLocationPermission && state.isWifiEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        LocationPermissionBanner(onGrantClick = onRequestLocationPermission)
                    }

                    if (!state.isWifiEnabled) {
                        WifiDisabledState(
                            onEnable = { viewModel.toggleWifi(true) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.height(12.dp))

                        // Search bar & quick filter chips
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            placeholder = { Text(stringResource(R.string.search_networks_hint)) },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null)
                            },
                            trailingIcon = {
                                if (state.searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { viewModel.updateSearchQuery("") },
                                        modifier = Modifier.testTag("clear_search_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = stringResource(R.string.clear_search)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("search_networks_input")
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NetworkFilter.entries.forEach { filter ->
                                val labelRes = when (filter) {
                                    NetworkFilter.ALL -> R.string.filter_all
                                    NetworkFilter.SAVED -> R.string.filter_saved
                                    NetworkFilter.BAND_5_GHZ -> R.string.filter_5ghz
                                    NetworkFilter.OPEN -> R.string.filter_open
                                }
                                FilterChip(
                                    selected = state.activeFilter == filter,
                                    onClick = { viewModel.setFilter(filter) },
                                    label = { Text(stringResource(labelRes)) },
                                    modifier = Modifier.testTag("filter_chip_${filter.name.lowercase()}")
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        val hasActiveFilters = state.searchQuery.isNotBlank() ||
                            state.activeFilter != NetworkFilter.ALL

                        if (state.networks.isEmpty() && state.connectedNetwork == null) {
                            EmptyScanState(
                                hasActiveFilters = hasActiveFilters,
                                isScanning = state.isScanning,
                                onRefresh = { viewModel.triggerScan() },
                                onClearFilters = { viewModel.clearFilters() },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentPadding = PaddingValues(bottom = 96.dp)
                            ) {
                                state.connectedNetwork?.let { connected ->
                                    item(key = "connected_hero") {
                                        ConnectedHeroCard(
                                            network = connected,
                                            onShowDetails = { viewModel.showConnectedDetails() },
                                            onForget = { pendingForgetSsid = connected.ssid }
                                        )
                                        Spacer(modifier = Modifier.height(14.dp))
                                    }
                                }

                                if (state.networks.isNotEmpty()) {
                                    item(key = "available_header") {
                                        Text(
                                            text = stringResource(
                                                R.string.available_networks_header,
                                                state.networks.size
                                            ),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(vertical = 6.dp)
                                        )
                                    }

                                    items(
                                        items = state.networks,
                                        key = { "${it.ssid}_${it.bssid}" }
                                    ) { network ->
                                        NetworkItem(
                                            network = network,
                                            onClick = {
                                                if (network.isSaved || !network.isSecured) {
                                                    viewModel.connectToNetwork(
                                                        network.ssid,
                                                        network.securityProtocol.cmdArg,
                                                        null
                                                    )
                                                } else {
                                                    showPasswordDialogFor = network
                                                }
                                            },
                                            onForget = {
                                                pendingForgetSsid = network.ssid
                                            }
                                        )
                                    }
                                } else if (hasActiveFilters) {
                                    item(key = "empty_filtered") {
                                        EmptyScanState(
                                            hasActiveFilters = true,
                                            isScanning = false,
                                            onRefresh = { viewModel.triggerScan() },
                                            onClearFilters = { viewModel.clearFilters() },
                                            modifier = Modifier.fillParentMaxHeight(0.6f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        showPasswordDialogFor?.let { target ->
            PasswordSheet(
                network = target,
                onDismiss = { showPasswordDialogFor = null },
                onConnect = { password ->
                    viewModel.connectToNetwork(
                        target.ssid,
                        target.securityProtocol.cmdArg,
                        password
                    )
                    showPasswordDialogFor = null
                }
            )
        }

        if (showAddManualNetwork) {
            AddManualNetworkSheet(
                onDismiss = { showAddManualNetwork = false },
                onConnect = { ssid, secType, password ->
                    viewModel.connectToNetwork(ssid, secType, password)
                    showAddManualNetwork = false
                }
            )
        }

        if (showSavedNetworksSheet) {
            SavedNetworksSheet(
                savedNetworks = state.savedNetworks,
                onForgetNetwork = { saved ->
                    viewModel.forgetNetworkById(saved.networkId, saved.ssid)
                },
                onDismiss = { showSavedNetworksSheet = false }
            )
        }

        pendingForgetSsid?.let { ssid ->
            ForgetConfirmDialog(
                ssid = ssid,
                onConfirm = {
                    viewModel.disconnectOrForget(ssid)
                    pendingForgetSsid = null
                },
                onDismiss = { pendingForgetSsid = null }
            )
        }

        state.errorMsg?.let { msg ->
            ErrorAlertDialog(
                errorMsg = msg,
                onDismiss = { viewModel.clearError() }
            )
        }

        state.connectionDetails?.let { details ->
            ConnectionDetailsDialog(
                details = details,
                onForget = { viewModel.disconnectOrForget(details.ssid) },
                onDismiss = { viewModel.clearConnectedDetails() }
            )
        }
    }
}
