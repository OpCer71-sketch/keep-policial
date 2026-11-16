package com.keeppolicial.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Auditoria de falhas do app. Travamentos E erros tratados ({@link Diag#e}) viram um relato
 * com CODIGO estavel (ex.: {@code KP-OCR-3F2A1C}): o mesmo defeito gera sempre o mesmo codigo,
 * em qualquer celular, para ser localizado e corrigido. Os relatos ficam numa fila no aparelho
 * e sao enviados ao servidor quando ha conexao; o administrador ve em "Falhas" no painel e o
 * servidor pode encaminhar ao desenvolvedor (Telegram).
 *
 * <p>Nunca vai dado de pessoa: so componente, mensagem fixa do codigo, tipo da excecao e a
 * pilha de chamadas (regra do {@link Diag}). O servidor ainda mascara CPF, IDs e IPs.</p>
 */
final class Falhas {
    private static final String ARQUIVO_ANTIGO = "ultima_falha.txt";
    private static final String ARQUIVO_FILA = "falhas_pendentes.json";
    private static final int MAX_BYTES = 8000;
    private static final int MAX_FILA = 20;
    /** Teto de relatos por execucao do app (um laco de erro nao enche o aparelho). */
    private static final int MAX_POR_EXECUCAO = 50;
    private static final Object TRAVA = new Object();
    private static volatile Context app;
    private static boolean instalado;
    private static volatile boolean enviando;
    private static int nestaExecucao;

    private Falhas() {
    }

    static synchronized void instalar(Context c) {
        app = c.getApplicationContext();
        if (instalado) return;
        instalado = true;
        final Thread.UncaughtExceptionHandler anterior = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                Diag.registrarSemRelato("CRASH", "excecao nao tratada na thread " + t.getName(), e);
                anotar("CRASH", "O aplicativo fechou sozinho: " + e.getClass().getName(), e);
            } catch (Throwable ignorado) {
            }
            if (anterior != null) anterior.uncaughtException(t, e);
        });
    }

    // -- codigos ------------------------------------------------------------------------

    /** KP-COMPONENTE-XXXXXX: componente + 6 hex de uma assinatura do defeito. */
    static String codigo(String componente, String assinatura) {
        String comp = componente == null ? "" : componente.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        if (comp.isEmpty()) comp = "APP";
        if (comp.length() > 8) comp = comp.substring(0, 8);
        CRC32 crc = new CRC32();
        crc.update(assinatura.getBytes(StandardCharsets.UTF_8));
        return String.format(Locale.ROOT, "KP-%s-%06X", comp, crc.getValue() & 0xFFFFFF);
    }

    /**
     * Assinatura do defeito: mensagem sem numeros (contagens e tempos mudam a cada vez),
     * tipo da excecao e o primeiro ponto do codigo do app na pilha.
     */
    private static String assinatura(String componente, String mensagem, Throwable t) {
        StringBuilder sb = new StringBuilder(componente).append('|')
                .append(mensagem == null ? "" : mensagem.replaceAll("\\d+", "#"));
        Throwable raiz = t;
        while (raiz != null && raiz.getCause() != null && raiz.getCause() != raiz) raiz = raiz.getCause();
        if (raiz != null) {
            sb.append('|').append(raiz.getClass().getName());
            for (StackTraceElement el : raiz.getStackTrace()) {
                if (el.getClassName().startsWith("com.keeppolicial")) {
                    sb.append('|').append(el.getClassName()).append('.').append(el.getMethodName());
                    break;
                }
            }
        }
        return sb.toString();
    }

    // -- registro -----------------------------------------------------------------------

    /** Chamado por {@link Diag#e}: erro tratado (o app seguiu funcionando). */
    static String anotar(String componente, String mensagem, Throwable t) {
        String cod = codigo(componente, assinatura(componente, mensagem, t));
        StringBuilder d = new StringBuilder();
        d.append("Codigo: ").append(cod).append('\n')
                .append("Componente: ").append(componente).append('\n')
                .append("Mensagem: ").append(mensagem).append('\n');
        if (t != null) d.append(pilhaSemMensagens(t));
        d.append("\n--- ultimos eventos ---\n").append(Diag.ultimas(25));
        enfileirar(cod, "[" + cod + "] " + componente + ": " + mensagem, d.toString());
        return cod;
    }

    /**
     * Pilha de chamadas SEM o texto das excecoes: a mensagem de uma JSONException ou
     * SQLiteException pode conter trechos da ficha (nome, CPF). So vao tipos e linhas.
     */
    static String pilhaSemMensagens(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int nivel = 0;
        for (Throwable x = t; x != null && nivel < 5; x = x.getCause() == x ? null : x.getCause(), nivel++) {
            sb.append(nivel == 0 ? "" : "Causado por: ").append(x.getClass().getName()).append('\n');
            StackTraceElement[] pilha = x.getStackTrace();
            for (int i = 0; i < pilha.length && i < 15; i++) sb.append("    at ").append(pilha[i]).append('\n');
            if (pilha.length > 15) sb.append("    ... +").append(pilha.length - 15).append('\n');
        }
        return sb.toString();
    }

    /** Problema relevante sem excecao (dados perdidos, banco corrompido...). */
    static void registrarLocal(Context c, String texto) {
        if (app == null && c != null) app = c.getApplicationContext();
        String primeira = texto.split("\n", 2)[0];
        String cod = codigo("APP", primeira.replaceAll("\\d+", "#"));
        enfileirar(cod, "[" + cod + "] " + primeira,
                "Codigo: " + cod + "\n" + texto + "\n--- ultimos eventos ---\n" + Diag.ultimas(25));
    }

    private static void enfileirar(String cod, String titulo, String detalhe) {
        Context c = app;
        if (c == null) return;
        synchronized (TRAVA) {
            if (nestaExecucao >= MAX_POR_EXECUCAO) return;
            nestaExecucao++;
            try {
                JSONArray fila = lerFila(c);
                JSONArray nova = new JSONArray();
                boolean repetida = false;
                for (int i = 0; i < fila.length(); i++) {
                    JSONObject o = fila.getJSONObject(i);
                    if (cod.equals(o.optString("codigo"))) {
                        o.put("vezes", o.optInt("vezes", 1) + 1).put("ultima", agora());
                        repetida = true;
                    }
                    nova.put(o);
                }
                if (!repetida) {
                    if (detalhe.length() > MAX_BYTES) detalhe = detalhe.substring(0, MAX_BYTES);
                    nova.put(new JSONObject().put("codigo", cod).put("titulo", titulo)
                            .put("detalhe", detalhe).put("vezes", 1).put("ultima", agora()));
                    while (nova.length() > MAX_FILA) nova.remove(0);
                }
                gravarFila(c, nova);
            } catch (Exception ignorado) {
                // relato de falha nunca pode causar outra falha
            }
        }
    }

    /** Relatos aguardando envio (para a tela de diagnostico). */
    static int pendentes(Context c) {
        synchronized (TRAVA) {
            return lerFila(c.getApplicationContext()).length();
        }
    }

    // -- envio --------------------------------------------------------------------------

    /** Envia os relatos pendentes (chamar com sessao online). */
    static void enviarPendente(Context c) {
        final Context ctx = c.getApplicationContext();
        if (app == null) app = ctx;
        if (enviando) return;
        enviando = true;
        BaseActivity.EXEC.execute(() -> {
            try {
                migrarArquivoAntigo(ctx);
                while (true) {
                    JSONObject o;
                    synchronized (TRAVA) {
                        JSONArray fila = lerFila(ctx);
                        if (fila.length() == 0) break;
                        o = fila.getJSONObject(0);
                    }
                    String detalhe = o.optString("detalhe");
                    int vezes = o.optInt("vezes", 1);
                    if (vezes > 1) detalhe = "Ocorreu " + vezes + " vez(es) neste aparelho (ultima "
                            + o.optString("ultima") + ")\n" + detalhe;
                    Api.relatarFalha(o.optString("codigo"), o.optString("titulo"), detalhe);
                    synchronized (TRAVA) {
                        JSONArray fila = lerFila(ctx), resto = new JSONArray();
                        for (int i = 0; i < fila.length(); i++) {
                            if (!o.optString("codigo").equals(fila.getJSONObject(i).optString("codigo"))) {
                                resto.put(fila.get(i));
                            }
                        }
                        gravarFila(ctx, resto);
                    }
                }
            } catch (Exception ignorado) {
                // sem rede ou limite do servidor: tenta de novo na proxima abertura
            } finally {
                enviando = false;
            }
        });
    }

    /** Relato gravado pela versao 3.3 ou anterior (um arquivo de texto so). */
    private static void migrarArquivoAntigo(Context c) {
        File f = new File(c.getFilesDir(), ARQUIVO_ANTIGO);
        if (!f.exists()) return;
        try {
            byte[] b = new byte[(int) Math.min(f.length(), MAX_BYTES)];
            int lidos;
            try (FileInputStream in = new FileInputStream(f)) {
                lidos = Math.max(0, in.read(b));
            }
            String texto = new String(b, 0, lidos, StandardCharsets.UTF_8);
            String primeira = texto.split("\n", 2)[0];
            String cod = codigo("APP", primeira.replaceAll("\\d+", "#"));
            enfileirar(cod, "[" + cod + "] " + primeira, "Codigo: " + cod + "\n" + texto);
        } catch (Exception ignorado) {
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static JSONArray lerFila(Context c) {
        File f = new File(c.getFilesDir(), ARQUIVO_FILA);
        if (!f.exists()) return new JSONArray();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), MAX_FILA * (MAX_BYTES + 1024L))];
            int lidos = 0, n;
            while (lidos < b.length && (n = in.read(b, lidos, b.length - lidos)) > 0) lidos += n;
            return new JSONArray(new String(b, 0, lidos, StandardCharsets.UTF_8));
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();  // arquivo corrompido: comeca de novo
            return new JSONArray();
        }
    }

    private static void gravarFila(Context c, JSONArray fila) throws Exception {
        File tmp = new File(c.getFilesDir(), ARQUIVO_FILA + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(fila.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(c.getFilesDir(), ARQUIVO_FILA))) throw new java.io.IOException("rename");
    }

    private static String agora() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
    }
}
