package io.github.mangi.eta.ui.screens.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.composables.icons.lucide.R as LucideR
import io.github.mangi.eta.R
import io.github.mangi.eta.core.DiagnosticLogStore
import io.github.mangi.eta.ui.components.MiuixPageBottomSpacer
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DiagnosticsScreen(
    context: Context,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    fun shareReport() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    DiagnosticLogStore.buildReport(context)
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    report,
                )
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, context.getString(R.string.diagnostics_share_text))
                    clipData = ClipData.newRawUri("Eta diagnostics", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(share, context.getString(R.string.diagnostics_share_title)))
            } catch (throwable: Throwable) {
                Toast.makeText(
                    context,
                    context.getString(R.string.diagnostics_failed, throwable.javaClass.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                busy = false
            }
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.diagnostics_title),
        onBack = onBack,
    ) {
        item(key = "diagnostics-info") {
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                BasicComponent(
                    title = stringResource(R.string.diagnostics_info_title),
                    summary = stringResource(R.string.diagnostics_info_summary),
                )
            }
        }
        item(key = "diagnostics-actions-title") {
            SmallTitle(stringResource(R.string.diagnostics_actions))
        }
        item(key = "diagnostics-actions") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.diagnostics_share),
                    summary = if (busy) {
                        stringResource(R.string.diagnostics_working)
                    } else {
                        stringResource(R.string.diagnostics_share_summary)
                    },
                    enabled = !busy,
                    startAction = {
                        DiagnosticsIcon(
                            icon = LucideR.drawable.lucide_ic_file_text,
                            loading = busy,
                        )
                    },
                    onClick = ::shareReport,
                )
                top.yukonga.miuix.kmp.basic.HorizontalDivider()
                ArrowPreference(
                    title = stringResource(R.string.diagnostics_clear),
                    summary = stringResource(R.string.diagnostics_clear_summary),
                    enabled = !busy,
                    startAction = {
                        DiagnosticsIcon(
                            icon = LucideR.drawable.lucide_ic_trash_2,
                            loading = false,
                        )
                    },
                    onClick = {
                        DiagnosticLogStore.clear()
                        Toast.makeText(context, R.string.diagnostics_cleared, Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
        item(key = "diagnostics-bottom-spacer") {
            MiuixPageBottomSpacer()
        }
    }
}

@Composable
private fun DiagnosticsIcon(icon: Int, loading: Boolean) {
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .size(36.dp)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            InfiniteProgressIndicator(size = 20.dp)
        } else {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                tint = MiuixTheme.colorScheme.onBackground,
            )
        }
    }
}
