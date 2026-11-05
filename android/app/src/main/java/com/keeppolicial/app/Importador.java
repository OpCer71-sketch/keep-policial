package com.keeppolicial.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Importacao de fichas a partir de planilha (.xlsx ou .csv) ou de arquivo .json.
 *
 * Planilha: a primeira linha tem os nomes das colunas (nome, alcunha, cpf, rg,
 * endereco, pai, mae, comportamento, observacoes, reds, data, natureza, marca,
 * modelo, cor, placa). Cada linha e uma ficha; linhas com o mesmo CPF (ou mesmo
 * nome e mae), ou sem nome logo abaixo de uma ficha, acrescentam passagens e
 * veiculos a ela.
 */
final class Importador {
    static final int MAX_BYTES = 15 * 1024 * 1024;
    static final int MAX_FICHAS = 3000;
    private static final int MAX_DESCOMPACTADO = 60 * 1024 * 1024;
    private static final int MAX_PASSAGENS = 100;

    private static final String[] CAMPOS = {
            "nome", "alcunha", "cpf", "rg", "endereco", "pai", "mae", "comportamento", "observacoes"};
    private static final Map<String, Integer> LIMITES = new HashMap<>();
    private static final Map<String, String> APELIDOS = new HashMap<>();

    static {
        LIMITES.put("nome", 150);
        LIMITES.put("alcunha", 100);
        LIMITES.put("cpf", 14);
        LIMITES.put("rg", 20);
        LIMITES.put("endereco", 300);
        LIMITES.put("pai", 150);
        LIMITES.put("mae", 150);
        LIMITES.put("observacoes", 200);
        LIMITES.put("reds", 30);
        LIMITES.put("natureza", 300);
        LIMITES.put("marca", 40);
        LIMITES.put("modelo", 60);
        LIMITES.put("cor", 30);
        LIMITES.put("placa", 8);
        apelidos("nome", "nome", "nomecompleto", "nomedosuspeito", "suspeito", "nomesuspeito");
        apelidos("alcunha", "alcunha", "apelido", "vulgo");
        apelidos("cpf", "cpf", "ncpf", "numerocpf");
        apelidos("rg", "rg", "identidade", "nrg", "numerorg");
        apelidos("endereco", "endereco", "enderecocompleto", "residencia", "logradouro");
        apelidos("pai", "pai", "nomedopai", "nomepai", "filiacaopai");
        apelidos("mae", "mae", "nomedamae", "nomemae", "filiacaomae");
        apelidos("comportamento", "comportamento", "conduta");
        apelidos("observacoes", "observacoes", "observacao", "obs");
        apelidos("reds", "reds", "nreds", "noreds", "numeroreds", "numerodoreds", "nr", "bo");
        apelidos("data", "data", "datareds", "datadoreds", "datapassagem", "datadofato", "datafato");
        apelidos("natureza", "natureza", "historico", "descricao", "naturezahistorico", "delito",
                "crime", "naturezadapassagem");
        apelidos("marca", "marca", "marcaveiculo", "marcadoveiculo");
        apelidos("modelo", "modelo", "modeloveiculo", "modelodoveiculo");
        apelidos("cor", "cor", "corveiculo", "cordoveiculo");
        apelidos("placa", "placa", "placaveiculo", "placadoveiculo");
    }

    private static void apelidos(String campo, String... nomes) {
        for (String n : nomes) APELIDOS.put(n, campo);
    }

    private Importador() {
    }

    /** Ficha lida do arquivo, ainda nao enviada. */
    static class Rascunho {
        final Map<String, String> campos = new LinkedHashMap<>();
        final List<JSONObject> passagens = new ArrayList<>();
        final List<JSONObject> veiculos = new ArrayList<>();
        int linha;

        String rotulo() {
            String n = campos.get("nome");
            if (n == null || n.isEmpty()) n = campos.get("alcunha");
            return (linha > 0 ? "Linha " + linha + " - " : "") + (n == null ? "?" : n);
        }

        JSONObject paraJson() throws JSONException {
            JSONObject o = new JSONObject();
            for (String c : CAMPOS) o.put(c, valor(campos.get(c)));
            o.put("passagens", new JSONArray(passagens));
            o.put("veiculos", new JSONArray(veiculos));
            return o;
        }
    }

