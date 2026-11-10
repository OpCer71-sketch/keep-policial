#!/usr/bin/env bash
# Publica uma release do Keep Policial no GitHub com os arquivos que o
# atualizador do app e do servidor procuram.
# Uso: ./publicar-release.sh v3.0 caminho/para/app-release.apk
set -euo pipefail
TAG="${1:?Informe a tag, ex.: v3.0}"
APK="${2:?Informe o APK compilado}"
RAIZ="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

VERSAO_SRV="$(sed -n 's/^VERSAO = "\([0-9.]*\)".*/\1/p' "$RAIZ/servidor/keep_servidor.py")"
VERSAO_APP="$(sed -n "s/.*versionName '\([0-9.]*\)'.*/\1/p" "$RAIZ/android/app/build.gradle")"
echo "Tag: $TAG | servidor: $VERSAO_SRV | app: $VERSAO_APP"
case "$VERSAO_SRV" in "${TAG#v}"*) ;; *) echo "AVISO: VERSAO do servidor ($VERSAO_SRV) difere da tag." ;; esac
[ "$VERSAO_APP" = "${TAG#v}" ] || echo "AVISO: versionName do app ($VERSAO_APP) difere da tag."

cp "$APK" "$TMP/KeepPolicial.apk"
cp "$RAIZ/servidor/keep_servidor.py" "$TMP/keep_servidor.py"
(cd "$TMP" && sha256sum KeepPolicial.apk keep_servidor.py > SHA256SUMS && cat SHA256SUMS)

read -rp "Publicar a release $TAG? (s/N) " OK
[ "$OK" = "s" ] || exit 0
gh release create "$TAG" --repo OpCer71-sketch/keep-policial --title "Keep Policial ${TAG#v}" \
    --notes-file <(git -C "$RAIZ" log -1 --pretty=%B) \
    "$TMP/KeepPolicial.apk" "$TMP/keep_servidor.py" "$TMP/SHA256SUMS"
