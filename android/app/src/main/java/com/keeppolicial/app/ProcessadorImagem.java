package com.keeppolicial.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/**
 * Preparo da imagem para o OCR em condicoes ruins (abordagem noturna, pouca luz):
 * mede a luminosidade e, se preciso, gera uma copia em tons de cinza com contraste
 * esticado e brilho corrigido. Tudo no aparelho, sem rede.
 */
final class ProcessadorImagem {
    private ProcessadorImagem() {
    }

    /** Decodifica reduzindo (inSampleSize) para nao estourar a memoria; null se invalida. */
    static Bitmap decodificar(byte[] jpeg, int ladoMax) {
        BitmapFactory.Options lim = new BitmapFactory.Options();
        lim.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, lim);
        if (lim.outWidth <= 0 || lim.outHeight <= 0) return null;
        int amostra = 1;
        while (lim.outWidth / (amostra * 2) >= ladoMax || lim.outHeight / (amostra * 2) >= ladoMax) amostra *= 2;
        BitmapFactory.Options op = new BitmapFactory.Options();
        op.inSampleSize = amostra;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, op);
    }

    /** Luminancia media (0 = preto, 255 = branco), amostrando ~10 mil pontos. */
    static double luminanciaMedia(Bitmap b) {
        int passoX = Math.max(1, b.getWidth() / 100), passoY = Math.max(1, b.getHeight() / 100);
        double soma = 0;
        long n = 0;
        for (int y = 0; y < b.getHeight(); y += passoY) {
            for (int x = 0; x < b.getWidth(); x += passoX) {
                soma += luma(b.getPixel(x, y));
                n++;
            }
        }
        return n == 0 ? 0 : soma / n;
    }

    private static int luma(int c) {
        return (int) (0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF));
    }

    /**
     * Cinza + esticamento de contraste (ignora os 1% mais escuros e mais claros) + gama
     * proporcional ao quao escura esta a foto. Devolve uma copia; o original nao e alterado.
     */
    static Bitmap realcar(Bitmap origem) {
        int w = origem.getWidth(), h = origem.getHeight();
        int[] px = new int[w * h];
        origem.getPixels(px, 0, w, 0, 0, w, h);
        int[] hist = new int[256];
        for (int i = 0; i < px.length; i++) {
            int l = luma(px[i]);
            px[i] = l;
            hist[l]++;
        }
        int corte = Math.max(1, px.length / 100);
        int minimo = 0, maximo = 255, acum = 0;
        for (int i = 0; i < 256; i++) {
            acum += hist[i];
            if (acum >= corte) {
                minimo = i;
                break;
            }
        }
        acum = 0;
        for (int i = 255; i >= 0; i--) {
            acum += hist[i];
            if (acum >= corte) {
                maximo = i;
                break;
            }
        }
        if (maximo - minimo < 16) {  // imagem quase uniforme: nao ha o que realcar
            minimo = 0;
            maximo = 255;
        }
        double media = 0;
        for (int i = 0; i < 256; i++) media += (double) i * hist[i];
        media /= px.length;
        // foto escura -> gama < 1 clareia; foto normal -> 1.0
        double gama = media < 60 ? 0.5 : media < 100 ? 0.7 : 1.0;
        int[] tabela = new int[256];
        for (int i = 0; i < 256; i++) {
            double v = (i - minimo) / (double) (maximo - minimo);
            v = Math.min(1, Math.max(0, v));
            tabela[i] = (int) Math.round(255 * Math.pow(v, gama));
        }
        for (int i = 0; i < px.length; i++) {
            int v = tabela[px[i]];
            px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        Bitmap saida = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        saida.setPixels(px, 0, w, 0, 0, w, h);
        return saida;
    }
}
