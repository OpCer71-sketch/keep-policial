package com.keeppolicial.app;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Painel de filtros avancados da lista de fichas. */
final class FiltrosDialog {
    private static final String[] STATUS_ROTULOS = {"Qualquer", "Em acompanhamento", "Preso",
            "Foragido", "Inativo"};
    private static final String[] STATUS_VALORES = {"", "EM_ACOMPANHAMENTO", "PRESO", "FORAGIDO",
            "INATIVO"};

    private FiltrosDialog() {
    }

    static String rotuloStatus(String valor) {
        for (int i = 0; i < STATUS_VALORES.length; i++) {
            if (STATUS_VALORES[i].equals(valor)) return STATUS_ROTULOS[i];
        }
        return "";
    }

    static String rotuloPeriodo(Repositorio.Filtro f) {
        String base = f.porAtualizacao ? "atualizadas" : "cadastradas";
        switch (f.periodo) {
            case 1:
                return base + " hoje";
            case 2:
                return base + " nos últimos 7 dias";
            case 3:
                return base + " nos últimos 30 dias";
            case 4:
                SimpleDateFormat d = new SimpleDateFormat("dd/MM/yy", Locale.US);
                return base + " de " + d.format(new Date(f.de)) + " a "
                        + (f.ate > 0 ? d.format(new Date(f.ate)) : "hoje");
            default:
                return "";
        }
    }

    /** Descricao curta dos filtros ativos (mostrada sob a barra de busca). */
    static String resumo(Repositorio.Filtro f) {
        StringBuilder sb = new StringBuilder();
        if (!f.comportamentos.isEmpty()) {
            sb.append("Periculosidade: ");
            boolean primeiro = true;
            for (String c : new String[]{"RESISTENTE_ATIVO", "RESISTENTE_PASSIVO", "OBEDIENTE", ""}) {
                if (!f.comportamentos.contains(c)) continue;
                if (!primeiro) sb.append(", ");
                sb.append(c.isEmpty() ? "sem classificação"
                        : ListaActivity.rotuloComportamento(c).toLowerCase(Locale.ROOT));
                primeiro = false;
            }
            sb.append("  •  ");
        }
        if (!f.status.isEmpty()) sb.append("Status: ").append(rotuloStatus(f.status)).append("  •  ");
        if (!f.cnhCategoria.isEmpty()) sb.append("CNH ").append(f.cnhCategoria).append("  •  ");
        if (f.cnhVencida) sb.append("CNH vencida  •  ");
        if (f.comVeiculo) sb.append("Com veículo  •  ");
        if (f.somentePendentes) sb.append("Envio pendente  •  ");
        if (f.periodo != 0) sb.append("Fichas ").append(rotuloPeriodo(f)).append("  •  ");
        String t = sb.toString();
        return t.endsWith("  •  ") ? t.substring(0, t.length() - 5) : t;
    }

    private static CheckBox caixa(Context c, String texto, boolean marcada) {
        CheckBox cb = new CheckBox(c);
        cb.setText(texto);
        cb.setChecked(marcada);
        cb.setTextSize(17);
        cb.setMinHeight(dp(c, 56));
        return cb;
    }

    private static RadioButton radio(Context c, String texto, int id) {
        RadioButton rb = new RadioButton(c);
        rb.setId(id);
        rb.setText(texto);
        rb.setTextSize(17);
        rb.setMinHeight(dp(c, 56));
        return rb;
    }

    private static TextView titulo(Context c, String texto) {
        TextView t = new TextView(c);
        t.setText(texto.toUpperCase(Locale.ROOT));
        t.setTextSize(14);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(c.getColor(R.color.destaque));
        t.setPadding(0, dp(c, 18), 0, dp(c, 2));
        return t;
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Abre o painel; ao aplicar, altera o filtro recebido e chama {@code aoAplicar}. */
    static void mostrar(final Context c, final Repositorio.Filtro f, final Runnable aoAplicar) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(c, 20), dp(c, 4), dp(c, 20), dp(c, 12));

        col.addView(titulo(c, "Nível de periculosidade"));
        final CheckBox cbAtivo = caixa(c, "Resistente ativo", f.comportamentos.contains("RESISTENTE_ATIVO"));
        final CheckBox cbPassivo = caixa(c, "Resistente passivo", f.comportamentos.contains("RESISTENTE_PASSIVO"));
        final CheckBox cbObed = caixa(c, "Obediente", f.comportamentos.contains("OBEDIENTE"));
        final CheckBox cbSem = caixa(c, "Sem classificação", f.comportamentos.contains(""));
        col.addView(cbAtivo);
        col.addView(cbPassivo);
        col.addView(cbObed);
        col.addView(cbSem);

        col.addView(titulo(c, "Status operacional"));
        final RadioGroup rgStatus = new RadioGroup(c);
        for (int i = 0; i < STATUS_ROTULOS.length; i++) rgStatus.addView(radio(c, STATUS_ROTULOS[i], 100 + i));
        for (int i = 0; i < STATUS_VALORES.length; i++) {
            if (STATUS_VALORES[i].equals(f.status)) rgStatus.check(100 + i);
        }
        col.addView(rgStatus);

