package com.example.kolammasterkotlin

import android.util.Base64

internal object KmpSecret {
    private val encodedParts = arrayOf(
        "S01fMjAyNl8=",
        "UnVuWW91Q2xldmVy",
        "Qm95UnVuQW5k",
        "UmVtZW1iZXJPbmU="
    )

    fun value(): String = buildString {
        encodedParts.forEach { part ->
            append(String(Base64.decode(part, Base64.NO_WRAP), Charsets.UTF_8))
        }
    }
}
