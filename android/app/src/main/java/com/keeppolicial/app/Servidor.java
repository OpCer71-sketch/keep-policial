package com.keeppolicial.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Servidor de um destacamento ja acessado neste aparelho. */
public class Servidor {
    public static final int PORTA_PADRAO = 8443;
    private static final String PREFS = "servidores";

    public String nome = "";
    public String url = "";
    /** SHA-256 do certificado, confirmado pelo policial no primeiro acesso (pinning). */
    public String impressao = "";
    public String ultimoUsuario = "";
    /** Endereco pela internet informado pelo servidor (https://host:porta) ou "". */
    public String alternativa = "";
    /** Ultimo endereco que respondeu (url ou alternativa): tentado primeiro. */
    public String preferida = "";

    JSONObject paraJson() throws JSONException {
        return new JSONObject()
                .put("nome", nome)
                .put("url", url)
                .put("impressao", impressao)
                .put("ultimoUsuario", ultimoUsuario)
                .put("alternativa", alternativa)
                .put("preferida", preferida);
    }

    static Servidor deJson(JSONObject o) {
        Servidor s = new Servidor();
        s.nome = o.optString("nome");
        s.url = o.optString("url");
        s.impressao = o.optString("impressao");
        s.ultimoUsuario = o.optString("ultimoUsuario");
        s.alternativa = o.optString("alternativa");
        s.preferida = o.optString("preferida");
        return s;
    }

    /** Endereco a usar agora: o ultimo que funcionou, ou o digitado. */
    String base() {
        if (!preferida.isEmpty() && (preferida.equals(url) || preferida.equals(alternativa))) {
            return preferida;
        }
        return url;
    }

    /** O outro endereco do mesmo servidor (rede local x internet), ou null. */
    String outro(String atual) {
        if (alternativa.isEmpty() || alternativa.equals(url)) return null;
        if (atual.equals(url)) return alternativa;
        if (atual.equals(alternativa)) return url;
        return null;
    }

    /** Guarda o endereco pela internet que o servidor informou (ignora se invalido). */
    void definirAlternativa(String enderecoPublico) {
        if (enderecoPublico == null || enderecoPublico.trim().isEmpty()) {
            alternativa = "";
            return;
        }
        try {
            String n = normalizarEndereco(enderecoPublico);
            alternativa = n.equals(url) ? "" : n;
        } catch (IllegalArgumentException ignorado) {
        }
    }

    /** Endereco de rede privada (so funciona dentro da rede do quartel). */
    static boolean enderecoPrivado(String url) {
        String h = url.replaceFirst("^https://", "").replaceFirst(":\\d+$", "");
        return h.matches("10\\..*|192\\.168\\..*|172\\.(1[6-9]|2\\d|3[01])\\..*")
                || h.endsWith(".local") || h.endsWith(".lan");
    }

    /** Lista do mais recente para o mais antigo. */
    static List<Servidor> carregar(Context c) {
        List<Servidor> lista = new ArrayList<>();
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(p.getString("lista", "[]"));
            for (int i = 0; i < a.length(); i++) lista.add(deJson(a.getJSONObject(i)));
        } catch (JSONException ignorado) {
        }
        return lista;
    }

    static void salvar(Context c, List<Servidor> lista) {
        JSONArray a = new JSONArray();
        try {
            for (Servidor s : lista) a.put(s.paraJson());
        } catch (JSONException ignorado) {
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("lista", a.toString()).apply();
    }

    /** Grava (ou atualiza) o servidor e o coloca no topo da lista. */
    static void lembrar(Context c, Servidor s) {
        List<Servidor> lista = carregar(c);
        for (int i = lista.size() - 1; i >= 0; i--) {
            if (lista.get(i).url.equals(s.url)) lista.remove(i);
        }
        lista.add(0, s);
        salvar(c, lista);
    }

    static Servidor porUrl(List<Servidor> lista, String url) {
        for (Servidor s : lista) if (s.url.equals(url)) return s;
        return null;
    }

    static Servidor porImpressao(List<Servidor> lista, String impressao) {
        for (Servidor s : lista) if (s.impressao.equalsIgnoreCase(impressao)) return s;
        return null;
    }

    /** Aceita "192.168.0.10", "nome.ts.net:8443" ou "https://..." e devolve https://host:porta */
    static String normalizarEndereco(String entrada) {
        String e = entrada.trim().replaceFirst("(?i)^https?://", "");
        int barra = e.indexOf('/');
        if (barra >= 0) e = e.substring(0, barra);
        if (e.isEmpty() || !e.matches("[A-Za-z0-9.\\-]+(:\\d{1,5})?")) {
            throw new IllegalArgumentException("Endereço do servidor inválido. "
                    + "Exemplo: sd.silva@192.168.0.10");
        }
        if (!e.contains(":")) e = e + ":" + PORTA_PADRAO;
        return "https://" + e.toLowerCase();
    }

    /**
     * Separa "usuario@endereco" (como um e-mail). Devolve {usuario, url} ou
     * {usuario, null} quando nao ha endereco.
     */
    static String[] separarAcesso(String acesso) {
        String a = acesso.trim();
        int i = a.lastIndexOf('@');
        if (i < 0) return new String[]{a.toLowerCase(), null};
        String usuario = a.substring(0, i).trim().toLowerCase();
        if (usuario.isEmpty()) throw new IllegalArgumentException("Informe o usuário antes do @.");
        return new String[]{usuario, normalizarEndereco(a.substring(i + 1))};
    }

    String enderecoCurto() {
        return url.replaceFirst("^https://", "").replaceFirst(":" + PORTA_PADRAO + "$", "");
    }

    String acesso(String usuario) {
        return usuario + "@" + enderecoCurto();
    }
}
