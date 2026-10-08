package com.kolammaster.app.notifications

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

internal data class ForegroundAnnouncementAlert(
    val announcementId: String?,
    val sequence: Long
)

internal object ForegroundAnnouncementAlertStore {
    private const val PREFERENCES_NAME = "kolam_master_foreground_announcements"
    private const val ANNOUNCEMENT_ID_KEY = "announcement_id"
    private const val SEQUENCE_KEY = "sequence"

    fun record(context: Context, announcementId: String?) {
        val preferences = preferences(context)
        check(preferences.edit()
            .putString(ANNOUNCEMENT_ID_KEY, announcementId.orEmpty())
            .putLong(SEQUENCE_KEY, preferences.getLong(SEQUENCE_KEY, 0L) + 1L)
            .commit()
        ) { "Could not persist the foreground announcement alert." }
    }

    fun pending(context: Context): ForegroundAnnouncementAlert? {
        val preferences = preferences(context)
        if (!preferences.contains(ANNOUNCEMENT_ID_KEY)) return null
        return ForegroundAnnouncementAlert(
            announcementId = preferences.getString(ANNOUNCEMENT_ID_KEY, "")
                ?.takeIf(String::isNotBlank),
            sequence = preferences.getLong(SEQUENCE_KEY, 0L)
        )
    }

    fun dismiss(context: Context, alert: ForegroundAnnouncementAlert) {
        val preferences = preferences(context)
        if (preferences.getLong(SEQUENCE_KEY, 0L) == alert.sequence) {
            check(preferences.edit().remove(ANNOUNCEMENT_ID_KEY).commit()) {
                "Could not dismiss the foreground announcement alert."
            }
        }
    }

    fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
}

@Composable
internal fun AnnouncementForegroundAlertDialog(
    announcementId: String?,
    onOpen: (String?) -> Unit,
    onLater: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("A new announcement has been received") },
        text = { Text("Would you like to open it now?") },
        confirmButton = {
            TextButton(onClick = { onOpen(announcementId) }) { Text("Open") }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text("Later") }
        }
    )
}
