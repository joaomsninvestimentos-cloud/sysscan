package com.sysscan.repair.security

/**
 * Regras heurísticas da varredura de segurança. São puras (sem Android) para poderem ser
 * auditadas e testadas facilmente.
 *
 * IMPORTANTE: nada aqui é uma "assinatura de vírus". O objetivo é **levantar suspeitas**
 * com base em fatos verificáveis no aparelho (app escondido, permissões sensíveis,
 * acessibilidade ativa, origem da instalação, APK fora do lugar, nome que imita outro app).
 * Um indício isolado não significa infecção — por isso cada sinal é explicado ao usuário.
 */
object RiskRules {

    /** Como o app foi instalado. */
    enum class InstallerKind { STORE, MANUAL, THIRD_PARTY, UNKNOWN }

    data class Profile(
        val packageName: String,
        val label: String,
        val isSystemApp: Boolean,
        val hasLauncher: Boolean,
        val permissions: Set<String>,
        val accessibilityEnabled: Boolean,
        val notificationListener: Boolean,
        val isKeyboard: Boolean,
        val deviceAdmin: Boolean,
        val drawOverlays: Boolean,
        val installer: InstallerKind,
        val installerPackage: String?,
        val debuggable: Boolean,
        val targetSdk: Int,
        val apkPath: String,
        val foregroundMs: Long = 0L,
        val batteryShare: Double? = null
    )

    // ---------------------------------------------------------------- permissões

    private const val INSTALL_PACKAGES = "android.permission.REQUEST_INSTALL_PACKAGES"
    private const val OVERLAY = "android.permission.SYSTEM_ALERT_WINDOW"
    private const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    private const val CAMERA = "android.permission.CAMERA"

    private val SMS_PERMISSIONS = setOf(
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.SEND_SMS",
        "android.permission.RECEIVE_MMS",
        "android.permission.READ_INCOMING_MMS"
    )

    private val CALL_PERMISSIONS = setOf(
        "android.permission.READ_CALL_LOG",
        "android.permission.WRITE_CALL_LOG",
        "android.permission.PROCESS_OUTGOING_CALLS",
        "android.permission.CALL_PHONE"
    )

    private val LOCATION_PERMISSIONS = setOf(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION"
    )

    private val CONTACT_PERMISSIONS = setOf(
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS"
    )

    private val OTHER_DANGEROUS = setOf(
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_PHONE_NUMBERS",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.BODY_SENSORS",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
        "android.permission.GET_ACCOUNTS",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
        "android.permission.BIND_DEVICE_ADMIN",
        "android.permission.BIND_VPN_SERVICE"
    )

    /** Permissões mostradas na ficha do app como "sensíveis". */
    fun dangerousPermissions(permissions: Set<String>): List<String> =
        permissions.filter {
            it in SMS_PERMISSIONS || it in CALL_PERMISSIONS || it in LOCATION_PERMISSIONS ||
                it in CONTACT_PERMISSIONS || it in OTHER_DANGEROUS ||
                it == RECORD_AUDIO || it == CAMERA
        }.sorted()

    // ---------------------------------------------------------------- nomes

    private data class Brand(val keywords: List<String>, val packages: Set<String>)

    /** Só marcas cujo pacote oficial temos certeza — evita falso positivo. */
    private val BRANDS = listOf(
        Brand(listOf("whatsapp", "whats app", "zapzap"), setOf("com.whatsapp", "com.whatsapp.w4b")),
        Brand(
            listOf("instagram", "insta"),
            setOf("com.instagram.android", "com.instagram.lite")
        ),
        Brand(
            listOf("facebook"),
            setOf("com.facebook.katana", "com.facebook.lite", "com.facebook.orca")
        ),
        Brand(
            listOf("telegram"),
            setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus")
        ),
        Brand(
            listOf("tiktok", "tik tok"),
            setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
        ),
        Brand(listOf("netflix"), setOf("com.netflix.mediaclient")),
        Brand(listOf("spotify"), setOf("com.spotify.music")),
        Brand(listOf("youtube"), setOf("com.google.android.youtube")),
        Brand(listOf("gmail"), setOf("com.google.android.gm")),
        Brand(listOf("uber"), setOf("com.ubercab"))
    )

