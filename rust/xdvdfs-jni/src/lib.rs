//! Ponte JNI entre o app Android (Kotlin) e a biblioteca `xdvdfs` (motor real
//! de leitura/escrita do formato XDVDFS, vendorizada em ../xdvdfs-core).
//!
//! Além de empacotar/extrair, esta versão:
//!   - reporta progresso real (percentual) via callback para um objeto
//!     Kotlin (`NativeXdvdfs.ProgressListener`), usando o `ProgressVisitor`
//!     que o xdvdfs-core já expõe para o pack, e uma contagem manual
//!     equivalente para o unpack;
//!   - suporta cancelamento cooperativo: o Kotlin chama `requestCancel()`
//!     e a conversão em andamento para assim que possível (a cada
//!     gravação, no caso do pack; a cada arquivo, no caso do unpack).
//!
//! Não há suporte a imagens `.cso` (compressão usada por outros
//! emuladores/consoles) — o app trabalha apenas com XISO/XDVDFS "cru",
//! que é o formato que o emulador de Xbox clássico espera.

use std::fs::File;
use std::io::{self, BufReader, BufWriter, Read, Seek, SeekFrom, Write};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};

use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::jstring;
use jni::JNIEnv;

use xdvdfs::blockdev::{BlockDeviceWrite, OffsetWrapper};
use xdvdfs::write::fs::StdFilesystem;
use xdvdfs::write::img::{create_xdvdfs_image, ProgressInfo};

/// Flag global de cancelamento. O app só roda uma conversão por vez (os
/// botões ficam desabilitados durante o processamento), então uma flag
/// global simples é suficiente e evita ter que gerenciar ponteiros/handles
/// nativos do lado Kotlin.
static CANCELLED: AtomicBool = AtomicBool::new(false);

/// Prefixo usado para sinalizar ao Kotlin que a operação terminou porque
/// foi cancelada pelo usuário, e não por um erro de verdade.
const CANCELLED_MARKER: &str = "CANCELADO_PELO_USUARIO";

fn result_to_jstring(env: &mut JNIEnv, result: Result<(), String>) -> jstring {
    match result {
        Ok(()) => std::ptr::null_mut(),
        Err(msg) => match env.new_string(msg) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
    }
}

fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s)
        .map(|s| s.into())
        .map_err(|e| format!("Falha ao ler string vinda do Kotlin: {e}"))
}

/// Chama `listener.onProgress(percent, message)` no objeto Kotlin.
/// `percent` pode ser -1 quando ainda não sabemos o total (progresso
/// indeterminado); o lado Kotlin trata esse caso mostrando uma barra
/// "ocupado" em vez de uma porcentagem.
fn report_progress(env: &mut JNIEnv, listener: &JObject, percent: i32, message: &str) {
    let Ok(jmsg) = env.new_string(message) else {
        return;
    };
    let _ = env.call_method(
        listener,
        "onProgress",
        "(ILjava/lang/String;)V",
        &[JValue::Int(percent), JValue::Object(&jmsg)],
    );
    // Se o callback do lado Kotlin jogou uma exceção Java por algum motivo,
    // limpamos para não deixar o ambiente da JVM em estado inconsistente.
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
    }
}

// ---------------------------------------------------------------------------
// Empacotar: Pasta -> .iso (XDVDFS)
// ---------------------------------------------------------------------------

/// `std::io::Write`/`Seek` que finge um erro de I/O assim que percebe que o
/// usuário pediu cancelamento. Como `create_xdvdfs_image` escreve a imagem
/// em pequenos pedaços (setor a setor / arquivo a arquivo), isso faz a
/// conversão parar quase imediatamente após o cancelamento, sem precisar
/// alterar o algoritmo do xdvdfs-core.
struct CancellableWriter<W> {
    inner: W,
}

impl<W: Write> Write for CancellableWriter<W> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        if CANCELLED.load(Ordering::SeqCst) {
            // IMPORTANTE: nunca usar ErrorKind::Interrupted aqui.
            // Funções da biblioteca padrão como write_all/io::copy tratam
            // Interrupted como "tenta de novo automaticamente" (é pensado
            // para EINTR do POSIX) — como essa condição nunca muda depois
            // do cancelamento, isso gerava um LOOP INFINITO tentando
            // escrever pra sempre, travando o app. ErrorKind::Other não
            // tem esse comportamento especial e propaga o erro de verdade.
            return Err(io::Error::new(io::ErrorKind::Other, CANCELLED_MARKER));
        }
        self.inner.write(buf)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }
}

impl<W: Seek> Seek for CancellableWriter<W> {
    fn seek(&mut self, pos: SeekFrom) -> io::Result<u64> {
        self.inner.seek(pos)
    }
}

