package com.rawsmusic.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SpectrumDetailCapsule(label: String, expanded: Boolean, onClick: () -> Unit) {
    val colors = spectrumUiColors()
    val shape = RoundedCornerShape(50)
    Row(Modifier.clip(shape).background(if (expanded) colors.accent.copy(alpha = 0.1f) else colors.surface)
        .border(1.dp, if (expanded) colors.accent.copy(alpha = 0.4f) else colors.line, shape)
        .semantics { selected = expanded }.clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f, fill = false), color = if (expanded) colors.accent else colors.text,
            fontSize = 12.sp)
        Spacer(Modifier.width(3.dp))
        Icon(Icons.Rounded.KeyboardArrowDown, null,
            Modifier.size(16.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f },
            tint = colors.accent)
    }
}
