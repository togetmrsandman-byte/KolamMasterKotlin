package com.kolammaster.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kolammaster.app.withAppLanguage

private val publishSheetGold = KolamBronze
private val publishSheetSurface = KolamSurface

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun GalleryPublishPickerSheet(
    languageIdentifier: String,
    onDismiss: () -> Unit,
    onProceed: () -> Unit
) {
    val localizedContext = LocalContext.current.withAppLanguage(languageIdentifier)
    val congratulations = localizedContext.getString(R.string.congratulations)
    val kolamComplete = localizedContext.getString(R.string.your_kolam_is_complete)
    val shareKolam = localizedContext.getString(R.string.share_kolam_community)
    val continueFinishedKolam = localizedContext.getString(R.string.continue_finished_kolam)
    val proceed = localizedContext.getString(R.string.proceed)
    val cancel = localizedContext.getString(R.string.cancel)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = publishSheetSurface,
        scrimColor = Color.Black.copy(alpha = 0.62f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = congratulations,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = publishSheetGold
            )
            Text(
                text = kolamComplete,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = KolamText
            )
            Text(
                text = shareKolam,
                style = MaterialTheme.typography.bodyLarge,
                color = KolamText
            )
            Text(
                text = continueFinishedKolam,
                style = MaterialTheme.typography.bodyMedium,
                color = KolamMutedText
            )
            Button(
                onClick = onProceed,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KolamButtonYellow,
                    contentColor = KolamButtonText
                )
            ) {
                Text(proceed)
            }
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = kolamStandardButtonColors()
            ) {
                Text(cancel)
            }
        }
    }
}
