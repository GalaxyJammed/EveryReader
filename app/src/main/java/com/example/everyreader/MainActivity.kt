package com.example.everyreader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.everyreader.ui.theme.EveryReaderTheme
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.rememberLazyListState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val systemDark = isSystemInDarkTheme()
            var darkMode by rememberSaveable { mutableStateOf(systemDark) }
            EveryReaderTheme(darkTheme = darkMode) {
                EveryReaderHome(
                    darkMode = darkMode,
                    onDarkModeChange = { darkMode = it }
                )
            }
        }
    }
}

enum class FileType(
    val label: String,
    val extensions: List<String>,
    val mimeTypes: Array<String>,
    val accent: Color?
) {
    TEXT("Text", listOf("txt"), arrayOf("text/plain"), null),
    PDF("PDF", listOf("pdf"), arrayOf("application/pdf"), Color(0xFFD32F2F)),
    EXCEL(
        "Excel",
        listOf("xls", "xlsx", "xlsm", "xltx", "xltm", "ods", "fods", "csv", "tsv", "psv", "ssv"),
        arrayOf(
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.oasis.opendocument.spreadsheet",
            "text/csv",
            "text/tab-separated-values"
        ),
        Color(0xFF2E7D32)
    ),
    POWERPOINT(
        "PowerPoint",
        listOf("ppt", "pptx", "pptm", "potx", "potm", "ppsx", "ppsm", "odp", "otp"),
        arrayOf(
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.presentation"
        ),
        Color(0xFFEF6C00)
    ),
    WORD(
        "Word",
        listOf("docx", "docm", "dotx", "dotm", "odt", "ott", "rtf", "mht", "mhtml"),
        arrayOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/rtf",
            "application/vnd.oasis.opendocument.text",
            "multipart/related"
        ),
        Color(0xFF1565C0)
    ),
    EBOOK(
        "eBooks",
        listOf("epub", "fb2"),
        arrayOf("application/epub+zip", "application/x-fictionbook+xml"),
        Color(0xFF00838F)
    );

    val displayExtensions: String
        get() = extensions.joinToString(", ") { ".$it" }
}

enum class SortOrder(val label: String, val comparator: Comparator<File>) {
    CREATED_NEW("Newest to Oldest", Comparator { a, b -> createdMillis(b).compareTo(createdMillis(a)) }),
    CREATED_OLD("Oldest to Newest", Comparator { a, b -> createdMillis(a).compareTo(createdMillis(b)) }),
    UPDATED_NEW("Newest to Oldest", Comparator { a, b -> b.lastModified().compareTo(a.lastModified()) }),
    UPDATED_OLD("Oldest to Newest", Comparator { a, b -> a.lastModified().compareTo(b.lastModified()) }),
    NAME_ASC("A to Z", Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) }),
    NAME_DESC("Z to A", Comparator { a, b -> b.name.compareTo(a.name, ignoreCase = true) }),
    SIZE_BIG("Biggest to Smallest", Comparator { a, b -> b.length().compareTo(a.length()) }),
    SIZE_SMALL("Smallest to Biggest", Comparator { a, b -> a.length().compareTo(b.length()) })
}

val sortGroups: List<Pair<String, List<SortOrder>>> = listOf(
    "Date Created" to listOf(SortOrder.CREATED_NEW, SortOrder.CREATED_OLD),
    "Date Updated" to listOf(SortOrder.UPDATED_NEW, SortOrder.UPDATED_OLD),
    "Alphabetical" to listOf(SortOrder.NAME_ASC, SortOrder.NAME_DESC),
    "Content Size" to listOf(SortOrder.SIZE_BIG, SortOrder.SIZE_SMALL)
)

fun createdMillis(file: File): Long =
    runCatching {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            .creationTime()
            .toMillis()
    }.getOrDefault(file.lastModified())

fun hasFileAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

fun scanFiles(type: FileType): List<File> =
    Environment.getExternalStorageDirectory()
        .walkTopDown()
        .onEnter { dir -> !dir.name.startsWith(".") && dir.name != "Android" }
        .filter { it.isFile && it.extension.lowercase() in type.extensions }
        .toList()

fun pathLabel(file: File): String {
    val root = Environment.getExternalStorageDirectory().absolutePath
    val parts = file.parentFile?.absolutePath.orEmpty()
        .removePrefix(root)
        .split("/")
        .filter { it.isNotEmpty() }
    return (listOf("Internal Storage") + parts).joinToString(" > ")
}

