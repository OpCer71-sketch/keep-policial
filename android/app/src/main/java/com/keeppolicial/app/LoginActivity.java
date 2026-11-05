package com.keeppolicial.app;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Tela inicial. O policial entra como num e-mail: "login@servidor" e senha.
 * No primeiro acesso a um servidor o app confere, uma unica vez, o codigo de
 * verificacao; depois disso basta usuario e senha.
 */
public class LoginActivity extends BaseActivity {
    private AutoCompleteTextView etAcesso;
    private EditText etSenha;
    private CheckBox cbTermo;
    private Button btnEntrar;
    private TextView tvDestacamento, tvEndereco, tvServidores;

    @Override
    protected boolean exigeSessao() {
        return false;
    }

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        if (Sessao.ativa() && !Sessao.expirada()) {
            abrirFichas();
            return;
        }
        setContentView(R.layout.activity_login);
        tvDestacamento = findViewById(R.id.tvDestacamento);
        tvEndereco = findViewById(R.id.tvEndereco);
        tvServidores = findViewById(R.id.tvServidores);
        etAcesso = findViewById(R.id.etAcesso);
        etSenha = findViewById(R.id.etSenha);
        cbTermo = findViewById(R.id.cbTermo);
        btnEntrar = findViewById(R.id.btnEntrar);

