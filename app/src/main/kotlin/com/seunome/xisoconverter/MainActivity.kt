package com.seunome.xisoconverter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.ensureChannel(this)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    XisoAppScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XisoAppScreen() {
    val context = LocalContext.current
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Empacotar", "Extrair", "Como Usar")

    // Pede a permissão de notificação (Android 13+) assim que a tela abre,
    // para que as notificações de progresso/conclusão possam aparecer.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* concedida ou não: o app funciona dos dois jeitos */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Mandacaru — Conversor Xbox Clássico") })
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            TabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                when (selectedTabIndex) {
                    0 -> PackScreen()
                    1 -> UnpackScreen()
                    2 -> HowToUseScreen()
                }
            }
        }
    }
}

@Composable
fun PackScreen() {
    val context = LocalContext.current
    var folderUri by remember { mutableStateOf<Uri?>(null) }
    var folderName by remember { mutableStateOf<String?>(null) }
    var outputName by remember { mutableStateOf("MeuJogo.iso") }
    val state by ConversionStatus.state.collectAsState()
    val isProcessing = state is ServiceState.Running

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { /* alguns provedores não suportam; segue mesmo assim */ }
        }
        folderUri = uri
        folderName = uri?.let { SafFileOps.displayNameForTreeUri(context, it) }
        ConversionStatus.reset()
    }

    val saveIsoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destUri: Uri? ->
        val srcTree = folderUri
        if (destUri == null || srcTree == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                destUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) { /* segue mesmo assim */ }

        ConversionStatus.update(ServiceState.Running(-1, "Iniciando…", emptyList()))
        ConversionService.startPack(context, srcTree, destUri, outputName)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Selecione a pasta do jogo (a que contém o default.xbe na raiz).",
            style = MaterialTheme.typography.bodyMedium
        )

        Button(
            onClick = { folderLauncher.launch(null) },
            enabled = !isProcessing
        ) {
            Text("Selecionar Pasta do Jogo")
        }

        Text(
            text = "Pasta selecionada: ${folderName ?: "Nenhuma"}",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = outputName,
            onValueChange = { outputName = it },
            label = { Text("Nome do arquivo de saída (ex.: Halo.iso)") },
            enabled = !isProcessing,
            singleLine = true
        )

        Button(
            onClick = { saveIsoLauncher.launch(outputName) },
            enabled = folderUri != null && !isProcessing && outputName.isNotBlank()
        ) {
            Text("Converter para XISO (Pack)")
        }

        Text(
            "A conversão roda num serviço em primeiro plano: você pode sair do " +
                "app enquanto ela continua. Uma notificação avisa quando terminar.",
            style = MaterialTheme.typography.bodySmall
        )

        ProgressAndStatus(state)
    }
}

@Composable
fun UnpackScreen() {
    val context = LocalContext.current
    var isoUri by remember { mutableStateOf<Uri?>(null) }
    var isoName by remember { mutableStateOf<String?>(null) }
    val state by ConversionStatus.state.collectAsState()
    val isProcessing = state is ServiceState.Running

    val isoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { /* segue mesmo assim */ }
        }
        isoUri = uri
        isoName = uri?.let { SafFileOps.displayNameForFileUri(context, it) }
        ConversionStatus.reset()
    }

    val destFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { destTreeUri: Uri? ->
        val srcIso = isoUri
        if (destTreeUri == null || srcIso == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                destTreeUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) { /* segue mesmo assim */ }

        ConversionStatus.update(ServiceState.Running(-1, "Iniciando…", emptyList()))
        ConversionService.startUnpack(context, srcIso, destTreeUri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Selecione o arquivo .iso / .xiso do jogo.",
            style = MaterialTheme.typography.bodyMedium
        )

        Button(
            onClick = { isoLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
            enabled = !isProcessing
        ) {
            Text("Selecionar Arquivo .ISO / .XISO")
        }

        Text(
            text = "Arquivo selecionado: ${isoName ?: "Nenhum"}",
            style = MaterialTheme.typography.bodyMedium
        )

        Button(
            onClick = { destFolderLauncher.launch(null) },
            enabled = isoUri != null && !isProcessing
        ) {
            Text("Extrair Arquivos (Unpack)")
        }
        Text(
            "Ao tocar em Extrair, escolha a pasta onde os arquivos soltos (default.xbe, maps, etc.) serão salvos.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "A conversão roda num serviço em primeiro plano: você pode sair do " +
                "app enquanto ela continua. Uma notificação avisa quando terminar.",
            style = MaterialTheme.typography.bodySmall
        )

        ProgressAndStatus(state)
    }
}

/**
 * Barra de progresso real, texto de status, botão Cancelar e — em caso de
 * erro — uma seção detalhada com o log de passos até a falha (em vez de
 * só uma linha de texto), com opção de copiar tudo para a área de
 * transferência.
 */
@Composable
private fun ProgressAndStatus(state: ServiceState) {
    when (state) {
        is ServiceState.Idle -> {}

        is ServiceState.Running -> {
            var cancelRequested by remember(state::class) { mutableStateOf(false) }
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (state.percent in 0..100) {
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth(0.8f)
                    )
                    Text("${state.percent}%", style = MaterialTheme.typography.bodySmall)
                } else {
                    CircularProgressIndicator()
                }
                Text(state.message, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = {
                        cancelRequested = true
                        NativeXdvdfs.requestCancel()
                    },
                    enabled = !cancelRequested
                ) {
                    Text(if (cancelRequested) "Cancelando…" else "Cancelar")
                }
            }
        }

        is ServiceState.Success -> Text(
            state.message,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium
        )

        is ServiceState.Cancelled -> Text(
            "Operação cancelada.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )

        is ServiceState.Error -> ErrorDetails(state.message, state.log)
    }
}

/** Tela/seção de erro detalhada: mensagem final + log completo dos passos + copiar. */
@Composable
private fun ErrorDetails(message: String, log: List<LogEntry>) {
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Erro: $message",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Ocultar detalhes" else "Ver detalhes (${log.size} passos)")
            }
            TextButton(
                onClick = {
                    val fullLog = buildString {
                        appendLine("Erro: $message")
                        appendLine()
                        log.forEach { appendLine("[${it.formattedTime()}] ${it.message}") }
                    }
                    clipboard.setText(AnnotatedString(fullLog))
                }
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Copiar log")
            }
        }

        if (expanded) {
            Surface(
                tonalElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
            ) {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    items(log) { entry ->
                        Text(
                            "[${entry.formattedTime()}] ${entry.message}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (log.isEmpty()) {
                        item {
                            Text(
                                "Nenhum passo registrado antes da falha.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}
