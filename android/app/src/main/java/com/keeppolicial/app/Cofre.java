package com.keeppolicial.app;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Cifra os dados guardados no aparelho (fichas offline, fila de envio, fotos) com
 * AES-256-GCM. A chave fica no Android Keystore e nao sai do aparelho: copiar os
 * arquivos do app para outro lugar nao revela nada.
 */
final class Cofre {
    private static final String CHAVE = "keep_cofre";
    private static final int TAM_IV = 12;

    /** Os dados nao puderam ser abertos (chave invalidada, arquivo corrompido). */
    static class FalhaCofre extends Exception {
        FalhaCofre(Throwable causa) {
            super("Dados locais ilegíveis", causa);
        }
    }

    private Cofre() {
    }

    static byte[] cifrar(byte[] claro) throws FalhaCofre {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, chave());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(claro);
            byte[] saida = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, saida, 0, iv.length);
            System.arraycopy(ct, 0, saida, iv.length, ct.length);
            return saida;
        } catch (GeneralSecurityException | RuntimeException e) {
            Diag.e("Cofre", "falha ao cifrar", e);
            throw new FalhaCofre(e);
        }
    }

    static byte[] decifrar(byte[] cifrado) throws FalhaCofre {
        try {
            if (cifrado == null || cifrado.length <= TAM_IV) throw new FalhaCofre(null);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, chave(), new GCMParameterSpec(128,
                    Arrays.copyOfRange(cifrado, 0, TAM_IV)));
            return c.doFinal(cifrado, TAM_IV, cifrado.length - TAM_IV);
        } catch (GeneralSecurityException | RuntimeException e) {
            Diag.e("Cofre", "falha ao decifrar (chave invalidada ou dado corrompido)", e);
            throw new FalhaCofre(e);
        }
    }

    private static SecretKey chave() throws GeneralSecurityException {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(CHAVE)) return (SecretKey) ks.getKey(CHAVE, null);
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(CHAVE,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return g.generateKey();
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
    }
}
