#!/usr/bin/env bash
# provision-user.sh
# Provisiona (do zero) um Hermes ISOLADO de um usuário na VPS, a partir do
# hermes-lab (não do installer oficial) — todo mundo roda o mesmo código,
# a diferença entre perfis é só o overlay aplicado por cima.
#
# Camadas (ver provisioning/overlays/README.md):
#   Layer 0 BASE      — clone do hermes-lab + templates/instance/default
#   Layer 1 OVERLAY   — overlays/<overlay>/ (config, persona, sudo, features, skills, systemd)
#   Layer 2 FEATURES  — flags dentro do overlay, código já existe no hermes-lab
#   Layer 3 DADOS     — fora do escopo deste script (restore manual se necessário)
#
# Uso: ./provision-user.sh <email> <overlay_name> [lang]
# Exemplo: ./provision-user.sh diego@pessoal diego pt
# Idempotente: derruba o que existir para o usuário e reconstrói limpo.
# Rodar como root (exige sudo). NUNCA rode isto contra um usuário com dados
# que você quer preservar — é destrutivo por design (teardown completo).

set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "Uso: $0 <email> <overlay_name> [lang]" >&2
  echo "Overlays disponíveis: $(ls "$(dirname "$0")/../overlays" 2>/dev/null | tr '\n' ' ')" >&2
  exit 1
fi

EMAIL="$1"
OVERLAY_NAME="$2"
LANG_CODE="${3:-pt}"

if [[ ! "$EMAIL" =~ ^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$ ]]; then
  echo "ERRO: E-mail inválido '$EMAIL'." >&2
  exit 1
fi
if [ "$LANG_CODE" != "pt" ] && [ "$LANG_CODE" != "en" ]; then
  echo "ERRO: Idioma inválido. Use 'pt' ou 'en'." >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROVISIONING_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TEMPLATES_DIR="$PROVISIONING_ROOT/templates"
OVERLAY_DIR="$PROVISIONING_ROOT/overlays/$OVERLAY_NAME"
HERMES_LAB_GIT_URL="${HERMES_LAB_GIT_URL:-git@github.com:diegoberan/hermes-lab.git}"
# Deploy key read-only, dedicada, root-only -- root clona por conta do usuário
# novo (que nunca vê nem precisa de credencial git nenhuma). Ver provisioning/README.md.
HERMES_LAB_DEPLOY_KEY="${HERMES_LAB_DEPLOY_KEY:-/root/.ssh/hermes-lab-deploy}"

if [ ! -d "$OVERLAY_DIR" ]; then
  echo "ERRO: overlay '$OVERLAY_NAME' não encontrado em $PROVISIONING_ROOT/overlays/" >&2
  exit 1
fi

LOCAL_PART=$(echo "$EMAIL" | cut -d'@' -f1)
USER_NAME=$(echo "$LOCAL_PART" | sed 's/[^a-zA-Z0-9_]/_/g' | tr '[:upper:]' '[:lower:]')
DISPLAY_NAME="$(echo "${USER_NAME:0:1}" | tr '[:lower:]' '[:upper:]')${USER_NAME:1}"

# Porta: fixa no overlay (arquivo `port`), senão hash determinístico do e-mail
# (faixa 9100-9599) como fallback pra overlays que não declaram uma.
if [ -f "$OVERLAY_DIR/port" ]; then
  PORT="$(cat "$OVERLAY_DIR/port")"
