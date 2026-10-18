package com.smnexstudio.panelglass

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.smnexstudio.panelglass.core.data.update.AppUpdates
import com.smnexstudio.panelglass.core.data.update.UpdateState
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.PrimaryPill
import com.smnexstudio.panelglass.core.ui.StickerDialog
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.Tokens
import java.io.File
import com.smnexstudio.panelglass.core.ui.R as UiR

/**
 * The update flow over whatever screen is open: offer (Update / Later), download progress
 * (Cancel), install, and the results of a check from Settings. Nothing shows while it is [UpdateState.Idle].
 */
@Composable
fun UpdateHost(state: UpdateState, updates: AppUpdates) {
    val context = LocalContext.current
    when (state) {
        UpdateState.Idle -> Unit
        UpdateState.Checking -> Message(stringResource(UiR.string.update_checking), null, updates::cancel, stringResource(UiR.string.action_cancel))
        is UpdateState.UpToDate -> Message(stringResource(UiR.string.update_up_to_date, state.current), null, updates::dismiss, stringResource(UiR.string.action_close))
        is UpdateState.Available -> StickerDialog(updates::dismiss) {
            Title(stringResource(UiR.string.update_available_title))
            Body(stringResource(UiR.string.update_available_message, state.release.version, state.current))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextAction(stringResource(UiR.string.update_later), Tokens.InkSoft, updates::dismiss)
                Spacer(Modifier.width(8.dp))
                PrimaryPill(stringResource(UiR.string.update_action).uppercase(), letterSpaced = true, onClick = updates::download)
            }
        }
        is UpdateState.Downloading -> StickerDialog(onDismiss = {}) {
            Title(stringResource(UiR.string.update_downloading))
            Spacer(Modifier.height(12.dp))
            if (state.total > 0) LinearProgressIndicator(progress = { (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Tokens.Ink)
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Tokens.Ink)
            Body(stringResource(UiR.string.update_progress, Formatter.formatShortFileSize(context, state.bytes), Formatter.formatShortFileSize(context, state.total)))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextAction(stringResource(UiR.string.action_cancel), Tokens.InkSoft, updates::cancel)
            }
        }
        is UpdateState.Ready -> StickerDialog(updates::dismiss) {
            Title(stringResource(UiR.string.update_ready_title))
            Body(stringResource(UiR.string.update_ready_message, state.release.version))
            Body(stringResource(UiR.string.update_install_permission))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextAction(stringResource(UiR.string.update_later), Tokens.InkSoft, updates::dismiss)
                Spacer(Modifier.width(8.dp))
                PrimaryPill(stringResource(UiR.string.update_install).uppercase(), letterSpaced = true, onClick = { (context as? Activity)?.let { install(it, state.apk) } })
            }
        }
        is UpdateState.Failed -> Message(
            stringResource(if (state.release == null) UiR.string.update_failed_check else UiR.string.update_failed_download),
            if (state.release != null) ({ updates.download() } to stringResource(UiR.string.action_retry)) else null,
            updates::dismiss, stringResource(UiR.string.action_close),
        )
    }
}

/**
 * Hands the verified APK to Android's installer. The first time, Android needs the user to allow Panelglass to
 * install apps: its settings page opens, and Install is tapped again on return.
 */
private fun install(activity: Activity, apk: File) {
    if (!activity.packageManager.canRequestPackageInstalls()) {
        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
        return
    }
    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
    activity.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
}

@Composable
private fun Message(text: String, action: Pair<() -> Unit, String>?, onClose: () -> Unit, close: String) = StickerDialog(onClose) {
    Body(text)
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextAction(close, Tokens.InkSoft, onClose)
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            PrimaryPill(action.second.uppercase(), letterSpaced = true, onClick = action.first)
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 20.sp, color = Tokens.Ink)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = Tokens.InkSoft, modifier = Modifier.fillMaxWidth())
