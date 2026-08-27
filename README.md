# SysScan — Varredura e Reparo do Sistema Android

Aplicativo Android nativo (Kotlin) inspirado no `sfc /scannow` do Windows. Ele varre o
aparelho em busca de instabilidades do sistema operacional, gera um "score de saúde"
e oferece ações de correção — incluindo recursos avançados que usam **root**.

## Funcionalidades

### Varredura de diagnóstico (sem root)
- **Bateria**: nível, temperatura e saúde (via `BatteryManager` / `ACTION_BATTERY_CHANGED`)
- **Memória**: memória disponível, baixa memória do sistema e pressão dos processos
- **Processos pesados**: detecção de processos consumindo memória excessiva (acima de 300 MB)
- **Armazenamento**: espaço interno e externo, tamanho do cache
- **Rede**: conectividade, tipo de rede e resolução de DNS
- **CPU**: uso de CPU entre amostras de `/proc/stat`
- **Sensores**: presença dos sensores principais
- **Aplicativos**: quantidade, apps com permissões excessivas, fontes de instalação
- **Consumo de bateria por app**: via `UsageStatsManager` (requer acesso ao uso)
- **Integridade dos pacotes**: detecção de apps instalados com APK ausente/corrompido
- **Falhas recentes**: crashes e ANRs das últimas 24h via `DropBoxManager`
- **Integridade do sistema**: estado de montagem de `/system`, SELinux, arquivos essenciais

### Detecção refinada de root
O app identifica qual método de root está em uso e mostra no status:
- **Magisk** (com número de versão), **KernelSU**, **SuperSU** ou outro
- Presença de **BusyBox** e quantidade de **módulos ativos**
- Suspeita de **root oculto** (ex.: Shamiko) quando há binários `su` sem acesso confirmado

### Reparo
Ações executáveis pelo app (individuais ou em **lote** — "Corrigir tudo"):
- Abrir configurações de bateria, armazenamento, rede, apps e acesso ao uso
- Otimizar memória / encerrar processos em segundo plano

Ações que exigem **root**:
- Limpar caches dos apps (`pm trim-caches`)
- Restaurar proteção da partição `/system` (`mount -o remount,ro`)
- Restaurar SELinux para `Enforcing` (`setenforce 1`)
- Sincronização do sistema de arquivos (`sync`)

### Histórico e dark mode
- **Histórico de varreduras**: as 20 últimas varreduras são salvas com score, contagens e
  data, exibidas em uma tela com **gráfico da evolução do score** (view custom, sem
  dependências externas)
- **Dark mode**: alternância entre claro/escuro pelo botão no cabeçalho

### Atualização automática (OTA)
O app pode se auto-atualizar: consulta a última **Release do GitHub**, compara com a
versão instalada e, se houver novidade, baixa e instala o novo APK. O fluxo completo
(pipeline CI/CD no GitHub + ordem correta) está documentado em `DEPLOY.md`.

## Limitações importantes

O Android **não oferece** um repositório oficial de arquivos de sistema acessível a apps,
como o Windows oferece ao `sfc`. Portanto:

- A restauração de arquivos de sistema corrompidos não é possível por um app comum.
- Para corrupção grave, o caminho correto é reinstalar a ROM original pelo modo de
  recuperação (recovery) ou via atualização OTA.
- O app **identifica** sinais de instabilidade e oferece os reparos possíveis de forma
  segura, sem apagar dados do usuário.

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
│   ├── MemoryDiagnostic.kt      # Inclui detecção de processos pesados
│   ├── StorageDiagnostic.kt
│   ├── NetworkDiagnostic.kt
│   ├── CpuDiagnostic.kt
│   ├── SensorsDiagnostic.kt
│   ├── AppDiagnostic.kt
│   ├── UsageDiagnostic.kt       # Consumo de bateria por app (UsageStats)
│   ├── PackageIntegrityDiagnostic.kt  # Pacotes corrompidos
│   ├── CrashDiagnostic.kt
│   └── SystemIntegrityDiagnostic.kt
├── root/
│   └── RootChecker.kt           # Detecção de método de root, Magisk/KernelSU, BusyBox, módulos
├── repair/
│   └── RepairEngine.kt          # Registro e execução das ações de reparo
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
  --out dist/SysScan-v1.0-release.apk dist/app-aligned.apk
```

O APK assinado fica em `dist/SysScan-v1.1-release.apk` e já pode ser instalado.

> Em um projeto de produção, use seu próprio keystore e não o versione no git.

## Permissões

| Permissão | Uso |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | Diagnóstico de rede e DNS |
| `BATTERY_STATS` | Leitura de dados da bateria |
| `KILL_BACKGROUND_PROCESSES` | Otimização de memória |
| `QUERY_ALL_PACKAGES` | Listar e analisar apps instalados |
| `PACKAGE_USAGE_STATS` | Medir consumo de bateria por app (concedida em Configurações > Acesso especial) |
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
