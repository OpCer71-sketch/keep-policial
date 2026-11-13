package com.keeppolicial.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copia local das fichas (modo offline) e fila de envio ao servidor.
 *
 * <p>Tudo que tem dado de pessoa (ficha, conteudo da fila, fotos) e gravado CIFRADO
 * pelo {@link Cofre}. As fichas ficam tambem em memoria enquanto o app roda, para a
 * busca e os filtros serem instantaneos.</p>
 */
final class ArmazemLocal extends SQLiteOpenHelper {
    private static final int VERSAO_BANCO = 1;
    private static ArmazemLocal instancia;

    /** Operacao pendente de envio ao servidor. */
    static final class Op {
        long seq;
        String dono, tipo, fichaId, erro;
        boolean permanente;
        JSONObject dados;
    }

    private final Context app;
    private final Map<String, JSONObject> cache = new LinkedHashMap<>();
    private boolean carregado;
    /** true quando os dados locais estavam ilegiveis e foram descartados (avisar o usuario). */
    private volatile boolean perdaDetectada;
    private volatile int perdidas;

    private ArmazemLocal(Context c) {
        super(c.getApplicationContext(), "keep_local.db", null, VERSAO_BANCO);
        app = c.getApplicationContext();
    }

    static synchronized ArmazemLocal get(Context c) {
        if (instancia == null) instancia = new ArmazemLocal(c);
        return instancia;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE fichas (id TEXT PRIMARY KEY, atualizado_em TEXT, "
                + "pendente INTEGER NOT NULL DEFAULT 0, dados BLOB NOT NULL)");
        db.execSQL("CREATE TABLE fila (seq INTEGER PRIMARY KEY AUTOINCREMENT, dono TEXT NOT NULL, "
                + "tipo TEXT NOT NULL, ficha_id TEXT, dados BLOB, erro TEXT, "
                + "permanente INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE meta (chave TEXT PRIMARY KEY, valor TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int antiga, int nova) {
        // versao 1: nada a migrar
    }

    // -- fichas ----------------------------------------------------------------

    private synchronized void carregarTudo() {
        if (carregado) return;
        SQLiteDatabase db = getWritableDatabase();
        List<String> ruins = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id, dados FROM fichas", null)) {
            while (c.moveToNext()) {
                try {
                    JSONObject f = new JSONObject(new String(Cofre.decifrar(c.getBlob(1)),
                            StandardCharsets.UTF_8));
                    cache.put(c.getString(0), f);
                } catch (Cofre.FalhaCofre | JSONException e) {
                    ruins.add(c.getString(0));
                }
            }
        }
        if (!ruins.isEmpty()) descartarIlegiveis(db, ruins.size());
        carregado = true;
    }

