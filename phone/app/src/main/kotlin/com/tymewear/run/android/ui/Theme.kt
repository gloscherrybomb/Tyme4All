package com.tymewear.run.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

@Composable
fun Tyme4AllTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}

/** Karoo zone palette. Index 0 is "no zone". */
val ZONE_COLORS = longArrayOf(0xFF424242, 0xFF4DB6AC, 0xFF0277BD, 0xFFF57F17, 0xFFEF6C00, 0xFFC62828)
