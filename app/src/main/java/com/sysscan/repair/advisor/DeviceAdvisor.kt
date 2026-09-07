package com.sysscan.repair.advisor

object DeviceAdvisor {

    fun greeting(snapshot: ScanSnapshot?): String {
        if (snapshot == null) {
            return "Olá. Sou o assistente do SysScan.\n\n" +
                "Ainda não há uma varredura nesta sessão. Toque em Nova varredura na tela inicial " +
                "e volte aqui — eu leio o resultado e te digo o que melhorar no aparelho.\n\n" +
                "Você também pode perguntar, por exemplo:\n" +
                "• Como melhorar a bateria?\n" +
                "• O que está deixando o celular lento?\n" +
                "• Como conceder root no Magisk?"
        }
        val sb = StringBuilder()
        sb.append("Olá. Li a última varredura: saúde ${snapshot.score}")
        if (snapshot.hasRoot) sb.append(", com root ativo")
        sb.append(".\n\n")
        if (snapshot.issues.isEmpty()) {
            sb.append("Nenhum ponto crítico agora. Posso sugerir hábitos para manter bateria, RAM e armazenamento saudáveis. O que você quer melhorar?")
        } else {
            sb.append("Encontrei ${snapshot.issues.size} ponto(s) para cuidar:\n")
            snapshot.issues.take(5).forEach { issue ->
                val tag = if (issue.severity == "CRITICAL") "Crítico" else "Atenção"
                sb.append("• [$tag] ${issue.title}\n")
            }
            sb.append("\nPergunte o que quiser, ou peça: \"o que eu faço agora?\"")
        }
        return sb.toString()
    }

    fun answer(question: String, snapshot: ScanSnapshot?): String {
        val q = question.lowercase().trim()
        if (q.isBlank()) return "Pode perguntar sobre bateria, lentidão, aquecimento, armazenamento ou root."

        return when {
            matches(q, "o que eu faço", "o que fazer", "prioridade", "agora", "melhorar tudo", "plano") ->
                plan(snapshot)
            matches(q, "bateria", "descarreg", "autonomia", "carrega") ->
                battery(snapshot)
            matches(q, "lento", "lentid", "trav", "ram", "memória", "memoria", "lag") ->
                memory(snapshot)
            matches(q, "esquent", "quent", "temperat", "thermal", "hot") ->
                heat(snapshot)
            matches(q, "armazen", "espaço", "storage", "cheio", "cache") ->
                storage(snapshot)
            matches(q, "arquivo essencial", "arquivos essenciais", "app_process", "dalvikvm", "build.prop", "reflash", "rom") ->
                essentialFiles(snapshot)
            matches(q, "root", "magisk", "kernelsu", "su ") ->
                root(snapshot)
            matches(q, "rede", "wifi", "internet", "dados") ->
                network()
            matches(q, "score", "saúde", "saude", "nota") ->
                score(snapshot)
            matches(q, "atualizar", "update", "versão", "versao") ->
                "Na tela inicial, toque no ícone de download no topo. O SysScan consulta a Release do GitHub (joaomsninvestimentos-cloud/sysscan) e, se houver APK mais novo, baixa e instala."
            else -> fallback(q, snapshot)
        }
    }

    private fun matches(q: String, vararg keys: String): Boolean =
        keys.any { q.contains(it) }

    private fun plan(snapshot: ScanSnapshot?): String {
        if (snapshot == null) {
            return "Primeiro rode uma varredura. Com o relatório eu monto um plano curto, do mais urgente para o menos."
        }
        if (snapshot.issues.isEmpty()) {
            return "Prioridade agora é manutenção:\n" +
                "1. Manter 15–20% de armazenamento livre.\n" +
                "2. Fechar apps pesados que ficam em segundo plano.\n" +
                "3. Evitar carregar o celular em cima do travesseiro (esquenta a bateria).\n" +
                "4. Atualizar o sistema quando a fabricante publicar OTA."
        }
        val sb = StringBuilder("Plano com base na última varredura (score ${snapshot.score}):\n")
        snapshot.issues.take(6).forEachIndexed { i, issue ->
            sb.append("${i + 1}. ${issue.title} — ${shortTip(issue)}\n")
        }
        if (!snapshot.hasRoot) {
            sb.append("\nSem root, o SysScan não consegue limpar caches do sistema nem ajustar SELinux. Você ainda pode corrigir o que o Android permite pelo botão Corrigir.")
        }
        return sb.toString()
    }

