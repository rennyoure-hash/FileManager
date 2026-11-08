package com.example.feature.browser.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ConflictAmber
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun PermissionBanner(
    onRequestPermission: () -> Unit,
    onRequestSafFallback: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isAndroid11Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
            .testTag("permission_banner")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.FolderShared,
                    contentDescription = null,
                    tint = ConflictAmber,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isAndroid11Plus) "All Files Access Required" else "Storage Permission Required",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isAndroid11Plus) {
                    "To browse and manage files across internal storage and SD cards, MT Explorer needs All Files Access. You can also pick a specific folder using the Storage Access Framework (SAF) fallback."
                } else {
                    "To browse and manage storage, please grant storage permissions. You can also grant access to specific folders via Storage Access Framework (SAF)."
                },
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("btn_grant_storage_permission")
                ) {
                    Text(
                        text = if (isAndroid11Plus) "Grant All Files" else "Grant Storage",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                    onClick = onRequestSafFallback,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("btn_saf_fallback")
                ) {
                    Text(
                        text = "SAF Fallback",
                        color = CyanAccent,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
