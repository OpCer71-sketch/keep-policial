package com.keeppolicial.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Quadro de fichas (estilo bloco de notas): cada cartao e um suspeito. */
public class ListaActivity extends BaseActivity {
    private static final int MENU_ATUALIZAR = 1, MENU_SESSAO = 2, MENU_SAIR = 3, MENU_IMPORTAR = 4,
            MENU_VERSAO = 5, MENU_ATUALIZACAO = 6, MENU_APARENCIA = 7, MENU_PENDENCIAS = 8, MENU_DIAGNOSTICO = 9;
    private static final int REQ_IMPORTAR = 20;

    private final List<JSONObject> fichas = new ArrayList<>();
    private final Repositorio.Filtro filtro = new Repositorio.Filtro();
    private java.util.Set<String> comPendencia = new java.util.HashSet<>();
    private Adaptador adaptador;
    private EditText etBusca;
    private TextView tvVazio, tvSincronia, tvFiltros;
    private View progresso;
    /** Atualiza a lista e a faixa de estado quando a sincronizacao progride. */
    private final Sincronizador.Ouvinte aoSincronizar = () -> ui.post(() -> {
        if (isFinishing() || isDestroyed()) return;
        atualizarFaixa();
        carregar();
        mostrarAvisosDeSincronia();
    });
    private int geracao;
    private final Runnable buscar = this::carregar;
    /** Ha um aviso do administrador na tela (evita abrir dois ao mesmo tempo). */
    private boolean exibindoAviso;

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        if (encerrando) return;
        setContentView(R.layout.activity_lista);
        if (getActionBar() != null) {
            getActionBar().setTitle(Sessao.destacamento);
            getActionBar().setSubtitle(Sessao.nome);
        }
        etBusca = findViewById(R.id.etBusca);
        tvSincronia = findViewById(R.id.tvSincronia);
        tvFiltros = findViewById(R.id.tvFiltros);
        findViewById(R.id.btnFiltros).setOnClickListener(v ->
                FiltrosDialog.mostrar(this, filtro, this::carregar));
        tvSincronia.setOnClickListener(v -> aoTocarFaixa());
        tvVazio = findViewById(R.id.tvVazio);
        progresso = findViewById(R.id.progresso);
        GridView grade = findViewById(R.id.grade);
        adaptador = new Adaptador();
        grade.setAdapter(adaptador);
        grade.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < fichas.size()) {
                startActivity(new Intent(this, FichaActivity.class)
                        .putExtra("id", fichas.get(pos).optString("id")));
            }
        });
        findViewById(R.id.btnNova).setOnClickListener(v ->
                startActivity(new Intent(this, FichaActivity.class)));
        etBusca.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                ui.removeCallbacks(buscar);
                ui.postDelayed(buscar, 400);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Sessao.ativa()) {
            Sincronizador.ouvir(aoSincronizar);
            carregar();
            atualizarFaixa();
            Sincronizador.agendar(this);
            if (!Sessao.offline) {
                buscarAvisos();
                Falhas.enviarPendente(this);
            }
            avisarPerdaDeDados();
        }
    }

    @Override
    protected void onPause() {
        Sincronizador.deixarDeOuvir(aoSincronizar);
        super.onPause();
    }

    // -- faixa de estado (offline / pendencias) ---------------------------------------

    private void atualizarFaixa() {
        if (!Sessao.ativa()) return;
        ArmazemLocal a = ArmazemLocal.get(this);
        String dono = Repositorio.dono();
        int pend = a.contarPendentes(dono), erros = a.contarComErro(dono);
        boolean semRede = Sessao.offline || !Rede.disponivel(this);
        if (Sincronizador.servidorAntigo) {
            tvSincronia.setText("⚠ Servidor desatualizado (precisa da versão 3.2). Toque para saber mais.");
            tvSincronia.setBackgroundColor(getColor(R.color.resistente_ativo));
            tvSincronia.setTextColor(0xFFFFFFFF);
        } else if (erros > 0) {
            tvSincronia.setText("⚠ " + erros + (erros == 1 ? " alteração recusada" : " alterações recusadas")
                    + " pelo servidor. Toque para ver.");
            tvSincronia.setBackgroundColor(getColor(R.color.resistente_ativo));
            tvSincronia.setTextColor(0xFFFFFFFF);
        } else if (semRede) {
            tvSincronia.setText("OFFLINE" + (pend > 0 ? " — " + pend + " aguardando envio" : "")
                    + (Sessao.offline && Rede.disponivel(this) ? " — toque para entrar e sincronizar" : ""));
            tvSincronia.setBackgroundColor(getColor(R.color.alerta));
            tvSincronia.setTextColor(0xFF101010);
        } else if (pend > 0) {
            tvSincronia.setText((Sincronizador.sincronizando ? "Sincronizando… " : "")
                    + pend + " aguardando envio");
            tvSincronia.setBackgroundColor(getColor(R.color.alerta));
            tvSincronia.setTextColor(0xFF101010);
        } else {
            tvSincronia.setVisibility(View.GONE);
            return;
        }
        tvSincronia.setVisibility(View.VISIBLE);
    }

    private void aoTocarFaixa() {
        ArmazemLocal a = ArmazemLocal.get(this);
        if (Sincronizador.servidorAntigo) {
            alerta("Servidor desatualizado", Sincronizador.MSG_SERVIDOR_ANTIGO);
        } else if (a.contarComErro(Repositorio.dono()) > 0) {
            mostrarPendenciasComErro();
        } else if (Sessao.offline && Rede.disponivel(this)) {
            // A sessao offline nao tem token: entrar de novo com internet.
            Api.encerrarSessao(this);
            startActivity(new Intent(this, LoginActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        } else {
            Sincronizador.agendar(this);
            toast(Sincronizador.ultimoErro != null ? Sincronizador.ultimoErro : "Sincronizando...");
        }
    }

    private void mostrarPendenciasComErro() {
        final ArmazemLocal a = ArmazemLocal.get(this);
        StringBuilder sb = new StringBuilder();
        final List<Long> ids = new ArrayList<>();
        for (ArmazemLocal.Op o : a.fila(Repositorio.dono())) {
            if (!o.permanente) continue;
            ids.add(o.seq);
            JSONObject f = a.ficha(o.fichaId);
            sb.append("• ").append(f != null && !f.optString("nome").isEmpty()
                    ? f.optString("nome") : "(ficha)").append(" — ").append(o.tipo)
                    .append("\n   ").append(o.erro).append("\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("Alterações recusadas")
                .setView(textoRolavel(sb + "\nO que foi recusado fica guardado aqui e NÃO é reenviado. "
                        + "Corrija a ficha e salve de novo, ou descarte."))
                .setPositiveButton("Descartar todas", (d, w) -> {
                    for (long seq : ids) a.removerOp(seq);
                    atualizarFaixa();
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void mostrarAvisosDeSincronia() {
        for (String m : Sincronizador.pegarAvisos()) toast(m);
    }

    private void avisarPerdaDeDados() {
        ArmazemLocal a = ArmazemLocal.get(this);
        if (!a.perdaDetectada()) return;
        a.limparAvisoPerda();
        new AlertDialog.Builder(this)
                .setTitle("Dados do aparelho recarregados")
                .setMessage("Os dados guardados neste celular não puderam ser lidos (isso acontece, "
                        + "por exemplo, ao restaurar o aparelho). As fichas serão baixadas do "
                        + "servidor de novo."
                        + (a.perdidas() > 0 ? "\n\nATENÇÃO: " + a.perdidas() + " alteração(ões) "
                        + "feita(s) offline e ainda não enviada(s) foram perdidas. Confira as fichas "
                        + "e refaça o que faltar." : ""))
                .setPositiveButton("OK", (d, w) -> Sincronizador.agendar(this))
                .show();
    }

    // -- avisos do administrador (pop-up) ----------------------------------------------

    private void buscarAvisos() {
        if (exibindoAviso) return;
        exibindoAviso = true;
        EXEC.execute(() -> {
            JSONArray lista;
            try {
                lista = Api.avisos();
            } catch (Exception e) {
                lista = new JSONArray();  // falha de rede: tenta de novo na proxima abertura
            }
            final JSONArray avisos = lista;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    exibindoAviso = false;
                    return;
                }
                mostrarAviso(avisos, 0);
            });
        });
    }

    private void mostrarAviso(final JSONArray avisos, final int i) {
        final JSONObject a = avisos.optJSONObject(i);
        if (a == null || isFinishing() || isDestroyed()) {
            exibindoAviso = false;
            return;
        }
        String rodape = "\n\n— " + a.optString("criado_por") + ", "
                + a.optString("criado_em").replaceFirst(
                "^(\\d{4})-(\\d{2})-(\\d{2}) (\\d{2}:\\d{2}).*$", "$3/$2/$1 $4");
        new AlertDialog.Builder(this)
                .setTitle(a.optString("titulo", "Aviso do comando"))
                .setMessage(a.optString("mensagem") + rodape)
                .setIcon(android.R.drawable.ic_dialog_info)
                .setCancelable(false)
                .setPositiveButton("Ciente", (d, w) -> {
                    final long id = a.optLong("id");
                    EXEC.execute(() -> {
                        try {
                            Api.avisoCiente(id);
                        } catch (Exception ignorado) {
                            // sem rede: o aviso aparece de novo na proxima abertura
                        }
                    });
                    mostrarAviso(avisos, i + 1);
                })
                .show();
    }

    /** Le da copia local (busca + filtros): instantaneo e funciona sem internet. */
    private void carregar() {
        if (!Sessao.ativa()) return;
        final int minha = ++geracao;
        filtro.termo = etBusca.getText().toString().trim();
        final String termo = filtro.termo;
        final Repositorio.Filtro copia = filtro;
        if (fichas.isEmpty()) progresso.setVisibility(View.VISIBLE);
        emSegundoPlano(() -> {
            List<JSONObject> lista = Repositorio.listar(this, copia);
            comPendencia = ArmazemLocal.get(this).fichasComFila(Repositorio.dono());
            return lista;
        }, lista -> {
            if (minha != geracao) return;
            progresso.setVisibility(View.GONE);
            fichas.clear();
            fichas.addAll(lista);
            adaptador.notifyDataSetChanged();
            String resumo = FiltrosDialog.resumo(filtro);
            tvFiltros.setText("Filtros: " + resumo);
            tvFiltros.setVisibility(resumo.isEmpty() ? View.GONE : View.VISIBLE);
            if (fichas.isEmpty()) {
                // "Baixando..." so enquanto a 1a sincronizacao deste usuario ainda nao terminou.
                boolean primeiraVez = ArmazemLocal.get(this).meta("ultima_sync") == null;
                boolean sincronizandoVazio = ArmazemLocal.get(this).quantidade() == 0
                        && !Sincronizador.servidorAntigo && primeiraVez
                        && (Sincronizador.sincronizando || Rede.disponivel(this));
                tvVazio.setText(Sincronizador.servidorAntigo && ArmazemLocal.get(this).quantidade() == 0
                        ? "Este servidor ainda não está na versão 3.2.\nPeça ao comandante para atualizar o servidor."
                        : sincronizandoVazio
                        ? "Baixando as fichas do servidor..."
                        : termo.isEmpty() && filtro.ativos() == 0
                        ? "Nenhuma ficha cadastrada.\nToque em \"+ Nova ficha\" para começar."
                        : "Nada encontrado com a busca e os filtros atuais.");
                tvVazio.setVisibility(View.VISIBLE);
            } else {
                tvVazio.setVisibility(View.GONE);
            }
        }, () -> progresso.setVisibility(View.GONE));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_ATUALIZAR, 0, "Sincronizar agora");
        menu.add(0, MENU_APARENCIA, 1, "Aparência: " + Aparencia.rotulo(Aparencia.modo(this)));
        if (Sessao.admin()) menu.add(0, MENU_IMPORTAR, 1, "Importar fichas (planilha ou JSON)");
        menu.add(0, MENU_SESSAO, 2, "Sessão válida até "
                + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(new java.util.Date(Sessao.expiraEm))).setEnabled(false);
        menu.add(0, MENU_SAIR, 3, "Sair (encerrar sessão)");
        menu.add(0, MENU_ATUALIZACAO, 4, "Verificar atualização do app");
        menu.add(0, MENU_DIAGNOSTICO, 4, "Diagnóstico (registro de eventos)");
        menu.add(0, MENU_VERSAO, 5, "Keep Policial - versão " + BuildConfig.VERSION_NAME)
                .setEnabled(false);
        return true;
    }

    // -- importacao ------------------------------------------------------------------

    private void explicarImportacao() {
        new AlertDialog.Builder(this)
                .setTitle("Importar fichas")
                .setMessage("Escolha uma planilha (.xlsx ou .csv) ou um arquivo .json.\n\n"
                        + "Planilha: a 1ª linha tem os nomes das colunas:\n"
                        + "nome, alcunha, cpf, rg, endereco, pai, mae, comportamento, "
                        + "observacoes, reds, data, natureza, marca, modelo, cor, placa\n\n"
                        + "Cada linha é uma ficha. Para mais de uma passagem ou veículo, repita a "
                        + "pessoa (mesmo CPF) em outra linha, ou deixe o nome em branco na linha "
                        + "de baixo.\n\n"
                        + "Quem já está cadastrado (mesmo CPF, ou mesmo nome e mãe) não é "
                        + "duplicado: o arquivo só completa campos vazios e acrescenta passagens "
                        + "e veículos novos. Nada é apagado.\n\n"
                        + "Fotos não são importadas.")
                .setPositiveButton("Escolher arquivo", (d, w) -> escolherArquivo())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void escolherArquivo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/csv", "text/comma-separated-values", "text/plain", "application/json",
                "application/octet-stream", "application/vnd.ms-excel"});
        try {
            startActivityForResult(i, REQ_IMPORTAR);
        } catch (android.content.ActivityNotFoundException e) {
            alerta("Importar", "Nenhum aplicativo de arquivos encontrado no celular.");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent dados) {
        super.onActivityResult(req, res, dados);
        if (req != REQ_IMPORTAR || res != RESULT_OK || dados == null || dados.getData() == null) return;
        final android.net.Uri uri = dados.getData();
        final android.app.Dialog espera = carregando("Lendo arquivo...");
        emSegundoPlano(() -> Importador.ler(this, uri), leitura -> {
            espera.dismiss();
            confirmarImportacao(leitura);
        }, espera::dismiss);
    }

    private void confirmarImportacao(final Importador.Leitura l) {
        StringBuilder sb = new StringBuilder();
        sb.append("Arquivo: ").append(l.arquivo).append("\nFormato: ").append(l.formato)
                .append("\n\nFichas: ").append(l.fichas.size())
                .append("\nPassagens: ").append(l.qtdPassagens)
                .append("\nVeículos: ").append(l.qtdVeiculos);
        int mostrar = Math.min(5, l.fichas.size());
        sb.append("\n\nPrimeiras fichas:");
        for (int i = 0; i < mostrar; i++) sb.append("\n• ").append(l.fichas.get(i).rotulo());
        if (!l.avisos.isEmpty()) {
            sb.append("\n\nAvisos (").append(l.avisos.size()).append("):");
            for (int i = 0; i < Math.min(30, l.avisos.size()); i++) sb.append("\n• ").append(l.avisos.get(i));
            if (l.avisos.size() > 30) sb.append("\n• ...");
        }
        new AlertDialog.Builder(this)
                .setTitle("Conferir importação")
                .setView(textoRolavel(sb.toString()))
                .setPositiveButton("Importar " + l.fichas.size() + " ficha(s)", (d, w) -> importar(l))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void importar(final Importador.Leitura l) {
        final android.widget.TextView tv = new android.widget.TextView(this);
        tv.setTextSize(16);
        tv.setPadding(dp(22), dp(20), dp(22), dp(8));
        tv.setText("Importando...");
        final android.widget.ProgressBar barra = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        barra.setPadding(dp(22), 0, dp(22), dp(20));
        android.widget.LinearLayout caixa = new android.widget.LinearLayout(this);
        caixa.setOrientation(android.widget.LinearLayout.VERTICAL);
        caixa.addView(tv);
        caixa.addView(barra);
        final AlertDialog espera = new AlertDialog.Builder(this).setView(caixa).setCancelable(false).create();
        espera.show();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        emSegundoPlano(() -> Importador.enviar(l.fichas, (feitos, total) -> ui.post(() -> {
            tv.setText("Importando " + feitos + " de " + total + "...");
            barra.setMax(total);
            barra.setProgress(feitos);
        })), rel -> {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            espera.dismiss();
            Sincronizador.agendar(this);
            StringBuilder sb = new StringBuilder();
            if (rel.interrompido != null) {
                sb.append("A importação foi INTERROMPIDA: ").append(rel.interrompido)
                        .append("\nO que já foi enviado ficou gravado. Importe o mesmo arquivo de "
                                + "novo para continuar: nada será duplicado.\n\n");
            }
            sb.append("Fichas novas: ").append(rel.criadas)
                    .append("\nFichas completadas: ").append(rel.atualizadas)
                    .append("\nJá estavam iguais: ").append(rel.iguais)
                    .append("\nCom erro: ").append(rel.erros.size());
            for (int i = 0; i < Math.min(30, rel.erros.size()); i++) sb.append("\n• ").append(rel.erros.get(i));
            new AlertDialog.Builder(this)
                    .setTitle("Importação concluída")
                    .setView(textoRolavel(sb.toString()))
                    .setPositiveButton("OK", null)
                    .show();
            carregar();
        }, () -> {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            espera.dismiss();
            carregar();
        });
    }

    /** Estado do app e ultimos eventos (sem dados de fichas). Util nos testes e para o suporte. */
    private void mostrarDiagnostico() {
        ArmazemLocal a = ArmazemLocal.get(this);
        String dono = Repositorio.dono();
        final String texto = "Versão " + BuildConfig.VERSION_NAME + " | Android "
                + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ") | "
                + Api.dispositivo()
                + "\nSessão: " + (Sessao.offline ? "offline" : "online")
                + " | rede: " + (Rede.disponivel(this) ? "sim" : "não")
                + "\nFichas no aparelho: " + a.quantidade()
                + " | pendentes: " + a.contarPendentes(dono) + " | com erro: " + a.contarComErro(dono)
                + "\nÚltimo erro de sincronização: "
                + (Sincronizador.ultimoErro == null ? "nenhum" : Sincronizador.ultimoErro)
                + "\nOCR offline: " + (LeitorOcr.disponivel() ? "incluído" : "ausente")
                + "\n\n" + Diag.ultimas(80);
        new AlertDialog.Builder(this)
                .setTitle("Diagnóstico")
                .setView(textoRolavel(texto))
                .setPositiveButton("Copiar", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("diagnostico", texto));
                    toast("Copiado.");
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void escolherAparencia() {
        final String[] opcoes = {Aparencia.rotulo(Aparencia.AUTO), "Claro", "Escuro (visão noturna)"};
        new AlertDialog.Builder(this)
                .setTitle("Aparência")
                .setSingleChoiceItems(opcoes, Aparencia.modo(this), (d, which) -> {
                    d.dismiss();
                    if (which != Aparencia.modo(this)) {
                        Aparencia.definir(this, which);
                        recreate();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private View textoRolavel(String texto) {
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(texto);
        tv.setTextSize(14);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(22), dp(12), dp(22), dp(12));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        return sv;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_ATUALIZAR:
                Sincronizador.agendar(this);
                carregar();
                toast(Rede.disponivel(this) ? "Sincronizando..." : "Sem conexão: sincroniza quando a internet voltar.");
                return true;
            case MENU_APARENCIA:
                escolherAparencia();
                return true;
            case MENU_DIAGNOSTICO:
                mostrarDiagnostico();
                return true;
            case MENU_IMPORTAR:
                explicarImportacao();
                return true;
            case MENU_ATUALIZACAO:
                Atualizador.verificarAgora(this);
                return true;
            case MENU_SAIR:
                new AlertDialog.Builder(this)
                        .setMessage("Encerrar a sessão? Será preciso digitar a senha de novo.")
                        .setPositiveButton("Sair", (d, w) -> sair())
                        .setNegativeButton("Cancelar", null)
                        .show();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        // Voltar apenas minimiza: a sessao continua valida durante o turno.
        moveTaskToBack(true);
    }

    private void sair() {
        Api.encerrarSessao(this);
        startActivity(new Intent(this, LoginActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    static String rotuloComportamento(String c) {
        switch (c) {
            case "OBEDIENTE":
                return "OBEDIENTE";
            case "RESISTENTE_PASSIVO":
                return "RESISTENTE PASSIVO";
            case "RESISTENTE_ATIVO":
                return "RESISTENTE ATIVO";
            default:
                return "";
        }
    }

    /** Cor da tarja/borda: vermelho = resistente ativo, amarelo = passivo, verde = obediente. */
    static int corComportamento(String c) {
        switch (c) {
            case "OBEDIENTE":
                return R.color.obediente;
            case "RESISTENTE_PASSIVO":
                return R.color.resistente_passivo;
            case "RESISTENTE_ATIVO":
                return R.color.resistente_ativo;
            default:
                return R.color.borda;
        }
    }

    private class Adaptador extends BaseAdapter {
        @Override
        public int getCount() {
            return fichas.size();
        }

        @Override
        public Object getItem(int pos) {
            return fichas.get(pos);
        }

        @Override
        public long getItemId(int pos) {
            return pos;
        }

        @Override
        public View getView(int pos, View v, ViewGroup pai) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_suspeito, pai, false);
            JSONObject f = fichas.get(pos);
            String nome = f.optString("nome");
            String alcunha = f.optString("alcunha");
            String comp = f.optString("comportamento");
            JSONArray ps = f.optJSONArray("passagens");
            int qtd = ps == null ? 0 : ps.length();
            int cor = getColor(corComportamento(comp));

            // Hierarquia visual: a borda, a tarja e o selo seguem o nivel de periculosidade.
            android.graphics.drawable.GradientDrawable fundo = new android.graphics.drawable.GradientDrawable();
            fundo.setColor(getColor(R.color.cartao));
            fundo.setCornerRadius(dp(8));
            fundo.setStroke(dp(comp.equals("RESISTENTE_ATIVO") ? 4 : comp.isEmpty() ? 1 : 3), cor);
            v.findViewById(R.id.cartaoRaiz).setBackground(fundo);

            ((TextView) v.findViewById(R.id.tvNome)).setText(nome.isEmpty() ? "(sem nome)" : nome);
            TextView tvAlcunha = v.findViewById(R.id.tvAlcunha);
            tvAlcunha.setText(alcunha.isEmpty() ? "" : "vulgo \"" + alcunha + "\"");
            tvAlcunha.setVisibility(alcunha.isEmpty() ? View.GONE : View.VISIBLE);

            TextView tvComp = v.findViewById(R.id.tvComportamento);
            tvComp.setText(rotuloComportamento(comp));
            tvComp.setTextColor(comp.equals("RESISTENTE_PASSIVO") ? 0xFF101010 : 0xFFFFFFFF);
            tvComp.setBackgroundColor(cor);
            tvComp.setVisibility(comp.isEmpty() ? View.GONE : View.VISIBLE);
            v.findViewById(R.id.vFaixa).setBackgroundColor(cor);

            TextView tvAlerta = v.findViewById(R.id.tvAlertaCnh);
            boolean vencida = Cnh.vencida(f.optString("cnh_validade"));
            tvAlerta.setBackgroundColor(getColor(R.color.vencida));
            tvAlerta.setVisibility(vencida ? View.VISIBLE : View.GONE);

            TextView tvStatus = v.findViewById(R.id.tvStatus);
            String st = FiltrosDialog.rotuloStatus(f.optString("status"));
            tvStatus.setText(st.equals("Qualquer") ? "" : st.toUpperCase(java.util.Locale.ROOT));
            tvStatus.setVisibility(tvStatus.getText().length() == 0 ? View.GONE : View.VISIBLE);

            boolean pendente = comPendencia.contains(f.optString("id"));
            ((TextView) v.findViewById(R.id.tvPassagens)).setText(
                    (qtd == 0 ? "Sem passagens" : qtd + (qtd == 1 ? " passagem" : " passagens"))
                            + (pendente ? "  •  ⏳ envio pendente" : ""));

            ImageView iv = v.findViewById(R.id.ivFoto);
            JSONArray fotos = f.optJSONArray("fotos");
            String capa = fotos != null && fotos.length() > 0 ? fotos.optString(0) : "";
            if (capa.isEmpty()) {
                iv.setTag(R.id.tag_foto, null);
                iv.setImageResource(R.drawable.sem_foto);
            } else {
                Imagens.carregar(iv, capa, Fotos.LADO_MINIATURA);
            }
            return v;
        }
    }
}
