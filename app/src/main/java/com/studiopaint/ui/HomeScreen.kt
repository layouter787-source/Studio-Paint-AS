package com.studiopaint.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.studiopaint.format.AspkIO
import com.studiopaint.format.AspkProject
import com.studiopaint.storage.ProjectMeta
import com.studiopaint.storage.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SIZE_PRESETS = listOf(
    "Quadrado 2048" to (2048 to 2048), "Full HD 1920×1080" to (1920 to 1080), "Vertical 1080×1920" to (1080 to 1920),
    "A4 150dpi 1240×1754" to (1240 to 1754), "HD 1280×720" to (1280 to 720),
)

@Composable
fun HomeScreen(store: ProjectStore, incoming: MutableState<Uri?>, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val projects = remember(refresh) { store.list() }
    var showNew by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProjectMeta?>(null) }
    var pendingImport by remember { mutableStateOf<AspkProject?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    fun importUri(uri: Uri) = scope.launch {
        try {
            val p = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { AspkIO.read(it) } }
            if (p.frameIds.size > 1) pendingImport = p
            else { withContext(Dispatchers.IO) { store.importProject(p, 0) }; refresh++ }
        } catch (t: Throwable) { status = "Importação falhou: ${t.message}" }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) importUri(u) }
    LaunchedEffect(incoming.value) { incoming.value?.let { importUri(it); incoming.value = null } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Studio Paint AS", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FileOpen, null); Text(" Importar .aspk") }
        }
        status?.let { Text(it, color = Color(0xFFFF8A80)) }
        LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).clickable { showNew = true }, contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Add, "Novo"); Text("Novo projeto") }
                }
            }
            items(projects, key = { it.id }) { m ->
                Column(Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).clickable { onOpen(m.id) }.padding(8.dp)) {
                    val thumb = remember(m.updatedAt) { runCatching { BitmapFactory.decodeFile(store.thumbFile(m.id).path)?.asImageBitmap() }.getOrNull() }
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF2A2F38), RoundedCornerShape(10.dp))) {
                        if (thumb != null) Image(thumb, m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                    Text(m.name, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${m.width}×${m.height}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                        IconButton(onClick = { scope.launch { withContext(Dispatchers.IO) { store.duplicate(m.id) }; refresh++ } }) { Icon(Icons.Default.ContentCopy, "Duplicar") }
                        IconButton(onClick = { deleting = m }) { Icon(Icons.Default.Delete, "Apagar") }
                    }
                }
            }
        }
    }

    if (showNew) NewProjectDialog({ showNew = false }) { n, w, h ->
        showNew = false
        scope.launch { val m = withContext(Dispatchers.IO) { store.create(n, w, h) }; refresh++; onOpen(m.id) }
    }
    deleting?.let { m ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Apagar projeto?") },
            text = { Text("\"${m.name}\" será apagado de forma permanente.") },
            confirmButton = { TextButton(onClick = { store.delete(m.id); deleting = null; refresh++ }) { Text("Apagar") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } })
    }
    pendingImport?.let { p ->
        AlertDialog(onDismissRequest = { pendingImport = null }, title = { Text("Que frame editar?") },
            text = {
                Column {
                    Text("O projeto tem ${p.frameIds.size} frames. Os outros ficam intactos ao guardar.")
                    p.frameIds.indices.take(40).forEach { i ->
                        TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { store.importProject(p, i) }; pendingImport = null; refresh++ } }) { Text("Frame ${i + 1}") }
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancelar") } })
    }
}

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, Int, Int) -> Unit) {
    var name by remember { mutableStateOf("Meu desenho") }
    var w by remember { mutableStateOf("2048") }
    var h by remember { mutableStateOf("2048") }
    val wi = w.toIntOrNull(); val hi = h.toIntOrNull()
    val valid = name.isNotBlank() && wi != null && hi != null && wi in 64..4096 && hi in 64..4096
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Novo projeto") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
            SIZE_PRESETS.forEach { (label, s) -> TextButton(onClick = { w = s.first.toString(); h = s.second.toString() }) { Text(label) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(w, { w = it.filter(Char::isDigit) }, label = { Text("Largura") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(h, { h = it.filter(Char::isDigit) }, label = { Text("Altura") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            if (wi != null && hi != null) Text("~${wi.toLong() * hi * 4 / (1024 * 1024)} MB por camada (limite 64–4096 px)", style = MaterialTheme.typography.labelSmall)
        }
    }, confirmButton = { Button(enabled = valid, onClick = { onCreate(name.trim(), wi!!, hi!!) }) { Text("Criar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}
