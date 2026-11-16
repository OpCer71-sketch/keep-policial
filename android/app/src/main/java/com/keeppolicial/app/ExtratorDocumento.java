package com.keeppolicial.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrai CPF, RG, CNH (numero, categoria, validade), nome, data de nascimento e filiacao do
 * TEXTO reconhecido por OCR numa foto de documento. E logica pura (sem Android),
 * independente do motor de OCR: o que sai daqui e sempre uma SUGESTAO que o policial
 * confere antes de preencher a ficha.
 */
final class ExtratorDocumento {
    /** Campos encontrados (vazio = nao encontrado). Datas em AAAA-MM-DD. */
    static final class Resultado {
        String cpf = "", rg = "", cnhNumero = "", cnhCategoria = "", cnhValidade = "";
        String nome = "", nascimento = "", pai = "", mae = "";

        boolean vazio() {
            return cpf.isEmpty() && rg.isEmpty() && cnhNumero.isEmpty() && cnhCategoria.isEmpty()
                    && cnhValidade.isEmpty() && nome.isEmpty() && nascimento.isEmpty()
                    && pai.isEmpty() && mae.isEmpty();
        }
    }

    /** Uma data valida encontrada no texto. */
    private static final class Data {
        final String iso;
        final int inicio, fim;

        Data(String iso, int inicio, int fim) {
            this.iso = iso;
            this.inicio = inicio;
            this.fim = fim;
        }

        int ano() {
            return Integer.parseInt(iso.substring(0, 4));
        }
    }

    private static final Pattern CPF = Pattern.compile("(?<!\\d)(\\d{3})[.\\s]?(\\d{3})[.\\s]?(\\d{3})[-\\s]?(\\d{2})(?!\\d)");
    private static final Pattern DATA = Pattern.compile("(?<!\\d)(\\d{2})[/.\\-](\\d{2})[/.\\-](\\d{4})(?!\\d)");
    /** Data com cara de data, mas com letras no lugar de digitos (erro comum do OCR: 2O35, l5/12). */
    private static final Pattern DATA_SUJA = Pattern.compile(
            "(?<![0-9A-Z])([0-9OILS]{2})([/.\\-])([0-9OILS]{2})([/.\\-])([0-9OILS]{4})(?![0-9A-Z])");
    /** Data seguida de cidade/UF ("03/04/1990 CAMPINAS/SP"): na CNH e o campo de nascimento. */
    private static final Pattern DATA_LOCAL_UF = Pattern.compile(
            "(?<!\\d)(\\d{2}/\\d{2}/\\d{4})[ ,\\-]+[A-Z][A-Z .'\\-]{1,40}[/,\\-] ?[A-Z]{2}(?![A-Z])");
    private static final Pattern ONZE = Pattern.compile("(?<!\\d)\\d{11}(?!\\d)");
    private static final Pattern CATEGORIA = Pattern.compile(
            "CAT\\.?\\s*HAB\\.?\\s*[:\\-]?\\s*(AB|AC|AD|AE|A|B|C|D|E)(?![A-Z])");
    private static final Pattern RG = Pattern.compile(
            "(?:REGISTRO\\s+GERAL|\\bRG\\b|\\bR\\.G\\.)\\s*[:\\-]?\\s*([0-9A-Z][0-9A-Z.\\-/ ]{4,18})");
    /** Rotulo da filiacao, tolerando as trocas do OCR (I/L/1, C/G). */
    private static final Pattern FILIACAO = Pattern.compile("F[I1L]L[I1L]A[CG]A[O0]");
    /** Rotulos que encerram o bloco da filiacao. */
    private static final Pattern FIM_FILIACAO = Pattern.compile(
            ".*\\b(ASSINATURA|PORTADOR|OBSERVAC|LOCAL|EMISSAO|DETRAN|VALIDA|NACIONALIDADE|CPF|REGISTRO)\\b.*");
    /** Diferenca minima (anos) entre o nascimento e a proxima data de uma CNH (1a habilitacao aos 18). */
    private static final int ANOS_MIN_HABILITACAO = 16;

    private ExtratorDocumento() {
    }

    /** Remove acentos e passa para maiusculas: o OCR erra acentos com frequencia. */
    private static String limpo(String t) {
        String n = java.text.Normalizer.normalize(t == null ? "" : t, java.text.Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "").toUpperCase(Locale.ROOT);
    }

