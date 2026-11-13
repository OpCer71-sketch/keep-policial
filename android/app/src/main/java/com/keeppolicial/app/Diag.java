package com.keeppolicial.app;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Diagnostico: escreve no Logcat (tag KeepPolicial) e guarda as ultimas linhas em memoria
 * para a tela "Diagnostico" do app e para os relatorios de falha.
 *
 * <p>REGRA: nunca registrar dado de pessoa (nome, CPF, RG, CNH, placa, endereco, senha,
 * token). So componente, evento, contagens, codigos e tipos de excecao.</p>
 *
 * <p>No computador, com o celular no cabo USB:
 * {@code adb logcat -s KeepPolicial:V}</p>
 */
final class Diag {
    static final String TAG = "KeepPolicial";
    private static final int MAX_LINHAS = 150;
    private static final ArrayDeque<String> LINHAS = new ArrayDeque<>();

    private Diag() {
    }

    static void d(String comp, String msg) {
        registrar(Log.DEBUG, "D", comp, msg, null);
    }

    static void i(String comp, String msg) {
        registrar(Log.INFO, "I", comp, msg, null);
    }

    static void w(String comp, String msg, Throwable t) {
        registrar(Log.WARN, "W", comp, msg, t);
    }

    static void e(String comp, String msg, Throwable t) {
        registrar(Log.ERROR, "E", comp, msg, t);
    }

    private static void registrar(int nivel, String letra, String comp, String msg, Throwable t) {
        String texto = "[" + comp + "] " + msg + (t != null ? " | " + t.getClass().getSimpleName() : "");
        try {
            if (t != null && nivel >= Log.WARN) Log.println(nivel, TAG, texto + "\n" + Log.getStackTraceString(t));
            else Log.println(nivel, TAG, texto);
        } catch (RuntimeException ignorado) {
            // ambiente de teste sem Logcat
        }
        String hora = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (LINHAS) {
            if (LINHAS.size() >= MAX_LINHAS) LINHAS.removeFirst();
            LINHAS.addLast(hora + " " + letra + " " + texto);
        }
    }

    /** As ultimas {@code max} linhas (mais antigas primeiro). */
    static String ultimas(int max) {
        synchronized (LINHAS) {
            StringBuilder sb = new StringBuilder();
            int pular = Math.max(0, LINHAS.size() - max);
            int i = 0;
            for (String l : LINHAS) {
                if (i++ >= pular) sb.append(l).append('\n');
            }
            return sb.toString();
        }
    }
}
