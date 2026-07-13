#!/usr/bin/env bash
# delete-user.sh
# Remove permanentemente a instância Hermes e o usuário Linux da VPS.
# Uso: ./delete-user.sh <email>
# Rodar como root (exige sudo).

set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Uso: $0 <email>" >&2
  exit 1
fi

EMAIL="$1"

# 1. Derivar o nome do usuário Linux a partir do e-mail
LOCAL_PART=$(echo "$EMAIL" | cut -d'@' -f1)
USER_NAME=$(echo "$LOCAL_PART" | sed 's/[^a-zA-Z0-9_]/_/g' | tr '[:upper:]' '[:lower:]')

echo "=========================================================="
echo "== Excluindo permanentemente a instância de $EMAIL =="
echo "   - Usuário Unix a ser removido: $USER_NAME"
echo "=========================================================="

# 1. Parar e desabilitar os serviços no Systemd
echo "→ Parando e desabilitando serviços Systemd..."
systemctl stop "${USER_NAME}-dashboard.service" "${USER_NAME}-gateway.service" 2>/dev/null || true
systemctl disable "${USER_NAME}-dashboard.service" "${USER_NAME}-gateway.service" 2>/dev/null || true

# 2. Remover arquivos do Systemd
echo "→ Removendo definições de serviço..."
rm -f "/etc/systemd/system/${USER_NAME}-dashboard.service" \
      "/etc/systemd/system/${USER_NAME}-gateway.service"
systemctl daemon-reload

# 3. Remover helpers de sudoers e restart
echo "→ Removendo helpers operacionais e regras de sudoers..."
rm -f "/usr/local/bin/restart-dashboard-${USER_NAME}" \
      "/etc/sudoers.d/${USER_NAME}-dashboard"

# 4. Encerrar processos ativos do usuário e excluí-lo
echo "→ Encerrando processos remanescentes..."
if id "${USER_NAME}" >/dev/null 2>&1; then
  pkill -u "${USER_NAME}" 2>/dev/null || true
  sleep 2
  echo "→ Deletando usuário Unix e home directory de ${USER_NAME}..."
  userdel -r "${USER_NAME}" 2>/dev/null || true
fi

echo "✓ Instância e dados de $EMAIL foram removidos com sucesso!"
