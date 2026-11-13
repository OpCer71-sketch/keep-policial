package com.keeppolicial.app;

import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Historico de auditoria de uma ficha: QUEM (operador), O QUE (criacao, consulta,
 * edicao de campos), QUANDO (data e hora do servidor) e de ONDE (aparelho/IP).
 * O registro e gravado no servidor e nao pode ser alterado nem apagado.
 */
public class HistoricoActivity extends BaseActivity {
    private LinearLayout lista;
    private TextView tvEstado;
    private String idFicha;

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        if (encerrando) return;
        setTitle("Histórico de auditoria");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        idFicha = getIntent().getStringExtra("id");

        ScrollView sv = new ScrollView(this);
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setPadding(dp(12), dp(12), dp(12), dp(24));
        tvEstado = new TextView(this);
        tvEstado.setTextSize(16);
        tvEstado.setTextColor(getColor(R.color.texto_suave));
        tvEstado.setGravity(Gravity.CENTER);
        tvEstado.setPadding(dp(8), dp(24), dp(8), dp(24));
        raiz.addView(tvEstado);
        lista = new LinearLayout(this);
        lista.setOrientation(LinearLayout.VERTICAL);
        raiz.addView(lista);
        sv.addView(raiz);
        setContentView(sv);
        carregar();
    }

    private void carregar() {
        if (idFicha == null) {
            tvEstado.setText("Salve a ficha para começar o histórico.");
            return;
        }
        if (Sessao.offline || !Rede.disponivel(this)) {
            tvEstado.setText("O histórico completo fica no servidor.\nConecte-se à internet para consultá-lo.");
            return;
        }
        tvEstado.setText("Carregando...");
        emSegundoPlano(() -> Api.historico(idFicha), itens -> {
            if (itens.length() == 0) {
                tvEstado.setText("Nenhum registro.");
                return;
            }
            tvEstado.setText(itens.length() + " registro(s), do mais recente ao mais antigo.\n"
                    + "Este registro não pode ser editado nem apagado.");
            desenhar(itens);
        }, () -> tvEstado.setText("Não foi possível carregar o histórico."));
    }

    static String rotuloAcao(String acao) {
        switch (acao) {
            case "CRIAR_FICHA":
                return "CRIAÇÃO DA FICHA";
            case "VER_FICHA":
                return "CONSULTA";
            case "EDITAR_FICHA":
                return "EDIÇÃO";
            case "EDITAR_FICHA_CONFLITO":
                return "EDIÇÃO (CONFLITO)";
            case "ADICIONAR_FOTO":
                return "FOTO ADICIONADA";
            case "EXCLUIR_FOTO":
                return "FOTO REMOVIDA";
            case "EXCLUIR_FICHA":
                return "EXCLUSÃO";
            default:
                return acao;
        }
    }

    private static int corAcao(String acao) {
        if (acao.startsWith("EXCLUIR")) return R.color.resistente_ativo;
        if (acao.equals("VER_FICHA")) return R.color.texto_suave;
        if (acao.equals("EDITAR_FICHA_CONFLITO")) return R.color.alerta;
        return R.color.destaque;
    }

    private void desenhar(JSONArray itens) {
        lista.removeAllViews();
        for (int i = 0; i < itens.length(); i++) {
            JSONObject it = itens.optJSONObject(i);
            if (it == null) continue;
            String acao = it.optString("acao");
            LinearLayout cartao = new LinearLayout(this);
            cartao.setOrientation(LinearLayout.VERTICAL);
            cartao.setBackgroundResource(R.drawable.bg_cartao);
            cartao.setPadding(dp(14), dp(10), dp(14), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            cartao.setLayoutParams(lp);

            TextView t1 = new TextView(this);
            t1.setText(rotuloAcao(acao));
            t1.setTextSize(15);
            t1.setTypeface(null, android.graphics.Typeface.BOLD);
            t1.setTextColor(getColor(corAcao(acao)));
            cartao.addView(t1);

            String nome = it.optString("nome"), login = it.optString("usuario");
            TextView t2 = new TextView(this);
            t2.setText("Quem: " + (nome.equals(login) || nome.isEmpty() ? login : nome + " (" + login + ")"));
            t2.setTextSize(15);
            t2.setTextColor(getColor(R.color.texto));
            cartao.addView(t2);

            TextView t3 = new TextView(this);
            t3.setText("Quando: " + formatarData(it.optString("ts")));
            t3.setTextSize(14);
            t3.setTextColor(getColor(R.color.texto));
            cartao.addView(t3);

            String disp = it.optString("dispositivo"), ip = it.optString("ip");
            String origem = (disp.isEmpty() ? "" : disp) + (ip.isEmpty() ? "" : (disp.isEmpty() ? "" : " • ") + ip);
            if (!origem.isEmpty()) {
                TextView t4 = new TextView(this);
                t4.setText("Origem: " + origem);
                t4.setTextSize(13);
                t4.setTextColor(getColor(R.color.texto_suave));
                cartao.addView(t4);
            }
            String alvo = it.optString("alvo");
            int barra = alvo.indexOf(" | ");
            if (barra >= 0) {
                TextView t5 = new TextView(this);
                t5.setText(alvo.substring(barra + 3));
                t5.setTextSize(13);
                t5.setTextColor(getColor(R.color.texto_suave));
                t5.setPadding(0, dp(4), 0, 0);
                t5.setTextIsSelectable(true);
                cartao.addView(t5);
            }
            lista.addView(cartao);
        }
    }

    private static String formatarData(String ts) {
        return ts.replaceFirst("^(\\d{4})-(\\d{2})-(\\d{2}) (.*)$", "$3/$2/$1 $4");
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