else
  HEX_HASH=$(echo -n "$EMAIL" | md5sum | cut -d' ' -f1 | cut -c1-8)
  DEC_HASH=$((16#$HEX_HASH))
  PORT=$((9100 + (DEC_HASH % 500)))
fi

# Porta do Iris Orchestrator (loopback, por tenant): derivada da porta do
# dashboard pra nunca colidir entre tenants. Substituída em {{ORCH_PORT}}
# nos arquivos do overlay (config.d/*.yaml e env.d).
ORCH_PORT=$((PORT + 1000))

HERMES_HOME="/home/$USER_NAME/.hermes"
HERMES_AGENT_DIR="$HERMES_HOME/hermes-agent"
VENV="$HERMES_AGENT_DIR/venv"
SECRETS_DIR="/root/.hermes-provision-secrets"

echo "=========================================================="
echo "== Provisionando '$OVERLAY_NAME' para $EMAIL =="
echo "   - Usuário Linux: $USER_NAME"
echo "   - Porta Dashboard: $PORT"
echo "   - Idioma: $LANG_CODE"
echo "=========================================================="

# == 0. Teardown do que existir ==
echo "== 0. Teardown =="
systemctl stop "${USER_NAME}-dashboard.service" "${USER_NAME}-gateway.service" 2>/dev/null || true
systemctl disable "${USER_NAME}-dashboard.service" "${USER_NAME}-gateway.service" 2>/dev/null || true
rm -f "/etc/systemd/system/${USER_NAME}-dashboard.service" \
      "/etc/systemd/system/${USER_NAME}-gateway.service" \
      "/etc/sudoers.d/${USER_NAME}-dashboard" \
      "/etc/sudoers.d/${USER_NAME}-scoped" \
      "/usr/local/bin/restart-dashboard-${USER_NAME}"
systemctl daemon-reload 2>/dev/null || true
if id "${USER_NAME}" >/dev/null 2>&1; then
  pkill -u "${USER_NAME}" 2>/dev/null || true; sleep 2
  userdel -r "${USER_NAME}" 2>/dev/null || true
fi

# == 1. Usuário Unix + senha gerada ==
echo "== 1. Criando usuário Unix '${USER_NAME}' =="
useradd -m -s /bin/bash "${USER_NAME}"
chmod 750 "/home/${USER_NAME}"

mkdir -p "$SECRETS_DIR"
chmod 700 "$SECRETS_DIR"
USER_PASSWORD="$(openssl rand -base64 18)"
echo "${USER_NAME}:${USER_PASSWORD}" | chpasswd
printf '%s' "$USER_PASSWORD" > "$SECRETS_DIR/${USER_NAME}-unix-password"
chmod 600 "$SECRETS_DIR/${USER_NAME}-unix-password"
unset USER_PASSWORD
echo "  Senha Unix gerada -> $SECRETS_DIR/${USER_NAME}-unix-password (mover pro Bitwarden e apagar depois)"

# == 2. Clonar o hermes-lab (não o installer oficial) ==
# Root clona com a deploy key dedicada (read-only, só nesse repo) e entrega a
# posse pro usuário novo depois. O usuário novo nunca toca em credencial git
# nenhuma -- nem a deploy key, nem a chave pessoal do diego.
echo "== 2. Clonando hermes-lab (root, deploy key) =="
if [ ! -f "$HERMES_LAB_DEPLOY_KEY" ]; then
  echo "ERRO: deploy key não encontrada em $HERMES_LAB_DEPLOY_KEY (root-only, 600)." >&2
  exit 1
fi
mkdir -p "$HERMES_HOME"
# Overlay pode pinar o branch/tag clonado (arquivo `branch`); sem ele, clona o
# default do repo. Ex.: overlays demo pinam feat/iris-orchestrator, que carrega
# o Iris Orchestrator (LM Studio Bridge, tiers) que ainda não existe no main.
CLONE_ARGS=()
if [ -f "$OVERLAY_DIR/branch" ]; then
  CLONE_REF="$(tr -d '[:space:]' < "$OVERLAY_DIR/branch")"
  [ -n "$CLONE_REF" ] && CLONE_ARGS=(--branch "$CLONE_REF" --single-branch)
  echo "  branch pinado pelo overlay: ${CLONE_REF:-<vazio>}"
fi
GIT_SSH_COMMAND="ssh -F /dev/null -o IdentitiesOnly=yes -o UserKnownHostsFile=/root/.ssh/known_hosts -o StrictHostKeyChecking=accept-new -i $HERMES_LAB_DEPLOY_KEY" \
  git clone ${CLONE_ARGS[@]+"${CLONE_ARGS[@]}"} "$HERMES_LAB_GIT_URL" "$HERMES_AGENT_DIR"
chown -R "${USER_NAME}:${USER_NAME}" "$HERMES_HOME"

echo "== 2b. Bootstrap (setup-hermes.sh, sem wizard interativo) =="
sudo -u "${USER_NAME}" bash -c "cd '$HERMES_AGENT_DIR' && printf 'y\nn\n' | ./setup-hermes.sh"

# 'hermes' precisa estar no PATH do usuário -- setup-hermes.sh já symlinka em
# ~/.local/bin; garante que existe antes dos passos seguintes.
if [ ! -x "/home/${USER_NAME}/.local/bin/hermes" ]; then
  echo "ERRO: setup-hermes.sh não deixou 'hermes' executável em ~/.local/bin." >&2
  exit 1
fi

# == 3. Instância base (config.yaml/.env/AGENTS/SOUL) ==
echo "== 3. Aplicando template de instância base =="
cp -r "$TEMPLATES_DIR/instance/default/." "$HERMES_HOME/"
[ -f "$HERMES_HOME/.env.template" ] && mv "$HERMES_HOME/.env.template" "$HERMES_HOME/.env"

find "$HERMES_HOME" -maxdepth 1 -type f -exec sed -i \
  -e "s/{{USER_NAME}}/$USER_NAME/g" \
  -e "s/{{DISPLAY_NAME}}/$DISPLAY_NAME/g" \
  -e "s/{{PORT}}/$PORT/g" {} +

DASH_USER="${USER_NAME}"
DASH_PASS="$(openssl rand -hex 6)"
DASH_SECRET="$(openssl rand -base64 32)"
sed -i \
  -e "/^OPENCODE_GO_API_KEY=/d" -e "/^HERMES_DASHBOARD_BASIC_AUTH_/d" \
  -e "/^HERMES_USER_LANGUAGE=/d" -e "/^HERMES_USER_EMAIL=/d" -e "/^PORT=/d" \
  "$HERMES_HOME/.env"
cat >> "$HERMES_HOME/.env" <<EOENV
HERMES_USER_LANGUAGE=$LANG_CODE
HERMES_USER_EMAIL=$EMAIL
PORT=$PORT
HERMES_DASHBOARD_BASIC_AUTH_USERNAME=$DASH_USER
HERMES_DASHBOARD_BASIC_AUTH_PASSWORD=$DASH_PASS
HERMES_DASHBOARD_BASIC_AUTH_SECRET=$DASH_SECRET
EOENV
printf '%s' "$DASH_PASS" > "$SECRETS_DIR/${USER_NAME}-dashboard-password"
chmod 600 "$SECRETS_DIR/${USER_NAME}-dashboard-password"
unset DASH_PASS DASH_SECRET
echo "  Basic auth do dashboard -> $SECRETS_DIR/${USER_NAME}-dashboard-password (usuário: $DASH_USER)"

# == 4. Overlay (Layer 1) ==
echo "== 4. Aplicando overlay '$OVERLAY_NAME' =="

# 4a. config.d/*.yaml -- concatenados no fim do config.yaml (merge simples,
#     último valor declarado vence; hermes lê YAML top-level por chave)
if [ -d "$OVERLAY_DIR/config.d" ]; then
  for f in "$OVERLAY_DIR/config.d"/*.yaml; do
    [ -e "$f" ] || continue
    echo "" >> "$HERMES_HOME/config.yaml"
    sed -e "s/{{USER_NAME}}/$USER_NAME/g" -e "s/{{ORCH_PORT}}/$ORCH_PORT/g" "$f" >> "$HERMES_HOME/config.yaml"
    echo "  config.d: $(basename "$f")"
  done
fi

# 4b. env.d -- chaves extras (nomes das chaves NOVAS a rotacionar ficam como
#     placeholders <...>; valores reais vêm do bws no passo 4b2, se existirem)
if [ -f "$OVERLAY_DIR/env.d" ]; then
  sed -e "s/{{USER_NAME}}/$USER_NAME/g" -e "s/{{ORCH_PORT}}/$ORCH_PORT/g" \
    "$OVERLAY_DIR/env.d" >> "$HERMES_HOME/.env"
  echo "  env.d aplicado"
fi
if [ -f "$OVERLAY_DIR/bws-project-id" ] && command -v bws >/dev/null 2>&1 && [ -f "$SECRETS_DIR/bws-access-token" ]; then
  PROJECT_ID="$(cat "$OVERLAY_DIR/bws-project-id")"
  echo "  Puxando secrets do Bitwarden (projeto $PROJECT_ID)..."
  export BWS_ACCESS_TOKEN
  BWS_ACCESS_TOKEN="$(cat "$SECRETS_DIR/bws-access-token")"
  bws secret list "$PROJECT_ID" 2>/dev/null | python3 -c '
import json, sys
for s in json.load(sys.stdin):
    print(f"{s[\"key\"]}={s[\"value\"]}")
' > /tmp/.bws-inject-$$  || true
  if [ -s /tmp/.bws-inject-$$ ]; then
    while IFS='=' read -r k v; do
      sed -i "/^${k}=/d" "$HERMES_HOME/.env"
      echo "${k}=${v}" >> "$HERMES_HOME/.env"
    done < /tmp/.bws-inject-$$
  fi
  rm -f /tmp/.bws-inject-$$
  unset BWS_ACCESS_TOKEN
fi

# 4c. persona/ -- SOMENTE se o overlay declarar (senão fica o Blank Slate
#     padrão e o usuário define tudo no onboarding)
if [ -f "$OVERLAY_DIR/persona/SOUL.md" ]; then
  sed -e "s/{{USER_NAME}}/$USER_NAME/g" -e "s/{{DISPLAY_NAME}}/$DISPLAY_NAME/g" \
    "$OVERLAY_DIR/persona/SOUL.md" > "$HERMES_HOME/SOUL.md"
  echo "  persona/SOUL.md aplicada (onboarding NÃO vai rodar)"
fi
if [ -f "$OVERLAY_DIR/persona/AGENTS.md" ]; then
  sed -e "s/{{USER_NAME}}/$USER_NAME/g" -e "s/{{DISPLAY_NAME}}/$DISPLAY_NAME/g" \
    "$OVERLAY_DIR/persona/AGENTS.md" > "$HERMES_HOME/AGENTS.md"
fi

# 4d. skills.extra/ -- GTD sempre via hermes-gtd (fonte única); skills
#     puramente do overlay (não-GTD) copiadas direto
mkdir -p "$HERMES_HOME/skills"
if [ -f "$OVERLAY_DIR/gtd-install" ]; then
  GTD_REPO_DIR="${HERMES_GTD_DIR:-$PROVISIONING_ROOT/../../hermes-gtd}"
  if [ -d "$GTD_REPO_DIR" ]; then
    echo "  Instalando hermes-gtd (--lang $LANG_CODE)..."
    # Roda como root -- $GTD_REPO_DIR fica na home do diego (750, sem acesso
    # pro usuário novo). Ownership do que for escrito é corrigido pelo chown
    # -R geral logo depois deste bloco (linha ~254).
    python3 "$GTD_REPO_DIR/install.py" --lang "$LANG_CODE" \
      --hermes-home "$HERMES_HOME" --vault "/home/${USER_NAME}/vault" \
      --bin-dir "/home/${USER_NAME}/.local/bin" --db "/home/${USER_NAME}/gtd.db"
  else
    echo "  AVISO: overlay pede gtd-install mas $GTD_REPO_DIR não existe -- pulei." >&2
  fi
fi
if [ -d "$OVERLAY_DIR/skills.extra" ]; then
  for s in "$OVERLAY_DIR/skills.extra"/*/; do
    [ -d "$s" ] || continue
    cp -r "$s" "$HERMES_HOME/skills/$(basename "$s")"
    echo "  skill extra: $(basename "$s")"
  done
fi

# 4e. plugins/ do overlay (ex.: gtd-board vem pelo install.py acima; outros
#     plugins específicos do overlay entram aqui)
if [ -d "$OVERLAY_DIR/plugins" ]; then
  mkdir -p "$HERMES_HOME/plugins"
  cp -r "$OVERLAY_DIR/plugins/." "$HERMES_HOME/plugins/"
fi

# 4f. features -- flags do Speech Router / Local Services / desktop-session,
#     literal no config.yaml (o código já existe no hermes-lab; isto só liga)
if [ -f "$OVERLAY_DIR/features.yaml" ]; then
  echo "" >> "$HERMES_HOME/config.yaml"
  cat "$OVERLAY_DIR/features.yaml" >> "$HERMES_HOME/config.yaml"
  echo "  features.yaml aplicado"
fi

chown -R "${USER_NAME}:${USER_NAME}" "$HERMES_HOME" "/home/${USER_NAME}"

# == 5. Vault do Obsidian ==
echo "== 5. Estrutura do Vault =="
sudo -u "${USER_NAME}" mkdir -p "/home/${USER_NAME}/vault/_sobre" "/home/${USER_NAME}/vault/GTD" \
  "/home/${USER_NAME}/vault/GTD/Horizons/20k-areas" \
  "/home/${USER_NAME}/vault/GTD/Horizons/30k-metas" \
  "/home/${USER_NAME}/vault/GTD/Horizons/40k-visao" \
  "/home/${USER_NAME}/vault/GTD/Horizons/50k-proposito"

# == 6. Sudo policy (Layer 1) ==
echo "== 6. Aplicando sudo-policy =="
POLICY="none"
[ -f "$OVERLAY_DIR/sudo-policy" ] && POLICY="$(cat "$OVERLAY_DIR/sudo-policy")"
case "$POLICY" in
  none)
    echo "  sudo: nenhum privilégio extra"
    ;;
  restart-only)
    cat > "/etc/sudoers.d/${USER_NAME}-scoped" <<EOF
