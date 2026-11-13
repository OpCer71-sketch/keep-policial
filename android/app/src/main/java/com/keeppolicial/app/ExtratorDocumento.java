package com.keeppolicial.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrai CPF, RG, CNH (numero, categoria, validade), nome, filiacao e placa do TEXTO
 * reconhecido por OCR numa foto de documento ou de placa. E logica pura (sem Android),
 * independente do motor de OCR: o que sai daqui e sempre uma SUGESTAO que o policial
 * confere antes de preencher a ficha.
 */
final class ExtratorDocumento {
    /** Campos encontrados (vazio = nao encontrado). */
    static final class Resultado {
        String cpf = "", rg = "", cnhNumero = "", cnhCategoria = "", cnhValidade = "";
        String nome = "", pai = "", mae = "", placa = "";

        boolean vazio() {
            return cpf.isEmpty() && rg.isEmpty() && cnhNumero.isEmpty() && cnhCategoria.isEmpty()
                    && cnhValidade.isEmpty() && nome.isEmpty() && pai.isEmpty() && mae.isEmpty()
                    && placa.isEmpty();
        }
    }

    private static final Pattern CPF = Pattern.compile("(?<!\\d)(\\d{3})[.\\s]?(\\d{3})[.\\s]?(\\d{3})[-\\s]?(\\d{2})(?!\\d)");
    private static final Pattern DATA = Pattern.compile("(?<!\\d)(\\d{2})[/.\\-](\\d{2})[/.\\-](\\d{4})(?!\\d)");
    private static final Pattern ONZE = Pattern.compile("(?<!\\d)\\d{11}(?!\\d)");
    private static final Pattern CATEGORIA = Pattern.compile(
            "CAT\\.?\\s*HAB\\.?\\s*[:\\-]?\\s*(AB|AC|AD|AE|A|B|C|D|E)(?![A-Z])");
    private static final Pattern RG = Pattern.compile(
            "(?:REGISTRO\\s+GERAL|\\bRG\\b|\\bR\\.G\\.)\\s*[:\\-]?\\s*([0-9A-Z][0-9A-Z.\\-/ ]{4,18})");

    private ExtratorDocumento() {
    }

    /** Remove acentos e passa para maiusculas: o OCR erra acentos com frequencia. */
    private static String limpo(String t) {
        String n = java.text.Normalizer.normalize(t == null ? "" : t, java.text.Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "").toUpperCase(Locale.ROOT);
    }

    static Resultado extrair(String textoBruto) {
        Resultado r = new Resultado();
        String texto = limpo(textoBruto);
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

        // validade: a data que vem depois da palavra VALIDADE
        int iv = texto.indexOf("VALIDADE");
        if (iv >= 0) {
            m = DATA.matcher(texto.substring(iv));
            if (m.find()) {
                String iso = m.group(3) + "-" + m.group(2) + "-" + m.group(1);
                if (!Cnh.paraIso(m.group(1) + "/" + m.group(2) + "/" + m.group(3)).isEmpty()) {
                    r.cnhValidade = iso;
                }
            }
        }

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

        // nome e filiacao: linhas apos os rotulos
        for (int i = 0; i < linhas.size(); i++) {
            String l = linhas.get(i);
            if (r.nome.isEmpty() && l.matches(".*\\bNOME(\\s+E\\s+SOBRENOME|\\s+SOCIAL)?\\b.*") && !l.contains("FILIACAO")) {
                String depois = l.replaceFirst(".*\\bNOME(\\s+E\\s+SOBRENOME|\\s+SOCIAL)?\\b[:\\s]*", "").trim();
                if (pareceNome(depois)) r.nome = depois;
                else if (i + 1 < linhas.size() && pareceNome(linhas.get(i + 1))) r.nome = linhas.get(i + 1);
            }
            if (l.contains("FILIACAO")) {
                String a = l.replaceFirst(".*FILIACAO[:\\s]*", "").trim();
                List<String> nomes = new ArrayList<>();
                if (pareceNome(a)) nomes.add(a);
                for (int j = i + 1; j < linhas.size() && nomes.size() < 2; j++) {
                    if (pareceNome(linhas.get(j))) nomes.add(linhas.get(j));
                    else break;
                }
                if (nomes.size() >= 1) r.pai = nomes.get(0);
                if (nomes.size() >= 2) r.mae = nomes.get(1);
            }
        }
        r.placa = extrairPlaca(texto);
        return r;
    }

    /** So letras e espacos, 2 a 6 palavras, sem rotulos de documento. */
    private static boolean pareceNome(String t) {
        if (t == null) return false;
        String s = t.trim();
        if (!s.matches("[A-Z ]{5,80}")) return false;
        int palavras = s.split("\\s+").length;
        if (palavras < 2 || palavras > 8) return false;
        return !s.matches(".*\\b(REGISTRO|VALIDADE|CATEGORIA|DOCUMENTO|IDENTIDADE|NACIONAL|HABILITACAO|"
                + "REPUBLICA|FEDERATIVA|BRASIL|CARTEIRA|DATA|NASCIMENTO|NATURALIDADE|ASSINATURA)\\b.*");
    }

    /**
     * Placa brasileira: antiga (ABC1234) ou Mercosul (ABC1D23). Corrige as trocas
     * comuns do OCR (0/O, 1/I, 5/S, 8/B) conforme a posicao esperada.
     */
    static String extrairPlaca(String textoBruto) {
        String texto = limpo(textoBruto).replaceAll("[^A-Z0-9\\n ]", " ");
        for (String token : texto.split("[\\s\\n]+")) {
            String candidato = corrigirPlaca(token);
            if (candidato != null) return candidato;
        }
        // placa com espaco no meio ("ABC 1D23")
        Matcher m = Pattern.compile("([A-Z0-9]{3})\\s?([A-Z0-9]{4})").matcher(texto);
        while (m.find()) {
            String c = corrigirPlaca(m.group(1) + m.group(2));
            if (c != null) return c;
        }
        return "";
    }

    private static char paraLetra(char c) {
        switch (c) {
            case '0': return 'O';
            case '1': return 'I';
            case '5': return 'S';
            case '8': return 'B';
            case '2': return 'Z';
            case '6': return 'G';
            default: return c;
        }
    }

    private static char paraDigito(char c) {
        switch (c) {
            case 'O': case 'Q': case 'D': return '0';
            case 'I': case 'L': return '1';
            case 'S': return '5';
            case 'B': return '8';
            case 'Z': return '2';
            case 'G': return '6';
            default: return c;
        }
    }

    private static String corrigirPlaca(String t) {
        if (t == null || t.length() != 7) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            char c = paraLetra(t.charAt(i));
            if (c < 'A' || c > 'Z') return null;
            sb.append(c);
        }
        char c3 = paraDigito(t.charAt(3));
        if (c3 < '0' || c3 > '9') return null;
        sb.append(c3);
        char c4 = t.charAt(4);
        char c4d = paraDigito(c4);
        // 5o caractere: digito (placa antiga) ou letra (Mercosul). Se o OCR leu uma letra,
        // ela e mantida (placa Mercosul); so se corrige letra->digito nas posicoes sem ambiguidade.
        if (c4 >= '0' && c4 <= '9') sb.append(c4);
        else if (c4 >= 'A' && c4 <= 'Z') sb.append(c4);
        else if (c4d >= '0' && c4d <= '9') sb.append(c4d);
        else return null;
        for (int i = 5; i < 7; i++) {
            char c = paraDigito(t.charAt(i));
            if (c < '0' || c > '9') return null;
            sb.append(c);
        }
        return sb.toString();
    }
}
