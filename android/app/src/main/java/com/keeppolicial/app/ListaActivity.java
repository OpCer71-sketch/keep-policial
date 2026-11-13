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
            MENU_VERSAO = 5, MENU_ATUALIZACAO = 6;
    private static final int REQ_IMPORTAR = 20;

    private final List<JSONObject> fichas = new ArrayList<>();
    private Adaptador adaptador;
    private EditText etBusca;
    private TextView tvVazio;
    private View progresso;
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
        tvVazio = findViewById(R.id.tvVazio);
        progresso = findViewById(R.id.progresso);
        GridView grade = findViewById(R.id.grade);
        adaptador = new Adaptador();
        grade.setAdapter(adaptador);
        grade.setOnItemClickListener((p, v, pos, id) -> startActivity(
                new Intent(this, FichaActivity.class).putExtra("id", fichas.get(pos).optString("id"))));
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
            carregar();
            buscarAvisos();
            Falhas.enviarPendente(this);
        }
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

    private void carregar() {
        final int minha = ++geracao;
        final String termo = etBusca.getText().toString().trim();
        if (fichas.isEmpty()) progresso.setVisibility(View.VISIBLE);
        emSegundoPlano(() -> Api.listar(termo), lista -> {
            if (minha != geracao) return;
            progresso.setVisibility(View.GONE);
            fichas.clear();
            JSONArray a = lista != null ? lista : new JSONArray();
            for (int i = 0; i < a.length(); i++) fichas.add(a.optJSONObject(i));
            adaptador.notifyDataSetChanged();
            if (fichas.isEmpty()) {
                tvVazio.setText(termo.isEmpty()
                        ? "Nenhuma ficha cadastrada.\nToque em \"+ Nova ficha\" para começar."
                        : "Nada encontrado para \"" + termo + "\".");
                tvVazio.setVisibility(View.VISIBLE);
            } else {
                tvVazio.setVisibility(View.GONE);
            }
        }, () -> progresso.setVisibility(View.GONE));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_ATUALIZAR, 0, "Atualizar");
        if (Sessao.admin()) menu.add(0, MENU_IMPORTAR, 1, "Importar fichas (planilha ou JSON)");
        menu.add(0, MENU_SESSAO, 2, "Sessão válida até "
                + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(new java.util.Date(Sessao.expiraEm))).setEnabled(false);
        menu.add(0, MENU_SAIR, 3, "Sair (encerrar sessão)");
        menu.add(0, MENU_ATUALIZACAO, 4, "Verificar atualização do app");
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
                carregar();
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
            int qtd = f.optInt("qtd_passagens");

            ((TextView) v.findViewById(R.id.tvNome)).setText(nome.isEmpty() ? "(sem nome)" : nome);
            TextView tvAlcunha = v.findViewById(R.id.tvAlcunha);
            tvAlcunha.setText(alcunha.isEmpty() ? "" : "vulgo \"" + alcunha + "\"");
            tvAlcunha.setVisibility(alcunha.isEmpty() ? View.GONE : View.VISIBLE);
            TextView tvComp = v.findViewById(R.id.tvComportamento);
            tvComp.setText(rotuloComportamento(comp));
            tvComp.setTextColor(getColor(corComportamento(comp)));
            tvComp.setVisibility(comp.isEmpty() ? View.GONE : View.VISIBLE);
            v.findViewById(R.id.vFaixa).setBackgroundColor(getColor(corComportamento(comp)));
            ((TextView) v.findViewById(R.id.tvPassagens)).setText(
                    qtd == 0 ? "Sem passagens" : qtd + (qtd == 1 ? " passagem" : " passagens"));

            ImageView iv = v.findViewById(R.id.ivFoto);
            String capa = f.isNull("foto_capa") ? "" : f.optString("foto_capa");
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
