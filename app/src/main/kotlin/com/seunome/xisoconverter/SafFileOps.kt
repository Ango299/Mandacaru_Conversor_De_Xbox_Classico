package com.seunome.xisoconverter

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * O motor de conversão (Rust/xdvdfs-core) trabalha com caminhos de arquivo
 * de verdade (std::fs), não com content:// URIs do Storage Access Framework.
 * Estas funções fazem a ponte: copiam pastas/arquivos escolhidos pelo
 * usuário via SAF para uma área de trabalho real dentro do armazenamento
 * do próprio app (staging), e depois copiam o resultado de volta para
 * onde o usuário quiser salvar.
 *
 * Observação importante: isso implica uma cópia extra dos dados (SAF -> área
 * de staging -> SAF de novo). Para jogos de Xbox clássico (normalmente até
 * poucos GB) costuma ser tranquilo, mas exige espaço livre equivalente ao
 * tamanho do jogo dentro do armazenamento interno do celular.
 */
object SafFileOps {

    private fun stagingRoot(context: Context): File {
        val dir = File(context.cacheDir, "xiso_staging")
        dir.mkdirs()
        return dir
    }

    /** Limpa a área de staging antes de uma nova operação. */
    fun clearStaging(context: Context) {
        stagingRoot(context).deleteRecursively()
        stagingRoot(context) // recria
    }

    fun newStagingDir(context: Context, name: String): File {
        val dir = File(stagingRoot(context), name)
        dir.mkdirs()
        return dir
    }

    fun newStagingFile(context: Context, name: String): File {
        return File(stagingRoot(context), name)
    }

    /**
     * Copia recursivamente uma árvore de pasta escolhida via
     * ACTION_OPEN_DOCUMENT_TREE (SAF) para uma pasta real [destDir] no
     * armazenamento do app. Retorna o número de arquivos copiados.
     */
    fun copyTreeUriToLocalDir(context: Context, treeUri: Uri, destDir: File): Int {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("Não foi possível abrir a pasta selecionada")
        var count = 0
        fun walk(doc: DocumentFile, target: File) {
            if (doc.isDirectory) {
                target.mkdirs()
                for (child in doc.listFiles()) {
                    val childName = child.name ?: continue
                    walk(child, File(target, childName))
                }
            } else {
                target.parentFile?.mkdirs()
                context.contentResolver.openInputStream(doc.uri)?.use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                count++
            }
        }
        walk(root, destDir)
        return count
    }

    /**
     * Copia um único arquivo escolhido via ACTION_OPEN_DOCUMENT (SAF) para
     * um arquivo real [destFile] no armazenamento do app.
     */
    fun copyFileUriToLocalFile(context: Context, fileUri: Uri, destFile: File) {
        destFile.parentFile?.mkdirs()
        context.contentResolver.openInputStream(fileUri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Não foi possível abrir o arquivo selecionado")
    }

    /**
     * Copia um arquivo real [localFile] para dentro da árvore SAF
     * representada por [destTreeUri], usando [displayName] como nome final
     * (ex.: "Halo.iso"). Retorna a Uri do arquivo criado.
     */
    fun copyLocalFileToTreeUri(
        context: Context,
        localFile: File,
        destTreeUri: Uri,
        displayName: String,
        mimeType: String = "application/octet-stream"
    ): Uri {
        val destDir = DocumentFile.fromTreeUri(context, destTreeUri)
            ?: throw IllegalArgumentException("Não foi possível abrir a pasta de destino")
        // Remove um arquivo existente com o mesmo nome, se houver.
        destDir.findFile(displayName)?.delete()
        val outFile = destDir.createFile(mimeType, displayName)
            ?: throw IllegalStateException("Não foi possível criar o arquivo de saída")
        context.contentResolver.openOutputStream(outFile.uri)?.use { output ->
            localFile.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Não foi possível gravar o arquivo de saída")
        return outFile.uri
    }

    /**
     * Copia recursivamente uma pasta real [localDir] para dentro da árvore
     * SAF representada por [destTreeUri], recriando a mesma estrutura de
     * subpastas e arquivos.
     */
    fun copyLocalDirToTreeUri(context: Context, localDir: File, destTreeUri: Uri): Int {
        val destRoot = DocumentFile.fromTreeUri(context, destTreeUri)
            ?: throw IllegalArgumentException("Não foi possível abrir a pasta de destino")
        var count = 0
        fun walk(src: File, targetDoc: DocumentFile) {
            val children = src.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    val existing = targetDoc.findFile(child.name)
                    val childDoc = existing ?: targetDoc.createDirectory(child.name)
                    ?: throw IllegalStateException("Não foi possível criar a pasta ${child.name}")
                    walk(child, childDoc)
                } else {
                    targetDoc.findFile(child.name)?.delete()
                    val childDoc = targetDoc.createFile("application/octet-stream", child.name)
                        ?: throw IllegalStateException("Não foi possível criar o arquivo ${child.name}")
                    context.contentResolver.openOutputStream(childDoc.uri)?.use { output ->
                        child.inputStream().use { input -> input.copyTo(output) }
                    }
                    count++
                }
            }
        }
        walk(localDir, destRoot)
        return count
    }

    /** Tenta obter um nome amigável para exibir a partir de uma Uri de árvore/documento. */
    fun displayNameForTreeUri(context: Context, uri: Uri): String {
        return DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: uri.toString()
    }

    fun displayNameForFileUri(context: Context, uri: Uri): String {
        return DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment ?: uri.toString()
    }
}
