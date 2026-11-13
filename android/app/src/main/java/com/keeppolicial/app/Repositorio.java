package com.keeppolicial.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ponto unico de acesso as fichas para as telas. As telas leem e gravam AQUI (copia
 * local cifrada); o {@link Sincronizador} leva as alteracoes ao servidor quando ha
 * internet. Assim o app funciona igual com ou sem conexao.
 */
final class Repositorio {
    static final String PASTA_CACHE = "fotos_cache", PASTA_PEND = "fotos_pend";
    /** Protege as decisoes sobre a fila (nunca e mantida durante chamadas de rede). */
    static final Object TRAVA = new Object();

    private static final Map<String, Long> ULTIMA_CONSULTA = new HashMap<>();
    private static final Map<String, String> BUSCA = new HashMap<>();

    private Repositorio() {
    }

    static ArmazemLocal arm(Context c) {
        return ArmazemLocal.get(c);
    }

    /** Identifica quem e dono dos dados locais: usuario + servidor (pela impressao digital). */
    static String dono() {
        if (Sessao.servidor == null || Sessao.usuario == null) return "";
        return Sessao.usuario + "@" + Sessao.servidor.impressao;
    }

    static String agora() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    /** Chamado apos o login: se mudou o usuario/servidor, a copia local anterior e apagada. */
    static void aoEntrar(Context c) {
        ArmazemLocal a = arm(c);
        String atual = a.meta("dono"), novo = dono();
        if (atual != null && !atual.equals(novo)) a.limparFichas();
        a.meta("dono", novo);
        synchronized (BUSCA) {
            BUSCA.clear();
        }
    }

    static void aoSair(Context c) {
        arm(c).descarregarMemoria();
        synchronized (BUSCA) {
            BUSCA.clear();
        }
    }

    // -- leitura ---------------------------------------------------------------

    static JSONObject obter(Context c, String id) {
        return arm(c).ficha(id);
    }

    /** Registra que o policial abriu a ficha (vai ao servidor na proxima sincronizacao). */
    static void registrarConsulta(Context c, String id) {
        long agora = System.currentTimeMillis();
        synchronized (ULTIMA_CONSULTA) {
            Long antes = ULTIMA_CONSULTA.get(id);
            if (antes != null && agora - antes < 60_000) return;  // reabrir/girar a tela nao repete
            ULTIMA_CONSULTA.put(id, agora);
        }
        try {
            arm(c).enfileirar(dono(), "CONSULTA", id, new JSONObject().put("em", agora()));
            Sincronizador.agendar(c);
        } catch (IOException | JSONException ignorado) {
            // a consulta nao deixa de funcionar por falha ao anotar; o servidor tambem
            // audita as buscas quando o app esta online
        }
    }

    // -- filtros e busca -------------------------------------------------------

    /** Criterios de busca e filtros avancados. */
    static final class Filtro {
        String termo = "";
        /** Comportamentos aceitos; vazio = todos. "" representa "sem classificação". */
        final Set<String> comportamentos = new HashSet<>();
        String status = "";           // "" = qualquer
        String cnhCategoria = "";     // "" = qualquer
        boolean cnhVencida;
        boolean comVeiculo;
        boolean somentePendentes;
        /** 0 = qualquer data; 1 = hoje; 2 = 7 dias; 3 = 30 dias; 4 = intervalo. */
        int periodo;
        /** true: filtra pela data de ATUALIZACAO; false: pela de CADASTRO. */
        boolean porAtualizacao = true;
        long de, ate;                 // intervalo (ms), quando periodo == 4
        /** 0 = mais recentes; 1 = periculosidade; 2 = nome A-Z. */
        int ordem;

        int ativos() {
            int n = 0;
            if (!comportamentos.isEmpty()) n++;
            if (!status.isEmpty()) n++;
            if (!cnhCategoria.isEmpty()) n++;
            if (cnhVencida) n++;
            if (comVeiculo) n++;
            if (somentePendentes) n++;
            if (periodo != 0) n++;
            return n;
        }

        void limpar() {
            comportamentos.clear();
            status = "";
            cnhCategoria = "";
            cnhVencida = comVeiculo = somentePendentes = false;
            periodo = 0;
            porAtualizacao = true;
            de = ate = 0;
        }
    }

