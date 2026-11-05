package com.keeppolicial.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Comunicacao HTTPS com o servidor do destacamento (chamadas bloqueantes). */
public final class Api {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType JPEG = MediaType.get("image/jpeg");
    private static final String MARCA_CERT = "KEEP_IMPRESSAO_DIFERENTE";

    private Api() {
    }

    public static class ErroApi extends IOException {
        final int status;

        ErroApi(int status, String mensagem) {
            super(mensagem);
            this.status = status;
        }
    }

    /** O certificado do servidor nao e o que foi conferido no cadastro. */
    public static class ErroCertificado extends IOException {
        ErroCertificado() {
            super("A identidade do servidor mudou (código de verificação diferente do "
                    + "confirmado no primeiro acesso). Pode ser uma tentativa de interceptação "
                    + "ou o certificado do servidor foi refeito. Confirme com o comandante; se ele "
                    + "confirmar a troca, toque em \"Servidores salvos\", remova este servidor "
                    + "e entre de novo.");
        }
    }

    /** Versao curta da impressao digital (igual a exibida no painel do servidor). */
    static String codigoVerificacao(String impressao) {
        String h = impressao.replace(":", "").toUpperCase();
        if (h.length() < 16) return h;
        return h.substring(0, 4) + "-" + h.substring(4, 8) + "-" + h.substring(8, 12) + "-"
                + h.substring(12, 16);
    }

