package com.seunome.xisoconverter

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Uma linha do log detalhado da conversão (usado na tela de erro/detalhes). */
data class LogEntry(val timeMillis: Long, val message: String) {
    fun formattedTime(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(timeMillis)
}

/** Estado observável da conversão em andamento (ou do último resultado). */
sealed class ServiceState {
    object Idle : ServiceState()
    data class Running(val percent: Int, val message: String, val log: List<LogEntry>) : ServiceState()
    data class Success(val message: String, val log: List<LogEntry>) : ServiceState()
    data class Cancelled(val log: List<LogEntry>) : ServiceState()
    data class Error(val message: String, val log: List<LogEntry>) : ServiceState()
}

/**
 * Ponto único (compartilhado entre o serviço e a Activity, já que rodam no
 * mesmo processo) de onde a UI observa o andamento da conversão. Assim a
 * tela sempre mostra o estado real do [ConversionService], mesmo que a
 * Activity seja recriada enquanto a conversão continua em segundo plano.
 */
object ConversionStatus {
    private val _state = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    fun update(newState: ServiceState) {
        _state.value = newState
    }

    fun reset() {
        _state.value = ServiceState.Idle
    }
}

/**
 * Faz o trabalho pesado (cópia SAF ⇄ staging + chamada nativa ao
 * xdvdfs-core) dentro de um serviço em primeiro plano, para que a
 * conversão continue mesmo se o usuário sair do app. Mostra uma
 * notificação de progresso enquanto roda, e uma notificação final
 * ("processo concluído", cancelado ou com erro) ao terminar.
 */
class ConversionService : LifecycleService() {

