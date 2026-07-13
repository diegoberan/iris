# Overlays

Cada subpasta é um perfil de deploy. `provision-user.sh <email> <overlay> [lang]`
aplica: Layer 0 (clone do hermes-lab + template de instância base) → Layer 1
(este overlay) → Layer 2 (features, dentro do mesmo overlay).

## Arquivos reconhecidos (todos opcionais — ausente = comportamento padrão)

| Arquivo/pasta | Efeito | Padrão se ausente |
|---|---|---|
| `port` | Porta fixa do dashboard (um número, ex.: `9130`) | Hash determinístico do e-mail (9100-9599) |
| `yolo` | Conteúdo `1` liga `HERMES_YOLO_MODE=1` no systemd | Desligado (approval gate normal do Hermes) |
| `sudo-policy` | Uma linha: `none`\|`restart-only`\|`install-scoped`\|`full-with-password` | `none` |
| `config.d/*.yaml` | Concatenado no fim do `config.yaml` (merge simples) | Só o `config.yaml` base |
| `env.d` | Linhas `CHAVE=valor` extras anexadas ao `.env` | Só o `.env` base |
| `bws-project-id` | ID do projeto no Bitwarden Secrets Manager — todo secret do projeto vira `CHAVE=valor` no `.env`, sobrescrevendo `env.d` se houver conflito | Nenhum pull do Bitwarden |
| `persona/SOUL.md`, `persona/AGENTS.md` | Substitui o Blank Slate padrão — **onboarding não roda** | Blank Slate (usuário define tudo na primeira conversa) |
| `skills.extra/<nome>/` | Copiada pra `~/.hermes/skills/<nome>/` | Nenhuma skill extra |
| `gtd-install` | Presença (qualquer conteúdo) dispara `hermes-gtd/install.py --lang <lang>` | GTD não instalado |
| `plugins/` | Copiado pra `~/.hermes/plugins/` | Nenhum plugin extra (além do que o `gtd-install` traz) |
| `features.yaml` | Concatenado no fim do `config.yaml`, depois do `config.d` — é aqui que ligam as features de teste (Speech Router, Local Services, desktop-session) | Features desligadas (padrão do hermes-lab é dormente) |
| `systemd-extra/<nome>.service.template` | Vira `<user>-<nome>.service`, habilitado e iniciado junto | Nenhum serviço extra |

## Segredos

`provision-user.sh` gera senha Unix e basic-auth do dashboard, salva em
`/root/.hermes-provision-secrets/<user>-*` (só root lê). **Mover pro Bitwarden
e apagar o arquivo local depois de confirmar o deploy** — não é automático de
propósito (rotação de segredo é decisão humana, não deveria ser automática).

Chaves de API (`OPENCODE_GO_API_KEY`, etc.) **não são geradas** — vêm de
`bws-project-id` (Bitwarden) ou `env.d` (só pra placeholders/valores não
sensíveis). Nunca commitar valor real em `env.d`.

## Promovendo uma feature de teste pra outro overlay

Feature validada num perfil (ex.: `diego`) e pronta pra outro (ex.: `adria`):
copiar o bloco relevante de `diego/features.yaml` pra `adria/features.yaml` e
rodar o provision de novo (ou, pra não perder dados, aplicar manualmente as
mesmas chaves no `config.yaml` do usuário e reiniciar os serviços).