    private fun shortTip(issue: ScanSnapshot.Issue): String {
        val t = (issue.title + " " + issue.detail + " " + issue.category).lowercase()
        return when {
            t.contains("bateria") -> "reduza brilho, feche apps em segundo plano e evite calor."
            t.contains("memór") || t.contains("memor") || t.contains("ram") -> "feche apps pesados ou use Corrigir / otimizar memória."
            t.contains("armazen") || t.contains("cache") -> "apague cache e arquivos grandes; o botão Corrigir abre Armazenamento."
            t.contains("temper") || t.contains("thermal") -> "tire a capa, pause jogos e deixe esfriar."
            t.contains("rede") || t.contains("dns") -> "alterne Wi-Fi/dados ou reabra Rede nas configuracoes."
            t.contains("arquivo essencial") || t.contains("app_process") || t.contains("build.prop") ->
                "falso positivo comum: app_process32 não existe em celular só 64-bit; root não recria arquivo de ROM."
            t.contains("selinux") || t.contains("/system") -> "com root, use Corrigir para remount ro / setenforce 1."
            t.contains("crash") || t.contains("anr") -> "atualize ou desinstale o app que está falhando."
            else -> issue.detail.take(120)
        }
    }

    private fun battery(snapshot: ScanSnapshot?): String {
        val found = snapshot?.issues?.filter {
            it.category.contains("BATTERY", true) ||
                it.title.contains("bateria", true) ||
                it.detail.contains("bateria", true)
        }.orEmpty()
        val sb = StringBuilder()
        if (found.isNotEmpty()) {
            sb.append("Na varredura: ${found.joinToString("; ") { it.detail }}\n\n")
        }
        sb.append(
            "Para a bateria durar mais:\n" +
                "• Brilho automático e 60 Hz se o aparelho permitir.\n" +
                "• Desative 5G se a cobertura for fraca (o rádio gasta mais).\n" +
                "• Restrinja apps que consomem em segundo plano (Uso de bateria).\n" +
                "• Evite carregar até 100% todo dia se o celular esquentar.\n" +
                "• No SysScan, use Corrigir no item de bateria para abrir as configurações certas."
        )
        return sb.toString()
    }

    private fun memory(snapshot: ScanSnapshot?): String {
        val found = snapshot?.issues?.filter {
            it.category.contains("MEMORY", true) || it.category.contains("CPU", true) ||
                it.title.contains("memór", true) || it.title.contains("processo", true)
        }.orEmpty()
        val sb = StringBuilder()
        if (found.isNotEmpty()) {
            sb.append("Na varredura: ${found.joinToString("; ") { it.detail }}\n\n")
        }
        sb.append(
            "Para o aparelho ficar mais fluido:\n" +
                "• Feche apps de redes sociais, jogos e navegador em segundo plano.\n" +
                "• No SysScan, use Corrigir no item de memória (encerra processos pesados).\n" +
                "• Evite launchers e temas com animação pesada.\n" +
                "• Se tiver root, a análise profunda também limpa caches e sincroniza o sistema de arquivos."
        )
        return sb.toString()
    }

    private fun heat(snapshot: ScanSnapshot?): String {
        val found = snapshot?.issues?.filter {
            it.title.contains("temper", true) || it.detail.contains("°C", true) ||
                it.category.contains("CPU", true)
        }.orEmpty()
        val sb = StringBuilder()
        if (found.isNotEmpty()) {
            sb.append("Na varredura: ${found.joinToString("; ") { it.detail }}\n\n")
        }
        sb.append(
            "Aquecimento acelera desgaste da bateria e o sistema reduz o desempenho.\n" +
                "• Pause jogos e recarga rápida ao mesmo tempo.\n" +
                "• Tire a capa por alguns minutos.\n" +
                "• Evite sol direto e carregador genérico que aquece.\n" +
                "• Se o CPU estiver em modo performance (visto só com root), volte para o governador padrão."
        )
        return sb.toString()
    }

