package com.keeppolicial.app;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

/**
 * Baixa e mantem as fotos apenas em memoria (nada e gravado no aparelho).
 * O cache e apagado ao sair da sessao.
 */
final class Imagens {
    private static final Handler PRINCIPAL = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
                @Override
                protected int sizeOf(String chave, Bitmap b) {
                    return b.getByteCount();
                }
            };

    private Imagens() {
    }

    static void carregar(final ImageView iv, final String idFoto, final int maxLado) {
        final String chave = idFoto + "@" + maxLado;
        iv.setTag(R.id.tag_foto, chave);
        Bitmap b = CACHE.get(chave);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        iv.setImageResource(R.drawable.sem_foto);
        final android.content.Context ctx = iv.getContext().getApplicationContext();
        BaseActivity.EXEC.execute(() -> {
            try {
                // Aparelho primeiro (funciona offline); baixa do servidor so se faltar.
                byte[] bytes = Repositorio.fotoBytes(ctx, idFoto);
                if (bytes == null) return;
                Bitmap bm = Fotos.decodificar(bytes, maxLado);
                if (bm == null) return;
                CACHE.put(chave, bm);
                PRINCIPAL.post(() -> {
                    if (chave.equals(iv.getTag(R.id.tag_foto))) iv.setImageBitmap(bm);
                });
            } catch (Exception ignorado) {
            }
        });
    }

    static void limpar() {
        CACHE.evictAll();
    }
}
