package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.SavedNetworkInfo
import com.example.SecurityProtocol
import com.example.WifiConnectionDetails
import com.example.WifiNetworkUIModel
import com.example.WifiViewModel
import com.example.ui.theme.JetBrainsMonoFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordSheet(
    network: WifiNetworkUIModel,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isWep = network.securityProtocol == SecurityProtocol.WEP
    val isValid = if (isWep) password.length >= 5 else password.length >= 8

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.WifiLock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = network.ssid,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = securityDetailedLabel(network.securityProtocol),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.password)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (isWep) R.string.password_hint_wep else R.string.password_hint_wpa
                        )
                    )
                },
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        modifier = Modifier.testTag("password_visibility_toggle")
                    ) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(
                                if (passwordVisible) R.string.hide_password else R.string.show_password
                            )
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (isValid) onConnect(password)
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("password_input"),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("password_cancel_button")
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { onConnect(password) },
                    enabled = isValid,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("password_connect_button")
                ) {
                    Text(stringResource(R.string.connect))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddManualNetworkSheet(
    onDismiss: () -> Unit,
    onConnect: (String, String, String?) -> Unit
) {
    var ssid by remember { mutableStateOf("") }
    var selectedSecurity by remember { mutableStateOf(SecurityProtocol.WPA2) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val securityOptions = remember {
        listOf(SecurityProtocol.WPA2, SecurityProtocol.WPA3, SecurityProtocol.OPEN)
    }
    val canConnect = ssid.isNotBlank() && (!selectedSecurity.isSecured || password.length >= 8)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.add_network_manually),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it },
                label = { Text(stringResource(R.string.ssid)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual_ssid_input"),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.security_type),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(8.dp))

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual_security_selector")
            ) {
                securityOptions.forEachIndexed { index, protocol ->
                    SegmentedButton(
                        selected = selectedSecurity == protocol,
                        onClick = { selectedSecurity = protocol },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = securityOptions.size
                        )
                    ) {
                        Text(
                            text = securityShortLabel(protocol),
                            maxLines = 1
                        )
                    }
                }
            }

            if (selectedSecurity.isSecured) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password)) },
                    supportingText = { Text(stringResource(R.string.password_hint_wpa)) },
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(
                                    if (passwordVisible) R.string.hide_password else R.string.show_password
                                )
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (canConnect) {
                                onConnect(ssid.trim(), selectedSecurity.cmdArg, password)
                            }
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_password_input"),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("manual_cancel_button")
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        onConnect(
                            ssid.trim(),
                            selectedSecurity.cmdArg,
                            password.takeIf { selectedSecurity.isSecured }
                        )
                    },
                    enabled = canConnect,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("manual_connect_button")
                ) {
                    Text(stringResource(R.string.connect))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun ConnectionDetailsDialog(
    details: WifiConnectionDetails,
    onForget: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showConfirmForget by remember { mutableStateOf(false) }

    if (showConfirmForget) {
        ForgetConfirmDialog(
            ssid = details.ssid,
            onConfirm = {
                showConfirmForget = false
                onForget()
                onDismiss()
            },
            onDismiss = { showConfirmForget = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.connection_details_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DetailRow(
                    label = stringResource(R.string.detail_ssid),
                    value = details.ssid
                )
                DetailRow(
                    label = stringResource(R.string.detail_bssid),
                    value = details.bssid,
                    isLtrMono = true
                )
                DetailRow(
                    label = stringResource(R.string.detail_security),
                    value = securityDetailedLabel(details.securityProtocol)
                )
                DetailRow(
                    label = stringResource(R.string.detail_link_speed),
                    value = "${details.linkSpeedMbps} Mbps",
                    isLtrMono = true
                )
                DetailRow(
                    label = stringResource(R.string.detail_signal_strength),
                    value = "${signalQualityLabel(details.signalQuality)} (${details.signalStrengthDbm} dBm)"
                )
                val bandText = bandLabel(details.band)
                val channelText = details.channel?.let {
                    " • ${stringResource(R.string.channel_format, it)}"
                }.orEmpty()
                DetailRow(
                    label = stringResource(R.string.detail_frequency_band),
                    value = if (details.frequencyMhz > 0) {
                        "${details.frequencyMhz} MHz ($bandText)$channelText"
                    } else {
                        bandText
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DetailRow(
                        label = stringResource(R.string.detail_ip_address),
                        value = details.ipAddress,
                        isLtrMono = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Wi-Fi IP", details.ipAddress))
                            Toast.makeText(context, context.getString(R.string.ip_copied), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("copy_ip_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.copy_ip),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (!details.gatewayIp.isNullOrBlank()) {
                    DetailRow(
                        label = stringResource(R.string.detail_gateway),
                        value = details.gatewayIp,
                        isLtrMono = true
                    )
                }
                if (details.dnsServers.isNotEmpty()) {
                    DetailRow(
                        label = stringResource(R.string.detail_dns),
                        value = details.dnsServers.joinToString(", "),
                        isLtrMono = true
                    )
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { showConfirmForget = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("details_forget_button")
                ) {
                    Text(
                        text = stringResource(R.string.forget),
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("details_close_button")
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    )
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isLtrMono: Boolean = false
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isLtrMono) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedNetworksSheet(
    savedNetworks: List<SavedNetworkInfo>,
    onForgetNetwork: (SavedNetworkInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingForget by remember { mutableStateOf<SavedNetworkInfo?>(null) }

    pendingForget?.let { target ->
        ForgetConfirmDialog(
            ssid = target.ssid,
            onConfirm = {
                onForgetNetwork(target)
                pendingForget = null
            },
            onDismiss = { pendingForget = null }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.saved_networks_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (savedNetworks.isEmpty()) {
                Text(
                    text = stringResource(R.string.saved_networks_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(savedNetworks, key = { "${it.networkId}_${it.ssid}" }) { saved ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = saved.ssid,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${securityShortLabel(saved.securityProtocol)} • ${
                                            stringResource(R.string.saved_network_id, saved.networkId)
                                        }",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { pendingForget = saved },
                                    modifier = Modifier.testTag("forget_saved_${saved.networkId}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = stringResource(R.string.forget),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text(stringResource(R.string.close))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun ForgetConfirmDialog(
    ssid: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.BookmarkRemove,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(stringResource(R.string.forget_confirm_title)) },
        text = { Text(stringResource(R.string.forget_confirm_message, ssid)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("confirm_forget_button")
            ) {
                Text(stringResource(R.string.forget))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun ErrorAlertDialog(
    errorMsg: String,
    onDismiss: () -> Unit
) {
    val translatedMsg = when {
        errorMsg == WifiViewModel.ERROR_INCORRECT_PASSWORD ->
            stringResource(R.string.error_incorrect_password)
        errorMsg == WifiViewModel.ERROR_TIMEOUT ->
            stringResource(R.string.error_timeout)
        errorMsg.startsWith(WifiViewModel.ERROR_ROOT_PREFIX) -> {
            val detail = errorMsg.removePrefix(WifiViewModel.ERROR_ROOT_PREFIX).ifBlank {
                stringResource(R.string.error_unknown)
            }
            stringResource(R.string.error_root_command, detail)
        }
        else -> errorMsg.ifBlank { stringResource(R.string.error_unknown) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.error_title)) },
        text = { Text(translatedMsg) },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("error_ok_button")
            ) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}