    private fun storage(snapshot: ScanSnapshot?): String {
        val found = snapshot?.issues?.filter {
            it.category.contains("STORAGE", true) ||
                it.title.contains("armazen", true) || it.title.contains("cache", true)
        }.orEmpty()
        val sb = StringBuilder()
        if (found.isNotEmpty()) {
            sb.append("Na varredura: ${found.joinToString("; ") { it.detail }}\n\n")
        }
        sb.append(
            "Armazenamento cheio deixa o Android lento e impede atualizações.\n" +
                "• Apague cache pelo botão Corrigir (ou, com root, trim-caches).\n" +
                "• Mova fotos/vídeos para o computador ou nuvem.\n" +
                "• Desinstale apps que não usa.\n" +
                "• Mantenha pelo menos 2–4 GB livres."
        )
        return sb.toString()
    }

    private fun essentialFiles(snapshot: ScanSnapshot?): String {
        val found = snapshot?.issues?.filter {
            it.title.contains("essencial", true) ||
                it.detail.contains("app_process", true) ||
                it.title.contains("build.prop", true)
        }.orEmpty()
        val sb = StringBuilder()
        if (found.isNotEmpty()) {
            sb.append("Na varredura: ${found.joinToString("; ") { it.detail }}\n\n")
        }
        sb.append(
            "Isso quase nunca é arquivo da ROM faltando.\n" +
                "• Celular só 64-bit não tem /system/bin/app_process32 — é normal.\n" +
                "• A partir do Android 10, /system/bin é execute-only: o app não consegue dar stat e o check antigo marcava ausente.\n" +
                "• Como o SysScan está aberto, o Zygote/app_process existe.\n" +
                "• Root/Magisk não restaura binário de sistema. Não ative root por causa desse item.\n" +
                "• Só faria sentido reinstalar a ROM se o aparelho nem ligasse."
        )
        return sb.toString()
    }

    private fun root(snapshot: ScanSnapshot?): String {
        val sb = StringBuilder()
        if (snapshot?.hasRoot == true) {
            sb.append("Root já está ativo nesta varredura. Reparos avançados (cache do sistema, SELinux, remount) ficam disponíveis.\n\n")
        } else {
            sb.append(
                "O Magisk costuma esconder o binário su. No SysScan:\n" +
                    "1. Toque em Status do root.\n" +
                    "2. Aceite o popup Conceder no Magisk (Superuser).\n" +
                    "3. Se o popup não aparecer, abra o Magisk > Superuser e permita o SysScan.\n" +
                    "4. Volte e toque de novo no status, depois rode Nova varredura.\n\n"
            )
        }
        sb.append("Com root o app faz análise profunda: kernel, sensores térmicos, swap, governadores, zumbis, TRIM e partições.")
        return sb.toString()
    }

    private fun network(): String =
        "Se a rede falhar no diagnóstico:\n" +
            "• Alterne modo avião 10 segundos.\n" +
            "• Esqueça o Wi-Fi e reconecte.\n" +
            "• Teste dados móveis.\n" +
            "• DNS falhando costuma ser firewall, VPN ou DNS privado. Desative por um momento e rode a varredura de novo."

    private fun score(snapshot: ScanSnapshot?): String {
        if (snapshot == null) return "Rode uma varredura para eu ler o score."
        return "Score atual: ${snapshot.score}. " +
            "${snapshot.ok} OK, ${snapshot.warning} atenção, ${snapshot.critical} crítico. " +
            if (snapshot.issues.isEmpty()) "Está estável."
            else "O que mais puxa a nota para baixo: " +
                snapshot.issues.take(3).joinToString(", ") { it.title } + "."
    }

    private fun fallback(q: String, snapshot: ScanSnapshot?): String {
        val related = snapshot?.issues?.filter { issue ->
            q.split(" ").any { w -> w.length > 3 && (issue.title.lowercase().contains(w) || issue.detail.lowercase().contains(w)) }
        }.orEmpty()
        if (related.isNotEmpty()) {
            return "Isso aparece na varredura:\n" +
                related.joinToString("\n") { "• ${it.title}: ${it.detail}" } +
                "\n\nSe quiser, peça o plano: \"o que eu faço agora?\""
        }
        return "Posso ajudar com bateria, lentidão, aquecimento, espaço, rede, score e root. " +
            "Pergunte com essas palavras, ou peça o plano de melhoria."
    }
}
