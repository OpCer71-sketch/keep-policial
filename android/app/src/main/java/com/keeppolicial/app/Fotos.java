package com.keeppolicial.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Preparo das fotos: corrige rotacao, reduz tamanho e remove metadados (EXIF/GPS). */
final class Fotos {
    static final int LADO_ENVIO = 1600;
    static final int LADO_MINIATURA = 320;

    interface Abridor {
        InputStream abrir() throws IOException;
    }

    private Fotos() {
    }

    static byte[] prepararJpeg(Abridor fonte) throws IOException {
        BitmapFactory.Options limites = new BitmapFactory.Options();
        limites.inJustDecodeBounds = true;
        try (InputStream in = fonte.abrir()) {
            BitmapFactory.decodeStream(in, null, limites);
        }
        if (limites.outWidth <= 0 || limites.outHeight <= 0) {
            throw new IOException("Arquivo de imagem inválido.");
        }
        BitmapFactory.Options op = new BitmapFactory.Options();
        op.inSampleSize = amostragem(limites.outWidth, limites.outHeight, LADO_ENVIO);
        Bitmap original;
        try (InputStream in = fonte.abrir()) {
            original = BitmapFactory.decodeStream(in, null, op);
        }
        if (original == null) throw new IOException("Não foi possível ler a imagem.");

        int rotacao = 0;
        try (InputStream in = fonte.abrir()) {
            int o = new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) rotacao = 90;
            else if (o == ExifInterface.ORIENTATION_ROTATE_180) rotacao = 180;
            else if (o == ExifInterface.ORIENTATION_ROTATE_270) rotacao = 270;
        } catch (Exception ignorado) {
        }

        float escala = Math.min(1f, LADO_ENVIO / (float) Math.max(original.getWidth(), original.getHeight()));
        Matrix m = new Matrix();
        m.postScale(escala, escala);
        m.postRotate(rotacao);
        Bitmap final_ = Bitmap.createBitmap(original, 0, 0, original.getWidth(), original.getHeight(), m, true);
        // Recodificar em JPEG descarta todos os metadados da foto original.
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        int qualidade = 82;
        final_.compress(Bitmap.CompressFormat.JPEG, qualidade, saida);
        // Foto pesada no 4G falha mais: reduz ate ficar abaixo de ~700 KB.
        while (saida.size() > 700_000 && qualidade > 55) {
            qualidade -= 9;
            saida.reset();
            final_.compress(Bitmap.CompressFormat.JPEG, qualidade, saida);
        }
        if (final_ != original) final_.recycle();
        original.recycle();
        return saida.toByteArray();
    }

    static Bitmap decodificar(byte[] jpeg, int maxLado) {
        BitmapFactory.Options limites = new BitmapFactory.Options();
        limites.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, limites);
        BitmapFactory.Options op = new BitmapFactory.Options();
        op.inSampleSize = amostragem(limites.outWidth, limites.outHeight, maxLado);
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, op);
    }

    private static int amostragem(int largura, int altura, int maxLado) {
        int amostra = 1;
        while (largura / (amostra * 2) >= maxLado || altura / (amostra * 2) >= maxLado) amostra *= 2;
        return amostra;
    }
}
