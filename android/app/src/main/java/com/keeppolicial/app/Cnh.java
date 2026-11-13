package com.keeppolicial.app;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Regras da CNH: validacao do numero, datas e situacao da validade. */
final class Cnh {
    static final String[] CATEGORIAS = {"A", "B", "C", "D", "E", "AB", "AC", "AD", "AE"};

    private Cnh() {
    }

    /** Confere os 2 digitos verificadores do registro da CNH (11 digitos). */
    static boolean numeroValido(String numero) {
        String d = numero == null ? "" : numero.replaceAll("\\D", "");
        if (d.length() != 11 || d.chars().distinct().count() == 1) return false;
        int v = 0;
        for (int i = 0, j = 9; i < 9; i++, j--) v += (d.charAt(i) - '0') * j;
        int dsc = 0;
        int d1 = v % 11;
        if (d1 >= 10) {
            d1 = 0;
            dsc = 2;
        }
        v = 0;
        for (int i = 0, j = 1; i < 9; i++, j++) v += (d.charAt(i) - '0') * j;
        int x = v % 11;
        int d2 = x >= 10 ? 0 : x - dsc;
        if (d2 < 0) d2 += 11;
        if (d2 >= 10) d2 = 0;
        return d.charAt(9) - '0' == d1 && d.charAt(10) - '0' == d2;
    }

    /** "2026-12-31" -> "31/12/2026" (devolve o proprio texto se nao for ISO). */
    static String paraTela(String iso) {
        if (iso == null) return "";
        if (iso.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return iso.substring(8, 10) + "/" + iso.substring(5, 7) + "/" + iso.substring(0, 4);
        }
        return iso;
    }

    /** "31/12/2026" -> "2026-12-31"; vazio se invalida. */
    static String paraIso(String br) {
        Date d = analisar(br);
        return d == null ? "" : new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d);
    }

    /** Data dd/MM/aaaa valida (sem aceitar 31/02) ou null. */
    static Date analisar(String br) {
        if (br == null || !br.matches("\\d{2}/\\d{2}/\\d{4}")) return null;
        try {
            SimpleDateFormat f = new SimpleDateFormat("dd/MM/yyyy", Locale.US);
            f.setLenient(false);
            return f.parse(br);
        } catch (ParseException e) {
            return null;
        }
    }

    /**
     * Dias ate o vencimento: negativo = vencida, 0 = vence hoje.
     * Long.MIN_VALUE quando nao ha data valida.
     */
    static long diasParaVencer(String iso) {
        if (iso == null || !iso.matches("\\d{4}-\\d{2}-\\d{2}")) return Long.MIN_VALUE;
        try {
            Date v = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso);
            Date hoje = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(
                    new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
            return TimeUnit.MILLISECONDS.toDays(v.getTime() - hoje.getTime());
        } catch (ParseException e) {
            return Long.MIN_VALUE;
        }
    }

    static boolean vencida(String iso) {
        long d = diasParaVencer(iso);
        return d != Long.MIN_VALUE && d < 0;
    }
}