${USER_NAME} ALL=(root) NOPASSWD: /usr/bin/systemctl restart ${USER_NAME}-dashboard.service
EOF
    chmod 440 "/etc/sudoers.d/${USER_NAME}-scoped"
    echo "  sudo: restart-only (sem senha, escopado ao próprio dashboard)"
    ;;
  install-scoped)
    cat > "/etc/sudoers.d/${USER_NAME}-scoped" <<EOF
${USER_NAME} ALL=(root) PASSWD: /usr/bin/apt-get install *, /usr/bin/apt install *
${USER_NAME} ALL=(root) NOPASSWD: /usr/bin/systemctl restart ${USER_NAME}-dashboard.service
EOF
    chmod 440 "/etc/sudoers.d/${USER_NAME}-scoped"
    echo "  sudo: install-scoped (apt install com senha; restart do dashboard sem senha)"
    ;;
  full-with-password)
    usermod -aG sudo "${USER_NAME}"
    echo "  sudo: full, COM senha (grupo sudo) -- SO ainda pede a senha Unix do usuário"
    ;;
  *)
    echo "  AVISO: sudo-policy '$POLICY' desconhecida, nenhum privilégio aplicado." >&2
    ;;
esac
visudo -c >/dev/null || { echo "  Sudoers INVÁLIDO -- removendo scoped"; rm -f "/etc/sudoers.d/${USER_NAME}-scoped"; }

