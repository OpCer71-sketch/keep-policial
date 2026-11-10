#!/usr/bin/env bash
# Keep Policial - instala a versão nova do servidor baixada do GitHub.
#
# O próprio servidor consulta as Releases do repositório (ao iniciar e uma vez
# por dia), baixa e confere o arquivo novo em /var/lib/keeppolicial/atualizacao.
# Quando o administrador clica em "Instalar agora" no painel, é criado o arquivo
# de pedido INSTALAR; o systemd (keeppolicial-atualizar.path) percebe e roda
# este script como root, que troca o programa e reinicia o servidor.
#
# Também pode ser rodado à mão:  sudo keeppolicial-atualizar
set -euo pipefail

DIR_APP=/opt/keeppolicial
DIR_DADOS=/var/lib/keeppolicial
USUARIO_SVC=keeppolicial
A="$DIR_DADOS/atualizacao"
PEDIDO="$A/INSTALAR"
NOVO="$A/keep_servidor.py"
RES="$A/resultado.json"
ATUAL="$DIR_APP/keep_servidor.py"

if [ "$(id -u)" -ne 0 ]; then
    echo "Execute como administrador:  sudo keeppolicial-atualizar"
    exit 1
fi

registrar() {  # registrar ok|erro "mensagem"
    python3 -I -c 'import json,sys,datetime
json.dump({"situacao": sys.argv[2], "mensagem": sys.argv[3],
           "em": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")},
          open(sys.argv[1], "w", encoding="utf-8"), ensure_ascii=False)' "$RES" "$1" "$2"
    chown "$USUARIO_SVC:$USUARIO_SVC" "$RES" 2>/dev/null || true
    chmod 600 "$RES"
    echo "$2"
}

# Rodado à mão sem pedido pendente: consulta o GitHub e pede a instalação.
if [ ! -f "$PEDIDO" ] && [ -t 1 ]; then
    sudo -u "$USUARIO_SVC" env KEEP_DADOS="$DIR_DADOS" \
        python3 "$ATUAL" atualizacao --instalar || true
fi
[ -f "$PEDIDO" ] || { echo "Nenhuma instalação pendente."; exit 0; }

TMP="$(mktemp "$DIR_APP/.novo.XXXXXX")"
trap 'rm -f "$TMP" "$PEDIDO"' EXIT

# Copia antes de conferir: o arquivo conferido é exatamente o instalado.
cp "$NOVO" "$TMP" 2>/dev/null || { registrar erro "Arquivo da atualização não encontrado."; exit 1; }

ESPERADO="$(python3 -I -c 'import json,sys;print(json.load(open(sys.argv[1]))["sha256"])' "$PEDIDO" 2>/dev/null || true)"
OBTIDO="$(sha256sum "$TMP" | cut -d' ' -f1)"
if [ -z "$ESPERADO" ] || [ "$ESPERADO" != "$OBTIDO" ]; then
    registrar erro "SHA-256 do arquivo não confere com o baixado. Instalação cancelada."
    exit 1
fi
# Confere a sintaxe (compile não executa o código).
if ! python3 -I -c 'import sys;compile(open(sys.argv[1],encoding="utf-8").read(),"keep_servidor.py","exec")' "$TMP"; then
    registrar erro "Arquivo novo com erro de sintaxe. Instalação cancelada."
    exit 1
fi
VERSAO_NOVA="$(sed -n 's/^VERSAO = "\([0-9.]*\)".*/\1/p' "$TMP" | head -1)"

cp -p "$ATUAL" "$ATUAL.anterior"
install -m 644 "$TMP" "$ATUAL"
systemctl restart keeppolicial.service
sleep 6
if systemctl is-active --quiet keeppolicial.service; then
    registrar ok "Servidor atualizado para a versão $VERSAO_NOVA."
else
    install -m 644 "$ATUAL.anterior" "$ATUAL"
    systemctl restart keeppolicial.service || true
    registrar erro "A versão $VERSAO_NOVA não iniciou; a versão anterior foi restaurada. Veja: journalctl -u keeppolicial"
    exit 1
fi
