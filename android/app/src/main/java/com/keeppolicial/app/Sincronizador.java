package com.keeppolicial.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Leva ao servidor o que foi feito offline (fila) e traz o que mudou la.
 *
 * <p>Ordem: 1) envia a fila na ordem em que foi criada; 2) baixa as fichas alteradas
 * desde o ultimo ponto; 3) pre-carrega as fotos de capa. Falha de rede interrompe e
 * tenta de novo depois; erro "definitivo" do servidor (dado recusado) nao e repetido
 * nem apagado: fica marcado na fila para o usuario decidir.</p>
 */
final class Sincronizador {
    interface Ouvinte {
        void mudou();
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final List<Ouvinte> OUVINTES = new CopyOnWriteArrayList<>();
    private static final Set<Long> EM_ENVIO = Collections.synchronizedSet(new HashSet<Long>());
    private static final List<String> AVISOS = Collections.synchronizedList(new ArrayList<String>());
    private static final Object CONTROLE = new Object();
    private static boolean rodando, repetir;

    static volatile boolean sincronizando;
    static volatile String ultimoErro;
    /** O servidor ainda e anterior a 3.2 (sem sincronizacao): nada e enviado nem baixado. */
    static volatile boolean servidorAntigo;
    static final String MSG_SERVIDOR_ANTIGO = "Servidor desatualizado: precisa estar na versão 3.2 para "
            + "o modo offline. Peça ao comandante para atualizar o servidor (painel > Atualizações). "
            + "Suas alterações ficam guardadas no aparelho.";

    private Sincronizador() {
    }

    static void ouvir(Ouvinte o) {
        OUVINTES.add(o);
    }

    static void deixarDeOuvir(Ouvinte o) {
        OUVINTES.remove(o);
    }

    static boolean emEnvio(long seq) {
        return EM_ENVIO.contains(seq);
    }

    /** Avisos para mostrar ao usuario (ex.: conflito de edicao). Esvazia a lista. */
    static List<String> pegarAvisos() {
        synchronized (AVISOS) {
            List<String> copia = new ArrayList<>(AVISOS);
            AVISOS.clear();
            return copia;
        }
    }

    private static void notificar() {
        for (Ouvinte o : OUVINTES) o.mudou();
    }

    static void agendar(Context c) {
        final Context app = c.getApplicationContext();
        if (!Sessao.ativa()) return;
        synchronized (CONTROLE) {
            if (rodando) {
                repetir = true;
                return;
            }
            rodando = true;
        }
        EXEC.execute(() -> {
            boolean outra;
            do {
                synchronized (CONTROLE) {
                    repetir = false;
                }
                try {
                    rodada(app);
                } catch (Throwable t) {
                    ultimoErro = "Falha inesperada na sincronização.";
                    Falhas.registrarLocal(app, "Sincronização: " + t);
                }
                synchronized (CONTROLE) {
                    outra = repetir;
                    if (!outra) rodando = false;
                }
            } while (outra);
            sincronizando = false;
            notificar();
        });
    }

    private static void rodada(Context app) {
        ArmazemLocal a = ArmazemLocal.get(app);
        if (!Sessao.ativa()) return;
        if (!Rede.disponivel(app)) {
            ultimoErro = "Sem conexão";
            Diag.d("Sync", "sem rede: rodada adiada");
            return;
        }
        if (!garantirSessao(app)) return;
        // Servidor antigo nao tem /api/sync nem ids criados no app: enviar causaria duplicatas.
        String versaoServidor = Api.versaoDoServidor();
        if (versaoServidor != null && Atualizador.comparar("3.2", versaoServidor) > 0) {
            servidorAntigo = true;
            ultimoErro = MSG_SERVIDOR_ANTIGO;
            Diag.w("Sync", "servidor " + versaoServidor + " e anterior a 3.2: sincronizacao suspensa", null);
            notificar();
            return;
        }
        servidorAntigo = false;
        sincronizando = true;
        notificar();
        long inicio = System.currentTimeMillis();
        try {
            Diag.i("Sync", "rodada iniciada: " + a.contarPendentes(Repositorio.dono()) + " pendentes");
            enviarFila(app, a);
            receber(app, a);
            a.meta("ultima_sync", String.valueOf(System.currentTimeMillis()));
            ultimoErro = null;
            Diag.i("Sync", "rodada concluida em " + (System.currentTimeMillis() - inicio) + " ms");
        } catch (Api.ErroApi e) {
            Diag.w("Sync", "servidor respondeu HTTP " + e.status, e);
            ultimoErro = e.status == 401 ? "Sessão expirada: entre novamente para sincronizar."
                    : e.getMessage();
        } catch (IOException e) {
            Diag.w("Sync", "rodada interrompida (rede/armazenamento)", e);
            ultimoErro = e.getMessage() != null ? e.getMessage() : "Falha de conexão";
        } finally {
            sincronizando = false;
            notificar();
        }
    }