    static String impressaoDigital(X509Certificate c) throws CertificateException {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < h.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02X", h[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new CertificateException(e);
        }
    }

    /**
     * O servidor usa certificado proprio (autoassinado). A seguranca vem da
     * fixacao (pinning): so aceitamos o certificado cuja impressao digital o
     * usuario conferiu ao cadastrar o servidor.
     *
     * @param esperada  impressao cadastrada; null apenas na primeira consulta (/api/info)
     * @param capturada recebe a impressao apresentada pelo servidor (pode ser null)
     */
    static OkHttpClient criarCliente(final String esperada, final String[] capturada) {
        X509TrustManager tm = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                throw new CertificateException("Não suportado");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("Sem certificado");
                String fp = impressaoDigital(chain[0]);
                if (capturada != null) capturada[0] = fp;
                if (esperada != null && !fp.equalsIgnoreCase(esperada)) {
                    throw new CertificateException(MARCA_CERT);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, null);
            return new OkHttpClient.Builder()
                    .sslSocketFactory(ctx.getSocketFactory(), tm)
                    // O nome do host nao importa: o certificado exato ja foi fixado acima.
                    .hostnameVerifier((host, sessao) -> true)
                    // Curto para trocar logo para o outro endereco (rede local x internet).
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(40, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // -- chamadas ---------------------------------------------------------

    /** Primeiro contato: descobre o nome do destacamento e a impressao digital. */
    static JSONObject info(String url, String[] capturada) throws IOException {
        Request r = new Request.Builder().url(url + "/api/info").build();
        Servidor provisorio = new Servidor();
        provisorio.url = url;
        JSONObject o = json(criarCliente(null, capturada), r, provisorio);
        if (!"keeppolicial".equals(o.optString("app"))) {
            throw new IOException("O endereço informado não é um servidor Keep Policial.");
        }
        return o;
    }

    static void login(Servidor s, String usuario, String senha) throws IOException {
        OkHttpClient c = criarCliente(s.impressao, null);
        JSONObject corpo = new JSONObject();
        try {
            corpo.put("usuario", usuario).put("senha", senha).put("aceite_termo", true);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        Request r = new Request.Builder().url(s.base() + "/api/login")
                .post(RequestBody.create(corpo.toString(), JSON)).build();
        JSONObject resp = json(c, r, s);
        if (resp.has("endereco_publico")) s.definirAlternativa(resp.optString("endereco_publico"));
        Sessao.cliente = c;
        Sessao.servidor = s;
        Sessao.token = resp.optString("token");
        Sessao.usuario = resp.optString("usuario");
        Sessao.nome = resp.optString("nome");
        Sessao.papel = resp.optString("papel");
        Sessao.destacamento = resp.optString("destacamento");
        long agora = System.currentTimeMillis();
        long doServidor = resp.optLong("expira_em") * 1000;
        Sessao.expiraEm = doServidor > agora ? Math.min(doServidor, agora + Sessao.MAX_MS)
                : agora + 10L * 3600 * 1000;
    }

    /** Apaga a sessao local na hora e avisa o servidor em segundo plano. */
    static void encerrarSessao(android.content.Context ctx) {
        final OkHttpClient c = Sessao.cliente;
        final Servidor s = Sessao.servidor;
        final String token = Sessao.token;
        Sessao.encerrar(ctx);
        if (c == null || s == null || token == null) return;
        BaseActivity.EXEC.execute(() -> {
            try {
                executar(c, new Request.Builder().url(s.base() + "/api/logout")
                        .header("Authorization", "Bearer " + token)
                        .post(RequestBody.create("{}", JSON)).build(), s).close();
            } catch (IOException ignorado) {
            }
        });
    }

    static JSONArray listar(String busca) throws IOException {
        if (!Sessao.ativa()) throw new ErroApi(401, "Sessão encerrada. Faça login novamente.");
        HttpUrl url = HttpUrl.get(Sessao.servidor.base() + "/api/suspeitos").newBuilder()
                .addQueryParameter("q", busca).build();
        Request r = new Request.Builder().url(url)
                .header("Authorization", "Bearer " + Sessao.token).build();
        return json(r).optJSONArray("suspeitos");
    }

    static JSONObject obter(String id) throws IOException {
        return json(autenticado("/api/suspeitos/" + id).build());
    }

    /** Cria (id null) ou atualiza a ficha; devolve a ficha gravada. */
    static JSONObject salvar(String id, JSONObject ficha) throws IOException {
        RequestBody corpo = RequestBody.create(ficha.toString(), JSON);
        Request r = id == null
                ? autenticado("/api/suspeitos").post(corpo).build()
                : autenticado("/api/suspeitos/" + id).put(corpo).build();
        return json(r);
    }

    static void excluir(String id) throws IOException {
        executar(autenticado("/api/suspeitos/" + id).delete().build()).close();
    }

    static String enviarFoto(String idSuspeito, byte[] jpeg) throws IOException {
        Request r = autenticado("/api/suspeitos/" + idSuspeito + "/fotos")
                .post(RequestBody.create(jpeg, JPEG)).build();
        return json(r).optString("id");
    }

    static byte[] baixarFoto(String idFoto) throws IOException {
        try (Response r = executar(autenticado("/api/fotos/" + idFoto).build())) {
            return r.body().bytes();
        }
    }

    static void excluirFoto(String idFoto) throws IOException {
        executar(autenticado("/api/fotos/" + idFoto).delete().build()).close();
    }

    // -- infraestrutura ---------------------------------------------------

    private static Request.Builder autenticado(String caminho) throws IOException {
        if (!Sessao.ativa()) throw new ErroApi(401, "Sessão encerrada. Faça login novamente.");
        return new Request.Builder().url(Sessao.servidor.base() + caminho)
                .header("Authorization", "Bearer " + Sessao.token);
    }

    private static Response executar(Request r) throws IOException {
        return executar(Sessao.cliente, r, Sessao.servidor);
    }

    /** O celular nao alcancou o endereco (sem rota, recusado, nome inexistente). */
    private static class FalhaConexao extends IOException {
    }

    private static String mensagemSemConexao(Servidor s) {
        if (s != null && s.alternativa.isEmpty() && Servidor.enderecoPrivado(s.base())) {
            return "Não foi possível conectar ao servidor " + s.enderecoCurto() + ".\n\n"
                    + "Esse endereço é da rede interna: só funciona com o celular no mesmo "
                    + "Wi-Fi do servidor. Fora dela (dados móveis ou outro Wi-Fi), entre com o "
                    + "endereço pela internet informado pelo comandante.";
        }
        return "Não foi possível conectar ao servidor. Verifique a internet do celular e se "
                + "o endereço depois do @ está correto.";
    }

    /**
     * Executa a chamada. Se o celular nao alcancar o endereco atual (ex.: saiu do
     * Wi-Fi do quartel para o 4G), tenta o outro endereco do mesmo servidor. O
     * certificado fixado e o mesmo nos dois, entao a seguranca nao muda.
     */
    private static Response executar(OkHttpClient c, Request r, Servidor s) throws IOException {
        try {
            return executarUma(c, r);
        } catch (FalhaConexao e) {
            HttpUrl outro = s != null ? outroEndereco(s, r.url()) : null;
            if (outro == null) throw new IOException(mensagemSemConexao(s));
            Request r2 = r.newBuilder().url(r.url().newBuilder()
                    .host(outro.host()).port(outro.port()).build()).build();
            Response resp;
            try {
                resp = executarUma(c, r2);
            } catch (FalhaConexao e2) {
                throw new IOException(mensagemSemConexao(s));
            }
            s.preferida = "https://" + outro.host() + ":" + outro.port();
            return resp;
        }
    }

    private static HttpUrl outroEndereco(Servidor s, HttpUrl atual) {
        String[] candidatos = {s.url, s.alternativa};
        for (String cand : candidatos) {
            HttpUrl u = cand.isEmpty() ? null : HttpUrl.parse(cand);
            if (u != null && u.host().equals(atual.host()) && u.port() == atual.port()) {
                String o = s.outro(cand);
                return o == null ? null : HttpUrl.parse(o);
            }
        }
        return null;
    }

    private static Response executarUma(OkHttpClient c, Request r) throws IOException {
        Response resp;
        try {
            resp = c.newCall(r).execute();
        } catch (SSLException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t.getMessage() != null && t.getMessage().contains(MARCA_CERT)) {
                    throw new ErroCertificado();
                }
            }
            throw new IOException("Falha na conexão segura com o servidor: " + e.getMessage());
        } catch (ConnectException | NoRouteToHostException | UnknownHostException e) {
            throw new FalhaConexao();
        } catch (SocketTimeoutException e) {
            String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (m.contains("connect")) throw new FalhaConexao();
            throw new IOException("O servidor demorou a responder. Verifique o sinal de "
                    + "internet e tente novamente.");
        }
        if (!resp.isSuccessful()) {
            String msg = "Erro " + resp.code() + " no servidor.";
            try {
                String corpo = resp.body() != null ? resp.body().string() : "";
                String erro = new JSONObject(corpo).optString("erro");
                if (!erro.isEmpty()) msg = erro;
            } catch (Exception ignorado) {
            }
            resp.close();
            throw new ErroApi(resp.code(), msg);
        }
        return resp;
    }

    private static JSONObject json(Request r) throws IOException {
        return json(Sessao.cliente, r, Sessao.servidor);
    }

    private static JSONObject json(OkHttpClient c, Request r, Servidor s) throws IOException {
        try (Response resp = executar(c, r, s)) {
            return new JSONObject(resp.body().string());
        } catch (JSONException e) {
            throw new IOException("Resposta inválida do servidor.");
        }
    }
}
