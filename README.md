# SysScan — Análise e Diagnóstico do Sistema Android

Aplicativo Android nativo (Kotlin) inspirado no `sfc /scannow` do Windows. Ele varre o
aparelho em busca de instabilidades do sistema operacional, gera um "score de saúde" e
oferece ações de correção seguras — **tudo sem root**.

## Funcionalidades

### Varredura de diagnóstico (sem root)
- **Bateria**: nível, temperatura, saúde e **corrente de descarga agora** (mA / autonomia
  estimada) via `BatteryManager` / `ACTION_BATTERY_CHANGED`
- **Memória**: memória disponível, baixa memória do sistema e pressão dos processos
- **Processos pesados**: detecção de processos consumindo memória excessiva (acima de 300 MB)
- **Armazenamento**: espaço interno e externo, tamanho do cache do app
- **Rede**: conectividade, tipo de rede e resolução de DNS
- **CPU**: uso de CPU entre amostras de `/proc/stat`
- **Sensores**: presença dos sensores principais
- **Aplicativos**: quantidade, apps com permissões excessivas, fontes de instalação
- **Consumo de bateria por app (novo)**: descobre **qual aplicativo está gastando a bateria**,
  cruzando tempo de tela, sessões, tráfego de rede móvel/Wi-Fi, atividade em segundo plano e
  isenção do Doze (`UsageStatsManager` + `NetworkStatsManager` + `PowerManager`). Requer
  acesso ao uso; sem root.
- **Integridade dos pacotes**: detecção de apps instalados com APK ausente/corrompido
- **Falhas recentes**: crashes e ANRs das últimas 24h via `DropBoxManager`
- **Integridade do sistema**: estado de montagem de `/system`, SELinux e arquivos essenciais
- **Segurança (novo)**: apps **ocultos** (sem ícone), apps com poderes especiais
  (acessibilidade, leitura de notificações, administrador), indícios de spyware/malware e
  verificação de APK no VirusTotal

### Tela "Quem gasta a bateria?" (novo)

Botão na tela inicial (abaixo de *Iniciar varredura*) que abre o detalhamento do consumo:

- **Consumo agora**: corrente real de descarga do aparelho em **mA** (`CURRENT_NOW`), autonomia
  estimada, nível e temperatura. O botão *Medir de novo* tira uma média de 4 leituras.
- **Ranking por app**: para cada aplicativo, tempo em uso, número de aberturas, dados
  trafegados, **peso de consumo (0–100)** e **fatia do consumo estimado (%)**, com barra
  colorida (verde → vermelho).
- **Sinais**: marca apps com *atividade em segundo plano* (tráfego móvel alto e quase nenhuma
  tela) e apps *isentos da otimização de bateria* (fora do Doze).