    /** Em sessao offline, tenta entrar de verdade (a senha so existe na memoria). */
    private static boolean garantirSessao(Context app) {
        if (!Sessao.offline) return true;
        if (Sessao.senhaMemoria == null) {
            ultimoErro = "Entre com conexão para sincronizar.";
            return false;
        }
        try {
            Api.login(Sessao.servidor, Sessao.usuario, Sessao.senhaMemoria);
            Sessao.guardar(app);
            return true;
        } catch (IOException e) {
            ultimoErro = e.getMessage();
            return false;
        }
    }

    // -- envio ---------------------------------------------------------------------

    private static void enviarFila(Context app, ArmazemLocal a) throws IOException {
        String dono = Repositorio.dono();
        List<ArmazemLocal.Op> consultas = new ArrayList<>();
        for (ArmazemLocal.Op op : a.fila(dono)) {
            if (op.permanente) continue;
            if (op.tipo.equals("CONSULTA")) {
                consultas.add(op);
                continue;
            }
            EM_ENVIO.add(op.seq);
            try {
                executar(app, a, op, dono);
            } catch (Api.ErroApi e) {
                if (e.status == 401) throw e;
                if (e.status >= 400 && e.status < 500 && e.status != 408 && e.status != 429) {
                    // O servidor recusou este dado: nao adianta repetir nem pode sumir em silencio.
                    Diag.w("Sync", "op " + op.tipo + " (seq " + op.seq + ") RECUSADA, HTTP " + e.status
                            + " - marcada com erro", e);
                    a.marcarErro(op.seq, e.getMessage(), true);
                    AVISOS.add("Uma alteração foi recusada pelo servidor: " + e.getMessage());
                    continue;
                }
                throw e;
            } finally {
                EM_ENVIO.remove(op.seq);
            }
        }
        enviarConsultas(a, consultas);
    }

    private static void enviarConsultas(ArmazemLocal a, List<ArmazemLocal.Op> consultas)
            throws IOException {
        for (int i = 0; i < consultas.size(); i += 200) {
            List<ArmazemLocal.Op> lote = consultas.subList(i, Math.min(i + 200, consultas.size()));
            JSONArray itens = new JSONArray();
            try {
                for (ArmazemLocal.Op o : lote) {
                    itens.put(new JSONObject().put("id", o.fichaId).put("em", o.dados.optString("em")));
                }
            } catch (JSONException e) {
                throw new IOException(e);
            }
            Api.registrarConsultas(itens);
            for (ArmazemLocal.Op o : lote) a.removerOp(o.seq);
        }
    }

    private static void executar(Context app, ArmazemLocal a, ArmazemLocal.Op op, String dono)
            throws IOException {
        try {
            switch (op.tipo) {
                case "SALVAR":
                    enviarSalvar(app, a, op, dono);
                    break;
                case "FOTO_ADD":
                    enviarFoto(a, op, dono);
                    break;
                case "FOTO_DEL":
                    try {
                        Api.excluirFoto(op.dados.optString("foto"));
                    } catch (Api.ErroApi e) {
                        if (e.status != 404) throw e;
                    }
                    a.apagarFoto(Repositorio.PASTA_CACHE, op.dados.optString("foto"));
                    finalizar(a, op, dono);
                    break;
                case "EXCLUIR":
                    try {
                        Api.excluir(op.fichaId);
                    } catch (Api.ErroApi e) {
                        if (e.status != 404) throw e;
                    }
                    a.removerFicha(op.fichaId);
                    a.removerOp(op.seq);
                    break;
                default:
                    a.removerOp(op.seq);
            }
        } catch (JSONException e) {
            Diag.e("Sync", "dados da op " + op.tipo + " invalidos (seq " + op.seq + ")", e);
            a.marcarErro(op.seq, "Dados da alteração inválidos", true);
        }
    }

