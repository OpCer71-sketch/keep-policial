package com.keeppolicial.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import okhttp3.OkHttpClient;

/**
 * Sessao do policial. Vale pelo turno (10 h, definido no servidor): mesmo que o
 * Android feche o app, ela e recuperada sem novo login ate expirar. O token fica
 * gravado cifrado com uma chave do Android Keystore, que nao sai do aparelho.
 */
public final class Sessao {
    private static final String PREFS = "sessao";
    private static final String CHAVE = "keep_sessao";
    /** Limite no aparelho, mesmo que o servidor permita mais. */
    static final long MAX_MS = 24L * 3600 * 1000;

    static Servidor servidor;
    static OkHttpClient cliente;
    static String token;
    static String usuario;
    static String nome;
    static String papel;
    static String destacamento;
    /** Fim da sessao (ms desde 1970). */
    static long expiraEm;

    private Sessao() {
    }

    static boolean ativa() {
        return token != null && cliente != null && servidor != null;
    }

    static boolean expirada() {
        return System.currentTimeMillis() >= expiraEm;
    }

    static boolean admin() {
        return "admin".equals(papel);
    }

    /** Apaga a sessao da memoria e do aparelho. */
    static void encerrar(Context c) {
        token = null;
        usuario = null;
        nome = null;
        papel = null;
        expiraEm = 0;
        Imagens.limpar();
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply();
    }

    static void guardar(Context c) {
        try {
            JSONObject o = new JSONObject()
                    .put("servidor", servidor.paraJson())
                    .put("token", token)
                    .put("usuario", usuario)
                    .put("nome", nome)
                    .put("papel", papel)
                    .put("destacamento", destacamento)
                    .put("expira", expiraEm);
            Cipher cifra = Cipher.getInstance("AES/GCM/NoPadding");
            cifra.init(Cipher.ENCRYPT_MODE, chave());
            byte[] dados = cifra.doFinal(o.toString().getBytes(StandardCharsets.UTF_8));
            c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("iv", Base64.encodeToString(cifra.getIV(), Base64.NO_WRAP))
                    .putString("dados", Base64.encodeToString(dados, Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            // Sem Keystore a sessao fica so na memoria (vale enquanto o app estiver aberto).
        }
    }

    /** Recupera a sessao guardada, se ainda valida. */
    static boolean restaurar(Context c) {
        android.content.SharedPreferences p =
                c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String iv = p.getString("iv", null), dados = p.getString("dados", null);
        if (iv == null || dados == null) return false;
        try {
            Cipher cifra = Cipher.getInstance("AES/GCM/NoPadding");
            cifra.init(Cipher.DECRYPT_MODE, chave(),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            JSONObject o = new JSONObject(new String(
                    cifra.doFinal(Base64.decode(dados, Base64.NO_WRAP)), StandardCharsets.UTF_8));
            long expira = o.getLong("expira");
            if (System.currentTimeMillis() >= expira) {
                p.edit().clear().apply();
                return false;
            }
            servidor = Servidor.deJson(o.getJSONObject("servidor"));
            cliente = Api.criarCliente(servidor.impressao, null);
            token = o.getString("token");
            usuario = o.optString("usuario");
            nome = o.optString("nome");
            papel = o.optString("papel");
            destacamento = o.optString("destacamento");
            expiraEm = expira;
            return true;
        } catch (Exception e) {
            p.edit().clear().apply();
            return false;
        }
    }

    private static SecretKey chave() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(CHAVE)) return (SecretKey) ks.getKey(CHAVE, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(CHAVE,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }
}