- **Janelas de 1h / 6h / 24h** para ver o que pesou agora ou no dia.
- Toque em um app para ver os detalhes e usar **Restringir bateria** (abre os ajustes do app,
  onde se escolhe *Restringir*/*Otimizar*) ou **Abrir app**.
- O relatório fica salvo no aparelho e alimenta o assistente ("qual app gasta mais bateria?").

Como o Android **não entrega mAh por aplicativo** para apps comuns (isso exige a permissão
privilegiada `BATTERY_STATS`/`dumpsys batterystats`), o SysScan usa os sinais acima para
montar o ranking. Com o uso, ele também aprende o consumo real do aparelho (mAh/h) a partir
de amostras de carga e passa a estimar os mAh de cada app.

### Tela "Verificar vírus e apps ocultos" (novo)

Botão na tela inicial que abre a varredura de segurança. Ela cruza fatos verificáveis no
aparelho para achar o que costuma passar despercebido:

- **Apps sem ícone** — instalados pelo usuário mas ausentes da tela inicial e da gaveta de
  apps (aqueles que "existem e você não sabia"). Teclados e complementos legítimos também
  caem aqui, por isso cada um é explicado.
- **Poderes especiais ativos** — acessibilidade (vê a tela e toca por você), leitura de todas
  as notificações (inclui códigos de banco), administrador do aparelho (bloqueia
  desinstalação) e teclados de terceiros.
- **Poderes perigosos** — permissões de SMS, chamadas, localização, contatos, microfone,
  desenhar sobre outros apps e **instalar outros apps**.
- **Imitação de app famoso** — "WhatsApp GB/Plus", "Insta Pro", "Netflix Mod": nome de marca
  em pacote diferente do oficial.
- **Origem da instalação** — loja, APK manual, instalado por outro aplicativo ou origem
  desconhecida; builds **debuggable**; app compilado para Android antigo; **APK fora do
  lugar** ou inválido.
- **Cruzamento com bateria** — app oculto que aparece consumindo bateria ganha peso extra
  (é o cenário de app espião rodando em segundo plano).
- **VirusTotal (opcional)** — com a chave gratuita do usuário, o app envia **apenas o hash
  SHA-256** do APK (o arquivo não sai do aparelho) e mostra o veredito de ~60 antivírus.

Cada app tem uma ficha com todos os indícios, quanto cada um pesou no risco (0–100) e
botões para **desinstalar**, abrir os **ajustes do app** ou consultar o VirusTotal.

#### O que essa varredura consegue e o que não consegue

Ela **não é** um antivírus com assinaturas e roda **sem root**. Consegue: listar todos os
pacotes (inclusive ocultos), permissões, poderes ativos, origem da instalação, hash do APK e
cruzar com o consumo de bateria. **Não** consegue: ler todos os arquivos do sistema, achar
binários soltos em `/data`, inspecionar o conteúdo interno de cada APK ou remover malware
automaticamente. Por isso ela aponta **indícios explicados** e leva o usuário à ação correta
(desinstalar, revogar acessibilidade, trocar senhas). Nenhuma permissão nova foi adicionada —
usa as mesmas `QUERY_ALL_PACKAGES` e `PACKAGE_USAGE_STATS` que o app já declarava.

### Filtros OK / Atenção / Crítico
Os contadores são botões: toque em **Atenção** ou **Crítico** para filtrar e ir direto ao
primeiro item daquele tipo. Toque de novo para voltar à lista completa.

### Reparo
Todas as ações são executáveis pelo próprio app, sem privilégios especiais:
- Abrir configurações de bateria, armazenamento, rede, apps e acesso ao uso
- Otimizar memória / encerrar processos em segundo plano

As ações que exigem intervenção manual (como reinstalar a ROM) são apenas indicadas, sem
execução automática.

### Histórico e dark mode
- **Histórico de varreduras**: as 20 últimas varreduras são salvas com score, contagens e
  data, exibidas em uma tela com **gráfico da evolução do score** (view custom, sem
  dependências externas)
- **Dark mode**: alternância entre claro/escuro pelo botão no cabeçalho

### Atualização automática (OTA)
O app consulta `joaomsninvestimentos-cloud/sysscan` na API do GitHub (ao abrir e no
ícone de download). Se a última Release tiver um APK com versão maior, baixa e instala.
O fluxo CI/CD está em `DEPLOY.md`.

### Assistente (chat)
Ícone de conversa no topo. Lê a última varredura e responde com um plano de melhoria
(bateria, lentidão, calor, espaço, rede, **vírus e apps ocultos**). Funciona **offline**. Opcionalmente, no
próprio chat, cole sua chave `USER_LLM_API_KEY` (API compatível com OpenAI, ex. DeepSeek)
para respostas mais livres.

## Limitações importantes

O Android **não oferece** um repositório oficial de arquivos de sistema acessível a apps,
como o Windows oferece ao `sfc`. Portanto:

- A restauração de arquivos de sistema corrompidos não é possível por um app comum.
- Para corrupção grave, o caminho correto é reinstalar a ROM original pelo modo de
  recuperação (recovery) ou via atualização OTA.
- O app **identifica** sinais de instabilidade e oferece os reparos possíveis de forma
  segura, sem apagar dados do usuário.
- O SysScan **não requer e não solicita acesso root**.

## Estrutura do projeto

```
app/src/main/java/com/sysscan/repair/
├── MainActivity.kt              # Tela principal
├── ScanViewModel.kt             # Estado da varredura (StateFlow), reparo em lote
├── ScanResultsAdapter.kt        # Lista de resultados
├── model/
│   ├── ScanModels.kt            # Severidade, categoria, checks, resumo e score
│   └── ScanCheckBuilder.kt      # Helpers de construção de checks
├── diag/
│   ├── SystemDiagnostics.kt     # Orquestra a varredura (com progresso)
│   ├── BatteryDiagnostic.kt
│   ├── BatteryUsageDiagnostic.kt  # Ranking de consumo por app + corrente (mA)
│   ├── battery/
│   │   ├── BatteryUsageAnalyzer.kt  # Cruza uso, sessões, rede e Doze
│   │   ├── BatteryScoring.kt        # Peso de consumo (0–100) e fatias
│   │   ├── BatteryLiveMeter.kt      # CURRENT_NOW / CHARGE_COUNTER (mA, autonomia)
│   │   ├── BatteryDrainTracker.kt   # Amostras de carga → mAh/h observados
│   │   ├── BatteryUsageModels.kt
│   │   └── BatteryUsageStore.kt     # Último relatório salvo
│   ├── MemoryDiagnostic.kt      # Inclui detecção de processos pesados
│   ├── StorageDiagnostic.kt
│   ├── NetworkDiagnostic.kt
│   ├── CpuDiagnostic.kt
│   ├── SensorsDiagnostic.kt
│   ├── AppDiagnostic.kt
│   ├── PackageIntegrityDiagnostic.kt  # Pacotes corrompidos
│   ├── CrashDiagnostic.kt
│   ├── SecurityDiagnostic.kt    # Apps ocultos, espiões e malware
│   └── SystemIntegrityDiagnostic.kt
├── battery/
│   └── BatteryUsageActivity.kt  # Tela "Quem gasta a bateria?"
├── security/
│   ├── SecurityActivity.kt      # Tela "Verificar vírus e apps ocultos"
│   ├── SecurityViewModel.kt     # Estado da análise + consultas ao VirusTotal
│   ├── SecurityScanner.kt       # Coleta pacotes, poderes, origem e APK
│   ├── RiskRules.kt             # Heurísticas (peso de cada indício)
│   ├── SecurityModels.kt
│   ├── ApkInspector.kt          # SHA-256 / validade do ZIP
│   ├── VtClient.kt              # Consulta por hash no VirusTotal
│   ├── SecurityHashStore.kt     # Cache de hashes e laudos
│   ├── SecurityResultsStore.kt  # Último resultado (assistente)
│   └── SecurityAppAdapter.kt
├── repair/
│   └── RepairEngine.kt          # Registro e execução das ações de reparo
├── advisor/
│   ├── ChatActivity.kt          # Chat de dicas (local + LLM opcional)
│   ├── DeviceAdvisor.kt         # Respostas com base na última varredura
│   └── LlmClient.kt             # Cliente OpenAI-compatible (USER_LLM_*)
└── history/
    ├── ScanHistoryStore.kt      # Persistência das varreduras (SharedPreferences)
    ├── ScoreChartView.kt        # Gráfico da evolução do score (view custom)
    └── HistoryActivity.kt       # Tela de histórico
```

## Como compilar

Requisitos: JDK 17 e Android SDK (platform 34, build-tools 34).

```bash
# Instala o SDK (exemplo com sdkmanager)
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# Compilar APK de debug
./gradlew assembleDebug

# Compilar APK de release (minificado)
./gradlew assembleRelease
```

Os APKs ficam em `app/build/outputs/apk/debug/` e `app/build/outputs/apk/release/`.

## APK de release assinado

O projeto inclui um keystore de demonstração (`release.keystore`, senha `sysscan123`)
usado para assinar o APK de produção:

```bash
zipalign -f 4 app/build/outputs/apk/release/app-release-unsigned.apk dist/app-aligned.apk
apksigner sign --ks release.keystore --ks-key-alias sysscan \
  --ks-pass pass:sysscan123 --key-pass pass:sysscan123 \
  --out dist/SysScan-v3.0.2-release.apk dist/app-aligned.apk
```

O APK assinado fica em `dist/SysScan-v3.0.2-release.apk` e já pode ser instalado.

> Em um projeto de produção, use seu próprio keystore e não o versione no git.

## Permissões

| Permissão | Uso |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | Diagnóstico de rede e DNS |
| `BATTERY_STATS` | Leitura de dados da bateria |
| `KILL_BACKGROUND_PROCESSES` | Otimização de memória |
| `QUERY_ALL_PACKAGES` | Listar e analisar apps instalados |
| `PACKAGE_USAGE_STATS` | Ranking de consumo por app e tráfego de rede por UID (concedida em Configurações > Acesso especial > Acesso ao uso) |
| `READ_LOGS` | Leitura de registros de falhas (auto-concedida em builds de debug) |

## Como instalar

1. Habilite "Fontes desconhecidas" em Configurações > Segurança.
2. Copie o APK para o celular (ou use `adb install app-debug.apk`).
3. Abra o app e toque em **Iniciar varredura**.

> Permissões especiais (como `READ_LOGS`) são declaradas no manifest e concedidas
> automaticamente em builds de debug.

## Stack

- Kotlin 1.9.24, Android Gradle Plugin 8.3.2, Gradle 8.7
- compileSdk 34, targetSdk 34, minSdk 24 (Android 7.0+)
- Material Components, ViewModel + StateFlow, Coroutines, ViewBinding
- Versão atual: 3.2.0 (sem root)
