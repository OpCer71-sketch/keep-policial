#!/usr/bin/env bash
# Keep Policial - acesso pela internet (4G / qualquer Wi-Fi) com Tailscale Funnel.
# Uso:  sudo bash ligar-internet.sh            (liga)
#       sudo bash ligar-internet.sh desligar   (desliga)
# Rode DEPOIS do instalar.sh.
set -uo pipefail

if [ "$(id -u)" -ne 0 ]; then
    echo "Execute como administrador:  sudo bash ligar-internet.sh"
    exit 1
fi
if ! command -v keeppolicial >/dev/null; then
    echo "Instale o servidor primeiro:  sudo bash instalar.sh"
    exit 1
fi

PORTA=$(python3 -c "import json;print(json.load(open('/var/lib/keeppolicial/config.json'))['porta'])")

if [ "${1:-}" = "desligar" ]; then
    tailscale funnel --tcp="$PORTA" off
    keeppolicial configurar --endereco-publico= >/dev/null
    echo "Acesso pela internet DESLIGADO. O app volta a funcionar só na rede do quartel."
    exit 0
fi

case "$PORTA" in
    443|8443|10000) ;;
    *) echo "O Funnel só aceita as portas 443, 8443 ou 10000 (o servidor usa $PORTA)."; exit 1 ;;
esac

echo "== Keep Policial - ligar acesso pela internet =="

if ! command -v tailscale >/dev/null; then
    echo "Instalando o Tailscale..."
    if ! command -v curl >/dev/null; then
        apt-get update && apt-get install -y curl
    fi
    curl -fsSL https://tailscale.com/install.sh | sh || { echo "Falha ao instalar o Tailscale. Verifique a internet."; exit 1; }
fi

if ! tailscale status >/dev/null 2>&1; then
    echo
    echo "Vai aparecer um LINK. Abra-o no navegador e entre na conta Tailscale do destacamento."
    tailscale up || { echo "Não foi possível entrar no Tailscale."; exit 1; }
fi

echo
echo "Ligando o Funnel (se aparecer um link, abra-o no navegador e autorize o Funnel)..."
until tailscale funnel --bg --proxy-protocol=2 --tcp="$PORTA" "tcp://127.0.0.1:$PORTA"; do
    echo
    read -rp "Depois de autorizar no navegador, aperte Enter para tentar de novo (Ctrl+C cancela)... " _
done

NOME=$(tailscale status --json | python3 -c "import json,sys;print(json.load(sys.stdin)['Self']['DNSName'].rstrip('.'))")
if [ -z "$NOME" ]; then
    echo "Não consegui descobrir o nome na internet. Veja com:  tailscale funnel status"
    exit 1
fi
keeppolicial configurar --endereco-publico="$NOME" >/dev/null

ACESSO="$NOME"
[ "$PORTA" != "8443" ] && ACESSO="$NOME:$PORTA"

echo
echo "==================================================================="
echo " Acesso pela internet LIGADO."
echo " No celular (4G ou qualquer Wi-Fi) o policial entra com:"
echo "     login@$ACESSO"
echo " Exemplo:  sd.silva@$ACESSO"
echo
echo " Ele volta a ligar sozinho quando o computador reiniciar."
echo " Para desligar:  sudo bash ligar-internet.sh desligar"
echo "==================================================================="
