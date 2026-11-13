package com.keeppolicial.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Auditoria de falhas do app: se o aplicativo travar, o erro (somente a pilha
 * de chamadas, sem dados de fichas) e gravado no aparelho e enviado ao servidor
 * na proxima abertura. O administrador ve em "Falhas" no painel.
 */
final class Falhas {
    private static final String ARQUIVO = "ultima_falha.txt";
    private static final int MAX_BYTES = 8000;
    private static boolean instalado;
    private static boolean enviando;

    private Falhas() {
    }

    static synchronized void instalar(Context c) {
        if (instalado) return;
        instalado = true;
        final Context app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler anterior = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                Diag.e("CRASH", "excecao nao tratada na thread " + t.getName(), e);
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String texto = "O aplicativo fechou sozinho: " + e.getClass().getName() + "\n" + sw
                        + "\n--- ultimos eventos ---\n" + Diag.ultimas(25);
                if (texto.length() > MAX_BYTES) texto = texto.substring(0, MAX_BYTES);
                try (FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), ARQUIVO))) {
                    out.write(texto.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignorado) {
            }
            if (anterior != null) anterior.uncaughtException(t, e);
        });
    }

    /** Anota um problema (sem dados de fichas) para ser enviado ao servidor na proxima abertura. */
    static void registrarLocal(Context c, String texto) {
        try (FileOutputStream out = new FileOutputStream(
                new File(c.getApplicationContext().getFilesDir(), ARQUIVO))) {
            texto = texto + "\n--- ultimos eventos ---\n" + Diag.ultimas(25);
            String t = texto.length() > MAX_BYTES ? texto.substring(0, MAX_BYTES) : texto;
            out.write(t.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignorado) {
        }
    }

    /** Envia (uma vez) a falha gravada na ultima vez que o app travou. */
    static void enviarPendente(Context c) {
        final File f = new File(c.getApplicationContext().getFilesDir(), ARQUIVO);
        if (!f.exists() || enviando) return;
        enviando = true;
        BaseActivity.EXEC.execute(() -> {
            try {
                byte[] b = new byte[(int) Math.min(f.length(), MAX_BYTES)];
                try (FileInputStream in = new FileInputStream(f)) {
                    int lidos = in.read(b);
                    if (lidos < 0) lidos = 0;
                    String texto = new String(b, 0, lidos, StandardCharsets.UTF_8);
                    String titulo = texto.split("\n", 2)[0];
                    Api.relatarFalha(titulo, texto);
                }
                f.delete();
            } catch (Exception ignorado) {
                // tenta de novo na proxima abertura
            } finally {
                enviando = false;
            }
        });
    }
}