fn pack_directory_impl(
    env: &mut JNIEnv,
    listener: &JObject,
    source_dir: &str,
    output_iso: &str,
) -> Result<(), String> {
    CANCELLED.store(false, Ordering::SeqCst);

    let source_path = Path::new(source_dir);
    if !source_path.is_dir() {
        return Err(format!(
            "Pasta de origem não encontrada ou inválida: {source_dir}"
        ));
    }

    let out_file = File::options()
        .write(true)
        .truncate(true)
        .create(true)
        .open(output_iso)
        .map_err(|e| format!("Não foi possível criar o arquivo de saída ({output_iso}): {e}"))?;
    let mut writer = CancellableWriter {
        inner: BufWriter::with_capacity(1024 * 1024, out_file),
    };

    let mut fs = StdFilesystem::create(source_path);

    // Estado usado para calcular a porcentagem a partir dos eventos que o
    // xdvdfs-core dispara durante o empacotamento.
    let mut total_entries: i64 = -1;
    let mut processed: i64 = 0;
    let mut last_reported_percent = -2i32;

    let progress_visitor = |info: ProgressInfo<'_>| {
        match &info {
            ProgressInfo::FileCount(a) | ProgressInfo::DirCount(a) => {
                // entry_counts() dispara os dois eventos em sequência;
                // somamos ambos para termos o total real de entradas
                // (arquivos + pastas) que vão gerar um DirAdded/FileAdded.
                if total_entries < 0 {
                    total_entries = *a as i64;
                } else {
                    total_entries += *a as i64;
                }
            }
            ProgressInfo::DirAdded(..) | ProgressInfo::FileAdded(..) => {
                processed += 1;
            }
            _ => {}
        }

        let percent = if total_entries > 0 {
            ((processed * 100) / total_entries).clamp(0, 100) as i32
        } else {
            -1
        };

        if percent != last_reported_percent {
            last_reported_percent = percent;
            let msg = match &info {
                ProgressInfo::DirAdded(path, _) => format!("Empacotando pasta {path}…"),
                ProgressInfo::FileAdded(path, _) => format!("Empacotando {path}…"),
                ProgressInfo::FinishedCopyingImageData => {
                    "Finalizando imagem XDVDFS…".to_string()
                }
                _ => "Preparando arquivos…".to_string(),
            };
            report_progress(env, listener, percent, &msg);
        }
    };

    let pack_result = create_xdvdfs_image(&mut fs, &mut writer, progress_visitor);

    if let Err(e) = pack_result {
        if CANCELLED.load(Ordering::SeqCst) {
            return Err(CANCELLED_MARKER.to_string());
        }
        return Err(format!("Falha ao gerar a imagem XDVDFS: {e}"));
    }

    writer
        .inner
        .flush()
        .map_err(|e| format!("Falha ao finalizar a gravação do arquivo: {e}"))?;

    report_progress(env, listener, 100, "Concluído.");
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_com_seunome_xisoconverter_NativeXdvdfs_packDirectory<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    source_dir: JString<'local>,
    output_iso: JString<'local>,
    listener: JObject<'local>,
) -> jstring {
    let result = (|| -> Result<(), String> {
        let source_dir = jstring_to_string(&mut env, &source_dir)?;
        let output_iso = jstring_to_string(&mut env, &output_iso)?;
        pack_directory_impl(&mut env, &listener, &source_dir, &output_iso)
    })();

    result_to_jstring(&mut env, result)
}

// ---------------------------------------------------------------------------
// Extrair: .iso (XDVDFS) -> Pasta
// ---------------------------------------------------------------------------

