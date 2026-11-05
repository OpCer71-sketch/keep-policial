#!/usr/bin/env bash
# Instalador do servidor Keep Policial para Ubuntu (20.04 ou mais recente).
# Uso: sudo ./instalar.sh
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
    echo "Execute como administrador:  sudo ./instalar.sh"
    exit 1
fi

ORIGEM="$(cd "$(dirname "$0")" && pwd)"
DIR_APP=/opt/keeppolicial
DIR_DADOS=/var/lib/keeppolicial
USUARIO_SVC=keeppolicial

echo "== Keep Policial - instalação do servidor =="

if ! command -v python3 >/dev/null || ! command -v openssl >/dev/null; then
    apt-get update
    apt-get install -y python3 openssl
fi

if ! id "$USUARIO_SVC" >/dev/null 2>&1; then
    useradd --system --home-dir "$DIR_DADOS" --shell /usr/sbin/nologin "$USUARIO_SVC"
fi

install -d -m 755 "$DIR_APP"
install -m 644 "$ORIGEM/keep_servidor.py" "$DIR_APP/keep_servidor.py"
install -d -m 700 -o "$USUARIO_SVC" -g "$USUARIO_SVC" "$DIR_DADOS"

# Atalho de administração: "sudo keeppolicial" abre o menu
cat > /usr/local/bin/keeppolicial <<EOF
#!/usr/bin/env bash
cd "$DIR_DADOS"
exec sudo -u $USUARIO_SVC env KEEP_DADOS=$DIR_DADOS python3 $DIR_APP/keep_servidor.py "\$@"
EOF
chmod 755 /usr/local/bin/keeppolicial

if [ ! -f "$DIR_DADOS/config.json" ]; then
    read -rp "Nome do destacamento (ex.: 1º Pel PM - Cidade X): " NOME
    keeppolicial configurar --nome "${NOME:-Destacamento PM}" >/dev/null
fi

keeppolicial gerar-certificado

cat > /etc/systemd/system/keeppolicial.service <<EOF
[Unit]
Description=Keep Policial - servidor do destacamento
After=network-online.target
Wants=network-online.target

[Service]
User=$USUARIO_SVC
Group=$USUARIO_SVC
Environment=KEEP_DADOS=$DIR_DADOS
Environment=PYTHONUNBUFFERED=1
ExecStart=/usr/bin/python3 $DIR_APP/keep_servidor.py servir
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=$DIR_DADOS
UMask=0077

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now keeppolicial.service
systemctl restart keeppolicial.service

PORTA=$(python3 -c "import json;print(json.load(open('$DIR_DADOS/config.json'))['porta'])")
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
    ufw allow "$PORTA"/tcp comment "Keep Policial"
fi

echo
LISTA_USUARIOS="$(keeppolicial usuario listar)"
if ! grep -q " admin " <<<"$LISTA_USUARIOS"; then
    echo "Crie agora o usuário ADMINISTRADOR (comandante):"
    read -rp "Login do administrador (ex.: cmt.silva): " LOGIN_ADM
    keeppolicial usuario adicionar "$LOGIN_ADM" --admin
fi

# Atalho no menu de aplicativos: abre o painel do administrador no navegador.
PORTA_PAINEL=$(python3 -c "import json;print(json.load(open('$DIR_DADOS/config.json')).get('porta_painel', 8480))")
cat > /usr/share/applications/keeppolicial-painel.desktop <<EOF
[Desktop Entry]
Type=Application
Name=Keep Policial - Painel do administrador
Comment=Cadastrar policiais, trocar login e senha, ver auditoria
Exec=xdg-open http://127.0.0.1:$PORTA_PAINEL
Icon=preferences-system
Terminal=false
Categories=Office;
EOF
chmod 644 /usr/share/applications/keeppolicial-painel.desktop

echo
echo "==================================================================="
echo " Servidor instalado e em execução."
echo " No celular o policial entra com  login@endereço  (como e-mail)."
echo " Endereço(s) na rede local:"
for IP in $(hostname -I); do
    case "$IP" in *:*) ;; *) if [ "$PORTA" = "8443" ]; then echo "     $IP"; else echo "     $IP:$PORTA"; fi ;; esac
done
echo " Código de verificação (o celular mostra no 1º acesso):"
echo "     $(keeppolicial impressao-digital | sed -n 's/^Código de verificação: //p')"
echo
echo " Painel do administrador: menu de aplicativos > 'Keep Policial - Painel'"
echo "                          ou http://127.0.0.1:$PORTA_PAINEL neste computador"
echo " Menu no terminal:        sudo keeppolicial"
echo " Situação:                systemctl status keeppolicial"
echo "==================================================================="
