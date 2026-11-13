package com.keeppolicial.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Login sem internet. Depois de cada login ONLINE bem-sucedido o app guarda (cifrado)
 * um verificador da senha: PBKDF2-HMAC-SHA256 com sal proprio, nunca a senha.
 *
 * <p>Limites de seguranca: so vale ate {@link #JANELA_MS} apos o ultimo login no servidor
 * (depois, e preciso entrar com internet, quando o servidor confirma que o policial
 * continua ativo) e erra-se no maximo 5 vezes antes de esperar 15 minutos.</p>
 */
final class LoginOffline {
    static final long JANELA_MS = 72L * 3600 * 1000;
    private static final int ITERACOES = 100_000;
    private static final int TENTATIVAS = 5;
    private static final long ESPERA_MS = 15L * 60 * 1000;
    private static final String PREFS = "login_offline";

    private LoginOffline() {
    }

    private static String chaveDe(Servidor s, String usuario) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest((s.impressao + "|" + usuario.toLowerCase()).getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(h, Base64.NO_WRAP | Base64.URL_SAFE).substring(0, 22);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] pbkdf2(String senha, byte[] sal) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(senha.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] u = mac.doFinal(ByteBuffer.allocate(sal.length + 4).put(sal).putInt(1).array());
        byte[] t = u.clone();
        for (int i = 1; i < ITERACOES; i++) {
            u = mac.doFinal(u);
            for (int j = 0; j < t.length; j++) t[j] ^= u[j];
        }
        return t;
    }

    /** Guarda o verificador (chamar apos login online bem-sucedido). */
    static void registrar(Context c, Servidor s, String usuario, String senha) {
        try {
            byte[] sal = new byte[16];
            new SecureRandom().nextBytes(sal);
            JSONObject o = new JSONObject()
                    .put("sal", Base64.encodeToString(sal, Base64.NO_WRAP))
                    .put("hash", Base64.encodeToString(pbkdf2(senha, sal), Base64.NO_WRAP))
                    .put("nome", Sessao.nome).put("papel", Sessao.papel)
                    .put("usuario", Sessao.usuario)
                    .put("destacamento", Sessao.destacamento)
                    .put("ultimo_online", System.currentTimeMillis())
                    .put("falhas", 0).put("bloqueado_ate", 0);
            gravar(c, s, usuario, o);
        } catch (Exception e) {
            // sem Keystore: simplesmente nao havera login offline
        }
    }

    private static void gravar(Context c, Servidor s, String usuario, JSONObject o) throws Exception {
        byte[] cif = Cofre.cifrar(o.toString().getBytes(StandardCharsets.UTF_8));
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(chaveDe(s, usuario), Base64.encodeToString(cif, Base64.NO_WRAP)).apply();
    }

    private static JSONObject ler(Context c, Servidor s, String usuario) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String b = p.getString(chaveDe(s, usuario), null);
        if (b == null) return null;
        try {
            return new JSONObject(new String(Cofre.decifrar(Base64.decode(b, Base64.NO_WRAP)),
                    StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** Ha login offline possivel para este usuario neste servidor? */
    static boolean disponivel(Context c, Servidor s, String usuario) {
        JSONObject o = ler(c, s, usuario);
        return o != null && System.currentTimeMillis() - o.optLong("ultimo_online") < JANELA_MS;
    }

    /** Resultado da tentativa de login offline. */
    static final class Resultado {
        final boolean ok;
        final String mensagem;

        Resultado(boolean ok, String mensagem) {
            this.ok = ok;
            this.mensagem = mensagem;
        }
    }

    /** Confere a senha localmente e, se correta, abre uma sessao offline. */
    static Resultado entrar(Context c, Servidor s, String usuario, String senha) {
        JSONObject o = ler(c, s, usuario);
        if (o == null) {
            return new Resultado(false, "Sem conexão com o servidor e este usuário ainda não "
                    + "entrou neste celular com internet. Conecte-se para o primeiro acesso.");
        }
        long agora = System.currentTimeMillis();
        if (agora - o.optLong("ultimo_online") >= JANELA_MS) {
            return new Resultado(false, "Faz mais de 3 dias que este celular não confirma o "
                    + "acesso no servidor. Conecte-se à internet para entrar.");
        }
        if (o.optLong("bloqueado_ate") > agora) {
            long min = (o.optLong("bloqueado_ate") - agora) / 60000 + 1;
            return new Resultado(false, "Muitas tentativas erradas. Tente em " + min + " min.");
        }
        try {
            byte[] esperado = Base64.decode(o.getString("hash"), Base64.NO_WRAP);
            byte[] obtido = pbkdf2(senha, Base64.decode(o.getString("sal"), Base64.NO_WRAP));
            if (!MessageDigest.isEqual(esperado, obtido)) {
                int falhas = o.optInt("falhas") + 1;
                o.put("falhas", falhas >= TENTATIVAS ? 0 : falhas);
                if (falhas >= TENTATIVAS) o.put("bloqueado_ate", agora + ESPERA_MS);
                gravar(c, s, usuario, o);
                return new Resultado(false, "Usuário ou senha incorretos.");
            }
            o.put("falhas", 0).put("bloqueado_ate", 0);
            gravar(c, s, usuario, o);
            Sessao.servidor = s;
            Sessao.cliente = Api.criarCliente(s.impressao, null);
            Sessao.token = "";
            Sessao.offline = true;
            Sessao.senhaMemoria = senha;
            Sessao.usuario = o.optString("usuario", usuario);
            Sessao.nome = o.optString("nome");
            Sessao.papel = o.optString("papel");
            Sessao.destacamento = o.optString("destacamento");
            Sessao.expiraEm = agora + 10L * 3600 * 1000;
            return new Resultado(true, "");
        } catch (Exception e) {
            return new Resultado(false, "Não foi possível conferir a senha neste aparelho.");
        }
    }
}