    companion object {
        private const val TAG = "Mandacaru"

        private const val ACTION_START_PACK = "com.seunome.xisoconverter.action.START_PACK"
        private const val ACTION_START_UNPACK = "com.seunome.xisoconverter.action.START_UNPACK"

        private const val EXTRA_SOURCE_TREE_URI = "sourceTreeUri"
        private const val EXTRA_DEST_ISO_URI = "destIsoUri"
        private const val EXTRA_OUTPUT_NAME = "outputName"
        private const val EXTRA_ISO_URI = "isoUri"
        private const val EXTRA_DEST_TREE_URI = "destTreeUri"

        fun startPack(context: Context, sourceTreeUri: Uri, destIsoUri: Uri, outputName: String) {
            val intent = Intent(context, ConversionService::class.java).apply {
                action = ACTION_START_PACK
                putExtra(EXTRA_SOURCE_TREE_URI, sourceTreeUri)
                putExtra(EXTRA_DEST_ISO_URI, destIsoUri)
                putExtra(EXTRA_OUTPUT_NAME, outputName)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startUnpack(context: Context, isoUri: Uri, destTreeUri: Uri) {
            val intent = Intent(context, ConversionService::class.java).apply {
                action = ACTION_START_UNPACK
                putExtra(EXTRA_ISO_URI, isoUri)
                putExtra(EXTRA_DEST_TREE_URI, destTreeUri)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_PACK -> {
                val sourceTreeUri = IntentCompat.getParcelableExtra(intent, EXTRA_SOURCE_TREE_URI, Uri::class.java)
                val destIsoUri = IntentCompat.getParcelableExtra(intent, EXTRA_DEST_ISO_URI, Uri::class.java)
                val outputName = intent.getStringExtra(EXTRA_OUTPUT_NAME) ?: "saida.iso"

                if (sourceTreeUri == null || destIsoUri == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(
                    NotificationHelper.PROGRESS_NOTIFICATION_ID,
                    NotificationHelper.buildProgressNotification(this, "Empacotando…", "Iniciando…", -1)
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    runPack(sourceTreeUri, destIsoUri, outputName)
                }
            }

            ACTION_START_UNPACK -> {
                val isoUri = IntentCompat.getParcelableExtra(intent, EXTRA_ISO_URI, Uri::class.java)
                val destTreeUri = IntentCompat.getParcelableExtra(intent, EXTRA_DEST_TREE_URI, Uri::class.java)

                if (isoUri == null || destTreeUri == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForeground(
                    NotificationHelper.PROGRESS_NOTIFICATION_ID,
                    NotificationHelper.buildProgressNotification(this, "Extraindo…", "Iniciando…", -1)
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    runUnpack(isoUri, destTreeUri)
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun updateProgressNotification(title: String, message: String, percent: Int) {
        try {
            NotificationManagerCompat.from(this).notify(
                NotificationHelper.PROGRESS_NOTIFICATION_ID,
                NotificationHelper.buildProgressNotification(this, title, message, percent)
            )
        } catch (_: SecurityException) {
            // Sem permissão de notificação; a conversão continua normalmente.
        }
    }

    private fun finishWith(state: ServiceState) {
        Log.i(TAG, "finishWith: ${state::class.simpleName}")
        ConversionStatus.update(state)

        val (title, message) = when (state) {
            is ServiceState.Success -> "Mandacaru — Processo concluído" to state.message
            is ServiceState.Cancelled -> "Mandacaru — Cancelado" to "A conversão foi cancelada pelo usuário."
            is ServiceState.Error -> "Mandacaru — Erro na conversão" to state.message
            else -> "Mandacaru" to ""
        }
        NotificationHelper.showResultNotification(this, title, message)

        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Empacota: copia a pasta escolhida via SAF para a área de staging,
     * chama o motor nativo (Rust/xdvdfs-core) e copia o .iso resultante
     * para o destino escolhido pelo usuário.
     */
    private suspend fun runPack(sourceTreeUri: Uri, destIsoUri: Uri, outputName: String) {
        val log = mutableListOf<LogEntry>()
        fun update(percent: Int, message: String) {
            log.add(LogEntry(System.currentTimeMillis(), message))
            ConversionStatus.update(ServiceState.Running(percent, message, log.toList()))
            updateProgressNotification("Empacotando…", message, percent)
        }

        try {
            SafFileOps.clearStaging(this)

            update(-1, "Copiando arquivos da pasta selecionada…")
            val stagingSrc = SafFileOps.newStagingDir(this, "pack_src")
            SafFileOps.copyTreeUriToLocalDir(this, sourceTreeUri, stagingSrc)

            if (!File(stagingSrc, "default.xbe").exists()) {
                update(-1, "Aviso: default.xbe não encontrado na raiz da pasta selecionada.")
            }

            val stagingOutIso = SafFileOps.newStagingFile(this, "pack_out.iso")
            val listener = NativeXdvdfs.ProgressListener { percent, message -> update(percent, message) }
            Log.i(TAG, "Chamando NativeXdvdfs.packDirectory…")
            val error = NativeXdvdfs.packDirectory(stagingSrc.absolutePath, stagingOutIso.absolutePath, listener)
            Log.i(TAG, "NativeXdvdfs.packDirectory retornou: ${error ?: "sucesso"}")
            if (error != null) {
                finishWith(
                    if (NativeXdvdfs.isCancelledResult(error)) ServiceState.Cancelled(log.toList())
                    else ServiceState.Error(error, log.toList())
                )
                return
            }

            update(100, "Salvando o arquivo final…")
            val wrote = contentResolver.openOutputStream(destIsoUri)?.use { output ->
                stagingOutIso.inputStream().use { input -> input.copyTo(output) }
                true
            } ?: false

            if (!wrote) {
                finishWith(ServiceState.Error("Não foi possível gravar no destino escolhido", log.toList()))
                return
            }

            finishWith(ServiceState.Success("ISO XDVDFS \"$outputName\" criado com sucesso.", log.toList()))
        } catch (t: Throwable) {
            finishWith(ServiceState.Error(t.message ?: "Falha desconhecida ao empacotar", log.toList()))
        } finally {
            // Roda sempre — sucesso, cancelamento ou erro — para nunca deixar
            // arquivos temporários (jogo copiado / iso parcial) presos no
            // cache do app.
            runCatching { SafFileOps.clearStaging(this) }
        }
    }

    /**
     * Extrai: copia a ISO escolhida via SAF para a área de staging, chama
     * o motor nativo e copia os arquivos extraídos para a pasta de destino
     * escolhida pelo usuário.
     */
    private suspend fun runUnpack(isoUri: Uri, destTreeUri: Uri) {
        val log = mutableListOf<LogEntry>()
        fun update(percent: Int, message: String) {
            log.add(LogEntry(System.currentTimeMillis(), message))
            ConversionStatus.update(ServiceState.Running(percent, message, log.toList()))
            updateProgressNotification("Extraindo…", message, percent)
        }

        try {
            SafFileOps.clearStaging(this)

            update(-1, "Copiando o arquivo ISO selecionado…")
            val stagingIso = SafFileOps.newStagingFile(this, "unpack_src.iso")
            SafFileOps.copyFileUriToLocalFile(this, isoUri, stagingIso)

            val stagingOutDir = SafFileOps.newStagingDir(this, "unpack_out")
            val listener = NativeXdvdfs.ProgressListener { percent, message -> update(percent, message) }
            Log.i(TAG, "Chamando NativeXdvdfs.unpackIso…")
            val error = NativeXdvdfs.unpackIso(stagingIso.absolutePath, stagingOutDir.absolutePath, listener)
            Log.i(TAG, "NativeXdvdfs.unpackIso retornou: ${error ?: "sucesso"}")
            if (error != null) {
                finishWith(
                    if (NativeXdvdfs.isCancelledResult(error)) ServiceState.Cancelled(log.toList())
                    else ServiceState.Error(error, log.toList())
                )
                return
            }

            update(100, "Copiando arquivos extraídos para a pasta de destino…")
            SafFileOps.copyLocalDirToTreeUri(this, stagingOutDir, destTreeUri)

            finishWith(ServiceState.Success("Arquivos extraídos com sucesso.", log.toList()))
        } catch (t: Throwable) {
            finishWith(ServiceState.Error(t.message ?: "Falha desconhecida ao extrair", log.toList()))
        } finally {
            runCatching { SafFileOps.clearStaging(this) }
        }
    }
}
