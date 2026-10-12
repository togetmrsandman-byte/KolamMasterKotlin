package com.kolammaster.app

import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable

@Composable
internal fun kolamStandardButtonColors() = ButtonDefaults.buttonColors(
    containerColor = KolamButtonYellow,
    contentColor = KolamButtonText,
    disabledContainerColor = KolamButtonYellow,
    disabledContentColor = KolamButtonText
)
