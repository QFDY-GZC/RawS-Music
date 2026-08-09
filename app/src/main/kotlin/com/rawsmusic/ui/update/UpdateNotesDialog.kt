package com.rawsmusic.ui.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rawsmusic.R
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val QQ_GROUP_URL = "https://qm.qq.com/q/bOvqTQPABi"

@Composable
fun UpdateNotesDialog(
    versionName: String,
    onClose: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val notes = stringArrayResource(R.array.update_notes)
    val developerMessage = remember {
        context.resources.getStringArray(R.array.update_developer_quotes).randomOrNull().orEmpty()
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .heightIn(max = 680.dp),
            shape = RoundedCornerShape(32.dp),
            color = scheme.surface,
            contentColor = scheme.onSurface,
            shadowElevation = 18.dp
        ) {
            Column(modifier = Modifier.padding(top = 26.dp)) {
                Text(
                    text = stringResource(R.string.update_dialog_title, versionName),
                    modifier = Modifier.padding(horizontal = 24.dp),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.update_dialog_time),
                    modifier = Modifier.padding(start = 24.dp, top = 6.dp, end = 24.dp),
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = 13.sp
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(top = 18.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 24.dp,
                        end = 24.dp,
                        bottom = 18.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(notes) { note ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text("•", color = scheme.primary, fontSize = 16.sp)
                            Text(
                                text = note,
                                modifier = Modifier.padding(start = 10.dp),
                                color = scheme.onSurface,
                                fontSize = 14.sp,
                                lineHeight = 21.sp
                            )
                        }
                    }
                    item {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.update_developer_message_title),
                            color = scheme.primary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = developerMessage,
                            modifier = Modifier.padding(top = 8.dp),
                            color = scheme.onSurfaceVariantSummary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.update_join_qq_group),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { uriHandler.openUri(QQ_GROUP_URL) }
                            .padding(vertical = 12.dp),
                        color = scheme.primary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.update_close))
                    }
                }
            }
        }
    }
}
