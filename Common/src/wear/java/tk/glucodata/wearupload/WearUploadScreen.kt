package tk.glucodata.wearupload

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.ui.WearNavigationRow
import tk.glucodata.ui.WearSectionTitle

@Composable
internal fun WearUploadScreen(onEdit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<WearSibionicsUploader.Status?>(null) }
    var feedback by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            status = try {
                withContext(Dispatchers.IO) { WearSibionicsUploader.readStatus(context) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalStateException) {
                WearSibionicsUploader.Status(null, 0, -3, 0)
            } catch (_: android.database.sqlite.SQLiteException) {
                WearSibionicsUploader.Status(null, 0, -3, 0)
            }
            if (status?.pending == 0L && feedback == R.string.wear_upload_send_requested) feedback = null
            delay(2_000L)
        }
    }

    BackHandler(enabled = confirmStop && !busy) { confirmStop = false }
    ScreenScaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 28.dp),
        ) {
            item { WearSectionTitle(stringResource(R.string.wear_upload_title)) }
            val current = status
            if (confirmStop) {
                item { UploadHint(stringResource(R.string.wear_upload_stop_hint)) }
                item {
                    Button(
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { WearSibionicsUploader.stop(context) }
                                    status = WearSibionicsUploader.Status(null, 0, 0, 0)
                                    feedback = null
                                    confirmStop = false
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    feedback = R.string.wear_upload_error
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy,
                        label = { Text(stringResource(R.string.wear_upload_stop)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item { WearNavigationRow(stringResource(R.string.cancel)) { if (!busy) confirmStop = false } }
            } else if (current != null) {
                item { UploadStatusCard(current) }
                if (!busy) {
                    item {
                        WearNavigationRow(
                            stringResource(if (current.active) R.string.wear_upload_edit else R.string.wear_upload_start),
                        ) { onEdit() }
                    }
                }
                if (current.active) {
                    item {
                        Button(
                            onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        val queued = withContext(Dispatchers.IO) { WearSibionicsUploader.sendNow(context) }
                                        feedback = if (queued) {
                                            R.string.wear_upload_send_requested
                                        } else {
                                            R.string.wear_upload_empty
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        feedback = R.string.wear_upload_error
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy && current.pending > 0L,
                            label = { Text(stringResource(R.string.wear_upload_send_now)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        WearNavigationRow(stringResource(R.string.wear_upload_stop)) {
                            if (!busy) {
                                feedback = null
                                confirmStop = true
                            }
                        }
                    }
                }
            }
            feedback?.let { message -> item { UploadHint(stringResource(message)) } }
        }
    }
}

@Composable
private fun UploadStatusCard(status: WearSibionicsUploader.Status) {
    val context = LocalContext.current
    val last = remember(status.sentMs, context) {
        if (status.sentMs == 0L) "—" else {
            val time = Date(status.sentMs)
            DateFormat.getDateFormat(context).format(time) + " " + DateFormat.getTimeFormat(context).format(time)
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            stringResource(if (status.active) R.string.wear_upload_active else R.string.wear_upload_inactive),
            style = MaterialTheme.typography.labelLarge,
            color = if (status.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status.active) {
            UploadHint(stringResource(R.string.wear_upload_pending, status.pending))
            UploadHint(stringResource(R.string.wear_upload_last, last))
        }
        val error = when (status.code) {
            0, 200, 201 -> null
            -1 -> stringResource(R.string.wear_upload_network)
            -2 -> stringResource(R.string.wear_upload_full)
            -3 -> stringResource(R.string.wear_upload_error)
            -4 -> stringResource(R.string.wear_upload_native_enabled)
            401 -> stringResource(R.string.wear_upload_bad_token)
            403 -> stringResource(R.string.wear_upload_forbidden)
            else -> "HTTP ${status.code}"
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun WearUploadSettingsScreen(onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var savedAddress by remember { mutableStateOf<String?>(null) }
    // Keep the replacement only in this screen's memory, never saved navigation state.
    var token by remember { mutableStateOf("") }
    var approvedAddress by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        try {
            val saved = withContext(Dispatchers.IO) { WearSibionicsUploader.readStatus(context).address }
            address = saved.orEmpty()
            savedAddress = saved
            loaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            error = R.string.wear_upload_error
        } catch (_: android.database.sqlite.SQLiteException) {
            error = R.string.wear_upload_error
        }
    }
    val normalizedAddress = remember(address) {
        try {
            WearUploadConfig.address(address)
        } catch (_: WearUploadConfig.InvalidAddress) {
            null
        }
    }
    val hasSavedToken = savedAddress != null && normalizedAddress == savedAddress
    val destinationApproved = normalizedAddress != null && normalizedAddress == approvedAddress
    ScreenScaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 28.dp),
        ) {
            item { WearSectionTitle(stringResource(R.string.wear_upload_edit)) }
            if (loaded) {
                item { UploadHint(stringResource(R.string.wear_upload_start_hint)) }
                item {
                    UploadField(
                        label = stringResource(R.string.wear_upload_address),
                        value = address,
                        placeholder = "https://",
                        enabled = !busy,
                        onChange = {
                            if (it.length <= 512 && it != address) {
                                address = it
                                token = ""
                                approvedAddress = null
                            }
                        },
                    )
                }
                item {
                    UploadField(
                        label = stringResource(R.string.wear_upload_token),
                        value = token,
                        placeholder = if (hasSavedToken) stringResource(R.string.wear_upload_token_saved) else "",
                        enabled = !busy && normalizedAddress != null,
                        secret = true,
                        onChange = { if (it.length <= 256) token = it },
                    )
                }
                if (hasSavedToken && token.isEmpty()) {
                    item { UploadHint(stringResource(R.string.wear_upload_keep_token)) }
                }
                if (!hasSavedToken) {
                    item { UploadHint(stringResource(R.string.wear_upload_token_help)) }
                    item {
                        CheckboxButton(
                            checked = destinationApproved,
                            onCheckedChange = { approvedAddress = if (it) normalizedAddress else null },
                            enabled = !busy && normalizedAddress != null,
                            label = { Text(stringResource(R.string.wear_upload_only_uploader)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    Button(
                        onClick = {
                            busy = true
                            error = null
                            val newAddress = address
                            val newToken = token
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        WearSibionicsUploader.saveSettings(context, newAddress, newToken)
                                    }
                                    token = ""
                                    onSaved()
                                } catch (_: WearSibionicsUploader.PendingOnPreviousServer) {
                                    error = R.string.wear_upload_pending_on_previous
                                } catch (_: WearSibionicsUploader.NativeUploaderEnabled) {
                                    error = R.string.wear_upload_native_enabled
                                } catch (_: WearUploadConfig.InvalidAddress) {
                                    error = R.string.wear_upload_bad_address
                                } catch (_: WearUploadConfig.InvalidToken) {
                                    error = R.string.wear_upload_token_required
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    error = R.string.wear_upload_error
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && (hasSavedToken || (destinationApproved && token.isNotBlank())),
                        label = { Text(stringResource(if (savedAddress != null) R.string.save else R.string.wear_upload_start)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (address.isNotBlank() && normalizedAddress == null) {
                    item { UploadHint(stringResource(R.string.wear_upload_bad_address)) }
                }
            }
            error?.let { message -> item {
                Text(
                    stringResource(message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } }
        }
    }
}

@Composable
private fun UploadField(
    label: String,
    value: String,
    placeholder: String,
    enabled: Boolean,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        UploadHint(label)
        val textColor = MaterialTheme.colorScheme.onSurface
        val cursor = SolidColor(MaterialTheme.colorScheme.primary)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri,
            ),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor),
            cursorBrush = cursor,
            decorationBox = { field ->
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) UploadHint(placeholder)
                    field()
                }
            },
        )
    }
}

@Composable
private fun UploadHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
