#!/usr/bin/env bash
# Publica uma release do Keep Policial no GitHub com os arquivos que o
# atualizador do app e do servidor procuram, mais o pacote completo de
# instalação (KeepPolicial-instalacao.zip) para quem baixa pela primeira vez.
#
# Uso: ./publicar-release.sh v3.1 caminho/para/app-release.apk [notas.md]
#      ./publicar-release.sh --so-pacote v3.0 caminho/para/app.apk
#        (só monta o pacote em ./dist, sem publicar)
set -euo pipefail
SO_PACOTE=0
if [ "${1:-}" = "--so-pacote" ]; then SO_PACOTE=1; shift; fi
TAG="${1:?Informe a tag, ex.: v3.1}"
APK="${2:?Informe o APK compilado}"
NOTAS="${3:-}"
RAIZ="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

VERSAO_SRV="$(sed -n 's/^VERSAO = "\([0-9.]*\)".*/\1/p' "$RAIZ/servidor/keep_servidor.py")"
VERSAO_APP="$(sed -n "s/.*versionName '\([0-9.]*\)'.*/\1/p" "$RAIZ/android/app/build.gradle")"
echo "Tag: $TAG | servidor: $VERSAO_SRV | app: $VERSAO_APP"
case "$VERSAO_SRV" in "${TAG#v}"*) ;; *) echo "AVISO: VERSAO do servidor ($VERSAO_SRV) difere da tag." ;; esac
[ "$VERSAO_APP" = "${TAG#v}" ] || echo "AVISO: versionName do app ($VERSAO_APP) difere da tag."

# Arquivos lidos pelos atualizadores
cp "$APK" "$TMP/KeepPolicial.apk"
cp "$RAIZ/servidor/keep_servidor.py" "$TMP/keep_servidor.py"

# Pacote completo: KeepPolicial-<versão>/ com servidor, APK e manuais
PASTA="KeepPolicial-${TAG#v}"
P="$TMP/pacote/$PASTA"
mkdir -p "$P/servidor"
for f in keep_servidor.py instalar.sh atualizar-servidor.sh ligar-internet.sh; do
    cp "$RAIZ/servidor/$f" "$P/servidor/"
done
chmod 755 "$P/servidor/"*.sh
cp "$TMP/KeepPolicial.apk" "$RAIZ/LEIA-ME PRIMEIRO.txt" "$RAIZ/COMO_INSTALAR.md" "$P/"
[ -f "$RAIZ/ATUALIZAR_SERVIDOR_${TAG#v}.md" ] && cp "$RAIZ/ATUALIZAR_SERVIDOR_${TAG#v}.md" "$P/"
ls "$RAIZ"/ATUALIZAR_SERVIDOR_*.md >/dev/null 2>&1 && cp "$RAIZ"/ATUALIZAR_SERVIDOR_*.md "$P/"
sed -i "s/KeepPolicial-[0-9.]*\b/$PASTA/g" "$P/LEIA-ME PRIMEIRO.txt"
(cd "$TMP/pacote" && zip -qrX "$TMP/KeepPolicial-instalacao.zip" "$PASTA")

(cd "$TMP" && sha256sum KeepPolicial.apk keep_servidor.py KeepPolicial-instalacao.zip > SHA256SUMS && cat SHA256SUMS)

# Assina o SHA256SUMS (os servidores so instalam atualizacao com assinatura valida).
# A chave privada fica so com o mantenedor: ~/.keeppolicial/release-signing.pem
CHAVE_REL="${KEEP_ASSINATURA_RELEASE:-$HOME/.keeppolicial/release-signing.pem}"
[ -f "$CHAVE_REL" ] || { echo "ERRO: chave de assinatura das releases não encontrada em $CHAVE_REL"; exit 1; }
openssl dgst -sha256 -sign "$CHAVE_REL" -out "$TMP/SHA256SUMS.sig" "$TMP/SHA256SUMS"
openssl ec -in "$CHAVE_REL" -pubout 2>/dev/null | diff -q - "$RAIZ/servidor/chave-publica-release.pem" >/dev/null \
    || { echo "ERRO: a chave privada não corresponde a servidor/chave-publica-release.pem"; exit 1; }
openssl dgst -sha256 -verify "$RAIZ/servidor/chave-publica-release.pem" \
    -signature "$TMP/SHA256SUMS.sig" "$TMP/SHA256SUMS" >/dev/null && echo "SHA256SUMS assinado e conferido."
unzip -l "$TMP/KeepPolicial-instalacao.zip"

if [ "$SO_PACOTE" = 1 ]; then
    mkdir -p "$RAIZ/dist"
    cp "$TMP/KeepPolicial-instalacao.zip" "$TMP/SHA256SUMS" "$TMP/SHA256SUMS.sig" "$RAIZ/dist/"
    echo "Pacote em $RAIZ/dist/"
    exit 0
fi

read -rp "Publicar a release $TAG? (s/N) " OK
[ "$OK" = "s" ] || exit 0
if [ -n "$NOTAS" ]; then ARG_NOTAS=(--notes-file "$NOTAS"); else
    ARG_NOTAS=(--notes "$(git -C "$RAIZ" log -1 --pretty=%B | grep -v '^Co-Authored-By')"); fi
gh release create "$TAG" --repo OpCer71-sketch/keep-policial --title "Keep Policial ${TAG#v}" \
    "${ARG_NOTAS[@]}" \
    "$TMP/KeepPolicial.apk" "$TMP/keep_servidor.py" "$TMP/KeepPolicial-instalacao.zip" "$TMP/SHA256SUMS" "$TMP/SHA256SUMS.sig"
