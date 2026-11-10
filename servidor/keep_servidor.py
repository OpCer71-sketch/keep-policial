#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Keep Policial - Servidor do destacamento.

Armazena as fichas de suspeitos (dados, passagens e fotos) no computador do
destacamento e as disponibiliza ao aplicativo Android por HTTPS, mediante
login com usuario e senha cadastrados aqui pelo comandante.

Usa somente a biblioteca padrao do Python 3 (ja vem no Ubuntu).

Uso rapido:
    python3 keep_servidor.py                     -> menu interativo
    python3 keep_servidor.py servir              -> inicia o servidor
    python3 keep_servidor.py usuario adicionar sd.silva --nome "Sd Silva"
    python3 keep_servidor.py --help              -> todos os comandos
"""

import argparse
import base64
import getpass
import hashlib
import hmac
import html
import ipaddress
import json
import os
import re
import secrets
import socket
import sqlite3
import ssl
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import traceback
import unicodedata
import urllib.error
import urllib.request
import uuid
from datetime import date, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

VERSAO = "3.0.0"
DADOS = os.environ.get("KEEP_DADOS", "/var/lib/keeppolicial")

ARQ_CONFIG = "config.json"
ARQ_DB = "keep.db"
DIR_FOTOS = "fotos"
ARQ_CERT = "certificado.pem"
ARQ_CHAVE = "chave.pem"

# Atualizacao automatica a partir das "Releases" do repositorio no GitHub.
REPO_GITHUB = "OpCer71-sketch/keep-policial"
URL_RELEASE = "https://api.github.com/repos/%s/releases/latest" % REPO_GITHUB
ASSET_SERVIDOR = "keep_servidor.py"
ASSET_SOMAS = "SHA256SUMS"
DIR_ATUALIZACAO = "atualizacao"
ARQ_ESTADO_ATUALIZACAO = "estado.json"
# Pedido de instalacao: o servico "keeppolicial-atualizar.path" (root) vigia
# este arquivo e instala a versao baixada, reiniciando o servidor.
ARQ_PEDIDO_INSTALACAO = "INSTALAR"
ARQ_RESULTADO_INSTALACAO = "resultado.json"
MAX_BYTES_ATUALIZACAO = 5 * 1024 * 1024

# Avisos do administrador (pop-up no aplicativo)
LIMITE_TITULO_AVISO = 80
LIMITE_MENSAGEM_AVISO = 1500

CONFIG_PADRAO = {
    "nome_destacamento": "Destacamento PM",
    "endereco": "0.0.0.0",
    "porta": 8443,
    "horas_sessao": 10,
    # Painel de administracao: so responde no proprio computador (127.0.0.1).
    "porta_painel": 8480,
    # Endereco pelo qual os celulares chegam ao servidor pela internet
    # (ex.: nome do Tailscale ou DuckDNS). Apenas exibido no painel.
    "endereco_publico": "",
}

# Limites de campos (caracteres)
LIMITES = {
    "nome": 150,
    "alcunha": 100,
    "cpf": 14,
    "rg": 20,
    "endereco": 300,
    "pai": 150,
    "mae": 150,
    "observacoes": 200,
}
LIMITE_REDS = 30
LIMITE_DESCR_PASSAGEM = 300
MAX_PASSAGENS = 100
# Veiculos: marca, modelo, cor e placa (placa guardada sem traco, em maiusculas)
LIMITES_VEICULO = {"marca": 40, "modelo": 60, "cor": 30, "placa": 8}
MAX_VEICULOS = 20
MAX_FOTOS = 10
MAX_BYTES_FOTO = 8 * 1024 * 1024
MAX_BYTES_JSON = 128 * 1024
COMPORTAMENTOS = ("", "OBEDIENTE", "RESISTENTE_PASSIVO", "RESISTENTE_ATIVO")

TENTATIVAS_ANTES_BLOQUEIO = 5
MINUTOS_BLOQUEIO = 15
ITERACOES_PBKDF2 = 600_000
# Protecao extra quando o servidor fica acessivel pela internet: um mesmo IP
# que erra a senha muitas vezes (em qualquer usuario) fica bloqueado.
FALHAS_POR_IP = 20
MINUTOS_JANELA_IP = 15

_trava_login = threading.Lock()
_falhas_ip = {}


# ---------------------------------------------------------------------------
# Utilidades
# ---------------------------------------------------------------------------

def caminho(*partes):
    return os.path.join(DADOS, *partes)


def agora_iso():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def normalizar(texto):
    texto = unicodedata.normalize("NFKD", texto or "")
    return "".join(c for c in texto if not unicodedata.combining(c)).lower()


def somente_digitos(texto):
    return re.sub(r"\D", "", texto or "")


def carregar_config():
    cfg = dict(CONFIG_PADRAO)
    try:
        with open(caminho(ARQ_CONFIG), encoding="utf-8") as f:
            cfg.update(json.load(f))
    except FileNotFoundError:
        pass
    return cfg


def salvar_config(cfg):
    os.makedirs(DADOS, exist_ok=True)
    with open(caminho(ARQ_CONFIG), "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)


def hash_senha(senha):
    sal = secrets.token_bytes(16)
    dk = hashlib.pbkdf2_hmac("sha256", senha.encode("utf-8"), sal, ITERACOES_PBKDF2)
    return "pbkdf2_sha256$%d$%s$%s" % (
        ITERACOES_PBKDF2,
        base64.b64encode(sal).decode(),
        base64.b64encode(dk).decode(),
    )


def conferir_senha(senha, armazenado):
    try:
        _, iteracoes, sal, dk = armazenado.split("$")
        calc = hashlib.pbkdf2_hmac(
            "sha256", senha.encode("utf-8"), base64.b64decode(sal), int(iteracoes)
        )
        return hmac.compare_digest(calc, base64.b64decode(dk))
    except Exception:
        return False


def hash_token(token):
    return hashlib.sha256(token.encode()).hexdigest()


def impressao_digital_certificado():
    with open(caminho(ARQ_CERT), encoding="ascii") as f:
        der = ssl.PEM_cert_to_DER_cert(f.read())
    h = hashlib.sha256(der).hexdigest().upper()
    return ":".join(h[i:i + 2] for i in range(0, len(h), 2))


def codigo_verificacao():
    """Versao curta da impressao digital, conferida no celular no 1o acesso."""
    h = impressao_digital_certificado().replace(":", "")[:16]
    return "-".join(h[i:i + 4] for i in range(0, 16, 4))


def enderecos_locais():
    """IPs IPv4 deste computador (rede local, Tailscale etc.)."""
    ips = []
    try:
        saida = subprocess.run(["ip", "-4", "-o", "addr", "show"], capture_output=True,
                               text=True, timeout=5).stdout
        ips = re.findall(r"inet (\d+\.\d+\.\d+\.\d+)", saida)
    except (OSError, subprocess.SubprocessError):
        pass
    if not ips:
        try:
            ips = socket.gethostbyname_ex(socket.gethostname())[2]
        except OSError:
            pass
    return [ip for ip in dict.fromkeys(ips) if not ip.startswith("127.")]


def texto_acesso(login, cfg, host=None):
    """Como o policial digita no app: usuario@endereco (porta omitida se padrao)."""
    host = host or cfg.get("endereco_publico") or (enderecos_locais() or ["IP-DO-SERVIDOR"])[0]
    host = re.sub(r"^https?://", "", host.strip()).rstrip("/")
    if ":" not in host and int(cfg["porta"]) != 8443:
        host += ":%s" % cfg["porta"]
    if host.endswith(":8443"):
        host = host[:-5]
    return "%s@%s" % (login, host)


# ---------------------------------------------------------------------------
# Banco de dados
# ---------------------------------------------------------------------------

ESQUEMA = """
CREATE TABLE IF NOT EXISTS usuarios (
    id INTEGER PRIMARY KEY,
    usuario TEXT UNIQUE NOT NULL,
    nome TEXT NOT NULL,
    senha_hash TEXT NOT NULL,
    papel TEXT NOT NULL DEFAULT 'usuario',
    ativo INTEGER NOT NULL DEFAULT 1,
    criado_em TEXT NOT NULL,
    termo_aceito_em TEXT,
    falhas INTEGER NOT NULL DEFAULT 0,
    bloqueado_ate REAL NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS sessoes (
    token_hash TEXT PRIMARY KEY,
    usuario_id INTEGER NOT NULL,
    expira REAL NOT NULL,
    ip TEXT
);
CREATE TABLE IF NOT EXISTS suspeitos (
    id TEXT PRIMARY KEY,
    nome TEXT NOT NULL DEFAULT '',
    alcunha TEXT NOT NULL DEFAULT '',
    cpf TEXT NOT NULL DEFAULT '',
    rg TEXT NOT NULL DEFAULT '',
    endereco TEXT NOT NULL DEFAULT '',
    pai TEXT NOT NULL DEFAULT '',
    mae TEXT NOT NULL DEFAULT '',
    comportamento TEXT NOT NULL DEFAULT '',
    observacoes TEXT NOT NULL DEFAULT '',
    busca TEXT NOT NULL DEFAULT '',
    criado_por TEXT, criado_em TEXT,
    atualizado_por TEXT, atualizado_em TEXT
);
CREATE TABLE IF NOT EXISTS passagens (
    id INTEGER PRIMARY KEY,
    suspeito_id TEXT NOT NULL,
    ordem INTEGER NOT NULL,
    reds TEXT NOT NULL DEFAULT '',
    descricao TEXT NOT NULL DEFAULT '',
    data TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS fotos (
    id TEXT PRIMARY KEY,
    suspeito_id TEXT NOT NULL,
    ordem INTEGER NOT NULL,
    tamanho INTEGER NOT NULL,
    criado_por TEXT, criado_em TEXT
);
CREATE TABLE IF NOT EXISTS auditoria (
    id INTEGER PRIMARY KEY,
    ts TEXT NOT NULL,
    usuario TEXT,
    ip TEXT,
    acao TEXT NOT NULL,
    alvo TEXT
);
CREATE TABLE IF NOT EXISTS veiculos (
    id INTEGER PRIMARY KEY,
    suspeito_id TEXT NOT NULL,
    ordem INTEGER NOT NULL,
    marca TEXT NOT NULL DEFAULT '',
    modelo TEXT NOT NULL DEFAULT '',
    cor TEXT NOT NULL DEFAULT '',
    placa TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS avisos (
    id INTEGER PRIMARY KEY,
    titulo TEXT NOT NULL,
    mensagem TEXT NOT NULL,
    ativo INTEGER NOT NULL DEFAULT 1,
    criado_por TEXT,
    criado_em TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS avisos_ciencia (
    aviso_id INTEGER NOT NULL,
    usuario TEXT NOT NULL,
    em TEXT NOT NULL,
    PRIMARY KEY (aviso_id, usuario)
);
CREATE INDEX IF NOT EXISTS ix_passagens ON passagens(suspeito_id);
CREATE INDEX IF NOT EXISTS ix_veiculos ON veiculos(suspeito_id);
CREATE INDEX IF NOT EXISTS ix_fotos ON fotos(suspeito_id);
"""


def abrir_db():
    db = sqlite3.connect(caminho(ARQ_DB), timeout=15)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA foreign_keys=ON")
    return db


def preparar_dados():
    os.umask(0o077)
    os.makedirs(caminho(DIR_FOTOS), exist_ok=True)
    if not os.path.exists(caminho(ARQ_CONFIG)):
        salvar_config(dict(CONFIG_PADRAO))
    db = abrir_db()
    db.execute("PRAGMA journal_mode=WAL")
    db.executescript(ESQUEMA)
    db.commit()
    db.close()


def auditar(db, usuario, ip, acao, alvo=""):
    db.execute(
        "INSERT INTO auditoria (ts, usuario, ip, acao, alvo) VALUES (?,?,?,?,?)",
        (agora_iso(), usuario, ip, acao, alvo),
    )


class Erro(Exception):
    def __init__(self, status, mensagem):
        super().__init__(mensagem)
        self.status = status
        self.mensagem = mensagem


def _ip_bloqueado(ip):
    limite = time.time() - MINUTOS_JANELA_IP * 60
    lista = [t for t in _falhas_ip.get(ip, []) if t > limite]
    if lista:
        _falhas_ip[ip] = lista
    else:
        _falhas_ip.pop(ip, None)
    return len(lista) >= FALHAS_POR_IP


def conferir_credenciais(db, usuario, senha, ip, acao_falha="LOGIN_FALHOU"):
    """Confere usuario/senha com bloqueio por usuario e por IP. Devolve a linha do usuario."""
    with _trava_login:
        if _ip_bloqueado(ip):
            raise Erro(429, "Muitas tentativas erradas a partir desta conexão. "
                            "Aguarde %d minutos." % MINUTOS_JANELA_IP)
        linha = db.execute("SELECT * FROM usuarios WHERE usuario = ?", (usuario,)).fetchone()
        if linha and linha["bloqueado_ate"] > time.time():
            minutos = int((linha["bloqueado_ate"] - time.time()) / 60) + 1
            raise Erro(429, "Usuário bloqueado por excesso de tentativas. "
                            "Tente em %d min." % minutos)
        if not linha or not linha["ativo"] or not conferir_senha(senha, linha["senha_hash"]):
            if linha:
                falhas = linha["falhas"] + 1
                bloqueio = 0
                if falhas >= TENTATIVAS_ANTES_BLOQUEIO:
                    bloqueio = time.time() + MINUTOS_BLOQUEIO * 60
                    falhas = 0
                db.execute("UPDATE usuarios SET falhas=?, bloqueado_ate=? WHERE id=?",
                           (falhas, bloqueio, linha["id"]))
            _falhas_ip.setdefault(ip, []).append(time.time())
            auditar(db, usuario, ip, acao_falha)
            db.commit()
            time.sleep(1)
            raise Erro(401, "Usuário ou senha incorretos.")
        db.execute("UPDATE usuarios SET falhas=0, bloqueado_ate=0 WHERE id=?", (linha["id"],))
        return linha


# -- operacoes de usuarios (usadas pelo painel e pela linha de comando) -------

def validar_login(login):
    login = (login or "").strip().lower()
    if not re.fullmatch(r"[a-z0-9._-]{3,40}", login):
        raise ValueError("Login inválido: use 3 a 40 caracteres (letras minúsculas, "
                         "números, ponto, _ ou -), sem espaços.")
    return login


def validar_senha(senha):
    if len(senha or "") < 8:
        raise ValueError("A senha deve ter no mínimo 8 caracteres.")
    return senha


def _admins_ativos(db, exceto_id=None):
    return db.execute("SELECT COUNT(*) FROM usuarios WHERE papel='admin' AND ativo=1 AND id<>?",
                      (exceto_id or -1,)).fetchone()[0]


def op_criar_usuario(db, login, nome, papel, senha, autor, ip="local"):
    login = validar_login(login)
    validar_senha(senha)
    if papel not in ("admin", "usuario"):
        raise ValueError("Papel inválido.")
    if db.execute("SELECT 1 FROM usuarios WHERE usuario = ?", (login,)).fetchone():
        raise ValueError("Já existe um usuário com o login '%s'." % login)
    nome = (nome or "").strip()[:80] or login
    db.execute("INSERT INTO usuarios (usuario, nome, senha_hash, papel, criado_em) "
               "VALUES (?,?,?,?,?)", (login, nome, hash_senha(senha), papel, agora_iso()))
    auditar(db, autor, ip, "CRIAR_USUARIO", "%s (%s)" % (login, papel))
    db.commit()
    return login


def op_editar_usuario(db, uid, login, nome, papel, senha, autor, ip="local"):
    """Altera login, nome, papel e (se informada) a senha."""
    u = db.execute("SELECT * FROM usuarios WHERE id = ?", (uid,)).fetchone()
    if not u:
        raise ValueError("Usuário não encontrado.")
    login = validar_login(login)
    if papel not in ("admin", "usuario"):
        raise ValueError("Papel inválido.")
    if login != u["usuario"] and db.execute(
            "SELECT 1 FROM usuarios WHERE usuario = ?", (login,)).fetchone():
        raise ValueError("Já existe um usuário com o login '%s'." % login)
    if u["papel"] == "admin" and papel != "admin" and u["ativo"] and not _admins_ativos(db, uid):
        raise ValueError("É preciso manter ao menos um administrador ativo.")
    nome = (nome or "").strip()[:80] or login
    db.execute("UPDATE usuarios SET usuario=?, nome=?, papel=? WHERE id=?",
               (login, nome, papel, uid))
    if login != u["usuario"]:
        auditar(db, autor, ip, "RENOMEAR_USUARIO", "%s -> %s" % (u["usuario"], login))
    if papel != u["papel"]:
        auditar(db, autor, ip, "ALTERAR_PAPEL", "%s: %s" % (login, papel))
    if senha:
        validar_senha(senha)
        db.execute("UPDATE usuarios SET senha_hash=?, falhas=0, bloqueado_ate=0 WHERE id=?",
                   (hash_senha(senha), uid))
        db.execute("DELETE FROM sessoes WHERE usuario_id = ?", (uid,))
        auditar(db, autor, ip, "TROCAR_SENHA", login)
    db.commit()
    return login


def op_definir_ativo(db, uid, ativo, autor, ip="local"):
    u = db.execute("SELECT * FROM usuarios WHERE id = ?", (uid,)).fetchone()
    if not u:
        raise ValueError("Usuário não encontrado.")
    if not ativo and u["papel"] == "admin" and not _admins_ativos(db, uid):
        raise ValueError("É preciso manter ao menos um administrador ativo.")
    db.execute("UPDATE usuarios SET ativo=?, falhas=0, bloqueado_ate=0 WHERE id=?",
               (1 if ativo else 0, uid))
    if not ativo:
        db.execute("DELETE FROM sessoes WHERE usuario_id = ?", (uid,))
    auditar(db, autor, ip, "ATIVAR_USUARIO" if ativo else "DESATIVAR_USUARIO", u["usuario"])
    db.commit()
    return u["usuario"]


def op_remover_usuario(db, uid, autor, ip="local"):
    u = db.execute("SELECT * FROM usuarios WHERE id = ?", (uid,)).fetchone()
    if not u:
        raise ValueError("Usuário não encontrado.")
    if u["papel"] == "admin" and not _admins_ativos(db, uid):
        raise ValueError("É preciso manter ao menos um administrador ativo.")
    db.execute("DELETE FROM sessoes WHERE usuario_id = ?", (uid,))
    db.execute("DELETE FROM usuarios WHERE id = ?", (uid,))
    auditar(db, autor, ip, "REMOVER_USUARIO", u["usuario"])
    db.commit()
    return u["usuario"]


# -- avisos do administrador (pop-up no app) ---------------------------------

def op_publicar_aviso(db, titulo, mensagem, autor, ip="local"):
    titulo = (titulo or "").strip()
    mensagem = (mensagem or "").replace("\r\n", "\n").strip()
    if not mensagem:
        raise ValueError("Escreva a mensagem do aviso.")
    if len(titulo) > LIMITE_TITULO_AVISO:
        raise ValueError("O título deve ter no máximo %d caracteres." % LIMITE_TITULO_AVISO)
    if len(mensagem) > LIMITE_MENSAGEM_AVISO:
        raise ValueError("A mensagem deve ter no máximo %d caracteres." % LIMITE_MENSAGEM_AVISO)
    titulo = titulo or "Aviso do comando"
    cur = db.execute("INSERT INTO avisos (titulo, mensagem, ativo, criado_por, criado_em) "
                     "VALUES (?,?,1,?,?)", (titulo, mensagem, autor, agora_iso()))
    auditar(db, autor, ip, "PUBLICAR_AVISO", "%d (%s)" % (cur.lastrowid, titulo))
    db.commit()
    return cur.lastrowid


def op_definir_aviso_ativo(db, aid, ativo, autor, ip="local"):
    a = db.execute("SELECT * FROM avisos WHERE id = ?", (aid,)).fetchone()
    if not a:
        raise ValueError("Aviso não encontrado.")
    db.execute("UPDATE avisos SET ativo=? WHERE id=?", (1 if ativo else 0, aid))
    auditar(db, autor, ip, "REATIVAR_AVISO" if ativo else "ENCERRAR_AVISO",
            "%d (%s)" % (aid, a["titulo"]))
    db.commit()
    return a["titulo"]


# -- atualizacao do servidor pelo GitHub --------------------------------------

def versao_tupla(texto):
    """'v3.0' / '3.0.0' -> (3, 0, 0). Sem numeros -> (0,)."""
    nums = [int(n) for n in re.findall(r"\d+", str(texto or ""))[:4]]
    while len(nums) < 3:
        nums.append(0)
    return tuple(nums)


def _dir_atualizacao(*partes):
    return caminho(DIR_ATUALIZACAO, *partes)


def estado_atualizacao():
    try:
        with open(_dir_atualizacao(ARQ_ESTADO_ATUALIZACAO), encoding="utf-8") as f:
            est = json.load(f)
    except (FileNotFoundError, ValueError):
        est = {}
    # A versao baixada ja foi instalada (ou e mais antiga): nada pendente.
    if est.get("baixada") and versao_tupla(est.get("baixada")) <= versao_tupla(VERSAO):
        est.pop("baixada", None)
        est.pop("sha256", None)
        try:
            os.remove(_dir_atualizacao(ASSET_SERVIDOR))
        except FileNotFoundError:
            pass
    try:
        with open(_dir_atualizacao(ARQ_RESULTADO_INSTALACAO), encoding="utf-8") as f:
            est["resultado_instalacao"] = json.load(f)
    except (FileNotFoundError, ValueError):
        pass
    est["pedido_instalacao"] = os.path.exists(_dir_atualizacao(ARQ_PEDIDO_INSTALACAO))
    return est


def _salvar_estado_atualizacao(est):
    os.makedirs(_dir_atualizacao(), exist_ok=True)
    est = {k: v for k, v in est.items() if k not in ("resultado_instalacao", "pedido_instalacao")}
    tmp = _dir_atualizacao(ARQ_ESTADO_ATUALIZACAO + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(est, f, ensure_ascii=False, indent=2)
    os.replace(tmp, _dir_atualizacao(ARQ_ESTADO_ATUALIZACAO))


def _baixar(url, limite, aceitar="application/octet-stream"):
    req = urllib.request.Request(url, headers={"User-Agent": "KeepPolicial-Servidor",
                                               "Accept": aceitar})
    with urllib.request.urlopen(req, timeout=60) as r:
        dados = r.read(limite + 1)
    if len(dados) > limite:
        raise ValueError("arquivo maior que o esperado")
    return dados


def _versao_no_codigo(codigo):
    achou = re.search(r'^VERSAO\s*=\s*"([0-9.]+)"', codigo, re.M)
    return achou.group(1) if achou else ""


_trava_atualizacao = threading.Lock()


def verificar_atualizacao(forcar=False):
    """Consulta a ultima Release no GitHub (uma vez por dia, ou quando forcado) e,
    havendo versao nova do servidor, baixa e confere o arquivo. A instalacao so
    acontece depois que o administrador pedir (painel ou linha de comando)."""
    with _trava_atualizacao:
        est = estado_atualizacao()
        hoje = date.today().isoformat()
        if not forcar and est.get("ultima_verificacao") == hoje:
            return est
        est["ultima_verificacao_em"] = agora_iso()
        try:
            rel = json.loads(_baixar(URL_RELEASE, 1024 * 1024, "application/vnd.github+json"))
            tag = str(rel.get("tag_name") or "")
            est.update(ultima_release=tag, notas=str(rel.get("body") or "")[:3000],
                       pagina_release=str(rel.get("html_url") or ""), erro="")
            assets = {a.get("name"): a.get("browser_download_url")
                      for a in rel.get("assets") or [] if isinstance(a, dict)}
            if versao_tupla(tag) > versao_tupla(VERSAO) and assets.get(ASSET_SERVIDOR) \
                    and versao_tupla(est.get("baixada")) < versao_tupla(tag):
                dados = _baixar(assets[ASSET_SERVIDOR], MAX_BYTES_ATUALIZACAO)
                sha = hashlib.sha256(dados).hexdigest()
                if assets.get(ASSET_SOMAS):
                    somas = _baixar(assets[ASSET_SOMAS], 64 * 1024).decode("utf-8", "replace")
                    esperado = re.search(r"^([0-9a-fA-F]{64})\s+\*?%s\s*$"
                                         % re.escape(ASSET_SERVIDOR), somas, re.M)
                    if not esperado or esperado.group(1).lower() != sha:
                        raise ValueError("o SHA-256 do arquivo baixado não confere com o "
                                         "SHA256SUMS da release")
                codigo = dados.decode("utf-8")
                compile(codigo, ASSET_SERVIDOR, "exec")  # so confere a sintaxe; nao executa
                nova = _versao_no_codigo(codigo)
                if "def cmd_servir" not in codigo or not nova:
                    raise ValueError("o arquivo baixado não parece ser o servidor Keep Policial")
                if versao_tupla(nova) > versao_tupla(VERSAO):
                    os.makedirs(_dir_atualizacao(), exist_ok=True)
                    tmp = _dir_atualizacao(ASSET_SERVIDOR + ".tmp")
                    with open(tmp, "wb") as f:
                        f.write(dados)
                    os.replace(tmp, _dir_atualizacao(ASSET_SERVIDOR))
                    est.update(baixada=nova, sha256=sha, baixada_em=agora_iso())
                    print("Atualização %s do servidor baixada. Aguardando o administrador "
                          "autorizar a instalação (painel > Atualizações)." % nova)
            est["ultima_verificacao"] = hoje
        except (OSError, ValueError, UnicodeDecodeError, SyntaxError, urllib.error.URLError) as e:
            # Sem internet ou GitHub fora: tenta de novo mais tarde (nao marca o dia).
            est["erro"] = str(e)[:300]
        _salvar_estado_atualizacao(est)
        return estado_atualizacao()


def op_pedir_instalacao(db, autor, ip="local"):
    est = estado_atualizacao()
    arq = _dir_atualizacao(ASSET_SERVIDOR)
    if not est.get("baixada") or not os.path.exists(arq):
        raise ValueError("Não há atualização baixada para instalar.")
    with open(arq, "rb") as f:
        if hashlib.sha256(f.read()).hexdigest() != est.get("sha256"):
            raise ValueError("O arquivo baixado foi alterado. Clique em 'Verificar agora' "
                             "para baixar de novo.")
    try:
        os.remove(_dir_atualizacao(ARQ_RESULTADO_INSTALACAO))
    except FileNotFoundError:
        pass
    with open(_dir_atualizacao(ARQ_PEDIDO_INSTALACAO), "w", encoding="utf-8") as f:
        json.dump({"versao": est["baixada"], "sha256": est["sha256"], "autor": autor,
                   "em": agora_iso()}, f)
    auditar(db, autor, ip, "PEDIR_ATUALIZACAO_SERVIDOR", "%s -> %s" % (VERSAO, est["baixada"]))
    db.commit()
    return est["baixada"]


def iniciar_verificador_diario():
    """Verifica ao iniciar o servidor e, depois, na primeira hora de cada novo dia."""
    def laco():
        while True:
            try:
                verificar_atualizacao()
            except Exception:
                traceback.print_exc()
            time.sleep(1800)
    threading.Thread(target=laco, daemon=True, name="atualizacao").start()


# ---------------------------------------------------------------------------
# API HTTPS
# ---------------------------------------------------------------------------

ID = r"([0-9a-f]{32})"
ROTAS = [
    ("GET", r"^/api/info$", "info", False),
    ("POST", r"^/api/login$", "login", False),
    ("POST", r"^/api/logout$", "logout", True),
    ("GET", r"^/api/eu$", "eu", True),
    ("GET", r"^/api/suspeitos$", "listar", True),
    ("POST", r"^/api/suspeitos$", "criar", True),
    ("GET", r"^/api/suspeitos/" + ID + r"$", "obter", True),
    ("PUT", r"^/api/suspeitos/" + ID + r"$", "atualizar", True),
    ("DELETE", r"^/api/suspeitos/" + ID + r"$", "excluir", True),
    ("POST", r"^/api/suspeitos/" + ID + r"/fotos$", "enviar_foto", True),
    ("GET", r"^/api/fotos/" + ID + r"$", "obter_foto", True),
    ("DELETE", r"^/api/fotos/" + ID + r"$", "excluir_foto", True),
    ("GET", r"^/api/avisos$", "avisos", True),
    ("POST", r"^/api/avisos/(\d{1,9})/ciente$", "aviso_ciente", True),
]


class Handler(BaseHTTPRequestHandler):
    server_version = "KeepPolicial/" + VERSAO
    sys_version = ""
    protocol_version = "HTTP/1.1"
    timeout = 60

    def log_message(self, formato, *args):
        sys.stderr.write("[%s] %s %s\n" % (agora_iso(), self.client_address[0], formato % args))

    # -- infraestrutura ----------------------------------------------------

    def do_GET(self):
        self._tratar("GET")

    def do_POST(self):
        self._tratar("POST")

    def do_PUT(self):
        self._tratar("PUT")

    def do_DELETE(self):
        self._tratar("DELETE")

    @property
    def ip(self):
        return self.client_address[0]

    def _descartar_corpo(self):
        # Le o corpo nao consumido antes de responder com erro; se a conexao
        # fechasse com dados pendentes, o TCP descartaria a resposta (RST).
        if self._corpo_lido:
            return
        self._corpo_lido = True
        try:
            restante = min(int(self.headers.get("Content-Length", "0")), 2 * MAX_BYTES_FOTO)
            while restante > 0:
                bloco = self.rfile.read(min(65536, restante))
                if not bloco:
                    break
                restante -= len(bloco)
        except (ValueError, OSError):
            pass

    def _tratar(self, metodo):
        self._corpo_lido = False
        try:
            url = urlparse(self.path)
            for m, padrao, nome, exige_login in ROTAS:
                if m != metodo:
                    continue
                achou = re.match(padrao, url.path)
                if not achou:
                    continue
                db = abrir_db()
                try:
                    usuario = self._autenticar(db) if exige_login else None
                    getattr(self, "r_" + nome)(db, usuario, parse_qs(url.query), *achou.groups())
                finally:
                    db.close()
                return
            raise Erro(404, "Rota não encontrada.")
        except Erro as e:
            self._descartar_corpo()
            self.close_connection = True
            self._json(e.status, {"erro": e.mensagem})
        except Exception:
            traceback.print_exc()
            self._descartar_corpo()
            self.close_connection = True
            self._json(500, {"erro": "Erro interno no servidor."})

    def _cabecalhos_comuns(self):
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        if self.close_connection:
            self.send_header("Connection", "close")

    def _json(self, status, obj):
        corpo = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(corpo)))
        self._cabecalhos_comuns()
        self.end_headers()
        self.wfile.write(corpo)

    def _ler_corpo(self, limite):
        try:
            tamanho = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            raise Erro(400, "Content-Length inválido.")
        if tamanho > limite:
            raise Erro(413, "Conteúdo grande demais.")
        self._corpo_lido = True
        return self.rfile.read(tamanho) if tamanho > 0 else b""

    def _ler_json(self):
        bruto = self._ler_corpo(MAX_BYTES_JSON)
        try:
            dados = json.loads(bruto.decode("utf-8") or "{}")
        except (ValueError, UnicodeDecodeError):
            raise Erro(400, "JSON inválido.")
        if not isinstance(dados, dict):
            raise Erro(400, "JSON inválido.")
        return dados

    def _autenticar(self, db):
        cab = self.headers.get("Authorization", "")
        if not cab.startswith("Bearer "):
            raise Erro(401, "Faça login.")
        linha = db.execute(
            "SELECT u.* FROM sessoes s JOIN usuarios u ON u.id = s.usuario_id "
            "WHERE s.token_hash = ? AND s.expira > ? AND u.ativo = 1",
            (hash_token(cab[7:].strip()), time.time()),
        ).fetchone()
        if not linha:
            raise Erro(401, "Sessão expirada. Faça login novamente.")
        return linha

    # -- rotas -------------------------------------------------------------

    def r_info(self, db, u, q):
        cfg = carregar_config()
        self._json(200, {"app": "keeppolicial", "versao": VERSAO,
                         "destacamento": cfg["nome_destacamento"],
                         "endereco_publico": cfg.get("endereco_publico") or ""})

    def r_login(self, db, u, q):
        dados = self._ler_json()
        usuario = str(dados.get("usuario", "")).strip().lower()
        senha = str(dados.get("senha", ""))
        if dados.get("aceite_termo") is not True:
            raise Erro(403, "É obrigatório aceitar o Termo de Compromisso de Sigilo.")
        linha = conferir_credenciais(db, usuario, senha, self.ip)
        cfg = carregar_config()
        token = secrets.token_urlsafe(32)
        expira = time.time() + float(cfg["horas_sessao"]) * 3600
        db.execute("DELETE FROM sessoes WHERE expira < ?", (time.time(),))
        db.execute("INSERT INTO sessoes VALUES (?,?,?,?)",
                   (hash_token(token), linha["id"], expira, self.ip))
        db.execute("UPDATE usuarios SET termo_aceito_em=? WHERE id=?", (agora_iso(), linha["id"]))
        auditar(db, usuario, self.ip, "ACEITE_TERMO_SIGILO")
        auditar(db, usuario, self.ip, "LOGIN")
        db.commit()
        self._json(200, {
            "token": token,
            "expira_em": int(expira),
            "usuario": linha["usuario"],
            "nome": linha["nome"],
            "papel": linha["papel"],
            "destacamento": cfg["nome_destacamento"],
            "endereco_publico": cfg.get("endereco_publico") or "",
        })

    def r_logout(self, db, u, q):
        cab = self.headers.get("Authorization", "")
        db.execute("DELETE FROM sessoes WHERE token_hash = ?", (hash_token(cab[7:].strip()),))
        auditar(db, u["usuario"], self.ip, "LOGOUT")
        db.commit()
        self._json(200, {"ok": True})

    def r_eu(self, db, u, q):
        self._json(200, {"usuario": u["usuario"], "nome": u["nome"], "papel": u["papel"]})

    def r_avisos(self, db, u, q):
        """Avisos ativos que este policial ainda nao marcou como 'ciente'."""
        itens = [dict(r) for r in db.execute(
            "SELECT a.id, a.titulo, a.mensagem, a.criado_por, a.criado_em FROM avisos a "
            "WHERE a.ativo = 1 AND NOT EXISTS (SELECT 1 FROM avisos_ciencia c "
            "WHERE c.aviso_id = a.id AND c.usuario = ?) ORDER BY a.id", (u["usuario"],))]
        self._json(200, {"avisos": itens})

    def r_aviso_ciente(self, db, u, q, aid):
        a = db.execute("SELECT titulo FROM avisos WHERE id = ?", (int(aid),)).fetchone()
        if not a:
            raise Erro(404, "Aviso não encontrado.")
        cur = db.execute("INSERT OR IGNORE INTO avisos_ciencia (aviso_id, usuario, em) "
                         "VALUES (?,?,?)", (int(aid), u["usuario"], agora_iso()))
        if cur.rowcount:
            auditar(db, u["usuario"], self.ip, "CIENTE_AVISO", "%s (%s)" % (aid, a["titulo"]))
        db.commit()
        self._json(200, {"ok": True})

    def r_listar(self, db, u, q):
        termo = normalizar((q.get("q") or [""])[0]).strip()
        sql = ("SELECT s.id, s.nome, s.alcunha, s.cpf, s.comportamento, s.atualizado_em, "
               "(SELECT f.id FROM fotos f WHERE f.suspeito_id = s.id ORDER BY f.ordem LIMIT 1) "
               "AS foto_capa, "
               "(SELECT COUNT(*) FROM passagens p WHERE p.suspeito_id = s.id) AS qtd_passagens "
               "FROM suspeitos s")
        args = []
        if termo:
            partes = termo.split()
            # Cada termo vale como digitado ou sem pontuação (placa ABC-1D23 = ABC1D23).
            sql += " WHERE " + " AND ".join(["(s.busca LIKE ? OR s.busca LIKE ?)"] * len(partes))
            for p in partes:
                p = p.replace("%", "").replace("_", "")
                args += ["%" + p + "%", "%" + (re.sub(r"[^0-9a-z]", "", p) or p) + "%"]
        sql += " ORDER BY s.atualizado_em DESC"
        itens = [dict(r) for r in db.execute(sql, args).fetchall()]
        self._json(200, {"suspeitos": itens})

    def _validar_ficha(self, dados):
        ficha = {}
        for campo, limite in LIMITES.items():
            valor = str(dados.get(campo, "") or "").strip()
            if len(valor) > limite:
                raise Erro(400, "Campo '%s' excede %d caracteres." % (campo, limite))
            ficha[campo] = valor
        comp = str(dados.get("comportamento", "") or "")
        if comp not in COMPORTAMENTOS:
            raise Erro(400, "Comportamento inválido.")
        ficha["comportamento"] = comp
        passagens = dados.get("passagens") or []
        if not isinstance(passagens, list) or len(passagens) > MAX_PASSAGENS:
            raise Erro(400, "Lista de passagens inválida.")
        lista = []
        for p in passagens:
            if not isinstance(p, dict):
                raise Erro(400, "Passagem inválida.")
            reds = str(p.get("reds", "") or "").strip()
            descr = str(p.get("descricao", "") or "").strip()
            data = str(p.get("data", "") or "").strip()[:10]
            if len(reds) > LIMITE_REDS or len(descr) > LIMITE_DESCR_PASSAGEM:
                raise Erro(400, "Passagem com texto longo demais.")
            if reds or descr:
                lista.append((reds, descr, data))
        # App 1.0 nao conhece veiculos: sem a chave, os veiculos gravados sao mantidos.
        veiculos = (dados.get("veiculos") or []) if "veiculos" in dados else None
        if veiculos is not None and (not isinstance(veiculos, list) or len(veiculos) > MAX_VEICULOS):
            raise Erro(400, "Lista de veículos inválida (máximo %d)." % MAX_VEICULOS)
        lista_v = None if veiculos is None else []
        for v in veiculos or []:
            if not isinstance(v, dict):
                raise Erro(400, "Veículo inválido.")
            item = {c: str(v.get(c, "") or "").strip() for c in LIMITES_VEICULO}
            item["placa"] = re.sub(r"[^A-Za-z0-9]", "", item["placa"]).upper()
            for c, lim in LIMITES_VEICULO.items():
                if len(item[c]) > lim:
                    raise Erro(400, "Veículo: campo '%s' excede %d caracteres." % (c, lim))
            if any(item.values()):
                lista_v.append(item)
        if not (ficha["nome"] or ficha["alcunha"]):
            raise Erro(400, "Informe ao menos o nome ou a alcunha.")
        ficha["busca"] = ""  # calculado em _gravar_passagens, depois de gravar os veiculos
        return ficha, (lista, lista_v)

    def _gravar_passagens(self, db, sid, listas, ficha):
        lista, lista_v = listas
        db.execute("DELETE FROM passagens WHERE suspeito_id = ?", (sid,))
        for i, (reds, descr, data) in enumerate(lista):
            db.execute("INSERT INTO passagens (suspeito_id, ordem, reds, descricao, data) "
                       "VALUES (?,?,?,?,?)", (sid, i, reds, descr, data))
        if lista_v is not None:
            db.execute("DELETE FROM veiculos WHERE suspeito_id = ?", (sid,))
            for i, v in enumerate(lista_v):
                db.execute("INSERT INTO veiculos (suspeito_id, ordem, marca, modelo, cor, placa) "
                           "VALUES (?,?,?,?,?,?)",
                           (sid, i, v["marca"], v["modelo"], v["cor"], v["placa"]))
        veic = db.execute("SELECT placa, modelo FROM veiculos WHERE suspeito_id = ?", (sid,)).fetchall()
        busca = " ".join([
            normalizar(ficha["nome"]), normalizar(ficha["alcunha"]),
            somente_digitos(ficha["cpf"]), somente_digitos(ficha["rg"]), normalizar(ficha["rg"]),
            normalizar(ficha["mae"]), " ".join(normalizar(r) for r, _, _ in lista),
            " ".join(normalizar(v["placa"] + " " + v["modelo"]) for v in veic),
        ])
        db.execute("UPDATE suspeitos SET busca = ? WHERE id = ?", (busca, sid))

    def _ficha_completa(self, db, sid):
        s = db.execute("SELECT * FROM suspeitos WHERE id = ?", (sid,)).fetchone()
        if not s:
            raise Erro(404, "Ficha não encontrada.")
        ficha = dict(s)
        ficha.pop("busca", None)
        ficha["passagens"] = [dict(r) for r in db.execute(
            "SELECT reds, descricao, data FROM passagens WHERE suspeito_id = ? ORDER BY ordem",
            (sid,))]
        ficha["veiculos"] = [dict(r) for r in db.execute(
            "SELECT marca, modelo, cor, placa FROM veiculos WHERE suspeito_id = ? ORDER BY ordem",
            (sid,))]
        ficha["fotos"] = [r["id"] for r in db.execute(
            "SELECT id FROM fotos WHERE suspeito_id = ? ORDER BY ordem", (sid,))]
        return ficha

    def r_criar(self, db, u, q):
        ficha, lista = self._validar_ficha(self._ler_json())
        sid = uuid.uuid4().hex
        agora = agora_iso()
        db.execute(
            "INSERT INTO suspeitos (id, nome, alcunha, cpf, rg, endereco, pai, mae, "
            "comportamento, observacoes, busca, criado_por, criado_em, atualizado_por, "
            "atualizado_em) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (sid, ficha["nome"], ficha["alcunha"], ficha["cpf"], ficha["rg"], ficha["endereco"],
             ficha["pai"], ficha["mae"], ficha["comportamento"], ficha["observacoes"],
             ficha["busca"], u["usuario"], agora, u["usuario"], agora))
        self._gravar_passagens(db, sid, lista, ficha)
        auditar(db, u["usuario"], self.ip, "CRIAR_FICHA", "%s (%s)" % (sid, ficha["nome"]))
        db.commit()
        self._json(201, self._ficha_completa(db, sid))

    def r_obter(self, db, u, q, sid):
        ficha = self._ficha_completa(db, sid)
        auditar(db, u["usuario"], self.ip, "VER_FICHA", "%s (%s)" % (sid, ficha["nome"]))
        db.commit()
        self._json(200, ficha)

    def r_atualizar(self, db, u, q, sid):
        if not db.execute("SELECT 1 FROM suspeitos WHERE id = ?", (sid,)).fetchone():
            raise Erro(404, "Ficha não encontrada.")
        ficha, lista = self._validar_ficha(self._ler_json())
        db.execute(
            "UPDATE suspeitos SET nome=?, alcunha=?, cpf=?, rg=?, endereco=?, pai=?, mae=?, "
            "comportamento=?, observacoes=?, busca=?, atualizado_por=?, atualizado_em=? "
            "WHERE id=?",
            (ficha["nome"], ficha["alcunha"], ficha["cpf"], ficha["rg"], ficha["endereco"],
             ficha["pai"], ficha["mae"], ficha["comportamento"], ficha["observacoes"],
             ficha["busca"], u["usuario"], agora_iso(), sid))
        self._gravar_passagens(db, sid, lista, ficha)
        auditar(db, u["usuario"], self.ip, "EDITAR_FICHA", "%s (%s)" % (sid, ficha["nome"]))
        db.commit()
        self._json(200, self._ficha_completa(db, sid))

    def r_excluir(self, db, u, q, sid):
        if u["papel"] != "admin":
            raise Erro(403, "Somente o administrador (comandante) pode excluir fichas.")
        s = db.execute("SELECT nome FROM suspeitos WHERE id = ?", (sid,)).fetchone()
        if not s:
            raise Erro(404, "Ficha não encontrada.")
        fotos = [r["id"] for r in db.execute("SELECT id FROM fotos WHERE suspeito_id=?", (sid,))]
        db.execute("DELETE FROM fotos WHERE suspeito_id = ?", (sid,))
        db.execute("DELETE FROM passagens WHERE suspeito_id = ?", (sid,))
        db.execute("DELETE FROM veiculos WHERE suspeito_id = ?", (sid,))
        db.execute("DELETE FROM suspeitos WHERE id = ?", (sid,))
        auditar(db, u["usuario"], self.ip, "EXCLUIR_FICHA", "%s (%s)" % (sid, s["nome"]))
        db.commit()
        for fid in fotos:
            try:
                os.remove(caminho(DIR_FOTOS, fid + ".jpg"))
            except FileNotFoundError:
                pass
        self._json(200, {"ok": True})

    def r_enviar_foto(self, db, u, q, sid):
        if not db.execute("SELECT 1 FROM suspeitos WHERE id = ?", (sid,)).fetchone():
            raise Erro(404, "Ficha não encontrada.")
        qtd = db.execute("SELECT COUNT(*) FROM fotos WHERE suspeito_id = ?", (sid,)).fetchone()[0]
        if qtd >= MAX_FOTOS:
            raise Erro(400, "Limite de %d fotos por ficha atingido." % MAX_FOTOS)
        conteudo = self._ler_corpo(MAX_BYTES_FOTO)
        if not conteudo.startswith(b"\xff\xd8\xff"):
            raise Erro(400, "A foto deve estar no formato JPEG.")
        fid = uuid.uuid4().hex
        with open(caminho(DIR_FOTOS, fid + ".jpg"), "wb") as f:
            f.write(conteudo)
        ordem = db.execute("SELECT COALESCE(MAX(ordem), -1) + 1 FROM fotos WHERE suspeito_id=?",
                           (sid,)).fetchone()[0]
        db.execute("INSERT INTO fotos VALUES (?,?,?,?,?,?)",
                   (fid, sid, ordem, len(conteudo), u["usuario"], agora_iso()))
        db.execute("UPDATE suspeitos SET atualizado_por=?, atualizado_em=? WHERE id=?",
                   (u["usuario"], agora_iso(), sid))
        auditar(db, u["usuario"], self.ip, "ADICIONAR_FOTO", "%s / %s" % (sid, fid))
        db.commit()
        self._json(201, {"id": fid})

    def r_obter_foto(self, db, u, q, fid):
        if not db.execute("SELECT 1 FROM fotos WHERE id = ?", (fid,)).fetchone():
            raise Erro(404, "Foto não encontrada.")
        try:
            with open(caminho(DIR_FOTOS, fid + ".jpg"), "rb") as f:
                conteudo = f.read()
        except FileNotFoundError:
            raise Erro(404, "Arquivo da foto não encontrado.")
        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Content-Length", str(len(conteudo)))
        self._cabecalhos_comuns()
        self.end_headers()
        self.wfile.write(conteudo)

    def r_excluir_foto(self, db, u, q, fid):
        f = db.execute("SELECT suspeito_id FROM fotos WHERE id = ?", (fid,)).fetchone()
        if not f:
            raise Erro(404, "Foto não encontrada.")
        db.execute("DELETE FROM fotos WHERE id = ?", (fid,))
        db.execute("UPDATE suspeitos SET atualizado_por=?, atualizado_em=? WHERE id=?",
                   (u["usuario"], agora_iso(), f["suspeito_id"]))
        auditar(db, u["usuario"], self.ip, "EXCLUIR_FOTO", "%s / %s" % (f["suspeito_id"], fid))
        db.commit()
        try:
            os.remove(caminho(DIR_FOTOS, fid + ".jpg"))
        except FileNotFoundError:
            pass
        self._json(200, {"ok": True})


_ASSINATURA_PROXY_V2 = b"\r\n\r\n\x00\r\nQUIT\n"


def _eh_local(ip):
    return ip.startswith("127.") or ip in ("::1", "::ffff:127.0.0.1")


def _receber_exato(sock, n):
    dados = b""
    while len(dados) < n:
        parte = sock.recv(n - len(dados))
        if not parte:
            raise ValueError("conexão encerrada no cabeçalho PROXY")
        dados += parte
    return dados


def ler_cabecalho_proxy(sock):
    """Lê o cabeçalho PROXY (v1 ou v2) enviado pelo Tailscale Funnel, se houver.

    Devolve o IP de origem ou None (conexão local comum, sem cabeçalho).
    Só é chamado para conexões vindas de 127.0.0.1: ninguém de fora consegue
    forjar o IP por aqui."""
    espiar = b""
    for _ in range(100):
        espiar = sock.recv(16, socket.MSG_PEEK)
        if not espiar:
            return None
        if len(espiar) >= 16 or not (_ASSINATURA_PROXY_V2.startswith(espiar[:12])
                                      or b"PROXY ".startswith(espiar[:6])):
            break
        time.sleep(0.01)
    if espiar.startswith(b"PROXY "):
        linha = b""
        while not linha.endswith(b"\r\n"):
            linha += _receber_exato(sock, 1)
            if len(linha) > 107:
                raise ValueError("cabeçalho PROXY v1 longo demais")
        partes = linha.decode("ascii", "replace").split()
        if len(partes) >= 3 and partes[1] in ("TCP4", "TCP6"):
            return str(ipaddress.ip_address(partes[2]))
        return None
    if espiar[:12] == _ASSINATURA_PROXY_V2:
        cab = _receber_exato(sock, 16)
        comando, familia = cab[12] & 0x0F, cab[13] >> 4
        corpo = _receber_exato(sock, int.from_bytes(cab[14:16], "big"))
        if comando == 1 and familia == 1 and len(corpo) >= 4:
            return str(ipaddress.IPv4Address(corpo[:4]))
        if comando == 1 and familia == 2 and len(corpo) >= 16:
            ip = ipaddress.IPv6Address(corpo[:16])
            return str(ip.ipv4_mapped or ip)
        return None
    return None


class ServidorHTTPS(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 64

    def __init__(self, endereco, contexto):
        super().__init__(endereco, Handler)
        self.contexto = contexto

    def handle_error(self, request, client_address):
        # Celular que perde o sinal no meio da conexao nao e erro do servidor.
        if not isinstance(sys.exc_info()[1], (ConnectionError, TimeoutError, ssl.SSLError)):
            super().handle_error(request, client_address)

    def finish_request(self, request, client_address):
        # O handshake TLS acontece aqui, ja na thread da requisicao, para que
        # um cliente lento nao trave o servidor inteiro.
        request.settimeout(30)
        if _eh_local(client_address[0]):
            # Conexoes vindas do proprio computador podem ser o Tailscale Funnel,
            # que informa o IP real do celular pelo PROXY protocol.
            try:
                ip_real = ler_cabecalho_proxy(request)
            except (ValueError, OSError):
                return
            if ip_real:
                client_address = (ip_real, 0)
        try:
            seguro = self.contexto.wrap_socket(request, server_side=True)
        except (ssl.SSLError, OSError):
            return
        try:
            self.RequestHandlerClass(seguro, client_address, self)
        finally:
            try:
                seguro.close()
            except OSError:
                pass


# ---------------------------------------------------------------------------
# Painel de administracao (abre no navegador, somente neste computador)
# ---------------------------------------------------------------------------

MINUTOS_SESSAO_PAINEL = 30
_sessoes_painel = {}
_trava_painel = threading.Lock()

CSS_PAINEL = """
*{box-sizing:border-box}
body{margin:0;font-family:"Segoe UI",Ubuntu,Roboto,Arial,sans-serif;background:#ECEFF4;color:#1A1F2B;font-size:15px}
header{background:#1B2A4A;color:#fff;padding:14px 24px;display:flex;align-items:center;gap:24px;flex-wrap:wrap}
header b{font-size:18px;margin-right:auto}
header b small{display:block;font-weight:normal;font-size:13px;color:#C9D2E3}
header a,header button{color:#fff;text-decoration:none;font-size:15px;background:none;border:0;cursor:pointer;padding:6px 2px;font-family:inherit}
header a.atual{border-bottom:3px solid #C9A227}
main{max-width:1100px;margin:0 auto;padding:20px 16px 48px}
.cartao{background:#fff;border:1px solid #D5DAE3;border-radius:10px;padding:18px 20px;margin-bottom:18px}
h1{font-size:20px;margin:0 0 14px}h2{font-size:17px;margin:0 0 12px;color:#1B2A4A}
table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:9px 8px;border-bottom:1px solid #E3E7EE;vertical-align:middle}
th{font-size:13px;color:#5B6475;font-weight:600}
td small{color:#5B6475}
label{display:block;font-size:13px;color:#5B6475;margin:10px 0 4px}
textarea{width:100%;padding:9px 10px;border:1px solid #C3CAD6;border-radius:6px;font-size:15px;font-family:inherit;resize:vertical}
pre.notas{white-space:pre-wrap;background:#F5F7FA;border-radius:6px;padding:10px;font-size:13px;max-height:260px;overflow:auto}
input,select{width:100%;padding:9px 10px;border:1px solid #C3CAD6;border-radius:6px;font-size:15px;font-family:inherit}
.linha{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:0 14px}
.botao{display:inline-block;background:#1B2A4A;color:#fff;border:0;border-radius:6px;padding:9px 16px;font-size:14px;cursor:pointer;text-decoration:none;font-family:inherit}
.botao.sec{background:#fff;color:#1B2A4A;border:1px solid #1B2A4A}
.botao.perigo{background:#C62828}
.botao.peq{padding:5px 10px;font-size:13px}
.acoes{display:flex;gap:6px;flex-wrap:wrap}.acoes form{margin:0}
.aviso{padding:11px 14px;border-radius:8px;margin-bottom:16px}
.aviso.ok{background:#E3F2E5;color:#1E5E22}.aviso.novo{background:#FFF4D6;color:#5C4400;border:1px solid #E8C766}.aviso.erro{background:#FBE4E4;color:#8E1F1F}
.etq{display:inline-block;padding:2px 8px;border-radius:10px;font-size:12px;font-weight:600}
.etq.ativo{background:#E3F2E5;color:#1E5E22}.etq.inativo{background:#E9ECF1;color:#5B6475}
.etq.bloq{background:#FDEBD8;color:#9A3D00}.etq.admin{background:#F6EDCB;color:#6B5200}
.codigo{font-family:Consolas,"Ubuntu Mono",monospace;font-size:24px;letter-spacing:1px;color:#1B2A4A;font-weight:bold}
.mono{font-family:Consolas,"Ubuntu Mono",monospace}
.dica{color:#5B6475;font-size:13px}
.entrada{max-width:380px;margin:60px auto}
.rolagem{overflow-x:auto}
"""

JS_PAINEL = """
function gerarSenha(id){var a="abcdefghjkmnpqrstuvwxyzACDEFGHJKLMNPQRSTUVWXYZ2345679",
r=new Uint32Array(10),s="";crypto.getRandomValues(r);
for(var i=0;i<10;i++)s+=a[r[i]%a.length];
document.querySelectorAll("#"+id+" input[name=senha],#"+id+" input[name=senha2]").forEach(
function(e){e.type="text";e.value=s;});}
"""


def _e(texto):
    return html.escape("" if texto is None else str(texto), quote=True)


class HandlerPainel(BaseHTTPRequestHandler):
    server_version = "KeepPolicial-Painel"
    sys_version = ""
    protocol_version = "HTTP/1.1"
    timeout = 60

    def log_message(self, formato, *args):
        pass

    def do_GET(self):
        self._tratar("GET")

    def do_POST(self):
        self._tratar("POST")

    # -- infraestrutura ----------------------------------------------------

    def _tratar(self, metodo):
        try:
            porta = self.server.server_address[1]
            host = self.headers.get("Host", "")
            # Bloqueia sites externos que tentem usar o painel (DNS rebinding / CSRF).
            if host not in ("127.0.0.1:%d" % porta, "localhost:%d" % porta):
                return self._html(400, "Endereço não permitido.")
            self.form = {}
            if metodo == "POST":
                origem = self.headers.get("Origin")
                if origem and origem != "http://" + host:
                    return self._html(403, "Origem não permitida.")
                tamanho = int(self.headers.get("Content-Length", "0") or 0)
                if tamanho > 64 * 1024:
                    self.close_connection = True
                    return self._html(413, "Formulário grande demais.")
                bruto = self.rfile.read(tamanho).decode("utf-8", "replace") if tamanho else ""
                self.form = {k: v[0] for k, v in parse_qs(bruto, keep_blank_values=True).items()}
            url = urlparse(self.path)
            db = abrir_db()
            try:
                self._rotear(db, metodo, url.path, parse_qs(url.query))
            finally:
                db.close()
        except Exception:
            traceback.print_exc()
            self.close_connection = True
            self._html(500, "Erro interno no painel.")

    def _enviar(self, status, corpo, extras=()):
        dados = corpo.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(dados)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("X-Content-Type-Options", "nosniff")
        # "same-origin" (e nao "no-referrer"): com no-referrer o navegador envia
        # Origin: null nos formularios e a conferencia de origem os recusaria.
        self.send_header("Referrer-Policy", "same-origin")
        self.send_header("Content-Security-Policy",
                         "default-src 'none'; style-src 'unsafe-inline'; "
                         "script-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
        for nome, valor in extras:
            self.send_header(nome, valor)
        if self.close_connection:
            self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(dados)

    def _html(self, status, mensagem):
        self._enviar(status, self._pagina("Keep Policial", "<div class='cartao'>%s</div>"
                                          % _e(mensagem)))

    def _redirecionar(self, destino, extras=()):
        self.send_response(303)
        self.send_header("Location", destino)
        self.send_header("Content-Length", "0")
        self.send_header("Cache-Control", "no-store")
        for nome, valor in extras:
            self.send_header(nome, valor)
        self.end_headers()

    def _cookie_sid(self):
        for parte in self.headers.get("Cookie", "").split(";"):
            nome, _, valor = parte.strip().partition("=")
            if nome == "kp_painel":
                return valor
        return ""

    def _sessao(self, db):
        sid = self._cookie_sid()
        with _trava_painel:
            s = _sessoes_painel.get(sid)
            if not s or s["expira"] < time.time():
                _sessoes_painel.pop(sid, None)
                return None
            s["expira"] = time.time() + MINUTOS_SESSAO_PAINEL * 60
        u = db.execute("SELECT * FROM usuarios WHERE id=? AND ativo=1 AND papel='admin'",
                       (s["uid"],)).fetchone()
        if not u:
            with _trava_painel:
                _sessoes_painel.pop(sid, None)
            return None
        s["usuario"] = u
        return s

    def _avisar(self, s, tipo, msg):
        s["aviso"] = (tipo, msg)

    def _pagina(self, titulo, corpo, s=None, atual=""):
        cfg = carregar_config()
        nav = ""
        if s:
            itens = [("/", "Policiais"), ("/avisos", "Avisos"), ("/auditoria", "Auditoria"),
                     ("/atualizacoes", "Atualizações"), ("/config", "Configurações")]
            nav = "".join("<a href='%s'%s>%s</a>" % (h, " class='atual'" if h == atual else "", t)
                          for h, t in itens)
            nav += ("<form method='post' action='/sair' style='margin:0'>"
                    "<input type='hidden' name='csrf' value='%s'>"
                    "<button title='Sair do painel'>Sair (%s)</button></form>"
                    % (_e(s["csrf"]), _e(s["usuario"]["usuario"])))
        aviso = ""
        if s and s.get("aviso"):
            tipo, msg = s.pop("aviso")
            aviso = "<div class='aviso %s'>%s</div>" % (tipo, _e(msg))
        if s and atual != "/atualizacoes":
            est = estado_atualizacao()
            if est.get("baixada") and not est.get("pedido_instalacao"):
                aviso += ("<div class='aviso novo'><b>Nova versão do servidor baixada: %s</b> "
                          "(atual: %s). <form method='post' action='/atualizacoes/instalar' "
                          "style='display:inline;margin-left:8px'><input type='hidden' "
                          "name='csrf' value='%s'><button class='botao peq'>Instalar agora"
                          "</button></form> <a href='/atualizacoes'>Ver detalhes</a></div>"
                          % (_e(est["baixada"]), _e(VERSAO), _e(s["csrf"])))
        return ("<!DOCTYPE html><html lang='pt-BR'><head><meta charset='utf-8'>"
                "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                "<title>%s - Keep Policial</title><style>%s</style><script>%s</script></head>"
                "<body><header><b>Keep Policial<small>%s - painel do administrador - versão %s"
                "</small></b>%s</header><main>%s%s</main></body></html>"
                % (_e(titulo), CSS_PAINEL, JS_PAINEL, _e(cfg["nome_destacamento"]),
                   VERSAO.rsplit(".", 1)[0], nav, aviso, corpo))

    # -- roteamento --------------------------------------------------------

    def _rotear(self, db, metodo, rota, q):
        if metodo == "POST" and rota == "/entrar":
            return self._p_entrar(db)
        if metodo == "POST" and rota == "/primeiro-admin":
            return self._p_primeiro_admin(db)
        s = self._sessao(db)
        if not s:
            if metodo == "GET" and rota == "/":
                return self._g_entrada(db)
            return self._redirecionar("/")
        if metodo == "POST" and not hmac.compare_digest(self.form.get("csrf", ""), s["csrf"]):
            return self._html(403, "Formulário expirado. Volte e tente novamente.")
        autor = s["usuario"]["usuario"]
        achou = re.fullmatch(r"/usuarios/(\d+)(/ativar|/remover)?", rota)
        achou_aviso = re.fullmatch(r"/avisos/(\d+)/ativar", rota)
        try:
            if metodo == "GET" and rota == "/avisos":
                return self._g_avisos(db, s)
            if metodo == "POST" and rota == "/avisos/novo":
                aid = op_publicar_aviso(db, self.form.get("titulo"), self.form.get("mensagem"),
                                        autor, "painel")
                self._avisar(s, "ok", "Aviso nº %d publicado. Os policiais verão o pop-up ao "
                                      "abrir o aplicativo." % aid)
                return self._redirecionar("/avisos")
            if metodo == "POST" and achou_aviso:
                ativo = self.form.get("valor") == "1"
                titulo = op_definir_aviso_ativo(db, int(achou_aviso.group(1)), ativo, autor,
                                                "painel")
                self._avisar(s, "ok", "Aviso '%s' %s." % (titulo, "reativado" if ativo
                                                          else "encerrado"))
                return self._redirecionar("/avisos")
            if metodo == "GET" and rota == "/atualizacoes":
                return self._g_atualizacoes(db, s)
            if metodo == "POST" and rota == "/atualizacoes/verificar":
                est = verificar_atualizacao(forcar=True)
                if est.get("erro"):
                    self._avisar(s, "erro", "Não foi possível consultar o GitHub: %s" % est["erro"])
                elif est.get("baixada"):
                    self._avisar(s, "ok", "Versão %s baixada e conferida. Clique em "
                                          "'Instalar agora'." % est["baixada"])
                else:
                    self._avisar(s, "ok", "O servidor já está na versão mais recente.")
                return self._redirecionar("/atualizacoes")
            if metodo == "POST" and rota == "/atualizacoes/instalar":
                versao = op_pedir_instalacao(db, autor, "painel")
                self._avisar(s, "ok", "Instalação da versão %s solicitada. O servidor será "
                                      "reiniciado em alguns segundos; recarregue esta página."
                             % versao)
                return self._redirecionar("/atualizacoes")
            if metodo == "GET" and rota == "/":
                return self._g_inicio(db, s)
            if metodo == "GET" and rota == "/auditoria":
                return self._g_auditoria(db, s, q)
            if metodo == "GET" and rota == "/config":
                return self._g_config(db, s)
            if metodo == "POST" and rota == "/config":
                return self._p_config(db, s, autor)
            if metodo == "POST" and rota == "/sair":
                with _trava_painel:
                    _sessoes_painel.pop(self._cookie_sid(), None)
                return self._redirecionar("/", [("Set-Cookie", "kp_painel=; Max-Age=0; Path=/")])
            if metodo == "POST" and rota == "/usuarios/novo":
                f = self.form
                if f.get("senha") != f.get("senha2"):
                    raise ValueError("As senhas digitadas não conferem.")
                login = op_criar_usuario(db, f.get("login"), f.get("nome"), f.get("papel"),
                                         f.get("senha"), autor, "painel")
                self._avisar(s, "ok", "Policial '%s' cadastrado. Acesso no app: %s"
                             % (login, texto_acesso(login, carregar_config())))
                return self._redirecionar("/")
            if achou:
                uid = int(achou.group(1))
                if metodo == "GET" and not achou.group(2):
                    return self._g_editar(db, s, uid)
                if metodo == "POST" and not achou.group(2):
                    f = self.form
                    if f.get("senha") != f.get("senha2"):
                        raise ValueError("As senhas digitadas não conferem.")
                    login = op_editar_usuario(db, uid, f.get("login"), f.get("nome"),
                                              f.get("papel"), f.get("senha"), autor, "painel")
                    self._avisar(s, "ok", "Dados de '%s' salvos%s." % (
                        login, " e senha alterada (sessões abertas foram encerradas)"
                        if f.get("senha") else ""))
                    return self._redirecionar("/")
                if metodo == "POST" and achou.group(2) == "/ativar":
                    ativo = self.form.get("valor") == "1"
                    login = op_definir_ativo(db, uid, ativo, autor, "painel")
                    self._avisar(s, "ok", "Usuário '%s' %s." % (
                        login, "ativado/desbloqueado" if ativo else "desativado"))
                    return self._redirecionar("/")
                if metodo == "POST" and achou.group(2) == "/remover":
                    login = op_remover_usuario(db, uid, autor, "painel")
                    self._avisar(s, "ok", "Usuário '%s' removido." % login)
                    return self._redirecionar("/")
        except ValueError as e:
            self._avisar(s, "erro", str(e))
            volta = rota if (achou and metodo == "POST" and not achou.group(2)) else "/"
            if rota == "/config" or rota.startswith("/avisos") or rota.startswith("/atualizacoes"):
                volta = "/" + rota.split("/")[1]
            return self._redirecionar(volta)
        return self._html(404, "Página não encontrada.")

    # -- entrada -----------------------------------------------------------

    def _g_entrada(self, db, erro=""):
        msg = "<div class='aviso erro'>%s</div>" % _e(erro) if erro else ""
        if not _admins_ativos(db):
            corpo = ("<div class='cartao entrada'><h1>Primeiro acesso</h1>"
                     "<p class='dica'>Nenhum administrador cadastrado. Crie o usuário do "
                     "comandante.</p>%s<form method='post' action='/primeiro-admin' id='fa'>"
                     "<label>Login</label><input name='login' required placeholder='cmt.silva'>"
                     "<label>Nome / graduação</label><input name='nome' placeholder='Cap PM Silva'>"
                     "<label>Senha (mínimo 8)</label><input name='senha' type='password' required>"
                     "<label>Repita a senha</label><input name='senha2' type='password' required>"
                     "<p><button class='botao'>Criar administrador</button></p></form></div>"
                     % msg)
        else:
            corpo = ("<div class='cartao entrada'><h1>Entrar no painel</h1>"
                     "<p class='dica'>Use o login e a senha de um administrador.</p>%s"
                     "<form method='post' action='/entrar'>"
                     "<label>Login</label><input name='usuario' required autofocus>"
                     "<label>Senha</label><input name='senha' type='password' required>"
                     "<p><button class='botao'>Entrar</button></p></form></div>" % msg)
        self._enviar(200, self._pagina("Entrar", corpo))

    def _abrir_sessao(self, uid):
        sid = secrets.token_urlsafe(32)
        with _trava_painel:
            agora = time.time()
            for k in [k for k, v in _sessoes_painel.items() if v["expira"] < agora]:
                del _sessoes_painel[k]
            _sessoes_painel[sid] = {"uid": uid, "csrf": secrets.token_urlsafe(24),
                                    "expira": agora + MINUTOS_SESSAO_PAINEL * 60}
        self._redirecionar("/", [("Set-Cookie",
                                  "kp_painel=%s; HttpOnly; SameSite=Strict; Path=/" % sid)])

    def _p_entrar(self, db):
        usuario = self.form.get("usuario", "").strip().lower()
        try:
            u = conferir_credenciais(db, usuario, self.form.get("senha", ""), "painel-local",
                                     "LOGIN_PAINEL_FALHOU")
        except Erro as e:
            return self._g_entrada(db, e.mensagem)
        if u["papel"] != "admin":
            db.commit()
            return self._g_entrada(db, "Somente administradores (comandante) acessam o painel.")
        auditar(db, usuario, "painel-local", "LOGIN_PAINEL")
        db.commit()
        self._abrir_sessao(u["id"])

    def _p_primeiro_admin(self, db):
        if _admins_ativos(db):
            return self._redirecionar("/")
        f = self.form
        try:
            if f.get("senha") != f.get("senha2"):
                raise ValueError("As senhas digitadas não conferem.")
            login = op_criar_usuario(db, f.get("login"), f.get("nome"), "admin",
                                     f.get("senha"), "painel-local", "painel")
        except ValueError as e:
            return self._g_entrada(db, str(e))
        u = db.execute("SELECT id FROM usuarios WHERE usuario=?", (login,)).fetchone()
        self._abrir_sessao(u["id"])

    # -- paginas -----------------------------------------------------------

    def _g_inicio(self, db, s):
        cfg = carregar_config()
        porta = int(cfg["porta"])
        enderecos = ["%s:%d" % (ip, porta) for ip in enderecos_locais()]
        publico = cfg.get("endereco_publico") or ""
        lista_end = "".join("<li class='mono'>%s</li>" % _e(x) for x in enderecos) or \
            "<li>(não identificado)</li>"
        acesso = ("<div class='cartao'><h2>Acesso pelos celulares</h2>"
                  "<div class='linha'><div><label>Código de verificação (1º acesso)</label>"
                  "<div class='codigo'>%s</div><p class='dica'>No primeiro acesso a este "
                  "servidor o app mostra este código. O policial confere e confirma uma única "
                  "vez.</p></div><div><label>Endereço pela internet</label>"
                  "<div class='mono' style='font-size:17px'>%s</div>"
                  "<label>Endereços na rede local</label><ul style='margin:4px 0'>%s</ul>"
                  "</div></div><p class='dica'>No aplicativo o policial digita "
                  "<b>login@endereço</b> (como um e-mail) e a senha. A porta %d é a padrão e "
                  "pode ser omitida.</p></div>"
                  % (_e(codigo_verificacao()),
                     _e(publico) if publico else "<span class='dica'>não configurado - "
                     "veja Configurações</span>", lista_end, porta))
        linhas = []
        agora = time.time()
        for u in db.execute("SELECT * FROM usuarios ORDER BY papel, usuario"):
            if not u["ativo"]:
                situacao = "<span class='etq inativo'>Desativado</span>"
            elif u["bloqueado_ate"] > agora:
                situacao = "<span class='etq bloq'>Bloqueado até %s</span>" % datetime.fromtimestamp(
                    u["bloqueado_ate"]).strftime("%H:%M")
            else:
                situacao = "<span class='etq ativo'>Ativo</span>"
            papel = ("<span class='etq admin'>Administrador</span>" if u["papel"] == "admin"
                     else "Policial")
            csrf = "<input type='hidden' name='csrf' value='%s'>" % _e(s["csrf"])
            if u["ativo"] and u["bloqueado_ate"] <= agora:
                botao_ativo = ("<form method='post' action='/usuarios/%d/ativar'>%s"
                               "<input type='hidden' name='valor' value='0'>"
                               "<button class='botao sec peq'>Desativar</button></form>"
                               % (u["id"], csrf))
            else:
                botao_ativo = ("<form method='post' action='/usuarios/%d/ativar'>%s"
                               "<input type='hidden' name='valor' value='1'>"
                               "<button class='botao sec peq'>%s</button></form>"
                               % (u["id"], csrf, "Desbloquear" if u["ativo"] else "Reativar"))
            linhas.append(
                "<tr><td><b>%s</b><br><small class='mono'>%s</small></td><td>%s</td><td>%s</td>"
                "<td>%s</td><td><small>%s</small></td><td><div class='acoes'>"
                "<a class='botao peq' href='/usuarios/%d'>Editar / senha</a>%s</div></td></tr>"
                % (_e(u["usuario"]), _e(texto_acesso(u["usuario"], cfg)), _e(u["nome"]), papel,
                   situacao, _e(u["termo_aceito_em"] or "nunca"), u["id"], botao_ativo))
        tabela = ("<div class='cartao'><h2>Policiais cadastrados</h2><div class='rolagem'><table>"
                  "<tr><th>Login / acesso no app</th><th>Nome</th><th>Perfil</th><th>Situação</th>"
                  "<th>Último aceite do termo</th><th></th></tr>%s</table></div></div>"
                  % ("".join(linhas) or "<tr><td colspan='6'>Nenhum usuário.</td></tr>"))
        novo = ("<div class='cartao'><h2>Cadastrar policial</h2>"
                "<form method='post' action='/usuarios/novo' id='fnovo'>"
                "<input type='hidden' name='csrf' value='%s'><div class='linha'>"
                "<div><label>Login</label><input name='login' required placeholder='sd.silva' "
                "pattern='[a-zA-Z0-9._\\-]{3,40}' title='3 a 40 letras, números, ponto, _ ou -'>"
                "</div><div><label>Nome / graduação</label><input name='nome' "
                "placeholder='Sd PM Silva'></div><div><label>Perfil</label><select name='papel'>"
                "<option value='usuario'>Policial (cria e edita fichas)</option>"
                "<option value='admin'>Administrador (também exclui fichas)</option></select>"
                "</div><div><label>Senha (mínimo 8)</label><input name='senha' type='password' "
                "required minlength='8'></div><div><label>Repita a senha</label>"
                "<input name='senha2' type='password' required minlength='8'></div></div>"
                "<p class='acoes'><button class='botao'>Cadastrar</button>"
                "<button type='button' class='botao sec' onclick=\"gerarSenha('fnovo')\">"
                "Gerar senha</button></p><p class='dica'>Ao gerar, a senha aparece nos campos: "
                "anote e entregue ao policial pessoalmente.</p></form></div>" % _e(s["csrf"]))
        self._enviar(200, self._pagina("Policiais", acesso + tabela + novo, s, "/"))

    def _g_editar(self, db, s, uid):
        u = db.execute("SELECT * FROM usuarios WHERE id=?", (uid,)).fetchone()
        if not u:
            return self._html(404, "Usuário não encontrado.")
        opc = "".join("<option value='%s'%s>%s</option>" % (
            v, " selected" if u["papel"] == v else "", t) for v, t in (
            ("usuario", "Policial (cria e edita fichas)"),
            ("admin", "Administrador (também exclui fichas)")))
        csrf = "<input type='hidden' name='csrf' value='%s'>" % _e(s["csrf"])
        corpo = ("<div class='cartao' style='max-width:640px'><h1>Editar: %s</h1>"
                 "<form method='post' action='/usuarios/%d' id='fed'>%s"
                 "<label>Login (usado para entrar no app)</label><input name='login' value='%s' "
                 "required pattern='[a-zA-Z0-9._\\-]{3,40}'>"
                 "<label>Nome / graduação</label><input name='nome' value='%s'>"
                 "<label>Perfil</label><select name='papel'>%s</select>"
                 "<label>Nova senha (deixe em branco para manter a atual)</label>"
                 "<input name='senha' type='password' minlength='8' autocomplete='new-password'>"
                 "<label>Repita a nova senha</label><input name='senha2' type='password' "
                 "minlength='8' autocomplete='new-password'>"
                 "<p class='acoes'><button class='botao'>Salvar</button>"
                 "<button type='button' class='botao sec' onclick=\"gerarSenha('fed')\">"
                 "Gerar senha</button><a class='botao sec' href='/'>Voltar</a></p>"
                 "<p class='dica'>Trocar a senha encerra as sessões abertas desse usuário nos "
                 "celulares.</p></form></div>"
                 "<div class='cartao' style='max-width:640px'><h2>Remover usuário</h2>"
                 "<form method='post' action='/usuarios/%d/remover' "
                 "onsubmit=\"return confirm('Remover definitivamente o usuário %s?')\">%s"
                 "<p class='dica'>O histórico de auditoria é mantido.</p>"
                 "<button class='botao perigo'>Remover</button></form></div>"
                 % (_e(u["usuario"]), uid, csrf, _e(u["usuario"]), _e(u["nome"]), opc,
                    uid, _e(u["usuario"]), csrf))
        self._enviar(200, self._pagina("Editar usuário", corpo, s, "/"))

    def _g_auditoria(self, db, s, q):
        filtro = (q.get("usuario") or [""])[0].strip().lower()
        sql, args = "SELECT * FROM auditoria", []
        if filtro:
            sql += " WHERE usuario = ?"
            args.append(filtro)
        sql += " ORDER BY id DESC LIMIT 300"
        linhas = "".join(
            "<tr><td class='mono'>%s</td><td>%s</td><td class='mono'>%s</td><td>%s</td>"
            "<td><small>%s</small></td></tr>" % (_e(r["ts"]), _e(r["usuario"]), _e(r["ip"]),
                                                _e(r["acao"]), _e(r["alvo"]))
            for r in db.execute(sql, args))
        corpo = ("<div class='cartao'><h2>Registro de acessos (últimos 300)</h2>"
                 "<form method='get' class='acoes' style='margin-bottom:12px'>"
                 "<input name='usuario' value='%s' placeholder='Filtrar por login' "
                 "style='max-width:260px'><button class='botao peq'>Filtrar</button>"
                 "<a class='botao sec peq' href='/auditoria'>Limpar</a></form>"
                 "<div class='rolagem'><table><tr><th>Data/hora</th><th>Usuário</th><th>IP</th>"
                 "<th>Ação</th><th>Detalhe</th></tr>%s</table></div></div>"
                 % (_e(filtro), linhas or "<tr><td colspan='5'>Nenhum registro.</td></tr>"))
        self._enviar(200, self._pagina("Auditoria", corpo, s, "/auditoria"))

    def _g_avisos(self, db, s):
        csrf = "<input type='hidden' name='csrf' value='%s'>" % _e(s["csrf"])
        ativos = db.execute("SELECT COUNT(*) FROM usuarios WHERE ativo = 1").fetchone()[0]
        novo = ("<div class='cartao'><h2>Novo aviso (pop-up no aplicativo)</h2>"
                "<form method='post' action='/avisos/novo'>%s"
                "<label>Título</label><input name='titulo' maxlength='%d' "
                "placeholder='Aviso do comando'>"
                "<label>Mensagem</label><textarea name='mensagem' rows='5' required "
                "maxlength='%d' placeholder='Ex.: Reunião geral amanhã às 7h no quartel.'>"
                "</textarea><p><button class='botao'>Publicar aviso</button></p>"
                "<p class='dica'>Cada policial vê o aviso em uma janela ao abrir o aplicativo "
                "e precisa tocar em <b>Ciente</b>. A confirmação fica registrada abaixo e na "
                "auditoria. Encerre o aviso quando ele não for mais necessário.</p></form></div>"
                % (csrf, LIMITE_TITULO_AVISO, LIMITE_MENSAGEM_AVISO))
        linhas = []
        for a in db.execute("SELECT * FROM avisos ORDER BY id DESC LIMIT 100").fetchall():
            cientes = db.execute("SELECT usuario, em FROM avisos_ciencia WHERE aviso_id = ? "
                                 "ORDER BY em", (a["id"],)).fetchall()
            lista = ", ".join("%s (%s)" % (_e(c["usuario"]), _e(c["em"][5:16])) for c in cientes)
            situacao = ("<span class='etq ativo'>No ar</span>" if a["ativo"]
                        else "<span class='etq inativo'>Encerrado</span>")
            botao = ("<form method='post' action='/avisos/%d/ativar'>%s"
                     "<input type='hidden' name='valor' value='%s'>"
                     "<button class='botao sec peq'>%s</button></form>"
                     % (a["id"], csrf, "0" if a["ativo"] else "1",
                        "Encerrar" if a["ativo"] else "Reativar"))
            linhas.append(
                "<tr><td><b>%s</b><br><small>%s</small><div style='white-space:pre-wrap;"
                "margin-top:6px'>%s</div></td><td>%s</td><td><b>%d</b> de %d<br>"
                "<small>%s</small></td><td>%s</td></tr>"
                % (_e(a["titulo"]), _e("%s por %s" % (a["criado_em"], a["criado_por"])),
                   _e(a["mensagem"]), situacao, len(cientes), ativos,
                   lista or "ninguém ainda", botao))
        tabela = ("<div class='cartao'><h2>Avisos publicados</h2><div class='rolagem'><table>"
                  "<tr><th>Aviso</th><th>Situação</th><th>Ciência dos policiais</th><th></th>"
                  "</tr>%s</table></div></div>"
                  % ("".join(linhas) or "<tr><td colspan='4'>Nenhum aviso publicado.</td></tr>"))
        self._enviar(200, self._pagina("Avisos", novo + tabela, s, "/avisos"))

    def _g_atualizacoes(self, db, s):
        est = estado_atualizacao()
        csrf = "<input type='hidden' name='csrf' value='%s'>" % _e(s["csrf"])
        if est.get("pedido_instalacao"):
            situacao = ("<div class='aviso novo'>Instalação solicitada. Aguarde alguns "
                        "segundos e recarregue a página. Se nada mudar em 1 minuto, rode no "
                        "terminal: <span class='mono'>sudo keeppolicial-atualizar</span></div>")
        elif est.get("baixada"):
            situacao = ("<div class='aviso novo'><b>Versão %s baixada e conferida.</b>"
                        "<form method='post' action='/atualizacoes/instalar' style='margin:10px 0 0'>"
                        "%s<button class='botao'>Instalar agora</button></form>"
                        "<p class='dica' style='margin-bottom:0'>O servidor fica fora do ar por "
                        "alguns segundos durante a reinicialização. Os dados não são alterados e "
                        "a versão anterior é guardada como cópia de segurança.</p></div>"
                        % (_e(est["baixada"]), csrf))
        else:
            situacao = "<div class='aviso ok'>O servidor está na versão mais recente conhecida.</div>"
        res = est.get("resultado_instalacao") or {}
        resultado = ""
        if res:
            resultado = ("<p><b>Última instalação:</b> %s - %s</p>"
                         % (_e(res.get("em", "")), _e(res.get("mensagem", ""))))
        erro = ("<p class='aviso erro'>Última consulta falhou: %s</p>" % _e(est["erro"])
                if est.get("erro") else "")
        notas = ("<label>Novidades da release %s</label><pre class='notas'>%s</pre>"
                 % (_e(est.get("ultima_release", "")), _e(est["notas"]))
                 if est.get("notas") else "")
        corpo = ("<div class='cartao' style='max-width:760px'><h1>Atualizações</h1>%s"
                 "<div class='linha'><div><label>Versão instalada</label><b class='mono'>%s</b>"
                 "</div><div><label>Última release no GitHub</label><b class='mono'>%s</b></div>"
                 "<div><label>Última consulta</label>%s</div></div>%s%s%s"
                 "<form method='post' action='/atualizacoes/verificar' style='margin-top:14px'>%s"
                 "<button class='botao sec'>Verificar agora</button></form>"
                 "<p class='dica'>O servidor consulta o repositório <span class='mono'>%s</span> "
                 "ao iniciar e uma vez por dia. Quando há versão nova, ela é baixada e conferida "
                 "automaticamente, mas só é instalada quando o administrador clica em "
                 "<b>Instalar agora</b>. O aplicativo dos celulares se atualiza sozinho pelo "
                 "mesmo repositório.</p></div>"
                 % (situacao, _e(VERSAO), _e(est.get("ultima_release") or "-"),
                    _e(est.get("ultima_verificacao_em") or "nunca"), erro, resultado, notas,
                    csrf, _e(REPO_GITHUB)))
        self._enviar(200, self._pagina("Atualizações", corpo, s, "/atualizacoes"))

    def _g_config(self, db, s):
        cfg = carregar_config()
        corpo = ("<div class='cartao' style='max-width:640px'><h1>Configurações</h1>"
                 "<form method='post' action='/config'>"
                 "<input type='hidden' name='csrf' value='%s'>"
                 "<label>Nome do destacamento</label><input name='nome' value='%s' required "
                 "maxlength='80'>"
                 "<label>Endereço pela internet (ex.: servidor-pm.tail1234.ts.net ou "
                 "pm-cidade.duckdns.org)</label><input name='endereco_publico' value='%s' "
                 "maxlength='120' placeholder='deixe em branco se usar só a rede local'>"
                 "<label>Duração da sessão no celular (horas)</label>"
                 "<input name='horas' type='number' min='1' max='24' step='1' value='%s'>"
                 "<p><button class='botao'>Salvar</button></p>"
                 "<p class='dica'>As mudanças valem na hora (a duração vale para os próximos "
                 "logins). Porta do servidor: %s. Código de verificação: <b class='mono'>%s</b>"
                 "</p></form></div>"
                 % (_e(s["csrf"]), _e(cfg["nome_destacamento"]), _e(cfg.get("endereco_publico")),
                    _e(int(float(cfg["horas_sessao"]))), _e(cfg["porta"]),
                    _e(codigo_verificacao())))
        self._enviar(200, self._pagina("Configurações", corpo, s, "/config"))

    def _p_config(self, db, s, autor):
        f = self.form
        nome = f.get("nome", "").strip()[:80]
        publico = re.sub(r"^https?://", "", f.get("endereco_publico", "").strip()).rstrip("/")
        if not nome:
            raise ValueError("Informe o nome do destacamento.")
        if publico and not re.fullmatch(r"[A-Za-z0-9.\-]+(:\d{1,5})?", publico):
            raise ValueError("Endereço pela internet inválido. Use só o nome ou IP "
                             "(e a porta, se não for a padrão).")
        try:
            horas = int(f.get("horas", "10"))
        except ValueError:
            raise ValueError("Duração da sessão inválida.")
        if not 1 <= horas <= 24:
            raise ValueError("A duração da sessão deve ficar entre 1 e 24 horas.")
        cfg = carregar_config()
        cfg.update(nome_destacamento=nome, endereco_publico=publico, horas_sessao=horas)
        salvar_config(cfg)
        auditar(db, autor, "painel", "ALTERAR_CONFIGURACAO",
                "nome=%s; internet=%s; horas=%d" % (nome, publico or "-", horas))
        db.commit()
        self._avisar(s, "ok", "Configurações salvas.")
        self._redirecionar("/config")


class ServidorPainel(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        if not isinstance(sys.exc_info()[1], (ConnectionError, TimeoutError)):
            super().handle_error(request, client_address)


def iniciar_painel(cfg):
    porta = int(cfg.get("porta_painel", 8480))
    try:
        srv = ServidorPainel(("127.0.0.1", porta), HandlerPainel)
    except OSError as e:
        print("AVISO: painel de administração não iniciado na porta %d (%s)." % (porta, e))
        return None
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    print("Painel do administrador (só neste computador): http://127.0.0.1:%d" % porta)
    return srv


# ---------------------------------------------------------------------------
# Comandos de administracao
# ---------------------------------------------------------------------------

def cmd_gerar_certificado(forcar=False):
    preparar_dados()
    if os.path.exists(caminho(ARQ_CERT)) and not forcar:
        print("Certificado já existe. Use --forcar para gerar outro "
              "(os celulares precisarão confirmar a nova impressão digital).")
    else:
        subprocess.run([
            "openssl", "req", "-x509", "-newkey", "rsa:3072", "-sha256", "-nodes",
            "-days", "3650", "-subj", "/CN=keeppolicial/O=Keep Policial",
            "-keyout", caminho(ARQ_CHAVE), "-out", caminho(ARQ_CERT),
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        os.chmod(caminho(ARQ_CHAVE), 0o600)
        print("Certificado gerado.")
    print("Código de verificação (o celular mostra no 1º acesso): " + codigo_verificacao())
    print("Impressão digital completa: " + impressao_digital_certificado())


def cmd_servir():
    preparar_dados()
    if not os.path.exists(caminho(ARQ_CERT)):
        cmd_gerar_certificado()
    cfg = carregar_config()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(caminho(ARQ_CERT), caminho(ARQ_CHAVE))
    db = abrir_db()
    if not db.execute("SELECT 1 FROM usuarios WHERE ativo = 1").fetchone():
        print("AVISO: nenhum usuário cadastrado. Cadastre com: usuario adicionar <login>")
    db.close()
    servidor = ServidorHTTPS((cfg["endereco"], int(cfg["porta"])), ctx)
    print("Keep Policial %s - %s" % (VERSAO, cfg["nome_destacamento"]))
    print("Ouvindo em https://%s:%s" % (cfg["endereco"], cfg["porta"]))
    print("Código de verificação (1º acesso no celular): " + codigo_verificacao())
    iniciar_painel(cfg)
    iniciar_verificador_diario()
    sys.stdout.flush()
    try:
        servidor.serve_forever()
    except KeyboardInterrupt:
        print("\nEncerrado.")


def ler_senha_nova():
    while True:
        s1 = getpass.getpass("Senha (mínimo 8 caracteres): ")
        if len(s1) < 8:
            print("Senha curta demais.")
            continue
        s2 = getpass.getpass("Repita a senha: ")
        if s1 != s2:
            print("As senhas não conferem.")
            continue
        return s1


def _cli(funcao, *args):
    try:
        return funcao(*args)
    except ValueError as e:
        raise SystemExit(str(e))


def cmd_usuario_adicionar(login, nome, admin):
    preparar_dados()
    login = _cli(validar_login, login)
    db = abrir_db()
    if db.execute("SELECT 1 FROM usuarios WHERE usuario = ?", (login,)).fetchone():
        raise SystemExit("Usuário '%s' já existe." % login)
    nome = nome or input("Nome/graduação (ex.: Sd PM Silva): ").strip() or login
    senha = ler_senha_nova()
    _cli(op_criar_usuario, db, login, nome, "admin" if admin else "usuario", senha, "console")
    print("Usuário '%s' criado%s." % (login, " como ADMINISTRADOR" if admin else ""))


def _usuario_existente(db, login):
    linha = db.execute("SELECT * FROM usuarios WHERE usuario = ?", (login.lower(),)).fetchone()
    if not linha:
        raise SystemExit("Usuário '%s' não encontrado." % login)
    return linha


def cmd_usuario_senha(login):
    preparar_dados()
    db = abrir_db()
    u = _usuario_existente(db, login)
    senha = ler_senha_nova()
    _cli(op_editar_usuario, db, u["id"], u["usuario"], u["nome"], u["papel"], senha, "console")
    print("Senha alterada. Sessões abertas desse usuário foram encerradas.")


def cmd_usuario_renomear(login, novo):
    preparar_dados()
    db = abrir_db()
    u = _usuario_existente(db, login)
    novo = _cli(op_editar_usuario, db, u["id"], novo, u["nome"], u["papel"], "", "console")
    print("Login '%s' alterado para '%s'." % (u["usuario"], novo))


def cmd_usuario_ativo(login, ativo):
    preparar_dados()
    db = abrir_db()
    u = _usuario_existente(db, login)
    _cli(op_definir_ativo, db, u["id"], ativo, "console")
    print("Usuário '%s' %s." % (u["usuario"], "ativado" if ativo else "desativado"))


def cmd_usuario_remover(login):
    preparar_dados()
    db = abrir_db()
    u = _usuario_existente(db, login)
    _cli(op_remover_usuario, db, u["id"], "console")
    print("Usuário '%s' removido." % u["usuario"])


def cmd_usuario_listar():
    preparar_dados()
    db = abrir_db()
    linhas = db.execute("SELECT * FROM usuarios ORDER BY usuario").fetchall()
    if not linhas:
        print("Nenhum usuário cadastrado.")
        return
    print("%-20s %-28s %-8s %-6s %s" % ("LOGIN", "NOME", "PAPEL", "ATIVO", "ÚLTIMO ACEITE DO TERMO"))
    for u in linhas:
        print("%-20s %-28s %-8s %-6s %s" % (u["usuario"], u["nome"][:28], u["papel"],
                                           "sim" if u["ativo"] else "não",
                                           u["termo_aceito_em"] or "-"))


def cmd_auditoria(limite, usuario=None):
    preparar_dados()
    db = abrir_db()
    sql, args = "SELECT * FROM auditoria", []
    if usuario:
        sql += " WHERE usuario = ?"
        args.append(usuario.lower())
    sql += " ORDER BY id DESC LIMIT ?"
    args.append(limite)
    for r in reversed(db.execute(sql, args).fetchall()):
        print("%s  %-16s %-15s %-20s %s" % (r["ts"], r["usuario"] or "-", r["ip"] or "-",
                                           r["acao"], r["alvo"] or ""))


def cmd_configurar(nome, porta, horas, publico=None):
    preparar_dados()
    cfg = carregar_config()
    if publico is not None:
        publico = re.sub(r"^https?://", "", publico.strip().lower()).rstrip("/").rstrip(".")
        if publico and not re.fullmatch(r"[a-z0-9.\-]+(:\d{1,5})?", publico):
            sys.exit("Endereço pela internet inválido: %s" % publico)
        cfg["endereco_publico"] = publico
    if nome:
        cfg["nome_destacamento"] = nome
    if porta:
        cfg["porta"] = porta
    if horas:
        cfg["horas_sessao"] = horas
    salvar_config(cfg)
    print(json.dumps(cfg, ensure_ascii=False, indent=2))
    print("Reinicie o servidor para aplicar (sudo systemctl restart keeppolicial).")


def cmd_backup(destino):
    preparar_dados()
    if not destino:
        os.makedirs(caminho("backups"), exist_ok=True)
        destino = caminho("backups", "keeppolicial-backup-%s.tar.gz"
                          % datetime.now().strftime("%Y%m%d-%H%M%S"))
    destino = os.path.abspath(destino)
    with tempfile.TemporaryDirectory() as tmp:
        copia = os.path.join(tmp, ARQ_DB)
        origem = abrir_db()
        alvo = sqlite3.connect(copia)
        origem.backup(alvo)
        alvo.close()
        origem.close()
        with tarfile.open(destino, "w:gz") as tar:
            tar.add(copia, arcname=ARQ_DB)
            tar.add(caminho(DIR_FOTOS), arcname=DIR_FOTOS)
            tar.add(caminho(ARQ_CONFIG), arcname=ARQ_CONFIG)
    os.chmod(destino, 0o600)
    print("Backup salvo em: " + destino)
    print("ATENÇÃO: o arquivo contém dados sigilosos. Guarde em mídia protegida.")


def cmd_atualizacao(instalar=False):
    preparar_dados()
    est = verificar_atualizacao(forcar=True)
    print("Versão instalada: %s | última release no GitHub: %s"
          % (VERSAO, est.get("ultima_release") or "-"))
    if est.get("erro"):
        print("Falha ao consultar o GitHub: " + est["erro"])
    if not est.get("baixada"):
        print("Nenhuma atualização pendente.")
        return
    print("Versão %s baixada e conferida." % est["baixada"])
    if instalar:
        versao = _cli(op_pedir_instalacao, abrir_db(), "console")
        print("Instalação da versão %s solicitada. O servidor reinicia em instantes." % versao)
    else:
        print("Para instalar: sudo keeppolicial atualizacao --instalar")


def menu():
    opcoes = [
        ("Listar usuários", lambda: cmd_usuario_listar()),
        ("Adicionar usuário (policial)",
         lambda: cmd_usuario_adicionar(input("Login: "), "", False)),
        ("Adicionar administrador (comandante)",
         lambda: cmd_usuario_adicionar(input("Login: "), "", True)),
        ("Trocar senha de usuário", lambda: cmd_usuario_senha(input("Login: "))),
        ("Trocar login de usuário",
         lambda: cmd_usuario_renomear(input("Login atual: "), input("Novo login: "))),
        ("Desativar usuário", lambda: cmd_usuario_ativo(input("Login: "), False)),
        ("Reativar / desbloquear usuário", lambda: cmd_usuario_ativo(input("Login: "), True)),
        ("Remover usuário", lambda: cmd_usuario_remover(input("Login: "))),
        ("Ver auditoria (últimos 50 registros)", lambda: cmd_auditoria(50)),
        ("Nome do destacamento",
         lambda: cmd_configurar(input("Nome do destacamento: ").strip(), None, None)),
        ("Mostrar código de verificação do servidor", lambda: cmd_gerar_certificado()),
        ("Fazer backup", lambda: cmd_backup(None)),
        ("Verificar / instalar atualização",
         lambda: cmd_atualizacao(input("Instalar se houver versão nova? (s/N): ")
                                 .strip().lower() == "s")),
    ]
    while True:
        cfg = carregar_config() if os.path.isdir(DADOS) else CONFIG_PADRAO
        print("\n=== Keep Policial - %s ===" % cfg["nome_destacamento"])
        for i, (rotulo, _) in enumerate(opcoes, 1):
            print("%2d) %s" % (i, rotulo))
        print(" 0) Sair")
        escolha = input("Opção: ").strip()
        if escolha in ("0", ""):
            return
        try:
            opcoes[int(escolha) - 1][1]()
        except (ValueError, IndexError):
            print("Opção inválida.")
        except SystemExit as e:
            print(e)


def main():
    p = argparse.ArgumentParser(description="Keep Policial - servidor do destacamento")
    sub = p.add_subparsers(dest="cmd")
    sub.add_parser("servir", help="inicia o servidor HTTPS")
    sub.add_parser("menu", help="menu interativo de administração")
    gc = sub.add_parser("gerar-certificado", help="gera o certificado TLS")
    gc.add_argument("--forcar", action="store_true")
    sub.add_parser("impressao-digital", help="mostra a impressão digital do certificado")

    pu = sub.add_parser("usuario", help="gerencia usuários")
    su = pu.add_subparsers(dest="acao", required=True)
    a = su.add_parser("adicionar")
    a.add_argument("login")
    a.add_argument("--nome", default="")
    a.add_argument("--admin", action="store_true", help="pode excluir fichas")
    for nome in ("senha", "desativar", "ativar", "remover"):
        su.add_parser(nome).add_argument("login")
    rn = su.add_parser("renomear", help="troca o login de um usuário")
    rn.add_argument("login")
    rn.add_argument("novo")
    su.add_parser("listar")

    au = sub.add_parser("auditoria", help="mostra o registro de acessos")
    au.add_argument("--limite", type=int, default=100)
    au.add_argument("--usuario")

    cf = sub.add_parser("configurar", help="altera nome do destacamento, porta etc.")
    cf.add_argument("--nome")
    cf.add_argument("--porta", type=int)
    cf.add_argument("--horas-sessao", type=float)
    cf.add_argument("--endereco-publico", help="endereço pela internet (ex.: nome.tail1234.ts.net)")

    at = sub.add_parser("atualizacao", help="consulta/baixa a versão nova no GitHub")
    at.add_argument("--instalar", action="store_true",
                    help="pede a instalação da versão baixada")

    bk = sub.add_parser("backup", help="gera backup .tar.gz dos dados")
    bk.add_argument("destino", nargs="?")

    args = p.parse_args()
    if args.cmd == "servir":
        cmd_servir()
    elif args.cmd == "gerar-certificado":
        cmd_gerar_certificado(args.forcar)
    elif args.cmd == "impressao-digital":
        print(impressao_digital_certificado())
        print("Código de verificação: " + codigo_verificacao())
    elif args.cmd == "usuario":
        if args.acao == "adicionar":
            cmd_usuario_adicionar(args.login, args.nome, args.admin)
        elif args.acao == "senha":
            cmd_usuario_senha(args.login)
        elif args.acao == "renomear":
            cmd_usuario_renomear(args.login, args.novo)
        elif args.acao == "desativar":
            cmd_usuario_ativo(args.login, False)
        elif args.acao == "ativar":
            cmd_usuario_ativo(args.login, True)
        elif args.acao == "remover":
            cmd_usuario_remover(args.login)
        elif args.acao == "listar":
            cmd_usuario_listar()
    elif args.cmd == "auditoria":
        cmd_auditoria(args.limite, args.usuario)
    elif args.cmd == "configurar":
        cmd_configurar(args.nome, args.porta, args.horas_sessao, args.endereco_publico)
    elif args.cmd == "backup":
        cmd_backup(args.destino)
    elif args.cmd == "atualizacao":
        cmd_atualizacao(args.instalar)
    else:
        menu()


if __name__ == "__main__":
    main()