    private static void enviarSalvar(Context app, ArmazemLocal a, ArmazemLocal.Op op, String dono)
            throws IOException, JSONException {
        JSONObject ficha = op.dados.getJSONObject("ficha");
        boolean novo = op.dados.optBoolean("novo");
        String base = op.dados.optString("base");
        JSONObject corpo = new JSONObject();
        for (String k : new String[]{"nome", "alcunha", "cpf", "rg", "endereco", "pai", "mae",
                "comportamento", "observacoes", "passagens", "veiculos", "cnh_numero",
                "cnh_categoria", "cnh_validade", "status"}) {
            if (ficha.has(k)) corpo.put(k, ficha.get(k));
        }
        JSONObject resp;
        if (novo) {
            corpo.put("id", op.fichaId);
            resp = Api.salvar(null, corpo);
        } else {
            if (!base.isEmpty()) corpo.put("base_atualizado_em", base);
            resp = Api.salvar(op.fichaId, corpo);
        }
        Diag.d("Sync", "SALVAR " + (novo ? "criou" : "atualizou") + " ficha (seq " + op.seq + ")");
        if (resp.optBoolean("conflito")) {
            Diag.w("Sync", "CONFLITO de edicao: o servidor tinha versao mais nova; a local foi aplicada", null);
            AVISOS.add("A ficha de " + resp.optString("nome", "(sem nome)") + " tinha sido alterada "
                    + "por outra pessoa. Sua versão foi aplicada e a anterior ficou no histórico.");
        }
        synchronized (Repositorio.TRAVA) {
            a.removerOp(op.seq);
            // Se o usuario editou de novo durante o envio, a copia local mais nova prevalece
            // e a proxima alteracao parte da versao que o servidor acabou de gravar.
            ArmazemLocal.Op proximo = null;
            for (ArmazemLocal.Op o : a.fila(dono)) {
                if (o.tipo.equals("SALVAR") && o.fichaId.equals(op.fichaId) && !o.permanente) proximo = o;
            }
            if (proximo != null) {
                proximo.dados.put("novo", false).put("base", resp.optString("atualizado_em"));
                a.atualizarOp(proximo.seq, proximo.dados);
                return;
            }
            resp.remove("conflito");
            a.gravarFicha(comFotosPendentes(a, dono, resp), a.fichaTemFila(op.fichaId, dono));
        }
    }

    /** A ficha vinda do servidor nao conhece as fotos ainda por enviar: reinclui as locais. */
    private static JSONObject comFotosPendentes(ArmazemLocal a, String dono, JSONObject f)
            throws JSONException {
        String id = f.getString("id");
        JSONArray fotos = new JSONArray();
        Set<String> remover = new HashSet<>();
        List<String> locais = new ArrayList<>();
        for (ArmazemLocal.Op o : a.fila(dono)) {
            if (!o.fichaId.equals(id) || o.permanente) continue;
            if (o.tipo.equals("FOTO_DEL")) remover.add(o.dados.optString("foto"));
            if (o.tipo.equals("FOTO_ADD")) locais.add(o.dados.optString("local"));
        }
        JSONArray atuais = f.optJSONArray("fotos");
        for (int i = 0; atuais != null && i < atuais.length(); i++) {
            if (!remover.contains(atuais.optString(i))) fotos.put(atuais.optString(i));
        }
        for (String l : locais) fotos.put(l);
        f.put("fotos", fotos);
        return f;
    }