    /** Corrige letras lidas no lugar de digitos DENTRO de datas (O->0, I/L->1, S->5). */
    private static String corrigirDatas(String texto) {
        Matcher m = DATA_SUJA.matcher(texto);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String d = m.group(1) + m.group(2) + m.group(3) + m.group(4) + m.group(5);
            String c = d.replace('O', '0').replace('I', '1').replace('L', '1').replace('S', '5');
            m.appendReplacement(sb, Matcher.quoteReplacement(c));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static Resultado extrair(String textoBruto) {
        Resultado r = new Resultado();
        String texto = corrigirDatas(limpo(textoBruto));
        List<String> linhas = new ArrayList<>();
        for (String l : texto.split("\\r?\\n")) {
            if (!l.trim().isEmpty()) linhas.add(l.trim());
        }

        // CPF: com pontuacao e digitos verificadores validos
        Matcher m = CPF.matcher(texto);
        while (m.find()) {
            String d = m.group(1) + m.group(2) + m.group(3) + m.group(4);
            if (FichaActivity.cpfValido(d)) {
                r.cpf = m.group(1) + "." + m.group(2) + "." + m.group(3) + "-" + m.group(4);
                break;
            }
        }
        String cpfDigitos = r.cpf.replaceAll("\\D", "");

        // CNH: 11 digitos seguidos com digitos verificadores validos (e que nao sejam o CPF)
        m = ONZE.matcher(texto.replaceAll("(?<=\\d)[ ](?=\\d)", ""));
        while (m.find()) {
            String d = m.group();
            if (!d.equals(cpfDigitos) && Cnh.numeroValido(d)) {
                r.cnhNumero = d;
                break;
            }
        }
        m = CATEGORIA.matcher(texto);
        if (m.find()) r.cnhCategoria = m.group(1);

        extrairDatas(texto, r);

        // RG
        m = RG.matcher(texto);
        if (m.find()) {
            String rg = m.group(1).trim().replaceAll("\\s{2,}.*$", "");
            // para antes de outro rotulo (ex.: "... SSP CPF ...")
            rg = rg.split("\\s(?:CPF|DATA|NOME|FILIACAO|NATURALIDADE)\\b")[0].trim();
            if (rg.replaceAll("[^0-9]", "").length() >= 5) r.rg = rg.replaceAll("[^0-9A-Z.\\-/]", "");
        }

        // RG no formato com UF (ex.: MG-12.345.678), comum nas carteiras de identidade
        if (r.rg.isEmpty()) {
            m = Pattern.compile("(?<![A-Z])([A-Z]{2})[-\\s]?(\\d{1,2}\\.?\\d{3}\\.?\\d{3})(?!\\d)").matcher(texto);
            if (m.find() && !m.group(1).equals("CP")) r.rg = m.group(1) + "-" + m.group(2);
        }

        // nome: linha apos o rotulo
        for (int i = 0; i < linhas.size() && r.nome.isEmpty(); i++) {
            String l = linhas.get(i);
            if (l.matches(".*\\bNOME(\\s+E\\s+SOBRENOME|\\s+SOCIAL)?\\b.*") && !FILIACAO.matcher(l).find()) {
                String depois = nomeLimpo(l.replaceFirst(".*\\bNOME(\\s+E\\s+SOBRENOME|\\s+SOCIAL)?\\b[:\\s]*", ""));
                if (pareceNome(depois)) r.nome = depois;
                else if (i + 1 < linhas.size() && pareceNome(nomeLimpo(linhas.get(i + 1)))) {
                    r.nome = nomeLimpo(linhas.get(i + 1));
                }
            }
        }
        extrairFiliacao(linhas, r);
        return r;
    }

    /**
     * Nascimento e validade pelo SIGNIFICADO das datas, e nao pela ordem em que o OCR as
     * devolve: o ML Kit agrupa o texto em blocos e a ordem dos blocos varia com o
     * enquadramento, entao "a data depois da palavra VALIDADE" muitas vezes era a do
     * nascimento. Numa CNH:
     * <ul>
     *   <li>nascimento = a data seguida de cidade/UF ("03/04/1990 CAMPINAS/SP") ou, na falta,
     *       a mais antiga, desde que pelo menos 16 anos antes de todas as outras;</li>
     *   <li>validade = a data mais recente (sempre posterior a emissao e a 1a habilitacao),
     *       nunca a de nascimento.</li>
     * </ul>
     */
    private static void extrairDatas(String texto, Resultado r) {
        List<Data> datas = new ArrayList<>();
        Matcher m = DATA.matcher(texto);
        while (m.find()) {
            String iso = Cnh.paraIso(m.group(1) + "/" + m.group(2) + "/" + m.group(3));
            if (!iso.isEmpty()) datas.add(new Data(iso, m.start(), m.end()));
        }
        if (datas.isEmpty()) return;

        // 1) nascimento: data seguida de local/UF
        m = DATA_LOCAL_UF.matcher(texto);
        if (m.find()) {
            String iso = Cnh.paraIso(m.group(1));
            if (!iso.isEmpty()) r.nascimento = iso;
        }
        // 2) nascimento: a mais antiga, bem distante das demais
        if (r.nascimento.isEmpty() && datas.size() >= 2) {
            Data maisAntiga = null;
            for (Data d : datas) if (maisAntiga == null || d.iso.compareTo(maisAntiga.iso) < 0) maisAntiga = d;
            boolean distante = true;
            for (Data d : datas) {
                if (!d.iso.equals(maisAntiga.iso) && d.ano() - maisAntiga.ano() < ANOS_MIN_HABILITACAO) {
                    distante = false;
                    break;
                }
            }
            if (distante) r.nascimento = maisAntiga.iso;
        }
        // 3) nascimento: data logo apos o rotulo NASCIMENTO (documento com uma data so)
        if (r.nascimento.isEmpty()) {
            Data d = dataAposRotulo(texto, datas, "NASCIMENTO");
            if (d != null) r.nascimento = d.iso;
        }

        // validade so faz sentido em CNH (a CIN tambem tem "VALIDADE" e nao e CNH)
        boolean ehCnh = texto.contains("HABILITACAO") || texto.contains("DRIVER LICENSE")
                || !r.cnhNumero.isEmpty() || !r.cnhCategoria.isEmpty();
        if (!ehCnh) return;
        List<Data> candidatas = new ArrayList<>();
        for (Data d : datas) if (!d.iso.equals(r.nascimento)) candidatas.add(d);
        String validade = "";
        if (candidatas.size() >= 2) {
            for (Data d : candidatas) if (d.iso.compareTo(validade) > 0) validade = d.iso;
        } else {
            // uma data so: aceita apenas se estiver colada ao rotulo VALIDADE
            Data d = dataAposRotulo(texto, candidatas, "VALIDADE");
            if (d != null) validade = d.iso;
        }
        // a validade nunca e anterior aos 18 anos do portador
        if (!validade.isEmpty() && !r.nascimento.isEmpty()
                && Integer.parseInt(validade.substring(0, 4)) - Integer.parseInt(r.nascimento.substring(0, 4)) < 18) {
            validade = "";
        }
        r.cnhValidade = validade;
    }

    /** Primeira data ate 40 caracteres depois do rotulo, sem outra data no caminho. */
    private static Data dataAposRotulo(String texto, List<Data> datas, String rotulo) {
        int i = texto.indexOf(rotulo);
        while (i >= 0) {
            int fimRotulo = i + rotulo.length();
            for (Data d : datas) {
                if (d.inicio >= fimRotulo && d.inicio - fimRotulo <= 40) return d;
            }
            i = texto.indexOf(rotulo, fimRotulo);
        }
        return null;
    }

    /**
     * Filiacao: os 2 primeiros nomes depois do rotulo FILIACAO (pai, depois mae, como na CNH
     * e no RG). Pula linhas de ruido (marca d'agua, tracos, sujeira) entre os dois nomes e para
     * em outro rotulo do documento.
     */
    private static void extrairFiliacao(List<String> linhas, Resultado r) {
        for (int i = 0; i < linhas.size(); i++) {
            Matcher m = FILIACAO.matcher(linhas.get(i));
            if (!m.find()) continue;
            List<String> nomes = new ArrayList<>();
            String resto = nomeLimpo(linhas.get(i).substring(m.end()));
            if (pareceNome(resto)) nomes.add(resto);
            for (int j = i + 1; j < linhas.size() && j <= i + 8 && nomes.size() < 2; j++) {
                String l = linhas.get(j);
                if (FIM_FILIACAO.matcher(l).matches()) break;
                String n = nomeLimpo(l);
                if (pareceNome(n) && !n.equals(r.nome)) nomes.add(n);
            }
            if (!nomes.isEmpty()) {
                r.pai = nomes.get(0);
                if (nomes.size() >= 2) r.mae = nomes.get(1);
                return;
            }
        }
    }

    /** Tira pontuacao solta que o OCR gruda nas pontas do nome ("JOSE DA SILVA." / "- MARIA"). */
    private static String nomeLimpo(String t) {
        return t == null ? "" : t.replaceAll("^[^A-Z]+|[^A-Z]+$", "").replaceAll("\\s{2,}", " ").trim();
    }

    /** So letras, espacos e apostrofo, 2 a 8 palavras, sem rotulos de documento. */
    private static boolean pareceNome(String t) {
        if (t == null) return false;
        String s = t.trim();
        if (!s.matches("[A-Z][A-Z ']{4,79}")) return false;
        int palavras = s.split("\\s+").length;
        if (palavras < 2 || palavras > 8) return false;
        return !s.matches(".*\\b(REGISTRO|VALIDADE|CATEGORIA|DOCUMENTO|IDENTIDADE|NACIONAL|HABILITACAO|"
                + "REPUBLICA|FEDERATIVA|BRASIL|CARTEIRA|DATA|NASCIMENTO|NATURALIDADE|ASSINATURA|"
                + "NACIONALIDADE|BRASILEIRO|BRASILEIRA|PORTADOR|FILIACAO|MINISTERIO|SECRETARIA|"
                + "TRANSITO|TRANSPORTES|DETRAN|EMISSAO|PERMISO|LICENSE)\\b.*");
    }
}