@Composable
fun EveryReaderHome(darkMode: Boolean, onDarkModeChange: (Boolean) -> Unit) {
    var openFile by remember { mutableStateOf<File?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf(FileType.PDF) }
    var sortOrder by rememberSaveable { mutableStateOf(SortOrder.UPDATED_NEW) }

    BackHandler(enabled = openFile != null || settingsOpen) {
        if (openFile != null) openFile = null else settingsOpen = false
    }

    val current = openFile
    when {
        current != null -> ReaderScreen(file = current, onBack = { openFile = null })
        settingsOpen -> SettingsScreen(
            darkMode = darkMode,
            onDarkModeChange = onDarkModeChange,
            onBack = { settingsOpen = false }
        )
        else -> FileListScreen(
            selected = category,
            onSelect = { category = it },
            sortOrder = sortOrder,
            onSortChange = { sortOrder = it },
            onOpenSettings = { settingsOpen = true },
            onOpen = { openFile = it }
        )
    }
}

@Composable
fun SortMenuContent(current: SortOrder, onSelect: (SortOrder) -> Unit) {
    Column(
        modifier = Modifier
            .width(260.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Sort By",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        sortGroups.forEachIndexed { groupIndex, (title, options) ->
            if (groupIndex == 0) {
                Spacer(modifier = Modifier.height(8.dp))
            } else {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            options.forEach { order ->
                val isOn = order == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(order) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isOn) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (isOn) "Selected" else null,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Text(
                        text = order.label,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@Composable
fun FileListScreen(
    selected: FileType,
    onSelect: (FileType) -> Unit,
    sortOrder: SortOrder,
    onSortChange: (SortOrder) -> Unit,
    onOpenSettings: () -> Unit,
    onOpen: (File) -> Unit
) {
    val context = LocalContext.current
    var categoryMenuOpen by remember { mutableStateOf(false) }
    var filterMenuOpen by remember { mutableStateOf(false) }
    var selectionMenuOpen by remember { mutableStateOf(false) }
    var rowMenuFor by remember { mutableStateOf<String?>(null) }
    var singleDelete by remember { mutableStateOf<File?>(null) }
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var hasAccess by remember { mutableStateOf(hasFileAccess(context)) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }

    val sortedFiles = remember(files, sortOrder) { files.sortedWith(sortOrder.comparator) }
    val listState = rememberLazyListState()
    LaunchedEffect(sortOrder, selected) {
        listState.scrollToItem(0)
    }

    val selecting = chosen.isNotEmpty()
    val exitSelection: () -> Unit = { chosen = emptySet() }

    BackHandler(enabled = selecting) { exitSelection() }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { hasAccess = hasFileAccess(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasAccess = granted }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> pickedName = uri?.lastPathSegment }

    val requestAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            settingsLauncher.launch(intent)
        } else {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasAccess) requestAccess()
    }

    LaunchedEffect(selected, hasAccess, refresh) {
        files = if (hasAccess) {
            withContext(Dispatchers.IO) { scanFiles(selected) }
        } else {
            emptyList()
        }
    }

    val baseBackground = MaterialTheme.colorScheme.background
    val isDark = baseBackground.luminance() < 0.5f
    val targetBackground = selected.accent?.let { lerp(baseBackground, it, if (isDark) 0.35f else 0.12f) } ?: baseBackground
    val background by animateColorAsState(targetValue = targetBackground, label = "background")
    val pillOutline = if (isDark) Color(0x33FFFFFF) else Color(0x33000000)

    Scaffold(containerColor = background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ShieldCard {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = { if (selecting) exitSelection() else onOpenSettings() },
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Icon(
                            imageVector = if (selecting) Icons.Default.Close else Icons.Default.Settings,
                            contentDescription = if (selecting) "Cancel selection" else "Settings"
                        )
                    }

                    Box {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .border(1.dp, pillOutline, RoundedCornerShape(50))
                                .clickable { categoryMenuOpen = true }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selected.label,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Change category"
                            )
                        }
                        DropdownMenu(
                            expanded = categoryMenuOpen,
                            onDismissRequest = { categoryMenuOpen = false }
                        ) {
                            FileType.entries.forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.label) },
                                    leadingIcon = {
                                        if (type == selected) Icon(Icons.Default.Check, contentDescription = null)
                                    },
                                    onClick = {
                                        onSelect(type)
                                        chosen = emptySet()
                                        categoryMenuOpen = false
                                    }
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                        if (selecting) {
                            IconButton(onClick = { selectionMenuOpen = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More actions"
                                )
                            }
                            DropdownMenu(
                                expanded = selectionMenuOpen,
                                onDismissRequest = { selectionMenuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Share Multiple") },
                                    onClick = {
                                        selectionMenuOpen = false
                                        shareFiles(context, sortedFiles.filter { it.absolutePath in chosen })
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete All") },
                                    onClick = {
                                        selectionMenuOpen = false
                                        confirmDelete = true
                                    }
                                )
                            }
                        } else {
                            IconButton(onClick = { filterMenuOpen = true }) {
                                Icon(
                                    imageVector = Icons.Default.FilterList,
                                    contentDescription = "Sort by"
                                )
                            }
                            DropdownMenu(
                                expanded = filterMenuOpen,
                                onDismissRequest = { filterMenuOpen = false }
                            ) {
                                SortMenuContent(
                                    current = sortOrder,
                                    onSelect = { order ->
                                        onSortChange(order)
                                        filterMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            SectionTitle("Scanned ${selected.label} files")

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    !hasAccess -> Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "All files access needed to scan",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(onClick = requestAccess) {
                            Text("Grant access")
                        }
                    }
                    sortedFiles.isEmpty() -> Text(
                        text = "No ${selected.label} files found",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(sortedFiles, key = { it.absolutePath }) { file ->
                            val isChosen = file.absolutePath in chosen
                            ShieldCard(
                                onClick = {
                                    if (selecting) {
                                        chosen = if (isChosen) chosen - file.absolutePath
                                        else chosen + file.absolutePath
                                    } else {
                                        onOpen(file)
                                    }
                                },
                                onLongClick = {
                                    if (!selecting) chosen = chosen + file.absolutePath
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        start = 16.dp,
                                        top = 16.dp,
                                        bottom = 16.dp,
                                        end = 4.dp
                                    ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (selecting) {
                                        Icon(
                                            imageVector = if (isChosen) Icons.Default.CheckCircle
                                            else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = if (isChosen) "Selected" else "Not selected",
                                            modifier = Modifier.padding(end = 12.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = file.name,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        HorizontalDivider(
                                            modifier = Modifier.padding(vertical = 8.dp),
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                        )
                                        Text(
                                            text = pathLabel(file),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (!selecting) {
                                        Box {
                                            IconButton(onClick = { rowMenuFor = file.absolutePath }) {
                                                Icon(
                                                    imageVector = Icons.Default.MoreVert,
                                                    contentDescription = "More"
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = rowMenuFor == file.absolutePath,
                                                onDismissRequest = { rowMenuFor = null }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("Share") },
                                                    onClick = {
                                                        rowMenuFor = null
                                                        shareFiles(context, listOf(file))
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Delete") },
                                                    onClick = {
                                                        rowMenuFor = null
                                                        singleDelete = file
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (hasAccess && sortedFiles.isNotEmpty()) {
                    ScrollIndicator { listState.scrollMetrics() }
                }
            }

            ShieldCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedButton(
                        onClick = { pickerLauncher.launch(selected.mimeTypes) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Pick another ${selected.label} file")
                    }
                    pickedName?.let {
                        Text(
                            text = it,
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val targets = sortedFiles.filter { it.absolutePath in chosen }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete files") },
            text = { Text("Are you sure you want to delete ${targets.size} file(s)?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val n = deleteFiles(context, targets)
                    Toast.makeText(context, "Deleted $n file(s)", Toast.LENGTH_SHORT).show()
                    chosen = emptySet()
                    refresh++
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }

    singleDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { singleDelete = null },
            title = { Text("Delete file") },
            text = { Text("Are you sure you want to delete this file?\n\n${target.name}") },
            confirmButton = {
                TextButton(onClick = {
                    singleDelete = null
                    val n = deleteFiles(context, listOf(target))
                    Toast.makeText(
                        context,
                        if (n > 0) "Deleted ${target.name}" else "Could not delete",
                        Toast.LENGTH_SHORT
                    ).show()
                    refresh++
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { singleDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun SectionTitle(title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        HorizontalDivider()
    }
}

@Composable
fun ShieldCard(
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val outline = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color(0x33FFFFFF)
    } else {
        Color(0x33000000)
    }
    val clickModifier = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick
        )
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, outline, RoundedCornerShape(16.dp))
            .then(clickModifier)
    ) {
        content()
    }
}