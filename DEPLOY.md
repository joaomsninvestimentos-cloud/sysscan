# Fluxo de Atualização Automática (CI/CD + OTA)

Este guia explica a **ordem correta** do fluxo para que uma única atualização no
código gere o APK, publique nas plataformas e atualize o app instalado no celular.

> Versão atual: **3.0.2** — o SysScan não requer nem solicita acesso root. O APK é
> compilado e assinado automaticamente pelo GitHub Actions.

## Visão geral

```
push/tag no GitHub
      │
      ▼
GitHub Actions (CI)              ← build automático
      ├─ compila o APK
      ├─ assina com o keystore
      └─ publica como Release (APK anexado)
      │
      ▼
SysScan no celular               ← auto-atualização (OTA)
      ├─ ao abrir, consulta a última Release (API do GitHub)
      ├─ compara a versão instalada com a disponível
      └─ se houver nova: baixa o APK e pede para instalar
```

Uma única ação (criar uma tag) dispara tudo. Você não precisa subir nada manualmente
nas plataformas.

## Configuração (uma vez)

### 1. Suba o projeto para o GitHub

```bash
git remote add origin https://github.com/joaomsninvestimentos-cloud/sysscan.git
git push -u origin master
```

### 2. Configure os Secrets do repositório

Em **GitHub > Settings > Secrets and variables > Actions**, crie:

| Secret | Valor |
|---|---|
| `KEYSTORE_BASE64` | o arquivo `release.keystore` codificado em base64 |
| `KEYSTORE_PASSWORD` | a senha do keystore (ex.: `sysscan123`) |

Para gerar o `KEYSTORE_BASE64`:

```bash
base64 -w0 release.keystore
```

> O keystore **não** deve ser commitado no repositório (já está no `.gitignore`).

### 3. Configure o repositório no app

Edite `app/src/main/java/com/sysscan/repair/updater/UpdateChecker.kt` e troque:

```kotlin
const val GITHUB_REPO = "joaomsninvestimentos-cloud/sysscan"
```

pelo seu repositório real (`usuario/repositorio`).

## Publicando uma atualização (o que você faz)

Depois de alterar o código e subir, basta criar uma tag:

```bash
git add .
git commit -m "feat: nova funcionalidade"
git push

# Versão da nova atualização (o app está na 3.0.2)
git tag v3.0.2
git push origin v3.0.2
```

O workflow `build-release.yml` roda, compila, assina e publica a Release
`v3.0.2` com o `SysScan.apk` anexado. Não é preciso ter JDK nem Android SDK
na máquina: o build acontece no runner do GitHub.

## Gerar o APK sem publicar (opcional)

Para apenas obter o APK, execute o workflow manualmente em
**GitHub > Actions > Build e Publicar Release > Run workflow**
(`workflow_dispatch`). O APK fica anexado à Release criada.

## Como o celular recebe a atualização

O app, ao tocar no botão de download (canto superior do app), consulta:

```
https://api.github.com/repos/joaomsninvestimentos-cloud/sysscan/releases/latest
```

- Se a versão da última tag for **maior** que a versão instalada, mostra
  "Nova versão disponível".
- Ao confirmar, baixa o APK e abre o instalador do Android.
- A primeira vez, o Android pede para permitir "Instalar apps desconhecidos"
  (conceda uma vez para o SysScan).

> A comparação usa a tag da Release (sem o prefixo `v`) contra o
> `versionName` do app. Ex.: tag `v3.0.2` atualiza um app na versão `2.5`.

## Como aplicar o mesmo fluxo ao seu app financeiro (outra IA)

O princípio é idêntico, independente de onde o app foi criado:

1. Coloque o código Android no GitHub (se ainda não está).
2. Copie o workflow `.github/workflows/build-release.yml` para o repositório
   (ajuste o nome do arquivo APK e o keystore, se necessário).
3. No app, adicione um "verificador de atualização" que consulta
   `https://api.github.com/repos/<usuario>/<repo>/releases/latest` — pode
   reutilizar o `UpdateChecker.kt` deste projeto.
4. Configure os Secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`).
5. Publique versões criando tags `vX.Y`.

## Vercel (página de download)

Para a página no Vercel sempre apontar para o APK mais recente:

- **Opção A (simples):** a página só precisa ter um link direto para
  `https://github.com/<usuario>/<repo>/releases/latest/download/SysScan.apk`
  — o GitHub redireciona esse link para o APK da última Release, sempre.
- **Opção B (deploy automático):** adicione um workflow extra que, na tag `v*`,
  chama a API de deploy do Vercel (`vercel deploy --prod`) com um webhook/vercel-token.

## Observações

- Atualização automática **silenciosa** (sem pedir confirmação) não é possível no
  Android fora da Play Store — o sistema sempre exige a aprovação do usuário na
  instalação de APK.
- Para distribuição por loja, o mesmo pipeline pode publicar no lançamento interno
  do Google Play (requer o plugin `com.github.triplet.play` + credenciais de serviço).