    static class Leitura {
        String arquivo = "";
        String formato = "";
        final List<Rascunho> fichas = new ArrayList<>();
        final List<String> avisos = new ArrayList<>();
        int qtdPassagens, qtdVeiculos;

        void aviso(String a) {
            if (avisos.size() < 200) avisos.add(a);
        }
    }

    // -- leitura do arquivo ------------------------------------------------------

    static Leitura ler(Context c, Uri uri) throws IOException {
        Leitura l = new Leitura();
        l.arquivo = nomeArquivo(c, uri);
        byte[] dados;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Não foi possível abrir o arquivo.");
            dados = lerTudo(in, MAX_BYTES);
        }
        String nome = l.arquivo.toLowerCase(Locale.ROOT);
        String inicio = new String(dados, 0, Math.min(dados.length, 64), StandardCharsets.ISO_8859_1)
                .replace("﻿", "").replace("ï»¿", "").trim();
        Construtor b = new Construtor(l);
        if (nome.endsWith(".xls") || (dados.length > 4 && (dados[0] & 0xFF) == 0xD0 && (dados[1] & 0xFF) == 0xCF)) {
            throw new IOException("Planilha no formato antigo do Excel (.xls). Abra-a no Excel ou "
                    + "no Google Planilhas e salve como .xlsx ou .csv.");
        } else if (nome.endsWith(".ods")) {
            throw new IOException("Planilha do LibreOffice (.ods). Salve como .xlsx ou .csv.");
        } else if (dados.length > 4 && dados[0] == 'P' && dados[1] == 'K') {
            l.formato = "Planilha Excel (.xlsx)";
            b.linhas(lerXlsx(dados));
        } else if (nome.endsWith(".json") || inicio.startsWith("[") || inicio.startsWith("{")) {
            l.formato = "JSON";
            lerJson(decodificar(dados), b);
        } else {
            l.formato = "Planilha CSV";
            b.linhas(lerCsv(decodificar(dados)));
        }
        b.concluir();
        if (l.fichas.isEmpty()) {
            throw new IOException("Nenhuma ficha encontrada no arquivo. A primeira linha da planilha "
                    + "precisa ter os nomes das colunas (ex.: nome, alcunha, cpf, reds, placa) e cada "
                    + "ficha precisa de nome ou alcunha.");
        }
        return l;
    }

    private static String nomeArquivo(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && cur.getString(0) != null) return cur.getString(0);
        } catch (Exception ignorado) {
        }
        String s = uri.getLastPathSegment();
        return s == null ? "arquivo" : s;
    }

    private static byte[] lerTudo(InputStream in, int limite) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > limite) {
                throw new IOException("Arquivo grande demais (máximo " + (limite / 1024 / 1024) + " MB).");
            }
        }
        return out.toByteArray();
    }

    /** UTF-8 (com ou sem BOM); se nao for UTF-8 valido, Windows-1252 (CSV do Excel). */
    static String decodificar(byte[] dados) {
        int ini = dados.length >= 3 && (dados[0] & 0xFF) == 0xEF && (dados[1] & 0xFF) == 0xBB
                && (dados[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(dados, ini, dados.length - ini)).toString();
        } catch (CharacterCodingException e) {
            return new String(dados, Charset.forName("windows-1252"));
        }
    }

    // -- CSV -------------------------------------------------------------------

    static List<List<String>> lerCsv(String texto) {
        String primeira = texto.split("\r?\n", 2)[0];
        char sep = ';';
        int melhor = -1;
        for (char c : new char[]{';', ',', '\t'}) {
            int n = 0;
            boolean aspas = false;
            for (char x : primeira.toCharArray()) {
                if (x == '"') aspas = !aspas;
                else if (x == c && !aspas) n++;
            }
            if (n > melhor) {
                melhor = n;
                sep = c;
            }
        }
        List<List<String>> linhas = new ArrayList<>();
        List<String> atual = new ArrayList<>();
        StringBuilder campo = new StringBuilder();
        boolean aspas = false;
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (aspas) {
                if (c == '"') {
                    if (i + 1 < texto.length() && texto.charAt(i + 1) == '"') {
                        campo.append('"');
                        i++;
                    } else {
                        aspas = false;
                    }
                } else {
                    campo.append(c);
                }
            } else if (c == '"' && campo.length() == 0) {
                aspas = true;
            } else if (c == sep) {
                atual.add(campo.toString());
                campo.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < texto.length() && texto.charAt(i + 1) == '\n') i++;
                atual.add(campo.toString());
                campo.setLength(0);
                linhas.add(atual);
                atual = new ArrayList<>();
            } else {
                campo.append(c);
            }
        }
        if (campo.length() > 0 || !atual.isEmpty()) {
            atual.add(campo.toString());
            linhas.add(atual);
        }
        return linhas;
    }

    // -- XLSX (Excel / Google Planilhas / LibreOffice) -----------------------------

    static List<List<String>> lerXlsx(byte[] dados) throws IOException {
        Map<String, byte[]> partes = new HashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(dados))) {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = zip.getNextEntry()) != null) {
                String n = e.getName();
                if (!(n.equals("xl/sharedStrings.xml") || n.equals("xl/workbook.xml")
                        || n.equals("xl/_rels/workbook.xml.rels") || n.startsWith("xl/worksheets/sheet"))) {
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int k;
                while ((k = zip.read(buf)) > 0) {
                    out.write(buf, 0, k);
                    total += k;
                    if (total > MAX_DESCOMPACTADO) throw new IOException("Planilha grande demais.");
                }
                partes.put(n, out.toByteArray());
            }
        }
        String planilha = primeiraPlanilha(partes);
        if (planilha == null || !partes.containsKey(planilha)) {
            throw new IOException("Não encontrei nenhuma aba de dados no arquivo .xlsx.");
        }
        List<String> textos = new ArrayList<>();
        try {
            if (partes.containsKey("xl/sharedStrings.xml")) {
                textos = lerTextosCompartilhados(partes.get("xl/sharedStrings.xml"));
            }
            return lerAba(partes.get(planilha), textos);
        } catch (Exception e) {
            throw new IOException("Não consegui ler a planilha .xlsx (" + e.getMessage() + ").");
        }
    }

    private static XmlPullParser parser(byte[] xml) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new ByteArrayInputStream(xml), "UTF-8");
        return p;
    }

    /** Arquivo da primeira aba, conforme workbook.xml; senao a de menor numero. */
    private static String primeiraPlanilha(Map<String, byte[]> partes) {
        try {
            String rid = null;
            XmlPullParser p = parser(partes.get("xl/workbook.xml"));
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT && rid == null; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG && p.getName().endsWith("sheet")
                        && !p.getName().endsWith("sheets")) {
                    rid = p.getAttributeValue(null, "r:id");
                }
            }
            p = parser(partes.get("xl/_rels/workbook.xml.rels"));
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG && p.getName().endsWith("Relationship")
                        && rid != null && rid.equals(p.getAttributeValue(null, "Id"))) {
                    String alvo = p.getAttributeValue(null, "Target");
                    if (alvo.startsWith("/")) return alvo.substring(1);
                    return "xl/" + alvo;
                }
            }
        } catch (Exception ignorado) {
        }
        String melhor = null;
        int menor = Integer.MAX_VALUE;
        for (String n : partes.keySet()) {
            Matcher m = Pattern.compile("xl/worksheets/sheet(\\d+)\\.xml").matcher(n);
            if (m.matches() && Integer.parseInt(m.group(1)) < menor) {
                menor = Integer.parseInt(m.group(1));
                melhor = n;
            }
        }
        return melhor;
    }

    private static List<String> lerTextosCompartilhados(byte[] xml) throws Exception {
        List<String> lista = new ArrayList<>();
        XmlPullParser p = parser(xml);
        StringBuilder atual = null;
        boolean emTexto = false, emFonetica = false;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            String n = ev == XmlPullParser.START_TAG || ev == XmlPullParser.END_TAG ? local(p.getName()) : "";
            if (ev == XmlPullParser.START_TAG) {
                if (n.equals("si")) atual = new StringBuilder();
                else if (n.equals("rPh")) emFonetica = true;
                else if (n.equals("t")) emTexto = true;
            } else if (ev == XmlPullParser.END_TAG) {
                if (n.equals("si") && atual != null) {
                    lista.add(atual.toString());
                    atual = null;
                } else if (n.equals("rPh")) emFonetica = false;
                else if (n.equals("t")) emTexto = false;
            } else if (ev == XmlPullParser.TEXT && emTexto && !emFonetica && atual != null) {
                atual.append(p.getText());
            }
        }
        return lista;
    }

    private static String local(String nome) {
        int i = nome.indexOf(':');
        return i >= 0 ? nome.substring(i + 1) : nome;
    }

    private static List<List<String>> lerAba(byte[] xml, List<String> textos) throws Exception {
        List<List<String>> linhas = new ArrayList<>();
        XmlPullParser p = parser(xml);
        TreeMap<Integer, String> linha = null;
        int col = 0, proxCol = 0;
        String tipo = null;
        StringBuilder valor = null;
        boolean emValor = false;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            String n = ev == XmlPullParser.START_TAG || ev == XmlPullParser.END_TAG ? local(p.getName()) : "";
            if (ev == XmlPullParser.START_TAG) {
                switch (n) {
                    case "row":
                        linha = new TreeMap<>();
                        proxCol = 0;
                        break;
                    case "c":
                        String ref = p.getAttributeValue(null, "r");
                        col = ref != null ? coluna(ref) : proxCol;
                        proxCol = col + 1;
                        tipo = p.getAttributeValue(null, "t");
                        valor = new StringBuilder();
                        break;
                    case "v":
                    case "t":
                        emValor = true;
                        break;
                }
            } else if (ev == XmlPullParser.TEXT && emValor && valor != null) {
                valor.append(p.getText());
            } else if (ev == XmlPullParser.END_TAG) {
                switch (n) {
                    case "v":
                    case "t":
                        emValor = false;
                        break;
                    case "c":
                        if (linha != null && valor != null) {
                            String v = valor.toString();
                            if ("s".equals(tipo)) {
                                try {
                                    int i = Integer.parseInt(v.trim());
                                    v = i >= 0 && i < textos.size() ? textos.get(i) : "";
                                } catch (NumberFormatException e) {
                                    v = "";
                                }
                            } else if ("b".equals(tipo)) {
                                v = "1".equals(v) ? "sim" : "não";
                            }
                            linha.put(col, v);
                        }
                        valor = null;
                        break;
                    case "row":
                        if (linha != null) {
                            List<String> l = new ArrayList<>();
                            int ultima = linha.isEmpty() ? -1 : linha.lastKey();
                            for (int i = 0; i <= ultima; i++) {
                                String v = linha.get(i);
                                l.add(v == null ? "" : v);
                            }
                            linhas.add(l);
                        }
                        linha = null;
                        break;
                }
            }
        }
        return linhas;
    }

    /** "C12" -> 2 */
    private static int coluna(String ref) {
        int c = 0;
        for (char x : ref.toCharArray()) {
            if (x >= 'A' && x <= 'Z') c = c * 26 + (x - 'A' + 1);
            else break;
        }
        return Math.max(0, c - 1);
    }

    // -- JSON ------------------------------------------------------------------

    private static void lerJson(String texto, Construtor b) throws IOException {
        Object raiz;
        try {
            raiz = new JSONTokener(texto.trim()).nextValue();
        } catch (JSONException e) {
            throw new IOException("Arquivo JSON inválido: " + e.getMessage());
        }
        JSONArray lista = null;
        if (raiz instanceof JSONArray) {
            lista = (JSONArray) raiz;
        } else if (raiz instanceof JSONObject) {
            JSONObject o = (JSONObject) raiz;
            lista = o.optJSONArray("fichas");
            if (lista == null) lista = o.optJSONArray("suspeitos");
            if (lista == null) lista = new JSONArray().put(o);
        }
        if (lista == null) throw new IOException("O JSON precisa ser uma lista de fichas.");
        for (int i = 0; i < lista.length(); i++) {
            JSONObject f = lista.optJSONObject(i);
            if (f == null) {
                b.l.aviso("JSON: item " + (i + 1) + " não é uma ficha e foi ignorado.");
                continue;
            }
            Map<String, String> reg = new HashMap<>();
            for (Iterator<String> it = f.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = f.opt(k);
                if (v instanceof JSONArray || v instanceof JSONObject || v == null || v == JSONObject.NULL) {
                    continue;
                }
                String campo = APELIDOS.get(chave(k));
                if (campo != null) reg.put(campo, String.valueOf(v));
            }
            Rascunho r = b.registro(reg, i + 1);
            if (r == null) continue;
            b.passagem(r, reg.get("reds"), reg.get("data"), reg.get("natureza"));
            b.veiculo(r, reg.get("marca"), reg.get("modelo"), reg.get("cor"), reg.get("placa"));
            JSONArray ps = f.optJSONArray("passagens");
            for (int j = 0; ps != null && j < ps.length(); j++) {
                JSONObject p = ps.optJSONObject(j);
                if (p == null) continue;
                b.passagem(r, p.optString("reds"), p.optString("data"),
                        p.has("descricao") ? p.optString("descricao") : p.optString("natureza"));
            }
            JSONArray vs = f.optJSONArray("veiculos");
            for (int j = 0; vs != null && j < vs.length(); j++) {
                JSONObject v = vs.optJSONObject(j);
                if (v == null) continue;
                b.veiculo(r, v.optString("marca"), v.optString("modelo"), v.optString("cor"),
                        v.optString("placa"));
            }
        }
    }

    // -- montagem das fichas -------------------------------------------------------

    /** Junta linhas/itens em fichas, sem repetir a mesma pessoa. */
    private static class Construtor {
        final Leitura l;
        final Map<String, Rascunho> porChave = new LinkedHashMap<>();
        Rascunho anterior;

        Construtor(Leitura l) {
            this.l = l;
        }

        void linhas(List<List<String>> linhas) throws IOException {
            int iCab = -1;
            String[] mapa = null;
            for (int i = 0; i < linhas.size() && iCab < 0; i++) {
                List<String> cab = linhas.get(i);
                String[] m = new String[cab.size()];
                int reconhecidas = 0;
                for (int c = 0; c < cab.size(); c++) {
                    m[c] = APELIDOS.get(chave(cab.get(c)));
                    if (m[c] != null) reconhecidas++;
                }
                if (reconhecidas >= 2 || (reconhecidas == 1 && cab.size() == 1)) {
                    iCab = i;
                    mapa = m;
                    for (int c = 0; c < cab.size(); c++) {
                        if (m[c] == null && !cab.get(c).trim().isEmpty()) {
                            l.aviso("Coluna \"" + cab.get(c).trim() + "\" não reconhecida: ignorada.");
                        }
                    }
                }
            }
            if (mapa == null) {
                throw new IOException("Não encontrei a linha de títulos na planilha. A primeira linha "
                        + "precisa ter os nomes das colunas, por exemplo: nome, alcunha, cpf, rg, "
                        + "endereco, pai, mae, comportamento, observacoes, reds, data, natureza, "
                        + "marca, modelo, cor, placa.");
            }
            for (int i = iCab + 1; i < linhas.size(); i++) {
                List<String> lin = linhas.get(i);
                Map<String, String> reg = new HashMap<>();
                for (int c = 0; c < lin.size() && c < mapa.length; c++) {
                    if (mapa[c] != null && !lin.get(c).trim().isEmpty()) reg.put(mapa[c], lin.get(c));
                }
                if (reg.isEmpty()) continue;
                Rascunho r = registro(reg, i + 1);
                if (r == null) continue;
                passagem(r, reg.get("reds"), reg.get("data"), reg.get("natureza"));
                veiculo(r, reg.get("marca"), reg.get("modelo"), reg.get("cor"), reg.get("placa"));
            }
        }

        /** Dados pessoais de uma linha; devolve a ficha (nova ou ja existente no arquivo). */
        Rascunho registro(Map<String, String> reg, int linha) {
            Map<String, String> campos = new HashMap<>();
            for (String c : CAMPOS) {
                String v = limpar(reg.get(c));
                if (v.isEmpty()) continue;
                if (c.equals("cpf")) v = formatarCpf(v);
                if (c.equals("comportamento")) {
                    String comp = comportamento(v);
                    if (comp == null) {
                        l.aviso("Linha " + linha + ": comportamento \"" + v + "\" desconhecido (use "
                                + "obediente, resistente passivo ou resistente ativo). Ficou em branco.");
                        continue;
                    }
                    v = comp;
                }
                campos.put(c, cortar(v, c, linha));
            }
            boolean temPessoa = campos.containsKey("nome") || campos.containsKey("alcunha");
            if (!temPessoa) {
                if (!campos.containsKey("cpf") && anterior != null) return anterior; // continuacao
                Rascunho r = campos.containsKey("cpf") ? porChave.get("c:" + digitos(campos.get("cpf"))) : null;
                if (r != null) return r;
                l.aviso("Linha " + linha + ": sem nome nem alcunha. Ignorada.");
                return null;
            }
            String k = chavePessoa(campos);
            Rascunho r = porChave.get(k);
            if (r == null) {
                if (porChave.size() >= MAX_FICHAS) {
                    l.aviso("Limite de " + MAX_FICHAS + " fichas por importação: o restante foi ignorado.");
                    return null;
                }
                r = new Rascunho();
                r.linha = linha;
                porChave.put(k, r);
            }
            for (Map.Entry<String, String> e : campos.entrySet()) {
                String atual = r.campos.get(e.getKey());
                if (atual == null || atual.isEmpty()) r.campos.put(e.getKey(), e.getValue());
            }
            anterior = r;
            return r;
        }

        void passagem(Rascunho r, String reds, String data, String natureza) {
            reds = cortar(limpar(reds), "reds", r.linha);
            natureza = cortar(limpar(natureza), "natureza", r.linha);
            data = formatarData(limpar(data));
            if (reds.isEmpty() && natureza.isEmpty()) return;
            for (JSONObject p : r.passagens) {
                boolean igual = !reds.isEmpty() ? reds.equalsIgnoreCase(p.optString("reds"))
                        : natureza.equalsIgnoreCase(p.optString("descricao"))
                        && data.equals(p.optString("data"));
                if (igual) return;
            }
            if (r.passagens.size() >= MAX_PASSAGENS) {
                l.aviso(r.rotulo() + ": mais de " + MAX_PASSAGENS + " passagens; o excesso foi ignorado.");
                return;
            }
            try {
                r.passagens.add(new JSONObject().put("reds", reds).put("data", data)
                        .put("descricao", natureza));
            } catch (JSONException ignorado) {
            }
        }

        void veiculo(Rascunho r, String marca, String modelo, String cor, String placa) {
            marca = cortar(limpar(marca), "marca", r.linha);
            modelo = cortar(limpar(modelo), "modelo", r.linha);
            cor = cortar(limpar(cor), "cor", r.linha);
            placa = cortar(Veiculos.normalizarPlaca(limpar(placa)), "placa", r.linha);
            if (marca.isEmpty() && modelo.isEmpty() && cor.isEmpty() && placa.isEmpty()) return;
            if (!placa.isEmpty() && !Veiculos.placaValida(placa)) {
                l.aviso(r.rotulo() + ": placa " + placa + " fora do padrão (importada assim mesmo).");
            }
            for (JSONObject v : r.veiculos) {
                boolean igual = !placa.isEmpty() ? placa.equals(v.optString("placa"))
                        : (marca + modelo + cor).equalsIgnoreCase(
                        v.optString("marca") + v.optString("modelo") + v.optString("cor"));
                if (igual) return;
            }
            if (r.veiculos.size() >= Veiculos.MAX) {
                l.aviso(r.rotulo() + ": mais de " + Veiculos.MAX + " veículos; o excesso foi ignorado.");
                return;
            }
            try {
                r.veiculos.add(new JSONObject().put("marca", marca).put("modelo", modelo)
                        .put("cor", cor).put("placa", placa));
            } catch (JSONException ignorado) {
            }
        }

        String cortar(String v, String campo, int linha) {
            Integer lim = LIMITES.get(campo);
            if (lim != null && v.length() > lim) {
                l.aviso("Linha " + linha + ": " + campo + " tinha mais de " + lim
                        + " caracteres e foi cortado.");
                return v.substring(0, lim);
            }
            return v;
        }

        void concluir() {
            l.fichas.addAll(porChave.values());
            for (Rascunho r : l.fichas) {
                l.qtdPassagens += r.passagens.size();
                l.qtdVeiculos += r.veiculos.size();
            }
        }
    }

    // -- normalizacao ------------------------------------------------------------

    static String valor(String s) {
        return s == null ? "" : s;
    }

    /** Sem acentos, minusculas, so letras e numeros: "Nome da Mãe" -> "nomedamae" */
    static String chave(String s) {
        return semAcento(valor(s)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static String semAcento(String s) {
        return Normalizer.normalize(valor(s), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** Mesma pessoa: mesmo CPF; sem CPF, mesmo nome e mesma mae. */
    static String chavePessoa(Map<String, String> campos) {
        String cpf = digitos(campos.get("cpf"));
        if (cpf.length() == 11) return "c:" + cpf;
        String nome = valor(campos.get("nome"));
        if (nome.isEmpty()) return "a:" + chave(campos.get("alcunha"));
        return "n:" + chave(nome) + "|" + chave(campos.get("mae"));
    }

    static String digitos(String s) {
        return valor(s).replaceAll("\\D", "");
    }

    private static String limpar(String s) {
        if (s == null) return "";
        // Mantem quebras de linha (historico em varias linhas), junta espacos repetidos.
        return s.replace(' ', ' ').replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t]+", " ").replaceAll(" *\n *", "\n").trim();
    }

    /** CPF com pontos e traco; repoe zeros a esquerda perdidos pela planilha. */
    static String formatarCpf(String v) {
        String s = v.trim();
        if (s.matches("[0-9.]+[eE][+]?[0-9]+")) {
            try {
                s = new BigDecimal(s).toBigInteger().toString();
            } catch (NumberFormatException ignorado) {
            }
        }
        if (s.matches("[0-9]+\\.0+")) s = s.replaceAll("\\.0+$", "");
        String d = s.replaceAll("\\D", "");
        if (s.matches("[0-9]+") && d.length() >= 8 && d.length() < 11) {
            while (d.length() < 11) d = "0" + d;
        }
        if (d.length() != 11) return v.trim();
        return d.substring(0, 3) + "." + d.substring(3, 6) + "." + d.substring(6, 9) + "-" + d.substring(9);
    }

    static String comportamento(String v) {
        String k = chave(v);
        if (k.isEmpty()) return "";
        if (k.contains("ativo") || k.equals("ra")) return "RESISTENTE_ATIVO";
        if (k.contains("passivo") || k.equals("rp")) return "RESISTENTE_PASSIVO";
        if (k.startsWith("obedien") || k.equals("o")) return "OBEDIENTE";
        return null;
    }

    /** dd/mm/aaaa a partir de dd/mm/aa, aaaa-mm-dd ou data numerica do Excel. */
    static String formatarData(String v) {
        if (v.isEmpty()) return "";
        Matcher m = Pattern.compile("^(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2,4})").matcher(v);
        if (m.find()) {
            String a = m.group(3);
            if (a.length() == 2) a = (Integer.parseInt(a) > 50 ? "19" : "20") + a;
            return dois(m.group(1)) + "/" + dois(m.group(2)) + "/" + a;
        }
        m = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(v);
        if (m.find()) return dois(m.group(3)) + "/" + dois(m.group(2)) + "/" + m.group(1);
        if (v.matches("\\d{4,5}(\\.\\d+)?")) {
            double serial = Double.parseDouble(v);
            if (serial > 1 && serial < 80000) {
                Calendar c = Calendar.getInstance();
                c.clear();
                c.set(1899, Calendar.DECEMBER, 30);
                c.add(Calendar.DAY_OF_MONTH, (int) serial);
                return String.format(Locale.ROOT, "%02d/%02d/%04d", c.get(Calendar.DAY_OF_MONTH),
                        c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR));
            }
        }
        return v.length() > 10 ? v.substring(0, 10) : v;
    }

    private static String dois(String s) {
        return s.length() == 1 ? "0" + s : s;
    }

    // -- envio ao servidor -------------------------------------------------------

    interface Progresso {
        void passo(int feitos, int total);
    }

    static class Relatorio {
        int criadas, atualizadas, iguais;
        final List<String> erros = new ArrayList<>();
        String interrompido;
    }

    /**
     * Envia as fichas. Pessoa que ja existe no servidor (mesmo CPF, ou mesmo nome e
     * mae) nao e duplicada: o arquivo so completa campos vazios e acrescenta
     * passagens e veiculos novos. Nada que ja estava gravado e apagado.
     */
    static Relatorio enviar(List<Rascunho> fichas, Progresso progresso) throws IOException {
        Relatorio rel = new Relatorio();
        Map<String, String> porCpf = new HashMap<>();
        Map<String, List<String>> porNome = new HashMap<>();
        JSONArray existentes = Api.listar("");
        for (int i = 0; existentes != null && i < existentes.length(); i++) {
            JSONObject e = existentes.optJSONObject(i);
            String cpf = digitos(e.optString("cpf"));
            if (cpf.length() == 11) porCpf.put(cpf, e.optString("id"));
            String n = chave(e.optString("nome"));
            if (!n.isEmpty()) {
                List<String> ids = porNome.get(n);
                if (ids == null) porNome.put(n, ids = new ArrayList<>());
                ids.add(e.optString("id"));
            }
        }
        int feitos = 0;
        for (Rascunho r : fichas) {
            progresso.passo(feitos++, fichas.size());
            try {
                JSONObject existente = procurar(r, porCpf, porNome);
                if (existente == null) {
                    JSONObject nova = Api.salvar(null, r.paraJson());
                    rel.criadas++;
                    String cpf = digitos(r.campos.get("cpf"));
                    if (cpf.length() == 11) porCpf.put(cpf, nova.optString("id"));
                } else if (mesclar(existente, r)) {
                    Api.salvar(existente.getString("id"), corpo(existente));
                    rel.atualizadas++;
                } else {
                    rel.iguais++;
                }
            } catch (Api.ErroApi e) {
                if (e.status == 401 || e.status == 403) throw e;
                rel.erros.add(r.rotulo() + ": " + e.getMessage());
            } catch (JSONException e) {
                rel.erros.add(r.rotulo() + ": " + e.getMessage());
            } catch (IOException e) {
                rel.interrompido = e.getMessage();
                break;
            }
        }
        progresso.passo(feitos, fichas.size());
        return rel;
    }

    private static JSONObject procurar(Rascunho r, Map<String, String> porCpf,
                                       Map<String, List<String>> porNome) throws IOException {
        String cpf = digitos(r.campos.get("cpf"));
        if (cpf.length() == 11) {
            String id = porCpf.get(cpf);
            return id == null ? null : Api.obter(id);
        }
        String nome = chave(r.campos.get("nome"));
        List<String> ids = nome.isEmpty() ? null : porNome.get(nome);
        if (ids == null) return null;
        for (String id : ids) {
            JSONObject f = Api.obter(id);
            if (chave(f.optString("mae")).equals(chave(r.campos.get("mae")))) return f;
        }
        return null;
    }

    /** Completa a ficha existente; devolve true se mudou algo. */
    private static boolean mesclar(JSONObject f, Rascunho r) throws JSONException {
        boolean mudou = false;
        for (String c : CAMPOS) {
            String novo = valor(r.campos.get(c));
            if (!novo.isEmpty() && f.optString(c).isEmpty()) {
                f.put(c, novo);
                mudou = true;
            }
        }
        JSONArray ps = f.optJSONArray("passagens");
        if (ps == null) f.put("passagens", ps = new JSONArray());
        for (JSONObject p : r.passagens) {
            boolean existe = false;
            for (int i = 0; i < ps.length() && !existe; i++) {
                JSONObject q = ps.getJSONObject(i);
                existe = !p.optString("reds").isEmpty()
                        ? p.optString("reds").equalsIgnoreCase(q.optString("reds"))
                        : p.optString("descricao").equalsIgnoreCase(q.optString("descricao"));
            }
            if (!existe && ps.length() < MAX_PASSAGENS) {
                ps.put(p);
                mudou = true;
            }
        }
        JSONArray vs = f.optJSONArray("veiculos");
        if (vs == null) f.put("veiculos", vs = new JSONArray());
        for (JSONObject v : r.veiculos) {
            boolean existe = false;
            for (int i = 0; i < vs.length() && !existe; i++) {
                JSONObject w = vs.getJSONObject(i);
                existe = !v.optString("placa").isEmpty()
                        ? v.optString("placa").equals(Veiculos.normalizarPlaca(w.optString("placa")))
                        : (v.optString("marca") + v.optString("modelo")).equalsIgnoreCase(
                        w.optString("marca") + w.optString("modelo"));
            }
            if (!existe && vs.length() < Veiculos.MAX) {
                vs.put(v);
                mudou = true;
            }
        }
        return mudou;
    }

    private static JSONObject corpo(JSONObject f) throws JSONException {
        JSONObject o = new JSONObject();
        for (String c : CAMPOS) o.put(c, f.optString(c));
        o.put("passagens", f.optJSONArray("passagens"));
        o.put("veiculos", f.optJSONArray("veiculos"));
        return o;
    }
}