    static String normalizar(String t) {
        String n = Normalizer.normalize(t == null ? "" : t, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    private static String digitos(String t) {
        return t == null ? "" : t.replaceAll("\\D", "");
    }

    /** Texto pesquisavel da ficha (mesma ideia do servidor: nome, CPF, RG, placa, CNH...). */
    private static String busca(JSONObject f) {
        String chave = f.optString("id") + "|" + f.optString("atualizado_em");
        synchronized (BUSCA) {
            String b = BUSCA.get(chave);
            if (b != null) return b;
            StringBuilder sb = new StringBuilder();
            sb.append(normalizar(f.optString("nome"))).append(' ')
                    .append(normalizar(f.optString("alcunha"))).append(' ')
                    .append(digitos(f.optString("cpf"))).append(' ')
                    .append(digitos(f.optString("rg"))).append(' ')
                    .append(normalizar(f.optString("rg"))).append(' ')
                    .append(normalizar(f.optString("mae"))).append(' ')
                    .append(digitos(f.optString("cnh_numero"))).append(' ');
            JSONArray ps = f.optJSONArray("passagens");
            for (int i = 0; ps != null && i < ps.length(); i++) {
                sb.append(normalizar(ps.optJSONObject(i).optString("reds"))).append(' ');
            }
            JSONArray vs = f.optJSONArray("veiculos");
            for (int i = 0; vs != null && i < vs.length(); i++) {
                JSONObject v = vs.optJSONObject(i);
                sb.append(normalizar(v.optString("placa") + " " + v.optString("modelo"))).append(' ');
            }
            b = sb.toString();
            BUSCA.put(chave, b);
            return b;
        }
    }

    private static int peso(String comportamento) {
        switch (comportamento) {
            case "RESISTENTE_ATIVO":
                return 0;
            case "RESISTENTE_PASSIVO":
                return 1;
            case "OBEDIENTE":
                return 2;
            default:
                return 3;
        }
    }

    private static long dataMs(String texto) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(texto).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Aplica busca + filtros + ordenacao sobre a copia local. */
    static List<JSONObject> listar(Context c, Filtro filtro) {
        List<JSONObject> saida = new ArrayList<>();
        String termo = normalizar(filtro.termo).trim();
        String[] palavras = termo.isEmpty() ? new String[0] : termo.split("\\s+");
        long agora = System.currentTimeMillis(), dia = 24L * 3600 * 1000;
        long inicio = 0, fim = Long.MAX_VALUE;
        if (filtro.periodo == 1) {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            inicio = cal.getTimeInMillis();
        } else if (filtro.periodo == 2) inicio = agora - 7 * dia;
        else if (filtro.periodo == 3) inicio = agora - 30 * dia;
        else if (filtro.periodo == 4) {
            inicio = filtro.de;
            fim = filtro.ate > 0 ? filtro.ate + dia - 1 : Long.MAX_VALUE;
        }
        ArmazemLocal a = arm(c);
        String dono = dono();
        for (JSONObject f : a.todas()) {
            if (palavras.length > 0) {
                String b = busca(f);
                boolean ok = true;
                for (String p : palavras) {
                    String semPonto = p.replaceAll("[^0-9a-z]", "");
                    if (!b.contains(p) && (semPonto.isEmpty() || !b.contains(semPonto))) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
            }
            if (!filtro.comportamentos.isEmpty()
                    && !filtro.comportamentos.contains(f.optString("comportamento"))) continue;
            if (!filtro.status.isEmpty() && !filtro.status.equals(f.optString("status"))) continue;
            if (!filtro.cnhCategoria.isEmpty()
                    && !filtro.cnhCategoria.equals(f.optString("cnh_categoria"))) continue;
            if (filtro.cnhVencida && !Cnh.vencida(f.optString("cnh_validade"))) continue;
            if (filtro.comVeiculo) {
                JSONArray vs = f.optJSONArray("veiculos");
                if (vs == null || vs.length() == 0) continue;
            }
            if (filtro.somentePendentes && !a.fichaTemFila(f.optString("id"), dono)) continue;
            if (filtro.periodo != 0) {
                long t = dataMs(f.optString(filtro.porAtualizacao ? "atualizado_em" : "criado_em"));
                if (t < inicio || t > fim) continue;
            }
            saida.add(f);
        }
        Comparator<JSONObject> porData = (x, y) ->
                y.optString("atualizado_em").compareTo(x.optString("atualizado_em"));
        if (filtro.ordem == 1) {
            Collections.sort(saida, (x, y) -> {
                int d = peso(x.optString("comportamento")) - peso(y.optString("comportamento"));
                return d != 0 ? d : porData.compare(x, y);
            });
        } else if (filtro.ordem == 2) {
            Collections.sort(saida, (x, y) -> normalizar(x.optString("nome"))
                    .compareTo(normalizar(y.optString("nome"))));
        } else {
            Collections.sort(saida, porData);
        }
        return saida;
    }

    // -- gravacao --------------------------------------------------------------

    /**
     * Grava a ficha NO APARELHO e a coloca na fila de envio. Funciona sem internet.
     *
     * @param fotosNovas    JPEGs ainda nao enviados
     * @param fotosRemovidas ids de fotos a remover (do servidor ou "local:...")
     */
    static JSONObject salvar(Context c, String idExistente, JSONObject corpo,
                             List<byte[]> fotosNovas, List<String> fotosRemovidas) throws IOException {
        ArmazemLocal a = arm(c);
        String dono = dono();
        if (dono.isEmpty()) throw new IOException("Sessão encerrada. Faça login novamente.");
        try {
            boolean novo = idExistente == null;
            String id = novo ? UUID.randomUUID().toString().replace("-", "") : idExistente;
            JSONObject antigo = novo ? null : a.ficha(id);
            JSONObject f = new JSONObject(corpo.toString());
            String agora = agora();
            f.put("id", id);
            if (antigo != null) {
                for (String k : new String[]{"criado_por", "criado_em"}) f.put(k, antigo.optString(k));
            } else {
                f.put("criado_por", Sessao.usuario).put("criado_em", agora);
            }
            f.put("atualizado_por", Sessao.usuario).put("atualizado_em", agora);

            // fotos: mantem as atuais, tira as removidas e acrescenta as novas (id "local:...")
            JSONArray fotos = new JSONArray();
            Set<String> tirar = new HashSet<>(fotosRemovidas);
            JSONArray antes = antigo == null ? null : antigo.optJSONArray("fotos");
            for (int i = 0; antes != null && i < antes.length(); i++) {
                if (!tirar.contains(antes.optString(i))) fotos.put(antes.optString(i));
            }
            List<String> idsNovos = new ArrayList<>();
            for (byte[] jpeg : fotosNovas) {
                String pid = UUID.randomUUID().toString().replace("-", "");
                a.gravarFoto(PASTA_PEND, pid, jpeg);
                idsNovos.add("local:" + pid);
                fotos.put("local:" + pid);
            }
            f.put("fotos", fotos);

            synchronized (TRAVA) {
                a.gravarFicha(f, true);
                // junta com um envio de ficha ainda nao iniciado (so vale a ultima versao)
                ArmazemLocal.Op existente = null;
                for (ArmazemLocal.Op o : a.fila(dono)) {
                    if (o.tipo.equals("SALVAR") && o.fichaId.equals(id) && !o.permanente
                            && !Sincronizador.emEnvio(o.seq)) {
                        existente = o;
                    }
                }
                JSONObject dados = new JSONObject().put("ficha", f);
                if (existente != null) {
                    dados.put("novo", existente.dados.optBoolean("novo"))
                            .put("base", existente.dados.optString("base"));
                    a.atualizarOp(existente.seq, dados);
                } else {
                    dados.put("novo", novo)
                            .put("base", antigo == null ? "" : antigo.optString("atualizado_em"));
                    a.enfileirar(dono, "SALVAR", id, dados);
                }
                for (String novoId : idsNovos) {
                    a.enfileirar(dono, "FOTO_ADD", id, new JSONObject().put("local", novoId));
                }
                for (String fid : fotosRemovidas) {
                    if (fid.startsWith("local:")) {
                        descartarFotoLocal(a, dono, id, fid);
                    } else {
                        a.enfileirar(dono, "FOTO_DEL", id, new JSONObject().put("foto", fid));
                    }
                }
            }
            Sincronizador.agendar(c);
            return f;
        } catch (JSONException e) {
            Diag.e("Repositorio", "ficha invalida ao salvar", e);
            throw new IOException("Ficha inválida.", e);
        } catch (IOException e) {
            Diag.e("Repositorio", "falha ao salvar a ficha no aparelho", e);
            throw e;
        }
    }

    /** Foto que ainda nao chegou ao servidor e foi removida: apaga o arquivo e o envio. */
    private static void descartarFotoLocal(ArmazemLocal a, String dono, String fichaId, String local)
            throws IOException {
        a.apagarFoto(PASTA_PEND, local.substring(6));
        for (ArmazemLocal.Op o : a.fila(dono)) {
            if (o.tipo.equals("FOTO_ADD") && local.equals(o.dados.optString("local"))) a.removerOp(o.seq);
        }
    }

    static void excluir(Context c, String id) throws IOException {
        ArmazemLocal a = arm(c);
        String dono = dono();
        try {
            synchronized (TRAVA) {
                boolean nuncaEnviada = false;
                for (ArmazemLocal.Op o : a.fila(dono)) {
                    if (!o.fichaId.equals(id)) continue;
                    if (o.tipo.equals("SALVAR") && o.dados.optBoolean("novo")) nuncaEnviada = true;
                    if (o.tipo.equals("FOTO_ADD")) a.apagarFoto(PASTA_PEND, o.dados.optString("local").replace("local:", ""));
                    if (!o.tipo.equals("CONSULTA")) a.removerOp(o.seq);
                }
                a.removerFicha(id);
                if (!nuncaEnviada) a.enfileirar(dono, "EXCLUIR", id, new JSONObject());
            }
        } catch (RuntimeException e) {
            throw new IOException("Não foi possível excluir a ficha.", e);
        }
        Sincronizador.agendar(c);
    }

    // -- fotos -----------------------------------------------------------------

    /** Bytes da foto: aparelho primeiro; se faltar e houver internet, baixa e guarda. */
    static byte[] fotoBytes(Context c, String idFoto) throws IOException {
        ArmazemLocal a = arm(c);
        if (idFoto.startsWith("local:")) return a.lerFoto(PASTA_PEND, idFoto.substring(6));
        byte[] b = a.lerFoto(PASTA_CACHE, idFoto);
        if (b != null) return b;
        if (!Rede.disponivel(c) || !Sessao.ativa() || Sessao.offline) return null;
        b = Api.baixarFoto(idFoto);
        try {
            a.gravarFoto(PASTA_CACHE, idFoto, b);
        } catch (IOException ignorado) {
            // sem espaco/chave: a foto aparece agora e sera baixada de novo depois
        }
        return b;
    }
}
