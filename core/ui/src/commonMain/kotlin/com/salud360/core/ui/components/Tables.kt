package com.salud360.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.salud360.core.ui.theme.Salud360Colors

data class TableColumn(val title: String, val width: Dp = 140.dp)

/**
 * Tabla con cabecera degradada (equivalente a `.rodri_th` + DataTables).
 * Cada fila es una lista de celdas; se puede pasar `trailing` para botones de acción.
 */
@Composable
fun <T> DataTable(
    columns: List<TableColumn>,
    rows: List<T>,
    cells: (T) -> List<String>,
    modifier: Modifier = Modifier,
    onRowClick: ((T) -> Unit)? = null,
    highlight: (T) -> Boolean = { false },
    trailing: (@Composable (T) -> Unit)? = null,
    emptyText: String = "No hay información",
    maxHeight: Dp = 600.dp,
) {
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .horizontalScroll(scroll),
    ) {
        Row(Modifier.background(Salud360Colors.BrandGradient).padding(vertical = 10.dp, horizontal = 8.dp)) {
            columns.forEach { col ->
                Text(
                    col.title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(col.width).padding(horizontal = 6.dp),
                )
            }
            if (trailing != null) Box(Modifier.width(120.dp))
        }
        if (rows.isEmpty()) {
            Text(
                emptyText,
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.heightIn(max = maxHeight)) {
                itemsIndexed(rows) { index, row ->
                    val bg = when {
                        highlight(row) -> Salud360Colors.HighlightRow.copy(alpha = 0.55f)
                        index % 2 == 1 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        else -> Color.Transparent
                    }
                    Row(
                        modifier = Modifier
                            .background(bg)
                            .then(if (onRowClick != null) Modifier.clickable { onRowClick(row) } else Modifier)
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val values = cells(row)
                        columns.forEachIndexed { i, col ->
                            Text(
                                values.getOrElse(i) { "" },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(col.width).padding(horizontal = 6.dp),
                            )
                        }
                        if (trailing != null) Box(Modifier.width(120.dp)) { trailing(row) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                }
            }
        }
    }
}

