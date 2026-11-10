package com.keeppolicial.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Atualizacao do aplicativo pelas "Releases" do repositorio no GitHub.
 *
 * Na primeira abertura do dia o app consulta a ultima release; havendo versao
 * nova, baixa o APK em segundo plano, confere (SHA256SUMS, nome do pacote e
 * versao) e pede ao policial para instalar. O Android ainda confere a
 * assinatura: so instala por cima se o APK for assinado com a mesma chave.
 */
final class Atualizador {
    static final String REPO = "OpCer71-sketch/keep-policial";
    private static final String URL_RELEASE = "https://api.github.com/repos/" + REPO
            + "/releases/latest";
    private static final String PREFS = "atualizacao";
    private static final String TIPO_APK = "application/vnd.android.package-archive";
    private static final long MAX_BYTES_APK = 100L * 1024 * 1024;

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static volatile boolean verificando;
    private static WeakReference<Activity> telaAtual = new WeakReference<>(null);
    /** Dialogo de instalacao ja mostrado nesta execucao do app. */
    private static boolean oferecido;
    /** O policial foi as configuracoes liberar "instalar apps desta fonte". */
    private static boolean aguardandoPermissao;

    private Atualizador() {
    }

    // -- chamado pelas telas ------------------------------------------------------

    /** Cada tela, ao aparecer: oferece a instalacao pendente ou verifica (1x por dia). */
    static void aoAbrir(Activity a) {
        telaAtual = new WeakReference<>(a);
        Context app = a.getApplicationContext();
        SharedPreferences p = prefs(app);
        limparSeJaInstalada(app, p);
        File apk = pendente(app);
        if (apk != null) {
            if (aguardandoPermissao && podeInstalar(a)) {
                aguardandoPermissao = false;
                instalar(a);
            } else if (!oferecido && !hoje().equals(p.getString("adiado_em", ""))) {
                oferecer(a);
            }
            return;
        }
        if (!hoje().equals(p.getString("ultima_verificacao", "")) && !verificando) {
            verificar(app, false);
        }
    }

    static void aoSair(Activity a) {
        if (telaAtual.get() == a) telaAtual = new WeakReference<>(null);
    }