    private static void enviarFoto(ArmazemLocal a, ArmazemLocal.Op op, String dono)
            throws IOException, JSONException {
        String local = op.dados.optString("local");
        byte[] jpeg = a.lerFoto(Repositorio.PASTA_PEND, local.replace("local:", ""));
        if (jpeg == null) {
            Diag.e("Sync", "foto pendente sumiu do aparelho (seq " + op.seq + ")", null);
            a.marcarErro(op.seq, "Foto perdida no aparelho", true);
            return;
        }
        String idServidor = Api.enviarFoto(op.fichaId, jpeg);
        synchronized (Repositorio.TRAVA) {
            a.gravarFoto(Repositorio.PASTA_CACHE, idServidor, jpeg);
            a.apagarFoto(Repositorio.PASTA_PEND, local.replace("local:", ""));
            JSONObject f = a.ficha(op.fichaId);
            if (f != null) {
                JSONArray fotos = f.optJSONArray("fotos");
                JSONArray nova = new JSONArray();
                for (int i = 0; fotos != null && i < fotos.length(); i++) {
                    nova.put(local.equals(fotos.optString(i)) ? idServidor : fotos.optString(i));
                }
                f.put("fotos", nova);
                a.removerOp(op.seq);
                a.gravarFicha(f, a.fichaTemFila(op.fichaId, dono));
            } else {
                a.removerOp(op.seq);
            }
        }
    }

    private static void finalizar(ArmazemLocal a, ArmazemLocal.Op op, String dono) throws IOException {
        synchronized (Repositorio.TRAVA) {
            a.removerOp(op.seq);
            JSONObject f = a.ficha(op.fichaId);
            if (f != null) {
                JSONArray fotos = f.optJSONArray("fotos");
                JSONArray nova = new JSONArray();
                String removida = op.dados.optString("foto");
                for (int i = 0; fotos != null && i < fotos.length(); i++) {
                    if (!removida.equals(fotos.optString(i))) nova.put(fotos.optString(i));
                }
                try {
                    f.put("fotos", nova);
                } catch (JSONException ignorado) {
                }
                a.gravarFicha(f, a.fichaTemFila(op.fichaId, dono));
            }
        }
    }

    // -- recebimento ---------------------------------------------------------------

    private static void receber(Context app, ArmazemLocal a) throws IOException {
        String dono = Repositorio.dono();
        String cursor = a.meta("cursor");
        String excl = a.meta("excl");
        if (cursor == null) cursor = "";
        int exclusao = excl == null ? 0 : Integer.parseInt(excl);
        boolean mais;
        do {
            JSONObject r = Api.sincronizar(cursor, exclusao);
            JSONArray fichas = r.optJSONArray("fichas");
            for (int i = 0; fichas != null && i < fichas.length(); i++) {
                JSONObject f = fichas.optJSONObject(i);
                if (f == null) continue;
                synchronized (Repositorio.TRAVA) {
                    // Ficha com alteracao local ainda por enviar nao e sobrescrita.
                    if (!a.fichaTemFila(f.optString("id"), dono)) a.gravarFicha(f, false);
                }
            }
            JSONArray excluidas = r.optJSONArray("excluidas");
            for (int i = 0; excluidas != null && i < excluidas.length(); i++) {
                String id = excluidas.optString(i);
                synchronized (Repositorio.TRAVA) {
                    if (!a.fichaTemFila(id, dono)) a.removerFicha(id);
                }
            }
            cursor = r.optString("cursor", cursor);
            exclusao = r.optInt("excl", exclusao);
            a.meta("cursor", cursor);
            a.meta("excl", String.valueOf(exclusao));
            mais = r.optBoolean("mais");
            notificar();
        } while (mais);
        precarregarCapas(app, a);
    }

    /** Baixa a foto de capa das fichas (ate 40 por rodada) para o app abrir bem sem internet. */
    private static void precarregarCapas(Context app, ArmazemLocal a) {
        int baixadas = 0;
        for (JSONObject f : a.todas()) {
            JSONArray fotos = f.optJSONArray("fotos");
            if (fotos == null || fotos.length() == 0) continue;
            String capa = fotos.optString(0);
            if (capa.startsWith("local:") || a.temFoto(Repositorio.PASTA_CACHE, capa)) continue;
            try {
                Repositorio.fotoBytes(app, capa);
            } catch (IOException e) {
                return;  // sem conexao: tenta na proxima
            }
            if (++baixadas >= 40) return;
        }
    }
}