    /** Palavras usadas por versões "modificadas" que normalmente carregam malware. */
    private val MOD_TOKENS = listOf(
        "gb", "plus", "pro", "mod", "blue", "gold", "turbo", "delta", "yowa",
        "fm", "og", "apk", "premium", "hack", "unlocked", "crack", "clone",
        "business", "lite"
    )

    /** Nomes associados a apps de monitoramento (stalkerware) conhecidos. */
    private val TRACKER_TOKENS = listOf(
        "mspy", "flexispy", "hoverwatch", "spyzie", "fonewatcher", "kidsguard",
        "cocospy", "minspy", "spyera", "htrack", "trackmyfone", "thetruthspy",
        "mobistealth", "xnspy", "eyezy", "umobix", "spyhuman", "spyier", "spyic",
        "ispyoo", "trackview", "spyapp", "keylogger"
    )

    /** Caminhos onde um APK instalado pelo sistema realmente fica. */
    private val SAFE_APK_PREFIXES = listOf(
        "/data/app/", "/system/", "/system_ext/", "/product/", "/vendor/",
        "/apex/", "/odm/", "/oem/", "/cust/"
    )

    // ---------------------------------------------------------------- avaliação

    fun evaluate(profile: Profile): List<RiskSignal> {
        val signals = mutableListOf<RiskSignal>()
        val permissions = profile.permissions
        val hidden = !profile.hasLauncher && !profile.isSystemApp
        val normalizedLabel = normalize(profile.label)
        val normalizedPackage = profile.packageName.lowercase()

        // 1. app sem ícone na tela / na gaveta
        if (hidden) {
            signals.add(
                RiskSignal(
                    "hidden", "App sem ícone na tela",
                    "Não aparece na tela inicial nem na lista de apps. Pode ser um serviço " +
                        "legítimo (teclado, complemento de relógio) ou algo escondido de propósito.",
                    18
                )
            )
            if (profile.foregroundMs > 0L) {
                signals.add(
                    RiskSignal(
                        "hidden_used", "App oculto em uso",
                        "Sem ícone, mas o Android registrou uso recente dele na tela.",
                        10
                    )
                )
            }
            val share = profile.batteryShare
            if (share != null && share >= 3.0) {
                signals.add(
                    RiskSignal(
                        "hidden_battery", "App oculto consumindo bateria",
                        String.format(java.util.Locale.US, "%.0f", share) +
                            "% do consumo de bateria vem dele, mesmo sem aparecer na tela.",
                        10
                    )
                )
            }
        }

        // 2. nome ligado a apps de monitoramento
        val trackerHit = TRACKER_TOKENS.firstOrNull {
            normalizedLabel.contains(it) || normalizedPackage.contains(it)
        }
        if (trackerHit != null) {
            signals.add(
                RiskSignal(
                    "tracker_name", "Nome associado a app de monitoramento",
                    "O nome contém \"$trackerHit\", usado por apps de rastreamento/spy. Se você " +
                        "não instalou isso de propósito, remova.",
                    45
                )
            )
        }

        // 3. imitação de app famoso (só quando o nome COMEÇA com a marca, para não
        //    confundir apps legítimos que apenas contêm a palavra, como "Instacart")
        val brand = BRANDS.firstOrNull { candidate ->
            candidate.keywords.any { keyword -> normalizedLabel.startsWith(keyword) }
        }
        if (brand != null && profile.packageName !in brand.packages) {
            val modded = MOD_TOKENS.any { hasToken(normalizedLabel, it) }
            val exactName = brand.keywords.any { normalizedLabel.trim() == it }
            when {
                modded -> signals.add(
                    RiskSignal(
                        "imitation_mod", "Versão modificada de app famoso",
                        "O nome imita um app conhecido com palavra de versão alterada " +
                            "(\"GB\", \"Plus\", \"Mod\", \"Pro\"...). Essas versões costumam " +
                            "trazer código malicioso e roubo de conta.",
                        40
                    )
                )
                exactName -> signals.add(
                    RiskSignal(
                        "imitation_name", "Nome de app famoso em pacote diferente",
                        "Usa o nome de uma marca conhecida (${profile.label}) mas o identificador " +
                            "(${profile.packageName}) não é o oficial. Verifique antes de abrir.",
                        30
                    )
                )
            }
        }

        // 4. poderes especiais ativos
        if (profile.accessibilityEnabled) {
            signals.add(
                RiskSignal(
                    "accessibility", "Serviço de acessibilidade ativo",
                    "Com acessibilidade o app consegue ler a tela e tocar por você. É o caminho " +
                        "preferido de golpes e spyware. Confirme se foi você quem ativou.",
                    25
                )
            )
        }
        if (profile.notificationListener) {
            signals.add(
                RiskSignal(
                    "notification_listener", "Lê todas as notificações",
                    "Consegue ler notificações de qualquer app, inclusive códigos de banco.",
                    20
                )
            )
        }
        if (profile.deviceAdmin) {
            signals.add(
                RiskSignal(
                    "device_admin", "Administrador do aparelho",
                    "Pode bloquear, apagar dados e impedir a desinstalação. Só apps de " +
                        "segurança/empresa deveriam ter isso.",
                    18
                )
            )
        }
        if (profile.isKeyboard) {
            signals.add(
                RiskSignal(
                    "keyboard", "Teclado de terceiros ativo",
                    "Teclados veem tudo o que você digita, inclusive senhas. Mantenha só os " +
                        "que você confia.",
                    8
                )
            )
        }

        // 5. permissões sensíveis
        if (permissions.any { it in SMS_PERMISSIONS }) {
            signals.add(
                RiskSignal(
                    "sms", "Acesso a SMS",
                    "Pode ler/enviar mensagens — usado para roubar códigos de confirmação.",
                    20
                )
            )
        }
        if (permissions.any { it in CALL_PERMISSIONS }) {
            signals.add(
                RiskSignal(
                    "calls", "Acesso a chamadas e registro",
                    "Pode ver e fazer ligações e ler o histórico de chamadas.",
                    16
                )
            )
        }
        if (RECORD_AUDIO in permissions && !profile.hasLauncher) {
            signals.add(
                RiskSignal(
                    "audio_hidden", "Microfone sem app visível",
                    "Pede acesso ao microfone e não tem ícone na tela.",
                    14
                )
            )
        }
        if (INSTALL_PACKAGES in permissions) {
            signals.add(
                RiskSignal(
                    "install_packages", "Pode instalar outros apps",
                    if (hidden) {
                        "Sem ícone e com poder de instalar outros aplicativos — padrão clássico " +
                            "de app que baixa malware depois."
                    } else {
                        "Pode instalar outros aplicativos. Fora de lojas de apps isso é incomum."
                    },
                    if (hidden) 22 else 12
                )
            )
        }
        if (OVERLAY in permissions || profile.drawOverlays) {
            signals.add(
                RiskSignal(
                    "overlay", "Desenha por cima de outros apps",
                    "Pode sobrepor telas — técnica usada para telas falsas de senha e golpes.",
                    10
                )
            )
        }
        val dangerousCount = dangerousPermissions(permissions).size
        if (dangerousCount >= 8) {
            signals.add(
                RiskSignal(
                    "many_permissions", "Muitas permissões sensíveis",
                    "Pede $dangerousCount permissões sensíveis diferentes de uma vez.",
                    10
                )
            )
        }

        // 6. combinação típica de stalkerware
        val surveillancePower = profile.accessibilityEnabled || profile.notificationListener ||
            profile.deviceAdmin
        val personalData = permissions.any {
            it in SMS_PERMISSIONS || it in CALL_PERMISSIONS || it in LOCATION_PERMISSIONS ||
                it in CONTACT_PERMISSIONS
        } || RECORD_AUDIO in permissions
        if (surveillancePower && personalData) {
            signals.add(
                RiskSignal(
                    "spy_combo", "Combinação típica de app espião",
                    "Tem poder de vigiar a tela/notificações E acesso a dados pessoais " +
                        "(mensagens, chamadas, localização ou microfone).",
                    30
                )
            )
        }

        // 7. origem da instalação
        when (profile.installer) {
            InstallerKind.UNKNOWN -> signals.add(
                RiskSignal(
                    "installer_unknown", "Origem de instalação desconhecida",
                    "O Android não sabe qual app/loja instalou este pacote (típico de " +
                        "instalação por ADB ou por ferramenta externa).",
                    12
                )
            )
            InstallerKind.THIRD_PARTY -> signals.add(
                RiskSignal(
                    "installer_app", "Instalado por outro aplicativo",
                    "Foi instalado por um app comum" +
                        (profile.installerPackage?.let { " ($it)" } ?: "") +
                        " e não por uma loja. É assim que instaladores maliciosos agem.",
                    14
                )
            )
            InstallerKind.MANUAL -> signals.add(
                RiskSignal(
                    "installer_manual", "Instalado manualmente (fora da loja)",
                    "APK instalado à mão. Só é risco se você não reconhece o app.",
                    8
                )
            )
            InstallerKind.STORE -> Unit
        }

        // 8. características técnicas anormais
        if (profile.debuggable) {
            signals.add(
                RiskSignal(
                    "debuggable", "App em modo de depuração",
                    "Build de teste (debuggable) instalado no aparelho: qualquer app pode se " +
                        "conectar e ler os dados dele.",
                    20
                )
            )
        }
        if (!profile.isSystemApp) {
            if (profile.targetSdk in 1..20) {
                signals.add(
                    RiskSignal(
                        "old_target", "App muito antigo (Android antigo)",
                        "Compilado para Android ${profile.targetSdk} ou menos — sem as " +
                            "proteções de segurança atuais.",
                        16
                    )
                )
            } else if (profile.targetSdk in 21..22) {
                signals.add(
                    RiskSignal(
                        "old_target", "App desatualizado",
                        "Compilado para Android ${profile.targetSdk}. Perde proteções recentes " +
                            "do sistema.",
                        10
                    )
                )
            }
        }
        if (profile.apkPath.isNotBlank() &&
            SAFE_APK_PREFIXES.none { profile.apkPath.startsWith(it) }
        ) {
            signals.add(
                RiskSignal(
                    "apk_path", "Arquivo do app fora do lugar",
                    "O APK está em ${profile.apkPath}, fora das pastas normais de instalação.",
                    30
                )
            )
        }

        return signals
    }

