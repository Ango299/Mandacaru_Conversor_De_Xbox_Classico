# Mandacaru — Conversor Xbox Clássico (Android) — LEIA-ME

Conversor Xbox Clássico ⇄ Android: **Empacotar** (Pasta → .iso XDVDFS) e
**Extrair** (.iso XDVDFS → Pasta), com interface em Jetpack Compose, aba de
tutorial embutida, progresso real, notificações e conversão em segundo
plano.

## O que já está pronto

- Interface em Compose com **3 abas**: Empacotar, Extrair e **Como Usar**
  (tutorial).
- Lógica real de conversão em Rust (`rust/xdvdfs-jni`), usando o código-fonte
  do `xdvdfs-core` que você enviou (vendorizado em `rust/xdvdfs-core`).
- Ponte JNI (`NativeXdvdfs.kt` ↔ `xdvdfs-jni/src/lib.rs`).
- Cópia SAF ⇄ armazenamento real do app (`SafFileOps.kt`).
- Gradle já configurado para compilar a lib Rust automaticamente antes do
  `assemble` (task `buildRustNativeLib`).
- **Progresso real (porcentagem)**: via `ProgressVisitor` do `xdvdfs-core`
  no empacotamento e contagem manual equivalente na extração, reportado por
  callback JNI para o Kotlin.
- **Botão Cancelar**, com cancelamento cooperativo no lado nativo.
- **Sem suporte a `.cso`** (de propósito — não é o formato que o Xbox
  Clássico usa).
- **Tela/lista de erro detalhada** (novo): em vez de uma única linha, o
  app guarda um log com timestamp de cada passo da conversão. Se der erro,
  aparece "Ver detalhes (N passos)" — expande a lista completa até a
  falha — e um botão "Copiar log" para colar e me mandar caso precise de
  ajuda para debugar.
- **Aba "Como Usar"** (novo): tutorial passo a passo para Empacotar e
  Extrair, cada passo com um ícone ilustrativo (não são capturas de tela
  reais do app — expliquei o motivo mais abaixo —, mas cada etapa tem um
  ícone bem específico: pasta, lápis, play, sino de notificação, check),
  mais uma seção de dicas gerais (espaço em disco, permissão de
  notificação, etc.).
- **Notificação de progresso + notificação de conclusão** (novo): a
  conversão agora roda dentro de um **serviço em primeiro plano**
  (`ConversionService`), não mais presa à tela. Isso significa que você
  pode sair do app (apertar Home, trocar de app) enquanto ele converte —
  o Android mostra uma notificação contínua com a porcentagem, e ao
  terminar aparece uma notificação "Mandacaru — Processo concluído" (ou
  "Cancelado" / "Erro na conversão") na área de notificações do celular.
- **Nome do app alterado** para "Mandacaru Conversor Xbox Clássico" (nome
  visível, em `strings.xml`). O nome interno do pacote/código
  (`com.seunome.xisoconverter`) foi mantido de propósito — trocar o
  `applicationId` depois de gerar o projeto não muda nada pra você
  funcionalmente, então preferi não mexer para não arriscar quebrar
  referências internas sem necessidade. Se quiser mesmo trocar o pacote,
  é só pedir.
  
## O que você precisa instalar antes de compilar

O Android Studio sozinho **não compila Rust**. Você precisa, uma única vez:

1. **Rust**: instale via [rustup.rs](https://rustup.rs).
2. **cargo-ndk**:
   ```bash
   cargo install cargo-ndk
   ```
3. **Alvos Android do Rust**:
   ```bash
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
   ```
4. **NDK do Android**: no Android Studio, vá em
   `Tools → SDK Manager → SDK Tools` e marque `NDK (Side by side)` e
   `CMake`. Instale.
5. Defina a variável de ambiente `ANDROID_NDK_HOME` apontando para a pasta
   do NDK instalado (algo como `~/Android/Sdk/ndk/<versão>` ou, no Windows,
   `%LOCALAPPDATA%\Android\Sdk\ndk\<versão>`).

Depois disso, é só abrir a pasta `XisoConverter/` no Android Studio,
deixar o Gradle sincronizar e mandar **Run** — o Gradle chama o
`cargo ndk` sozinho a cada build (task `buildRustNativeLib`, antes do
`preBuild`).

Se o `cargo` não estiver no PATH do Android Studio, edite
`gradle.properties` e ajuste `XISO_CARGO_BIN` para o caminho completo, ex.:
`XISO_CARGO_BIN=/home/SEUUSUARIO/.cargo/bin/cargo`.

## Estrutura do projeto

```
XisoConverter/
├── app/                         módulo Android (Kotlin + Compose)
│   └── src/main/kotlin/com/seunome/xisoconverter/
│       ├── MainActivity.kt        abas Empacotar / Extrair / Como Usar,
│       │                          tela de progresso e erro detalhado
│       ├── HowToUseScreen.kt       aba de tutorial passo a passo
│       ├── ConversionService.kt    serviço em primeiro plano que roda a
│       │                          conversão de verdade (progresso, log,
│       │                          notificações)
│       ├── NotificationHelper.kt   canal e montagem das notificações
│       ├── NativeXdvdfs.kt         declaração das funções JNI
│       └── SafFileOps.kt           cópia SAF ⇄ arquivos reais
└── rust/
    ├── xdvdfs-core/             seu xdvdfs vendorizado (biblioteca)
    └── xdvdfs-jni/              camada JNI que chama o xdvdfs-core
        └── src/lib.rs
```

## Como funciona por baixo dos panos

- **Empacotar/Extrair**: como antes — cópia SAF ⇄ área de staging real
  (`cacheDir`) porque o motor Rust usa `std::fs`, não URIs `content://`.
  É preciso ter espaço livre equivalente ao tamanho do jogo.
- **Segundo plano**: ao tocar em Converter/Extrair, a Activity dispara um
  `Intent` para o `ConversionService` (via `startForegroundService`), que
  chama `startForeground()` imediatamente com uma notificação de
  progresso e faz todo o trabalho (cópia + chamada nativa) numa
  coroutine própria do serviço — não mais amarrada à tela. A Activity e o
  serviço compartilham um `StateFlow` (`ConversionStatus.state`) só de
  leitura/observação, então a tela sempre reflete o estado real da
  conversão, mesmo se você sair e voltar ao app no meio do processo.
- **Notificações**: `NotificationHelper` cria um canal
  (`mandacaru_conversao`) e monta duas notificações — uma "em andamento"
  (atualizada a cada mudança de porcentagem, presa ao serviço) e uma
  final ("Processo concluído" / "Cancelado" / "Erro na conversão"), que é
  a que aparece mesmo com o app fechado.
- **Erro detalhado**: cada chamada de progresso vira uma `LogEntry`
  (horário + mensagem) guardada numa lista. Se a conversão falhar, a tela
  mostra a mensagem final em destaque e um botão que expande a lista
  completa de passos, além de um botão para copiar tudo.

## Bug corrigido: cancelar travava o app (precisava de "Forçar parada")

Se você testou uma versão anterior e o botão Cancelar deixava o app
"carregando infinitamente" até você precisar forçar a parada nas
configurações do Android — isso já está corrigido. A causa: o código que
interrompe a escrita da imagem, ao detectar o cancelamento, devolvia um
erro do tipo `ErrorKind::Interrupted`. Só que esse tipo de erro tem um
significado especial na biblioteca padrão do Rust — funções como
`write_all`/`io::copy` o interpretam como "foi só uma interrupção
passageira (EINTR do POSIX), tenta de novo" e **repetem a operação
automaticamente**. Como a condição de cancelamento nunca mudava, isso virou
um laço infinito tentando escrever pra sempre, sem nunca desistir — daí o
travamento. A correção troca esse erro para `ErrorKind::Other`, que não
tem esse comportamento de novo-tentativa automática.

Como consequência direta desse travamento, o processo nunca chegava a
limpar a pasta de staging (cache interno onde o jogo copiado e a ISO
parcial ficam durante a conversão) — por isso o "Armazenamento" do app
podia mostrar vários GB em cache mesmo depois de cancelar. Também corrigi
isso: agora a limpeza da staging roda sempre, em um bloco `finally`,
não importa se a conversão termina com sucesso, cancelamento ou erro.

Também adicionei logs (`Log.i`, tag `"Mandacaru"`) nos pontos-chave do
`ConversionService` e do cancelamento, para que, se algo parecido
acontecer de novo, o logcat mostre exatamente onde travou em vez de ficar
sem nenhuma pista.
> Este projeto usa o [xdvdfs](https://github.com/antangelo/xdvdfs) (MIT,
> © antangelo) como motor de leitura/escrita do formato XDVDFS.