    /**
     * Dados locais ilegiveis (ex.: a chave do Keystore foi perdida apos restaurar o
     * aparelho). Nao ha como recuperar: apaga a copia local, avisa e baixa tudo de novo.
     */
    private void descartarIlegiveis(SQLiteDatabase db, int quantidade) {
        int pend = 0;
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM fila", null)) {
            if (c.moveToFirst()) pend = c.getInt(0);
        }
        Diag.e("Armazem", "dados locais ilegiveis: " + quantidade + " fichas, " + pend
                + " envios pendentes serao descartados", null);
        db.delete("fichas", null, null);
        db.delete("fila", null, null);
        db.delete("meta", null, null);
        cache.clear();
        apagarArquivos("fotos_cache");
        apagarArquivos("fotos_pend");
        perdaDetectada = true;
        perdidas = pend;
        Falhas.registrarLocal(app, "Dados locais ilegíveis (" + quantidade + " fichas); "
                + pend + " envios pendentes perdidos");
    }

    boolean perdaDetectada() {
        return perdaDetectada;
    }

    int perdidas() {
        return perdidas;
    }

    void limparAvisoPerda() {
        perdaDetectada = false;
    }

    synchronized List<JSONObject> todas() {
        carregarTudo();
        return new ArrayList<>(cache.values());
    }

    synchronized JSONObject ficha(String id) {
        carregarTudo();
        return cache.get(id);
    }

    synchronized int quantidade() {
        carregarTudo();
        return cache.size();
    }

    synchronized void gravarFicha(JSONObject f, boolean pendente) throws IOException {
        carregarTudo();
        try {
            ContentValues v = new ContentValues();
            v.put("id", f.getString("id"));
            v.put("atualizado_em", f.optString("atualizado_em"));
            v.put("pendente", pendente ? 1 : 0);
            v.put("dados", Cofre.cifrar(f.toString().getBytes(StandardCharsets.UTF_8)));
            getWritableDatabase().insertWithOnConflict("fichas", null, v,
                    SQLiteDatabase.CONFLICT_REPLACE);
            cache.put(f.getString("id"), f);
        } catch (Cofre.FalhaCofre | JSONException e) {
            Diag.e("Armazem", "gravar ficha: cofre/json", e);
            throw new IOException("Não foi possível gravar a ficha no aparelho.", e);
        } catch (android.database.sqlite.SQLiteException e) {
            throw erroDeBanco("gravar ficha", e);
        }
    }

    synchronized boolean pendente(String id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT pendente FROM fichas WHERE id=?", new String[]{id})) {
            return c.moveToFirst() && c.getInt(0) == 1;
        }
    }

    synchronized void removerFicha(String id) {
        carregarTudo();
        getWritableDatabase().delete("fichas", "id=?", new String[]{id});
        cache.remove(id);
    }

    // -- fila de envio ---------------------------------------------------------

    synchronized long enfileirar(String dono, String tipo, String fichaId, JSONObject dados)
            throws IOException {
        try {
            ContentValues v = new ContentValues();
            v.put("dono", dono);
            v.put("tipo", tipo);
            v.put("ficha_id", fichaId);
            if (dados != null) {
                v.put("dados", Cofre.cifrar(dados.toString().getBytes(StandardCharsets.UTF_8)));
            }
            long seq = getWritableDatabase().insertOrThrow("fila", null, v);
            Diag.d("Armazem", "fila +" + tipo + " (seq " + seq + ")");
            return seq;
        } catch (Cofre.FalhaCofre e) {
            Diag.e("Armazem", "enfileirar: cofre", e);
            throw new IOException("Não foi possível guardar a alteração no aparelho.", e);
        } catch (android.database.sqlite.SQLiteException e) {
            throw erroDeBanco("enfileirar " + tipo, e);
        }
    }

    synchronized void atualizarOp(long seq, JSONObject dados) throws IOException {
        try {
            ContentValues v = new ContentValues();
            v.put("dados", Cofre.cifrar(dados.toString().getBytes(StandardCharsets.UTF_8)));
            getWritableDatabase().update("fila", v, "seq=?", new String[]{String.valueOf(seq)});
        } catch (Cofre.FalhaCofre e) {
            Diag.e("Armazem", "atualizar op: cofre", e);
            throw new IOException("Não foi possível guardar a alteração no aparelho.", e);
        } catch (android.database.sqlite.SQLiteException e) {
            throw erroDeBanco("atualizar fila", e);
        }
    }

    synchronized List<Op> fila(String dono) {
        List<Op> lista = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT seq, dono, tipo, ficha_id, dados, erro, permanente FROM fila "
                        + "WHERE dono=? ORDER BY seq", new String[]{dono})) {
            while (c.moveToNext()) {
                Op o = new Op();
                o.seq = c.getLong(0);
                o.dono = c.getString(1);
                o.tipo = c.getString(2);
                o.fichaId = c.getString(3);
                o.erro = c.getString(5);
                o.permanente = c.getInt(6) == 1;
                try {
                    byte[] b = c.getBlob(4);
                    o.dados = b == null ? new JSONObject()
                            : new JSONObject(new String(Cofre.decifrar(b), StandardCharsets.UTF_8));
                } catch (Cofre.FalhaCofre | JSONException e) {
                    o.dados = new JSONObject();
                    o.erro = "Conteúdo ilegível";
                    o.permanente = true;
                }
                lista.add(o);
            }
        }
        return lista;
    }

    synchronized int contarPendentes(String dono) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM fila WHERE dono=? AND permanente=0", new String[]{dono})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    synchronized int contarComErro(String dono) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM fila WHERE dono=? AND permanente=1", new String[]{dono})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    synchronized boolean fichaTemFila(String fichaId, String dono) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM fila WHERE ficha_id=? AND dono=? AND tipo<>'CONSULTA' LIMIT 1",
                new String[]{fichaId, dono})) {
            return c.moveToFirst();
        }
    }

    /** Ids das fichas com alteracao ainda nao enviada (para marcar na lista). */
    synchronized java.util.Set<String> fichasComFila(String dono) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT ficha_id FROM fila WHERE dono=? AND tipo<>'CONSULTA' "
                        + "AND permanente=0", new String[]{dono})) {
            while (c.moveToNext()) ids.add(c.getString(0));
        }
        return ids;
    }

    synchronized void removerOp(long seq) {
        getWritableDatabase().delete("fila", "seq=?", new String[]{String.valueOf(seq)});
    }

    synchronized void marcarErro(long seq, String mensagem, boolean permanente) {
        ContentValues v = new ContentValues();
        v.put("erro", mensagem);
        v.put("permanente", permanente ? 1 : 0);
        getWritableDatabase().update("fila", v, "seq=?", new String[]{String.valueOf(seq)});
    }

    /** Banco local com problema (disco cheio, arquivo corrompido): log + mensagem para o operador. */
    private IOException erroDeBanco(String operacao, android.database.sqlite.SQLiteException e) {
        boolean cheio = e instanceof android.database.sqlite.SQLiteFullException;
        Diag.e("Armazem", "banco local: " + operacao + (cheio ? " (SEM ESPACO)" : ""), e);
        Falhas.registrarLocal(app, "Banco local falhou em '" + operacao + "': " + e.getClass().getSimpleName());
        return new IOException(cheio
                ? "O celular está sem espaço livre. Libere espaço e salve de novo."
                : "Falha no armazenamento do aparelho. Tente de novo; se continuar, avise o suporte.", e);
    }

    // -- metadados -------------------------------------------------------------

    synchronized String meta(String chave) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT valor FROM meta WHERE chave=?", new String[]{chave})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    synchronized void meta(String chave, String valor) {
        ContentValues v = new ContentValues();
        v.put("chave", chave);
        v.put("valor", valor);
        getWritableDatabase().insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Troca de usuario/servidor: apaga a copia local das fichas (a fila de cada dono e mantida). */
    synchronized void limparFichas() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("fichas", null, null);
        db.delete("meta", "chave IN ('cursor','excl')", null);
        cache.clear();
        carregado = true;
        apagarArquivos("fotos_cache");
    }

    /** Libera a copia em memoria (ao encerrar a sessao). Os dados cifrados continuam no disco. */
    synchronized void descarregarMemoria() {
        cache.clear();
        carregado = false;
    }

    // -- fotos (arquivos cifrados) ---------------------------------------------

    private File pasta(String nome) {
        File d = new File(app.getFilesDir(), nome);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private void apagarArquivos(String nome) {
        File[] arqs = pasta(nome).listFiles();
        if (arqs != null) for (File f : arqs) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private static String seguro(String id) {
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static final long MAX_CACHE_FOTOS = 150L * 1024 * 1024;

    void gravarFoto(String pasta, String id, byte[] jpeg) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(pasta(pasta), seguro(id)))) {
            out.write(Cofre.cifrar(jpeg));
        } catch (Cofre.FalhaCofre e) {
            throw new IOException("Não foi possível gravar a foto no aparelho.", e);
        }
        if ("fotos_cache".equals(pasta)) limitarCache();
    }

    /** Mantem o cache de fotos abaixo de ~150 MB apagando as mais antigas (volta a baixar se precisar). */
    private void limitarCache() {
        File[] arqs = pasta("fotos_cache").listFiles();
        if (arqs == null) return;
        long total = 0;
        for (File f : arqs) total += f.length();
        if (total <= MAX_CACHE_FOTOS) return;
        java.util.Arrays.sort(arqs, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
        for (File f : arqs) {
            if (total <= MAX_CACHE_FOTOS * 8 / 10) break;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** Foto guardada no aparelho ou null (inexistente ou ilegivel). */
    byte[] lerFoto(String pasta, String id) {
        File f = new File(pasta(pasta), seguro(id));
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            return Cofre.decifrar(b);
        } catch (IOException | Cofre.FalhaCofre e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
    }

    void apagarFoto(String pasta, String id) {
        //noinspection ResultOfMethodCallIgnored
        new File(pasta(pasta), seguro(id)).delete();
    }

    boolean temFoto(String pasta, String id) {
        return new File(pasta(pasta), seguro(id)).exists();
    }
}