/// Copia todo o conteúdo de uma imagem XDVDFS já aberta para uma pasta real
/// no disco, recriando a árvore de diretórios, reportando progresso real e
/// checando o pedido de cancelamento a cada entrada. Baseado em
/// xdvdfs-cli/src/cmd_unpack.rs::copyout_directory.
fn copyout_directory(
    env: &mut JNIEnv,
    listener: &JObject,
    img: &mut OffsetWrapper<BufReader<File>>,
    dest_dir: &Path,
    dirtab: &xdvdfs::layout::DirectoryEntryTable,
) -> Result<(), String> {
    let tree = dirtab
        .file_tree(img)
        .map_err(|e| format!("Falha ao ler a árvore de diretórios da ISO: {e}"))?;

    let total = tree.len().max(1) as i64;
    let mut last_reported_percent = -2i32;

    for (processed, (dir, dirent)) in tree.iter().enumerate() {
        if CANCELLED.load(Ordering::SeqCst) {
            return Err(CANCELLED_MARKER.to_string());
        }

        let dir = dir.trim_start_matches('/');
        let dirname = dest_dir.join(dir);
        let file_name = dirent
            .name_str()
            .map_err(|e| format!("Nome de arquivo inválido dentro da ISO: {e}"))?;
        let file_path = dirname.join(&*file_name);
        let is_dir = dirent.node.dirent.is_directory();

        let percent = (((processed as i64 + 1) * 100) / total).clamp(0, 100) as i32;
        if percent != last_reported_percent {
            last_reported_percent = percent;
            let msg = format!("Extraindo {}…", file_path.display());
            report_progress(env, listener, percent, &msg);
        }

        std::fs::create_dir_all(&dirname)
            .map_err(|e| format!("Falha ao criar a pasta {dirname:?}: {e}"))?;

        if is_dir {
            let _ = std::fs::create_dir(&file_path);
            continue;
        }

        if dirent.node.dirent.filename_length == 0 {
            // Entrada sem nome; a ferramenta original apenas avisa e pula.
            continue;
        }

        let mut file = File::options()
            .write(true)
            .truncate(true)
            .create(true)
            .open(&file_path)
            .map_err(|e| format!("Falha ao criar o arquivo {file_path:?}: {e}"))?;

        if dirent.node.dirent.is_empty() {
            continue;
        }

        dirent
            .node
            .dirent
            .seek_to(img)
            .map_err(|e| format!("Falha ao posicionar leitura de {file_path:?}: {e}"))?;

        // Caminho rápido: clona o descritor de arquivo para copiar em
        // stream, sem carregar o arquivo inteiro na memória (importante
        // para arquivos grandes, como vídeos de introdução dos jogos).
        let data = img.get_ref().get_ref().try_clone();
        match data {
            Ok(data) => {
                let data = data.take(dirent.node.dirent.data.size as u64);
                let mut data = BufReader::new(data);
                std::io::copy(&mut data, &mut file)
                    .map_err(|e| format!("Falha ao copiar dados de {file_path:?}: {e}"))?;
            }
            Err(_) => {
                // Caminho alternativo (mais lento, carrega em memória).
                let data = dirent
                    .node
                    .dirent
                    .read_data_all(img)
                    .map_err(|e| format!("Falha ao ler dados de {file_path:?}: {e}"))?;
                file.write_all(&data)
                    .map_err(|e| format!("Falha ao gravar {file_path:?}: {e}"))?;
            }
        }
    }

    Ok(())
}

fn unpack_iso_impl(
    env: &mut JNIEnv,
    listener: &JObject,
    iso_path: &str,
    output_dir: &str,
) -> Result<(), String> {
    CANCELLED.store(false, Ordering::SeqCst);

    let path = Path::new(iso_path);
    let file = File::options()
        .read(true)
        .open(path)
        .map_err(|e| format!("Não foi possível abrir a ISO ({iso_path}): {e}"))?;
    let reader = BufReader::new(file);

    report_progress(env, listener, -1, "Lendo cabeçalho XDVDFS…");

    let mut img = OffsetWrapper::new(reader)
        .map_err(|e| format!("O arquivo não parece ser uma imagem XDVDFS (XISO) válida: {e}"))?;

    let volume = xdvdfs::read::read_volume(&mut img)
        .map_err(|e| format!("Falha ao ler o volume XDVDFS: {e}"))?;

    std::fs::create_dir_all(output_dir)
        .map_err(|e| format!("Não foi possível criar a pasta de saída: {e}"))?;

    copyout_directory(env, listener, &mut img, Path::new(output_dir), &volume.root_table)?;

    report_progress(env, listener, 100, "Concluído.");
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_com_seunome_xisoconverter_NativeXdvdfs_unpackIso<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    iso_path: JString<'local>,
    output_dir: JString<'local>,
    listener: JObject<'local>,
) -> jstring {
    let result = (|| -> Result<(), String> {
        let iso_path = jstring_to_string(&mut env, &iso_path)?;
        let output_dir = jstring_to_string(&mut env, &output_dir)?;
        unpack_iso_impl(&mut env, &listener, &iso_path, &output_dir)
    })();

    result_to_jstring(&mut env, result)
}

// ---------------------------------------------------------------------------
// Cancelamento
// ---------------------------------------------------------------------------

/// Chamado pelo Kotlin quando o usuário toca em "Cancelar". A conversão em
/// andamento (pack ou unpack) para assim que possível e devolve um erro
/// especial (CANCELLED_MARKER) que o Kotlin reconhece e trata como
/// cancelamento, não como falha real.
#[no_mangle]
pub extern "system" fn Java_com_seunome_xisoconverter_NativeXdvdfs_nativeRequestCancel<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    CANCELLED.store(true, Ordering::SeqCst);
}

// Referenciamos BlockDeviceWrite só para garantir, em tempo de compilação,
// que CancellableWriter<BufWriter<File>> satisfaz o bound que
// create_xdvdfs_image exige (implementado via blanket impl para
// Write + Seek + Send + Sync no xdvdfs-core). Isso não gera código, é só
// uma checagem estática.
#[allow(dead_code)]
fn _assert_cancellable_writer_is_block_device_write() {
    fn assert_bdw<T: BlockDeviceWrite>() {}
    assert_bdw::<CancellableWriter<BufWriter<File>>>();
}
