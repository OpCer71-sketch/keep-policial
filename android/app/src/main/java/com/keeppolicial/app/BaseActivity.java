package com.keeppolicial.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Base de todas as telas: confere a sessao (que dura o turno inteiro) e
 * executa chamadas de rede fora da thread principal.
 */
public abstract class BaseActivity extends Activity {
    static final ExecutorService EXEC = Executors.newFixedThreadPool(4);

    protected final Handler ui = new Handler(Looper.getMainLooper());
    /** true quando a tela foi aberta sem sessao e ja esta sendo fechada. */
    protected boolean encerrando;

    interface Tarefa<T> {
        T executar() throws Exception;
    }

    interface AoConcluir<T> {
        void ok(T resultado);
    }

    /** Telas que mostram dados exigem sessao ativa. */
    protected boolean exigeSessao() {
        return true;
    }

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        // Se o Android fechou o app, recupera a sessao guardada (ate expirar).
        if (!Sessao.ativa()) Sessao.restaurar(this);
        if (exigeSessao() && !Sessao.ativa()) {
            encerrando = true;
            irParaLogin();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Sessao.ativa() && Sessao.expirada()) {
            Sessao.encerrar(this);
            Toast.makeText(this, "Sessão expirada. Faça login novamente.", Toast.LENGTH_LONG).show();
        }
        if (exigeSessao() && !Sessao.ativa() && !encerrando) {
            encerrando = true;
            irParaLogin();
        }
    }

    protected void irParaLogin() {
        startActivity(new Intent(this, LoginActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    protected <T> void emSegundoPlano(Tarefa<T> tarefa, AoConcluir<T> aoConcluir) {
        emSegundoPlano(tarefa, aoConcluir, null);
    }

    protected <T> void emSegundoPlano(Tarefa<T> tarefa, AoConcluir<T> aoConcluir, Runnable aoFalhar) {
        EXEC.execute(() -> {
            try {
                T r = tarefa.executar();
                ui.post(() -> {
                    if (!isDestroyed()) aoConcluir.ok(r);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (isDestroyed()) return;
                    if (aoFalhar != null) aoFalhar.run();
                    tratarErro(e);
                });
            }
        });
    }

    protected void tratarErro(Exception e) {
        if (e instanceof Api.ErroApi && ((Api.ErroApi) e).status == 401 && exigeSessao()) {
            Sessao.encerrar(this);
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            irParaLogin();
            return;
        }
        String titulo = e instanceof Api.ErroCertificado ? "ATENÇÃO - servidor não confiável" : "Erro";
        alerta(titulo, e.getMessage() != null ? e.getMessage() : e.toString());
    }

    protected void alerta(String titulo, String mensagem) {
        new AlertDialog.Builder(this)
                .setTitle(titulo)
                .setMessage(mensagem)
                .setPositiveButton("OK", null)
                .show();
    }

    protected Dialog carregando(String mensagem) {
        LinearLayout l = new LinearLayout(this);
        int p = dp(20);
        l.setPadding(p, p, p, p);
        l.setGravity(android.view.Gravity.CENTER_VERTICAL);
        l.addView(new ProgressBar(this));
        TextView t = new TextView(this);
        t.setText(mensagem);
        t.setTextSize(16);
        t.setPadding(p, 0, 0, 0);
        l.addView(t);
        AlertDialog d = new AlertDialog.Builder(this).setView(l).setCancelable(false).create();
        d.show();
        return d;
    }

    protected void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    protected int dp(int valor) {
        return Math.round(valor * getResources().getDisplayMetrics().density);
    }
}