    /** Menu "Verificar atualização": consulta agora e informa o resultado. */
    static void verificarAgora(Activity a) {
        if (pendente(a.getApplicationContext()) != null) {
            oferecer(a);
            return;
        }
        if (verificando) {
            Toast.makeText(a, "Verificação em andamento...", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(a, "Procurando atualização...", Toast.LENGTH_SHORT).show();
        verificar(a.getApplicationContext(), true);
    }

    // -- verificacao e download ----------------------------------------------------

    private static void verificar(final Context app, final boolean manual) {
        verificando = true;
        BaseActivity.EXEC.execute(() -> {
            String resultado;
            try {
                resultado = consultarEBaixar(app);
                prefs(app).edit().putString("ultima_verificacao", hoje()).apply();
            } catch (Exception e) {
                // Sem internet: tenta de novo na proxima abertura (nao marca o dia).
                resultado = "Não foi possível verificar a atualização: "
                        + (e.getMessage() != null ? e.getMessage() : e.toString());
            } finally {
                verificando = false;
            }
            final String msg = resultado;
            UI.post(() -> {
                Activity a = telaAtual.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                if (pendente(app) != null) {
                    oferecer(a);
                } else if (manual && msg != null) {
                    Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /** Devolve uma mensagem para o policial (verificacao manual) ou null se baixou. */
    private static String consultarEBaixar(Context app) throws Exception {
        OkHttpClient c = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        JSONObject rel;
        try (Response r = c.newCall(new Request.Builder().url(URL_RELEASE)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "KeepPolicial-App").build()).execute()) {
            if (!r.isSuccessful()) throw new IOException("GitHub respondeu " + r.code());
            rel = new JSONObject(r.body().string());
        }
        String tag = rel.optString("tag_name");
        if (comparar(tag, BuildConfig.VERSION_NAME) <= 0) {
            return "O aplicativo já está na versão mais recente (" + BuildConfig.VERSION_NAME + ").";
        }
        String urlApk = null, urlSomas = null, nomeApk = null;
        long tamanho = 0;
        JSONArray assets = rel.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject as = assets.optJSONObject(i);
            if (as == null) continue;
            String nome = as.optString("name");
            if (nome.toLowerCase(Locale.ROOT).endsWith(".apk") && urlApk == null) {
                nomeApk = nome;
                urlApk = as.optString("browser_download_url");
                tamanho = as.optLong("size");
            } else if (nome.equals("SHA256SUMS")) {
                urlSomas = as.optString("browser_download_url");
            }
        }
        if (urlApk == null || urlApk.isEmpty()) {
            return "A versão " + tag + " ainda não tem o aplicativo publicado.";
        }
        if (tamanho > MAX_BYTES_APK) throw new IOException("APK grande demais.");

        File dir = new File(app.getCacheDir(), "atualizacao");
        apagarConteudo(dir);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File parcial = new File(dir, "baixando.part");
        File destino = new File(dir, "KeepPolicial-" + tag.replaceAll("[^0-9A-Za-z.]", "") + ".apk");
        String sha = baixar(c, urlApk, parcial);

        if (urlSomas != null) {
            String somas;
            try (Response r = c.newCall(new Request.Builder().url(urlSomas)
                    .header("User-Agent", "KeepPolicial-App").build()).execute()) {
                if (!r.isSuccessful()) throw new IOException("Falha ao baixar SHA256SUMS");
                somas = r.body().string();
            }
            Matcher m = Pattern.compile("(?m)^([0-9a-fA-F]{64})\\s+\\*?"
                    + Pattern.quote(nomeApk) + "\\s*$").matcher(somas);
            if (!m.find() || !m.group(1).equalsIgnoreCase(sha)) {
                //noinspection ResultOfMethodCallIgnored
                parcial.delete();
                throw new IOException("o arquivo baixado não confere com o SHA256SUMS da release.");
            }
        }

        PackageInfo info = app.getPackageManager().getPackageArchiveInfo(parcial.getPath(), 0);
        long codigo = info == null ? 0 : (Build.VERSION.SDK_INT >= 28
                ? info.getLongVersionCode() : info.versionCode);
        if (info == null || !app.getPackageName().equals(info.packageName)
                || codigo <= BuildConfig.VERSION_CODE) {
            //noinspection ResultOfMethodCallIgnored
            parcial.delete();
            throw new IOException("o APK publicado não é uma versão mais nova do Keep Policial.");
        }
        if (!parcial.renameTo(destino)) throw new IOException("Falha ao gravar o APK.");
        prefs(app).edit()
                .putString("apk_arquivo", destino.getPath())
                .putString("apk_versao", info.versionName != null ? info.versionName : tag)
                .putLong("apk_codigo", codigo)
                .putString("apk_notas", rel.optString("body"))
                .remove("adiado_em")
                .apply();
        oferecido = false;
        return null;
    }

    private static String baixar(OkHttpClient c, String url, File destino) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (Response r = c.newCall(new Request.Builder().url(url)
                .header("User-Agent", "KeepPolicial-App").build()).execute()) {
            if (!r.isSuccessful()) throw new IOException("Download falhou (" + r.code() + ")");
            long total = 0;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = r.body().byteStream();
                 OutputStream out = new FileOutputStream(destino)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES_APK) throw new IOException("APK grande demais.");
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // -- instalacao ----------------------------------------------------------------

    private static void oferecer(final Activity a) {
        if (a.isFinishing() || a.isDestroyed()) return;
        SharedPreferences p = prefs(a.getApplicationContext());
        oferecido = true;
        String notas = p.getString("apk_notas", "").trim();
        if (notas.length() > 800) notas = notas.substring(0, 800) + "...";
        new AlertDialog.Builder(a)
                .setTitle("Atualização disponível")
                .setMessage("A versão " + p.getString("apk_versao", "") + " do Keep Policial já "
                        + "foi baixada (versão atual: " + BuildConfig.VERSION_NAME + ")."
                        + (notas.isEmpty() ? "" : "\n\nNovidades:\n" + notas)
                        + "\n\nInstalar agora? Suas fichas ficam no servidor e não são afetadas.")
                .setCancelable(false)
                .setPositiveButton("Instalar", (d, w) -> instalar(a))
                .setNegativeButton("Depois", (d, w) -> p.edit().putString("adiado_em", hoje()).apply())
                .show();
    }

    private static boolean podeInstalar(Activity a) {
        return Build.VERSION.SDK_INT < 26 || a.getPackageManager().canRequestPackageInstalls();
    }

    private static void instalar(final Activity a) {
        if (pendente(a.getApplicationContext()) == null) return;
        if (!podeInstalar(a)) {
            new AlertDialog.Builder(a)
                    .setTitle("Permitir a instalação")
                    .setMessage("Para instalar a atualização, o Android precisa da sua "
                            + "autorização. Na próxima tela ative \"Permitir desta fonte\" e "
                            + "depois volte ao Keep Policial.")
                    .setPositiveButton("Abrir configuração", (d, w) -> {
                        aguardandoPermissao = true;
                        try {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName())));
                        } catch (Exception e) {
                            aguardandoPermissao = false;
                            Toast.makeText(a, "Abra as configurações do Android e permita que o "
                                    + "Keep Policial instale apps.", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            return;
        }
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(ApkProvider.URI, TIPO_APK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(a, "Não foi possível abrir o instalador: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    // -- estado ----------------------------------------------------------------------

    /** APK baixado e ainda nao instalado, ou null. */
    static File pendente(Context app) {
        String caminho = prefs(app).getString("apk_arquivo", null);
        if (caminho == null) return null;
        File f = new File(caminho);
        return f.isFile() ? f : null;
    }

    private static void limparSeJaInstalada(Context app, SharedPreferences p) {
        if (!p.contains("apk_arquivo")) return;
        if (p.getLong("apk_codigo", 0) <= BuildConfig.VERSION_CODE || pendente(app) == null) {
            apagarConteudo(new File(app.getCacheDir(), "atualizacao"));
            p.edit().remove("apk_arquivo").remove("apk_versao").remove("apk_codigo")
                    .remove("apk_notas").remove("adiado_em").apply();
        }
    }

    private static void apagarConteudo(File dir) {
        File[] arquivos = dir.listFiles();
        if (arquivos == null) return;
        //noinspection ResultOfMethodCallIgnored
        for (File f : arquivos) f.delete();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String hoje() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    /** Compara "v3.1" com "3.0" numero a numero. */
    static int comparar(String a, String b) {
        int[] x = numeros(a), y = numeros(b);
        for (int i = 0; i < 4; i++) {
            if (x[i] != y[i]) return Integer.compare(x[i], y[i]);
        }
        return 0;
    }

    private static int[] numeros(String s) {
        int[] r = new int[4];
        Matcher m = Pattern.compile("\\d+").matcher(s == null ? "" : s);
        for (int i = 0; i < 4 && m.find(); i++) {
            try {
                r[i] = Integer.parseInt(m.group());
            } catch (NumberFormatException ignorado) {
            }
        }
        return r;
    }
}
