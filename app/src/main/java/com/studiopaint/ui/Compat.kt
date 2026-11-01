package com.studiopaint.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.height as layoutHeight
import androidx.compose.foundation.layout.widthIn as layoutWidthIn

// Atalhos do pacote para os modificadores de layout usados em EditorScreen.kt.
fun Modifier.height(h: Dp): Modifier = layoutHeight(h)
fun Modifier.widthIn(max: Dp): Modifier = layoutWidthIn(max = max)
