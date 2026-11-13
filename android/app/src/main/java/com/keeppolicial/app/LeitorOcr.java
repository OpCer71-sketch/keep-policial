package com.keeppolicial.app;

import android.content.Context;
import android.graphics.Bitmap;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * OCR de documentos e placas com Google ML Kit, 100% OFFLINE: o modelo vai dentro do APK
 * e nenhuma imagem sai do aparelho.
 *
 * <p>Para fotos escuras (abordagem noturna) ou sem texto na 1a leitura, faz uma 2a leitura
 * sobre uma copia realcada (contraste/brilho) e fica com a que render mais campos.</p>
 */
final class LeitorOcr {
    private static final String COMP = "OCR";
    /** Abaixo disto (0-255) a foto e considerada escura. */
    private static final double LIMITE_ESCURA = 70;
    private static final int LADO_OCR = 2000;

    /** Resultado da leitura. */
    static final class Leitura {
        final String texto;
        /** A foto estava escura (sugerir mais luz). */
        final boolean escura;
        /** O realce de imagem foi o que melhor funcionou. */
        final boolean realcada;

        Leitura(String texto, boolean escura, boolean realcada) {
            this.texto = texto;
            this.escura = escura;
            this.realcada = realcada;
        }
    }

    interface Ouvinte {
        void texto(Leitura leitura);

        void erro(String mensagem);
    }

    private static TextRecognizer reconhecedor;

    private LeitorOcr() {
    }

    /** O motor esta incluido no app: os botoes de escanear ficam visiveis. */
    static boolean disponivel() {
        return true;
    }

    private static synchronized TextRecognizer cliente() {
        if (reconhecedor == null) {
            reconhecedor = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }
        return reconhecedor;
    }

    /** Carrega o motor em segundo plano antes do uso (a 1a leitura demora mais). */
    static void aquecer() {
        BaseActivity.EXEC.execute(() -> {
            try {
                cliente();
                Diag.d(COMP, "motor pronto");
            } catch (Throwable t) {
                Diag.w(COMP, "falha ao preparar o motor", t);
            }
        });
    }

    private static String reconhecer(Bitmap b) throws ExecutionException, InterruptedException, TimeoutException {
        Text t = Tasks.await(cliente().process(InputImage.fromBitmap(b, 0)), 40, TimeUnit.SECONDS);
        return t.getText();
    }

    private static int pontos(String texto, boolean soPlaca) {
        ExtratorDocumento.Resultado r = ExtratorDocumento.extrair(texto);
        if (soPlaca) return r.placa.isEmpty() ? 0 : 1;
        int n = 0;
        for (String s : new String[]{r.cpf, r.rg, r.cnhNumero, r.cnhCategoria, r.cnhValidade,
                r.nome, r.pai, r.mae, r.placa}) {
            if (!s.isEmpty()) n++;
        }
        return n;
    }

    /**
     * Le o texto do JPEG. Deve ser chamado FORA da thread principal. Nunca lanca excecao:
     * qualquer problema (memoria, motor, tempo) volta por {@link Ouvinte#erro}.
     */
    static void ler(Context c, byte[] jpeg, boolean soPlaca, Ouvinte o) {
        long inicio = System.currentTimeMillis();
        Bitmap original = null, realcada = null;
        try {
            original = ProcessadorImagem.decodificar(jpeg, LADO_OCR);
            if (original == null) {
                Diag.w(COMP, "imagem invalida (nao decodifica)", null);
                o.erro("A foto não pôde ser aberta. Tire outra.");
                return;
            }
            double luz = ProcessadorImagem.luminanciaMedia(original);
            boolean escura = luz < LIMITE_ESCURA;
            String texto1 = reconhecer(original);
            int p1 = pontos(texto1, soPlaca);
            String melhor = texto1;
            boolean usouRealce = false;
            if (escura || p1 == 0) {
                // 2a chance: contraste/brilho corrigidos
                realcada = ProcessadorImagem.realcar(original);
                String texto2 = reconhecer(realcada);
                int p2 = pontos(texto2, soPlaca);
                if (p2 > p1 || (p1 == 0 && texto1.trim().isEmpty())) {
                    melhor = texto2;
                    usouRealce = true;
                }
                Diag.d(COMP, "2a leitura com realce: campos " + p1 + " -> " + p2);
            }
            Diag.i(COMP, "leitura ok: luz=" + (int) luz + (escura ? " (escura)" : "")
                    + ", caracteres=" + melhor.length() + ", realce=" + usouRealce
                    + ", " + (System.currentTimeMillis() - inicio) + " ms");
            o.texto(new Leitura(melhor, escura, usouRealce));
        } catch (OutOfMemoryError e) {
            Diag.e(COMP, "memoria insuficiente ao ler a imagem", e);
            o.erro("O celular ficou sem memória para ler a foto. Feche outros aplicativos e tente de novo.");
        } catch (TimeoutException e) {
            Diag.e(COMP, "tempo esgotado", e);
            o.erro("A leitura demorou demais. Tente de novo.");
        } catch (ExecutionException e) {
            Diag.e(COMP, "falha do motor de OCR", e);
            o.erro("O leitor de texto falhou. Tente de novo; se continuar, digite os dados.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            o.erro("Leitura interrompida.");
        } catch (Throwable t) {
            Diag.e(COMP, "erro inesperado na leitura", t);
            o.erro("Não foi possível ler a imagem.");
        } finally {
            if (original != null) original.recycle();
            if (realcada != null) realcada.recycle();
        }
    }
}
