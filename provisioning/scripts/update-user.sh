#!/usr/bin/env bash
# update-user.sh
# Atualiza a instalação hermes-lab de um usuário para o HEAD atual do lab
# (não usa o installer/updater oficial -- isso reverteria pro stock).
# Uso: ./update-user.sh <email>
# Rodar como root (exige sudo).

set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Uso: $0 <email>" >&2
  exit 1
fi

EMAIL="$1"
LOCAL_PART=$(echo "$EMAIL" | cut -d'@' -f1)
USER_NAME=$(echo "$LOCAL_PART" | sed 's/[^a-zA-Z0-9_]/_/g' | tr '[:upper:]' '[:lower:]')
HERMES_AGENT_DIR="/home/${USER_NAME}/.hermes/hermes-agent"
HERMES_LAB_DEPLOY_KEY="${HERMES_LAB_DEPLOY_KEY:-/root/.ssh/hermes-lab-deploy}"

echo "=========================================================="
echo "== Atualizando hermes-lab para $EMAIL =="
echo "   - Usuário Unix: $USER_NAME"
echo "=========================================================="

if ! id "${USER_NAME}" >/dev/null 2>&1; then
  echo "ERRO: Usuário Unix '${USER_NAME}' não existe." >&2
  exit 1
fi
if [ ! -d "$HERMES_AGENT_DIR/.git" ]; then
  echo "ERRO: $HERMES_AGENT_DIR não é um clone git (instalação stock antiga?)." >&2
  exit 1
fi

echo "→ git pull (root, deploy key, fast-forward only)..."
if [ ! -f "$HERMES_LAB_DEPLOY_KEY" ]; then
  echo "ERRO: deploy key não encontrada em $HERMES_LAB_DEPLOY_KEY." >&2
  exit 1
fi
GIT_SSH_COMMAND="ssh -F /dev/null -o IdentitiesOnly=yes -o UserKnownHostsFile=/root/.ssh/known_hosts -o StrictHostKeyChecking=accept-new -i $HERMES_LAB_DEPLOY_KEY" \
  git -C "$HERMES_AGENT_DIR" pull --ff-only
chown -R "${USER_NAME}:${USER_NAME}" "$HERMES_AGENT_DIR"

echo "→ Ressincronizando dependências..."
sudo -u "${USER_NAME}" bash -c "cd '$HERMES_AGENT_DIR' && printf 'y\nn\n' | ./setup-hermes.sh" || \
  echo "  AVISO: setup-hermes.sh retornou erro -- verifique manualmente." >&2

echo "→ Reiniciando serviços Systemd..."
systemctl restart "${USER_NAME}-dashboard.service" "${USER_NAME}-gateway.service"
sleep 5

echo -n "  dashboard: "; systemctl is-active "${USER_NAME}-dashboard.service" || echo "INATIVO"
echo -n "  gateway:   "; systemctl is-active "${USER_NAME}-gateway.service" || echo "INATIVO"
echo "✓ Atualização concluída para $EMAIL"
