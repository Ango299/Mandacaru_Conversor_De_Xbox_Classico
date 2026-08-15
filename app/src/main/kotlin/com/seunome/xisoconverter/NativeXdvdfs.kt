package com.seunome.xisoconverter

import android.util.Log

/**
 * Ponte para a biblioteca nativa em Rust (rust/xdvdfs-jni), que usa o
 * xdvdfs-core (vendorizado em rust/xdvdfs-core) para de fato ler/escrever
 * o formato XDVDFS.
 *
 * As duas funções de conversão trabalham com caminhos de arquivo "de
 * verdade" no armazenamento interno do app (não com content:// URIs do
 * SAF), porque é bem mais simples e robusto abrir/gravar via std::fs no
 * lado Rust. A conversão entre URIs do SAF e caminhos reais é feita em
 * Kotlin, em SafFileOps.kt, antes/depois de chamar essas funções.
 *
 * Cada função de conversão retorna null em caso de sucesso, ou uma String
 * em caso de falha — que pode ser [CANCELLED_MARKER] quando o motivo foi
 * o usuário ter cancelado (ver [isCancelledResult]), ou uma mensagem de
 * erro de verdade em qualquer outro caso.
 */
object NativeXdvdfs {

    init {
        System.loadLibrary("xdvdfs_jni")
    }

    /** Callback chamado pelo código nativo durante a conversão. */
    fun interface ProgressListener {
        /**
         * @param percent 0..100 quando o progresso é conhecido, ou -1
         *   quando ainda é indeterminado (ex.: lendo o cabeçalho da ISO).
         * @param message texto curto descrevendo a etapa atual.
         */
        fun onProgress(percent: Int, message: String)
    }

    /** Valor de retorno usado pelo lado nativo para sinalizar cancelamento. */
    private const val CANCELLED_MARKER = "CANCELADO_PELO_USUARIO"

    fun isCancelledResult(errorMessage: String?): Boolean = errorMessage == CANCELLED_MARKER

    /**
     * Empacota o conteúdo da pasta [sourceDirPath] (que deve conter o
     * default.xbe na raiz) em uma imagem .iso/.xiso XDVDFS gravada em
     * [outputIsoPath]. [listener] recebe atualizações de progresso.
     */
    external fun packDirectory(
        sourceDirPath: String,
        outputIsoPath: String,
        listener: ProgressListener
    ): String?

    /**
     * Extrai o conteúdo da imagem XDVDFS em [isoPath] para a pasta
     * [outputDirPath] (criada se não existir). [listener] recebe
     * atualizações de progresso.
     */
    external fun unpackIso(
        isoPath: String,
        outputDirPath: String,
        listener: ProgressListener
    ): String?

    /**
     * Pede o cancelamento da conversão em andamento (pack ou unpack). A
     * conversão para assim que possível; o resultado da chamada em
     * andamento virá com [CANCELLED_MARKER] como mensagem de erro.
     */
    fun requestCancel() {
        Log.i("Mandacaru", "requestCancel() chamado")
        nativeRequestCancel()
    }

    private external fun nativeRequestCancel()
}