        col.addView(titulo(c, "CNH e veículos"));
        String[] cats = new String[Cnh.CATEGORIAS.length + 1];
        cats[0] = "Qualquer categoria de CNH";
        System.arraycopy(Cnh.CATEGORIAS, 0, cats, 1, Cnh.CATEGORIAS.length);
        final Spinner spCat = new Spinner(c);
        spCat.setMinimumHeight(dp(c, 56));
        spCat.setAdapter(new ArrayAdapter<>(c, android.R.layout.simple_spinner_dropdown_item, cats));
        for (int i = 0; i < Cnh.CATEGORIAS.length; i++) {
            if (Cnh.CATEGORIAS[i].equals(f.cnhCategoria)) spCat.setSelection(i + 1);
        }
        col.addView(spCat);
        final CheckBox cbVenc = caixa(c, "Somente CNH vencida", f.cnhVencida);
        final CheckBox cbVeic = caixa(c, "Somente com veículo cadastrado", f.comVeiculo);
        col.addView(cbVenc);
        col.addView(cbVeic);

        col.addView(titulo(c, "Período"));
        final RadioGroup rgData = new RadioGroup(c);
        rgData.addView(radio(c, "Atualização da ficha", 200));
        rgData.addView(radio(c, "Cadastro da ficha", 201));
        rgData.check(f.porAtualizacao ? 200 : 201);
        col.addView(rgData);
        final RadioGroup rgPer = new RadioGroup(c);
        String[] per = {"Qualquer data", "Hoje", "Últimos 7 dias", "Últimos 30 dias", "Intervalo de datas"};
        for (int i = 0; i < per.length; i++) rgPer.addView(radio(c, per[i], 300 + i));
        rgPer.check(300 + f.periodo);
        col.addView(rgPer);
        final long[] intervalo = {f.de, f.ate};
        final Button btnDe = new Button(c, null, 0, R.style.BotaoSecundario);
        final Button btnAte = new Button(c, null, 0, R.style.BotaoSecundario);
        final SimpleDateFormat fmt = new SimpleDateFormat("dd/MM/yyyy", Locale.US);
        btnDe.setText(intervalo[0] > 0 ? "De: " + fmt.format(new Date(intervalo[0])) : "De: escolher");
        btnAte.setText(intervalo[1] > 0 ? "Até: " + fmt.format(new Date(intervalo[1])) : "Até: hoje");
        View.OnClickListener escolher = v -> {
            final boolean ehDe = v == btnDe;
            Calendar cal = Calendar.getInstance();
            long atual = intervalo[ehDe ? 0 : 1];
            if (atual > 0) cal.setTimeInMillis(atual);
            new DatePickerDialog(c, (dp, a, m, d) -> {
                Calendar x = Calendar.getInstance();
                x.set(a, m, d, 0, 0, 0);
                x.set(Calendar.MILLISECOND, 0);
                intervalo[ehDe ? 0 : 1] = x.getTimeInMillis();
                (ehDe ? btnDe : btnAte).setText((ehDe ? "De: " : "Até: ") + fmt.format(x.getTime()));
                rgPer.check(304);
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
        };
        btnDe.setOnClickListener(escolher);
        btnAte.setOnClickListener(escolher);
        LinearLayout datas = new LinearLayout(c);
        datas.addView(btnDe);
        datas.addView(btnAte);
        col.addView(datas);

        col.addView(titulo(c, "Pendências"));
        final CheckBox cbPend = caixa(c, "Somente fichas com envio pendente", f.somentePendentes);
        col.addView(cbPend);

        col.addView(titulo(c, "Ordenar por"));
        final RadioGroup rgOrd = new RadioGroup(c);
        rgOrd.addView(radio(c, "Mais recentes", 400));
        rgOrd.addView(radio(c, "Periculosidade (maior primeiro)", 401));
        rgOrd.addView(radio(c, "Nome (A-Z)", 402));
        rgOrd.check(400 + f.ordem);
        col.addView(rgOrd);

        ScrollView sv = new ScrollView(c);
        sv.addView(col);
        new AlertDialog.Builder(c)
                .setTitle("Filtros avançados")
                .setView(sv)
                .setPositiveButton("Aplicar", (d, w) -> {
                    f.comportamentos.clear();
                    if (cbAtivo.isChecked()) f.comportamentos.add("RESISTENTE_ATIVO");
                    if (cbPassivo.isChecked()) f.comportamentos.add("RESISTENTE_PASSIVO");
                    if (cbObed.isChecked()) f.comportamentos.add("OBEDIENTE");
                    if (cbSem.isChecked()) f.comportamentos.add("");
                    int st = rgStatus.getCheckedRadioButtonId() - 100;
                    f.status = st >= 0 && st < STATUS_VALORES.length ? STATUS_VALORES[st] : "";
                    f.cnhCategoria = spCat.getSelectedItemPosition() > 0
                            ? Cnh.CATEGORIAS[spCat.getSelectedItemPosition() - 1] : "";
                    f.cnhVencida = cbVenc.isChecked();
                    f.comVeiculo = cbVeic.isChecked();
                    f.somentePendentes = cbPend.isChecked();
                    f.porAtualizacao = rgData.getCheckedRadioButtonId() != 201;
                    f.periodo = Math.max(0, rgPer.getCheckedRadioButtonId() - 300);
                    f.de = intervalo[0];
                    f.ate = intervalo[1];
                    f.ordem = Math.max(0, rgOrd.getCheckedRadioButtonId() - 400);
                    aoAplicar.run();
                })
                .setNeutralButton("Limpar", (d, w) -> {
                    f.limpar();
                    f.ordem = 0;
                    aoAplicar.run();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