        List<Servidor> lista = Servidor.carregar(this);
        if (!lista.isEmpty() && !lista.get(0).ultimoUsuario.isEmpty()) {
            etAcesso.setText(lista.get(0).acesso(lista.get(0).ultimoUsuario));
            etSenha.requestFocus();
        }
        prepararSugestoes(lista);
        identificarServidor();
        etAcesso.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                identificarServidor();
            }
        });

        atualizarBotao();
        cbTermo.setOnClickListener(v -> {
            // O aceite so vale depois de exibir o termo completo.
            if (cbTermo.isChecked()) {
                cbTermo.setChecked(false);
                mostrarTermo(true);
            }
            atualizarBotao();
        });
        findViewById(R.id.tvLerTermo).setOnClickListener(v -> mostrarTermo(!cbTermo.isChecked()));
        btnEntrar.setOnClickListener(v -> entrar());
        etSenha.setOnEditorActionListener((v, acao, ev) -> {
            if (acao == EditorInfo.IME_ACTION_DONE && cbTermo.isChecked()) {
                entrar();
                return true;
            }
            return false;
        });
        tvServidores.setOnClickListener(v -> gerenciarServidores());
        tvServidores.setVisibility(lista.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A cada novo login o termo deve ser aceito novamente.
        if (!Sessao.ativa() && cbTermo != null) {
            cbTermo.setChecked(false);
            atualizarBotao();
        }
    }

    private void abrirFichas() {
        startActivity(new Intent(this, ListaActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private void prepararSugestoes(List<Servidor> lista) {
        List<String> acessos = new ArrayList<>();
        for (Servidor s : lista) {
            if (!s.ultimoUsuario.isEmpty()) acessos.add(s.acesso(s.ultimoUsuario));
        }
        etAcesso.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, acessos));
    }

    /** Mostra o nome do destacamento quando o endereco digitado ja e conhecido. */
    private void identificarServidor() {
        Servidor s = null;
        try {
            String[] partes = Servidor.separarAcesso(etAcesso.getText().toString());
            if (partes[1] != null) s = Servidor.porUrl(Servidor.carregar(this), partes[1]);
        } catch (IllegalArgumentException ignorado) {
        }
        if (s != null) {
            tvDestacamento.setText(s.nome.isEmpty() ? "Keep Policial" : s.nome);
            tvEndereco.setText(s.enderecoCurto());
        } else {
            tvDestacamento.setText("Keep Policial");
            tvEndereco.setText("Acesso restrito");
        }
    }

    private void atualizarBotao() {
        btnEntrar.setEnabled(cbTermo.isChecked());
        btnEntrar.setAlpha(cbTermo.isChecked() ? 1f : 0.4f);
    }

    private void mostrarTermo(boolean pedirAceite) {
        TextView tv = new TextView(this);
        tv.setText(R.string.termo_texto);
        tv.setTextSize(14);
        tv.setPadding(dp(22), dp(12), dp(22), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.termo_titulo)
                .setView(sv)
                .setCancelable(false);
        if (pedirAceite) {
            b.setPositiveButton("Li e aceito", (d, w) -> {
                cbTermo.setChecked(true);
                atualizarBotao();
            });
            b.setNegativeButton("Não aceito", (d, w) -> {
                cbTermo.setChecked(false);
                atualizarBotao();
            });
        } else {
            b.setPositiveButton("Fechar", null);
        }
        b.show();
    }

    private void entrar() {
        final String senha = etSenha.getText().toString();
        final String usuario;
        String url;
        try {
            String[] partes = Servidor.separarAcesso(etAcesso.getText().toString());
            usuario = partes[0];
            url = partes[1];
        } catch (IllegalArgumentException e) {
            alerta("Atenção", e.getMessage());
            return;
        }
        List<Servidor> lista = Servidor.carregar(this);
        if (url == null) {
            // Sem "@servidor": usa o ultimo servidor acessado, se houver.
            if (lista.isEmpty()) {
                alerta("Atenção", "Digite o usuário junto com o endereço do servidor, "
                        + "como um e-mail. Exemplo: sd.silva@192.168.0.10");
                return;
            }
            url = lista.get(0).url;
        }
        if (usuario.isEmpty() || senha.isEmpty()) {
            alerta("Atenção", "Informe usuário e senha.");
            return;
        }
        if (!cbTermo.isChecked()) {
            alerta("Atenção", "É obrigatório aceitar o Termo de Compromisso de Sigilo.");
            return;
        }
        Servidor conhecido = Servidor.porUrl(lista, url);
        if (conhecido != null) {
            fazerLogin(conhecido, usuario, senha);
        } else {
            primeiroAcesso(url, usuario, senha);
        }
    }

    /** Servidor nunca acessado neste aparelho: busca a identidade e pede confirmacao. */
    private void primeiroAcesso(final String url, final String usuario, final String senha) {
        final String[] impressao = new String[1];
        final Dialog espera = carregando("Conectando ao servidor...");
        emSegundoPlano(() -> Api.info(url, impressao), info -> {
            espera.dismiss();
            Servidor novo = new Servidor();
            novo.url = url;
            novo.nome = info.optString("destacamento");
            novo.impressao = impressao[0];
            novo.definirAlternativa(info.optString("endereco_publico"));
            // Mesmo certificado de um servidor ja confirmado (ex.: endereco da internet
            // e da rede local do mesmo computador): nao precisa confirmar de novo.
            if (Servidor.porImpressao(Servidor.carregar(this), novo.impressao) != null) {
                Servidor.lembrar(this, novo);
                fazerLogin(novo, usuario, senha);
            } else {
                confirmarServidor(novo, info, usuario, senha);
            }
        }, espera::dismiss);
    }

    private void confirmarServidor(Servidor s, JSONObject info, String usuario, String senha) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(22), dp(12), dp(22), 0);
        TextView tv = new TextView(this);
        tv.setTextSize(15);
        tv.setText("Destacamento: " + info.optString("destacamento")
                + "\n\nÉ o primeiro acesso a este servidor neste celular. Confira com o comandante "
                + "se o código abaixo é IGUAL ao mostrado no painel do servidor. Isso só é "
                + "feito uma vez.");
        TextView cod = new TextView(this);
        cod.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        cod.setTextSize(22);
        cod.setGravity(Gravity.CENTER);
        cod.setPadding(0, dp(16), 0, dp(4));
        cod.setTextColor(getColor(R.color.primaria));
        cod.setText(Api.codigoVerificacao(s.impressao));
        l.addView(tv);
        l.addView(cod);
        new AlertDialog.Builder(this)
                .setTitle("Primeiro acesso")
                .setView(l)
                .setPositiveButton("É igual, entrar", (d, w) -> {
                    Servidor.lembrar(this, s);
                    fazerLogin(s, usuario, senha);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void fazerLogin(final Servidor s, final String usuario, final String senha) {
        final Dialog espera = carregando("Entrando...");
        emSegundoPlano(() -> {
            Api.login(s, usuario, senha);
            return true;
        }, ok -> {
            espera.dismiss();
            etSenha.setText("");
            s.ultimoUsuario = usuario;
            if (Sessao.destacamento != null && !Sessao.destacamento.isEmpty()) {
                s.nome = Sessao.destacamento;
            }
            Servidor.lembrar(this, s);
            Sessao.guardar(this);
            abrirFichas();
        }, () -> {
            espera.dismiss();
            etSenha.setText("");
        });
    }

    private void gerenciarServidores() {
        final List<Servidor> lista = Servidor.carregar(this);
        if (lista.isEmpty()) {
            toast("Nenhum servidor salvo.");
            return;
        }
        String[] itens = new String[lista.size()];
        for (int i = 0; i < lista.size(); i++) {
            Servidor s = lista.get(i);
            itens[i] = (s.nome.isEmpty() ? "Servidor" : s.nome) + "\n" + s.enderecoCurto();
        }
        new AlertDialog.Builder(this)
                .setTitle("Servidores salvos (toque para remover)")
                .setItems(itens, (d, pos) -> {
                    Servidor s = lista.get(pos);
                    new AlertDialog.Builder(this)
                            .setMessage("Remover \"" + s.nome + "\" (" + s.enderecoCurto()
                                    + ") deste celular? No próximo acesso será pedida a "
                                    + "confirmação do código de novo. Os dados continuam "
                                    + "no servidor.")
                            .setPositiveButton("Remover", (d2, w) -> {
                                lista.remove(pos);
                                Servidor.salvar(this, lista);
                                prepararSugestoes(lista);
                                identificarServidor();
                                tvServidores.setVisibility(lista.isEmpty() ? View.GONE : View.VISIBLE);
                                toast("Servidor removido.");
                            })
                            .setNegativeButton("Cancelar", null)
                            .show();
                })
                .setNegativeButton("Fechar", null)
                .show();
    }
}