# == 7. systemd-extra do overlay (ex.: media.service do diego) ==
if [ -d "$OVERLAY_DIR/systemd-extra" ]; then
  echo "== 7. Instalando unidades systemd extras do overlay =="
  for unit in "$OVERLAY_DIR/systemd-extra"/*.service.template; do
    [ -e "$unit" ] || continue
    name="$(basename "$unit" .service.template)"
    sed -e "s/{{USER_NAME}}/$USER_NAME/g" -e "s/{{PORT}}/$PORT/g" \
      "$unit" > "/etc/systemd/system/${USER_NAME}-${name}.service"
    echo "  ${USER_NAME}-${name}.service"
  done
fi

# == 8. Build de pré-aquecimento ==
echo "== 8. Pré-aquecimento do dashboard =="
sudo -u "${USER_NAME}" bash -lc "export PATH=\$HOME/.local/bin:\$HOME/.hermes/node/bin:\$PATH
  nohup hermes dashboard --port $PORT --host 127.0.0.1 --no-open > /home/${USER_NAME}/dash_build.log 2>&1 &"
for i in $(seq 1 36); do
  code=$(curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:$PORT/" 2>/dev/null || echo "000")
  [ "$code" = "200" ] && { echo "  Dashboard pronto (~$((i*10))s)"; break; }
  sleep 10
done
sudo -u "${USER_NAME}" pkill -f 'hermes dashboard' 2>/dev/null || true; sleep 2

# == 9. Systemd (Layer 0, YOLO controlado pelo overlay) ==
echo "== 9. Registrando serviços Systemd =="
YOLO_LINE=""
[ -f "$OVERLAY_DIR/yolo" ] && [ "$(cat "$OVERLAY_DIR/yolo")" = "1" ] && YOLO_LINE="Environment=HERMES_YOLO_MODE=1"

sed -e "s/{{USER_NAME}}/$USER_NAME/g" -e "s/{{PORT}}/$PORT/g" \
    -e "s|{{YOLO_ENV_LINE}}|$YOLO_LINE|g" \
    "$TEMPLATES_DIR/systemd/dashboard.service.template" > "/etc/systemd/system/${USER_NAME}-dashboard.service"
sed -e "s/{{USER_NAME}}/$USER_NAME/g" -e "s/{{PORT}}/$PORT/g" \
    -e "s|{{YOLO_ENV_LINE}}|$YOLO_LINE|g" \
    "$TEMPLATES_DIR/systemd/gateway.service.template" > "/etc/systemd/system/${USER_NAME}-gateway.service"

chown -R "${USER_NAME}:${USER_NAME}" "/home/${USER_NAME}"
systemctl daemon-reload
SERVICES="${USER_NAME}-dashboard.service ${USER_NAME}-gateway.service"
[ -d "$OVERLAY_DIR/systemd-extra" ] && for unit in "$OVERLAY_DIR/systemd-extra"/*.service.template; do
  [ -e "$unit" ] || continue
  SERVICES="$SERVICES ${USER_NAME}-$(basename "$unit" .service.template).service"
done
systemctl enable $SERVICES
systemctl start $SERVICES

# == 10. Verificação ==
echo "== 10. Verificação =="
echo -n "  dashboard: "; systemctl is-active "${USER_NAME}-dashboard.service"
echo -n "  gateway:   "; systemctl is-active "${USER_NAME}-gateway.service"
curl -s -o /dev/null -w "  dashboard HTTP: %{http_code}\n" "http://127.0.0.1:$PORT/" || echo "  dashboard offline!"

echo "=========================================================="
echo "✓ Provisionado: $EMAIL (overlay '$OVERLAY_NAME')"
echo "  Dashboard: http://127.0.0.1:$PORT/"
echo "  Segredos gerados em: $SECRETS_DIR/${USER_NAME}-*"
echo "  >>> Mover pro Bitwarden e apagar os arquivos locais quando confirmar. <<<"
echo "=========================================================="