    fun score(signals: List<RiskSignal>): Int =
        signals.sumOf { it.points }.coerceIn(0, 100)

    fun levelFor(score: Int): RiskLevel = when {
        score >= 60 -> RiskLevel.CRITICAL
        score >= 40 -> RiskLevel.HIGH
        score >= SecurityScanReport.SUSPICIOUS_SCORE -> RiskLevel.MEDIUM
        score > 0 -> RiskLevel.LOW
        else -> RiskLevel.CLEAN
    }

    /** Cor sugerida para o nível (recurso do app). */
    fun colorResFor(level: RiskLevel): Int = when (level) {
        RiskLevel.CRITICAL, RiskLevel.HIGH -> com.sysscan.repair.R.color.crit_red
        RiskLevel.MEDIUM -> com.sysscan.repair.R.color.warn_amber
        RiskLevel.LOW -> com.sysscan.repair.R.color.info_blue
        RiskLevel.CLEAN -> com.sysscan.repair.R.color.ok_green
    }

    private fun normalize(text: String): String =
        text.lowercase().replace("_", " ").trim()

    private fun hasToken(label: String, token: String): Boolean =
        label.split(' ', '-', '.', '(', ')', '[', ']', '+', '/').any { it == token } ||
            label.contains(" $token ") || label.endsWith(" $token") ||
            label.startsWith("$token ")
}
