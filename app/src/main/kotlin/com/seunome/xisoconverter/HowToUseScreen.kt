package com.seunome.xisoconverter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Tutorial passo a passo de como usar o Mandacaru, com um ícone
 * ilustrativo para cada passo (o app não usa capturas de tela reais, mas
 * cada etapa tem um ícone bem específico pra deixar claro o que fazer).
 */
@Composable
fun HowToUseScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            "Como usar o Mandacaru",
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            "Um passo a passo rápido das duas funções principais do app.",
            style = MaterialTheme.typography.bodyMedium
        )

        SectionTitle("📦 Empacotar (Pasta → XISO)")
        TutorialStep(
            number = 1,
            icon = Icons.Filled.FolderOpen,
            title = "Selecione a pasta do jogo",
            description = "Na aba \"Empacotar\", toque em \"Selecionar Pasta do Jogo\" e " +
                "escolha, no gerenciador de arquivos, a pasta que contém o arquivo " +
                "default.xbe na raiz (junto dele costumam vir pastas como \"media\" ou \"maps\")."
        )
        TutorialStep(
            number = 2,
            icon = Icons.Filled.Edit,
            title = "Escolha o nome do arquivo final",
            description = "No campo de texto, digite como o arquivo .iso deve se chamar, " +
                "por exemplo \"Halo.iso\". Isso é opcional — o app já sugere um nome padrão."
        )
        TutorialStep(
            number = 3,
            icon = Icons.Filled.PlayArrow,
            title = "Toque em \"Converter para XISO (Pack)\"",
            description = "O app vai pedir onde salvar o arquivo .iso — escolha a pasta de " +
                "destino no seu celular. A conversão começa assim que você confirmar."
        )
        TutorialStep(
            number = 4,
            icon = Icons.Filled.Notifications,
            title = "Acompanhe pela barra de progresso ou pela notificação",
            description = "A conversão roda em segundo plano: você pode sair do app e " +
                "acompanhar a porcentagem pela notificação. Se quiser, toque em " +
                "\"Cancelar\" a qualquer momento para interromper."
        )
        TutorialStep(
            number = 5,
            icon = Icons.Filled.CheckCircle,
            title = "Pronto!",
            description = "Quando terminar, uma notificação \"Processo concluído\" aparece " +
                "na área de notificações, e o arquivo .iso já está salvo onde você escolheu.",
            isLast = true
        )

        HorizontalDivider()

        SectionTitle("📀 Extrair (XISO → Pasta)")
        TutorialStep(
            number = 1,
            icon = Icons.Filled.Description,
            title = "Selecione o arquivo .iso / .xiso",
            description = "Na aba \"Extrair\", toque em \"Selecionar Arquivo .ISO / .XISO\" e " +
                "escolha o arquivo do jogo que você quer abrir."
        )
        TutorialStep(
            number = 2,
            icon = Icons.Filled.PlayArrow,
            title = "Toque em \"Extrair Arquivos (Unpack)\"",
            description = "O app vai pedir uma pasta de destino — escolha (ou crie) a pasta " +
                "onde os arquivos soltos do jogo (default.xbe, maps, etc.) serão salvos."
        )
        TutorialStep(
            number = 3,
            icon = Icons.Filled.Notifications,
            title = "Acompanhe pela barra de progresso ou pela notificação",
            description = "Assim como no empacotamento, dá pra sair do app e acompanhar " +
                "pela notificação, ou cancelar a qualquer momento."
        )
        TutorialStep(
            number = 4,
            icon = Icons.Filled.CheckCircle,
            title = "Pronto!",
            description = "Quando terminar, a notificação avisa e os arquivos já estarão na " +
                "pasta de destino escolhida.",
            isLast = true
        )

        HorizontalDivider()

        SectionTitle("💡 Dicas")
        TipRow(
            icon = Icons.Filled.Save,
            text = "É preciso ter espaço livre no celular equivalente ao tamanho do jogo: " +
                "o app copia os arquivos para uma área de trabalho interna antes de converter."
        )
        TipRow(
            icon = Icons.AutoMirrored.Filled.DriveFileMove,
            text = "Se aparecer um erro, toque em \"Ver detalhes\" para ver o passo a passo " +
                "completo até a falha, e em \"Copiar log\" para compartilhar o erro."
        )
        TipRow(
            icon = Icons.Filled.Warning,
            text = "O app não abre imagens .cso (formato comprimido de outros emuladores) — " +
                "apenas .iso / .xiso no formato XDVDFS, que é o que o Xbox Clássico usa."
        )
        TipRow(
            icon = Icons.Filled.Info,
            text = "Permita as notificações quando o Android pedir, assim você recebe o " +
                "aviso de \"processo concluído\" mesmo com o app em segundo plano."
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun TutorialStep(
    number: Int,
    icon: ImageVector,
    title: String,
    description: String,
    isLast: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        "$number",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(36.dp)
                    .align(Alignment.CenterVertically)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (!isLast) {
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun TipRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(24.dp)
        )
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}
