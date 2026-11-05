package com.keeppolicial.app;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Ficha de um suspeito: fotos, qualificacao, filiacao, comportamento, passagens e observacoes. */
public class FichaActivity extends BaseActivity {
    static final int MAX_FOTOS = 10;
    static final int MAX_OBS = 200;
    private static final int REQ_CAMERA = 10, REQ_GALERIA = 11;
    private static final int MENU_SALVAR = 1, MENU_EXCLUIR = 2;

    /** Foto ja gravada no servidor (id) ou ainda pendente de envio (jpeg). */
    private static class Foto {
        String id;
        byte[] jpeg;
        Bitmap miniatura;
        volatile String idEnviado;
    }

    private String id;
    private final List<Foto> fotos = new ArrayList<>();
    private final Set<String> fotosRemovidas = ConcurrentHashMap.newKeySet();
    private String fotografia = "";
    private boolean ocupado;

    private LinearLayout llFotos, llPassagens, llVeiculos;
    private TextView tvFotosTitulo, tvContObs, tvRodape;
    private EditText etNome, etAlcunha, etCpf, etRg, etEndereco, etPai, etMae, etObs;
    private RadioGroup rgComp;

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        if (encerrando) return;
        setContentView(R.layout.activity_ficha);
        id = getIntent().getStringExtra("id");
        setTitle(id == null ? "Nova ficha" : "Ficha do suspeito");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        llFotos = findViewById(R.id.llFotos);
        llPassagens = findViewById(R.id.llPassagens);
        llVeiculos = findViewById(R.id.llVeiculos);
        tvFotosTitulo = findViewById(R.id.tvFotosTitulo);
        tvContObs = findViewById(R.id.tvContObs);
        tvRodape = findViewById(R.id.tvRodape);
        etNome = findViewById(R.id.etNome);
        etAlcunha = findViewById(R.id.etAlcunha);
        etCpf = findViewById(R.id.etCpf);
        etRg = findViewById(R.id.etRg);
        etEndereco = findViewById(R.id.etEndereco);
        etPai = findViewById(R.id.etPai);
        etMae = findViewById(R.id.etMae);
        etObs = findViewById(R.id.etObservacoes);
        rgComp = findViewById(R.id.rgComportamento);

        etCpf.addTextChangedListener(new Mascara("###.###.###-##"));
        etObs.addTextChangedListener(new Observador(s ->
                tvContObs.setText(s.length() + "/" + MAX_OBS)));
        findViewById(R.id.tvLimparComportamento).setOnClickListener(v -> rgComp.clearCheck());
        findViewById(R.id.btnAddPassagem).setOnClickListener(v -> {
            View linha = adicionarPassagem("", "", "");
            linha.findViewById(R.id.etReds).requestFocus();
        });
        findViewById(R.id.btnAddVeiculo).setOnClickListener(v -> {
            if (llVeiculos.getChildCount() >= Veiculos.MAX) {
                toast("Limite de " + Veiculos.MAX + " veículos.");
                return;
            }
            View linha = adicionarVeiculo("", "", "", "");
            linha.findViewById(R.id.etPlaca).requestFocus();
        });
        findViewById(R.id.btnSalvar).setOnClickListener(v -> salvar());

        if (id != null) {
            carregar();
        } else {
            desenharFotos();
            fotografia = montarJson().toString();
        }
    }

    // -- carregar / preencher ----------------------------------------------

    private void carregar() {
        final Dialog espera = carregando("Abrindo ficha...");
        emSegundoPlano(() -> Api.obter(id), ficha -> {
            espera.dismiss();
            preencher(ficha);
        }, () -> {
            espera.dismiss();
            finish();
        });
    }

    private void preencher(JSONObject f) {
        id = f.optString("id");
        setTitle("Ficha do suspeito");
        etNome.setText(f.optString("nome"));
        etAlcunha.setText(f.optString("alcunha"));
        etCpf.setText(f.optString("cpf"));
        etRg.setText(f.optString("rg"));
        etEndereco.setText(f.optString("endereco"));
        etPai.setText(f.optString("pai"));
        etMae.setText(f.optString("mae"));
        etObs.setText(f.optString("observacoes"));
        switch (f.optString("comportamento")) {
            case "OBEDIENTE":
                rgComp.check(R.id.rbObediente);
                break;
            case "RESISTENTE_PASSIVO":
                rgComp.check(R.id.rbPassivo);
                break;
            case "RESISTENTE_ATIVO":
                rgComp.check(R.id.rbAtivo);
                break;
            default:
                rgComp.clearCheck();
        }
        llPassagens.removeAllViews();
        JSONArray ps = f.optJSONArray("passagens");
        if (ps != null) {
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                adicionarPassagem(p.optString("reds"), p.optString("data"), p.optString("descricao"));
            }
        }
        llVeiculos.removeAllViews();
        JSONArray vs = f.optJSONArray("veiculos");
        if (vs != null) {
            for (int i = 0; i < vs.length(); i++) {
                JSONObject v = vs.optJSONObject(i);
                adicionarVeiculo(v.optString("placa"), v.optString("cor"), v.optString("marca"),
                        v.optString("modelo"));
            }
        }
        fotos.clear();
        fotosRemovidas.clear();
        JSONArray fs = f.optJSONArray("fotos");
        if (fs != null) {
            for (int i = 0; i < fs.length(); i++) {
                Foto foto = new Foto();
                foto.id = fs.optString(i);
                fotos.add(foto);
            }
        }
        desenharFotos();
        tvRodape.setText("Cadastrada por " + f.optString("criado_por") + " em " + f.optString("criado_em")
                + "\nÚltima alteração: " + f.optString("atualizado_por") + " em " + f.optString("atualizado_em"));
        fotografia = montarJson().toString();
        invalidateOptionsMenu();
    }

    // -- fotos ---------------------------------------------------------------

    private void desenharFotos() {
        llFotos.removeAllViews();
        int lado = dp(104);
        for (final Foto f : fotos) {
            ImageView iv = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(lado, lado);
            lp.setMarginEnd(dp(8));
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.bg_cartao);
            if (f.jpeg != null) {
                iv.setImageBitmap(f.miniatura);
            } else {
                Imagens.carregar(iv, f.id, Fotos.LADO_MINIATURA);
            }
            iv.setOnClickListener(v -> ampliar(f));
            iv.setOnLongClickListener(v -> {
                confirmarRemocaoFoto(f);
                return true;
            });
            llFotos.addView(iv);
        }
        if (fotos.size() < MAX_FOTOS) {
            TextView mais = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(lado, lado);
            mais.setLayoutParams(lp);
            mais.setGravity(Gravity.CENTER);
            mais.setBackgroundResource(R.drawable.bg_foto_add);
            mais.setText("+\nFoto");
            mais.setTextSize(18);
            mais.setTextColor(getColor(R.color.texto_suave));
            mais.setOnClickListener(v -> escolherOrigemFoto());
            llFotos.addView(mais);
        }
        tvFotosTitulo.setText("FOTOS (" + fotos.size() + "/" + MAX_FOTOS + ")");
    }

    private void escolherOrigemFoto() {
        if (fotos.size() >= MAX_FOTOS) {
            toast("Limite de " + MAX_FOTOS + " fotos.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Adicionar foto")
                .setItems(new String[]{"Tirar foto com a câmera", "Escolher da galeria"}, (d, w) -> {
                    if (w == 0) abrirCamera();
                    else abrirGaleria();
                })
                .show();
    }

    private void abrirCamera() {
        //noinspection ResultOfMethodCallIgnored
        CameraProvider.arquivo(this).delete();
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, CameraProvider.URI);
        i.setClipData(ClipData.newRawUri("", CameraProvider.URI));
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_CAMERA);
        } catch (ActivityNotFoundException e) {
            alerta("Câmera", "Nenhum aplicativo de câmera encontrado.");
        }
    }

    private void abrirGaleria() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(Intent.createChooser(i, "Escolher fotos"), REQ_GALERIA);
        } catch (ActivityNotFoundException e) {
            alerta("Galeria", "Nenhum aplicativo de galeria encontrado.");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent dados) {
        super.onActivityResult(req, res, dados);
        if (req == REQ_CAMERA) {
            final File arq = CameraProvider.arquivo(this);
            if (res != RESULT_OK || arq.length() == 0) {
                //noinspection ResultOfMethodCallIgnored
                arq.delete();
                return;
            }
            List<Fotos.Abridor> fontes = new ArrayList<>();
            fontes.add(() -> new FileInputStream(arq));
            processarFotos(fontes, arq);
        } else if (req == REQ_GALERIA && res == RESULT_OK && dados != null) {
            List<Uri> uris = new ArrayList<>();
            if (dados.getClipData() != null) {
                for (int i = 0; i < dados.getClipData().getItemCount(); i++) {
                    uris.add(dados.getClipData().getItemAt(i).getUri());
                }
            } else if (dados.getData() != null) {
                uris.add(dados.getData());
            }
            int vagas = MAX_FOTOS - fotos.size();
            if (uris.size() > vagas) {
                toast("Somente " + vagas + " foto(s) adicionada(s): limite de " + MAX_FOTOS + ".");
                uris = uris.subList(0, Math.max(0, vagas));
            }
            List<Fotos.Abridor> fontes = new ArrayList<>();
            for (final Uri u : uris) fontes.add(() -> getContentResolver().openInputStream(u));
            processarFotos(fontes, null);
        }
    }

    private void processarFotos(final List<Fotos.Abridor> fontes, final File temporario) {
        if (fontes.isEmpty()) return;
        final Dialog espera = carregando("Preparando foto...");
        emSegundoPlano(() -> {
            List<Foto> novas = new ArrayList<>();
            try {
                for (Fotos.Abridor fonte : fontes) {
                    Foto f = new Foto();
                    f.jpeg = Fotos.prepararJpeg(fonte);
                    f.miniatura = Fotos.decodificar(f.jpeg, Fotos.LADO_MINIATURA);
                    novas.add(f);
                }
            } finally {
                //noinspection ResultOfMethodCallIgnored
                if (temporario != null) temporario.delete();
            }
            return novas;
        }, novas -> {
            espera.dismiss();
            for (Foto f : novas) {
                if (fotos.size() < MAX_FOTOS) fotos.add(f);
            }
            desenharFotos();
        }, espera::dismiss);
    }

    private void ampliar(Foto f) {
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ImageView iv = new ImageView(this);
        iv.setBackgroundColor(Color.BLACK);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (f.jpeg != null) {
            iv.setImageBitmap(Fotos.decodificar(f.jpeg, Fotos.LADO_ENVIO));
        } else {
            Imagens.carregar(iv, f.id, Fotos.LADO_ENVIO);
        }
        iv.setOnClickListener(v -> d.dismiss());
        d.setContentView(iv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        d.show();
    }

    private void confirmarRemocaoFoto(Foto f) {
        new AlertDialog.Builder(this)
                .setMessage("Remover esta foto da ficha? A remoção vale ao salvar.")
                .setPositiveButton("Remover", (d, w) -> {
                    fotos.remove(f);
                    if (f.id != null) fotosRemovidas.add(f.id);
                    desenharFotos();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // -- passagens -------------------------------------------------------------

    private View adicionarPassagem(String reds, String data, String descricao) {
        final View linha = getLayoutInflater().inflate(R.layout.item_passagem, llPassagens, false);
        EditText etReds = linha.findViewById(R.id.etReds);
        EditText etData = linha.findViewById(R.id.etData);
        EditText etDescr = linha.findViewById(R.id.etDescricao);
        // Linhas dinamicas repetem ids; o estado e controlado aqui, nao pelo sistema.
        etReds.setSaveEnabled(false);
        etData.setSaveEnabled(false);
        etDescr.setSaveEnabled(false);
        etData.addTextChangedListener(new Mascara("##/##/####"));
        etReds.setText(reds);
        etData.setText(data);
        etDescr.setText(descricao);
        linha.findViewById(R.id.btnRemover).setOnClickListener(v -> {
            boolean vazia = etReds.getText().length() == 0 && etDescr.getText().length() == 0;
            if (vazia) {
                llPassagens.removeView(linha);
                return;
            }
            new AlertDialog.Builder(this)
                    .setMessage("Remover esta passagem?")
                    .setPositiveButton("Remover", (d, w) -> llPassagens.removeView(linha))
                    .setNegativeButton("Cancelar", null)
                    .show();
        });
        llPassagens.addView(linha);
        return linha;
    }

    // -- veiculos --------------------------------------------------------------

    private View adicionarVeiculo(String placa, String cor, String marca, String modelo) {
        final View linha = getLayoutInflater().inflate(R.layout.item_veiculo, llVeiculos, false);
        final EditText etPlaca = linha.findViewById(R.id.etPlaca);
        AutoCompleteTextView etCor = linha.findViewById(R.id.etCor);
        AutoCompleteTextView etMarca = linha.findViewById(R.id.etMarca);
        EditText etModelo = linha.findViewById(R.id.etModelo);
        for (EditText e : new EditText[]{etPlaca, etCor, etMarca, etModelo}) e.setSaveEnabled(false);
        etCor.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line,
                Veiculos.CORES));
        etMarca.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line,
                Veiculos.MARCAS));
        etPlaca.setText(placa);
        etCor.setText(cor);
        etMarca.setText(marca);
        etModelo.setText(modelo);
        etPlaca.addTextChangedListener(new Observador(s -> {
            String n = Veiculos.normalizarPlaca(s.toString());
            if (!n.equals(s.toString())) s.replace(0, s.length(), n);
        }));
        linha.findViewById(R.id.btnRemover).setOnClickListener(v -> {
            boolean vazia = texto(etPlaca).isEmpty() && texto(etModelo).isEmpty()
                    && texto(etMarca).isEmpty() && texto(etCor).isEmpty();
            if (vazia) {
                llVeiculos.removeView(linha);
                return;
            }
            new AlertDialog.Builder(this)
                    .setMessage("Remover este veículo?")
                    .setPositiveButton("Remover", (d, w) -> llVeiculos.removeView(linha))
                    .setNegativeButton("Cancelar", null)
                    .show();
        });
        llVeiculos.addView(linha);
        return linha;
    }

    /** Primeira placa preenchida fora do padrao brasileiro, ou null. */
    private EditText placaForaDoPadrao() {
        for (int i = 0; i < llVeiculos.getChildCount(); i++) {
            EditText et = llVeiculos.getChildAt(i).findViewById(R.id.etPlaca);
            String p = texto(et);
            if (!p.isEmpty() && !Veiculos.placaValida(p)) return et;
        }
        return null;
    }

    // -- salvar ----------------------------------------------------------------

    private String comportamentoMarcado() {
        int c = rgComp.getCheckedRadioButtonId();
        if (c == R.id.rbObediente) return "OBEDIENTE";
        if (c == R.id.rbPassivo) return "RESISTENTE_PASSIVO";
        if (c == R.id.rbAtivo) return "RESISTENTE_ATIVO";
        return "";
    }

    private JSONObject montarJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("nome", texto(etNome));
            o.put("alcunha", texto(etAlcunha));
            o.put("cpf", texto(etCpf));
            o.put("rg", texto(etRg));
            o.put("endereco", texto(etEndereco));
            o.put("pai", texto(etPai));
            o.put("mae", texto(etMae));
            o.put("comportamento", comportamentoMarcado());
            o.put("observacoes", texto(etObs));
            JSONArray ps = new JSONArray();
            for (int i = 0; i < llPassagens.getChildCount(); i++) {
                View l = llPassagens.getChildAt(i);
                String reds = texto(l.findViewById(R.id.etReds));
                String descr = texto(l.findViewById(R.id.etDescricao));
                String data = texto(l.findViewById(R.id.etData));
                if (reds.isEmpty() && descr.isEmpty()) continue;
                ps.put(new JSONObject().put("reds", reds).put("descricao", descr).put("data", data));
            }
            o.put("passagens", ps);
            JSONArray vs = new JSONArray();
            for (int i = 0; i < llVeiculos.getChildCount(); i++) {
                View l = llVeiculos.getChildAt(i);
                String placa = Veiculos.normalizarPlaca(texto(l.findViewById(R.id.etPlaca)));
                String marca = texto(l.findViewById(R.id.etMarca));
                String modelo = texto(l.findViewById(R.id.etModelo));
                String cor = texto(l.findViewById(R.id.etCor));
                if (placa.isEmpty() && marca.isEmpty() && modelo.isEmpty() && cor.isEmpty()) continue;
                vs.put(new JSONObject().put("marca", marca).put("modelo", modelo).put("cor", cor)
                        .put("placa", placa));
            }
            o.put("veiculos", vs);
        } catch (JSONException ignorado) {
        }
        return o;
    }

    private static String texto(View v) {
        return ((EditText) v).getText().toString().trim();
    }

    private boolean alterada() {
        if (!fotosRemovidas.isEmpty()) return true;
        for (Foto f : fotos) if (f.jpeg != null) return true;
        return !montarJson().toString().equals(fotografia);
    }

    private void salvar() {
        if (ocupado) return;
        if (texto(etNome).isEmpty() && texto(etAlcunha).isEmpty()) {
            etNome.setError("Informe o nome ou a alcunha");
            etNome.requestFocus();
            return;
        }
        final EditText placaRuim = placaForaDoPadrao();
        if (placaRuim != null) {
            new AlertDialog.Builder(this)
                    .setTitle("Placa fora do padrão")
                    .setMessage("A placa " + texto(placaRuim) + " não segue o padrão brasileiro "
                            + "(ABC1234 ou ABC1D23). Salvar mesmo assim?")
                    .setPositiveButton("Salvar", (d, w) -> conferirCpfEEnviar())
                    .setNegativeButton("Corrigir", (d, w) -> placaRuim.requestFocus())
                    .show();
            return;
        }
        conferirCpfEEnviar();
    }

    private void conferirCpfEEnviar() {
        String cpf = texto(etCpf);
        if (!cpf.isEmpty() && !cpfValido(cpf)) {
            new AlertDialog.Builder(this)
                    .setTitle("CPF inválido")
                    .setMessage("O CPF informado não confere (dígitos verificadores). Salvar mesmo assim?")
                    .setPositiveButton("Salvar", (d, w) -> enviar())
                    .setNegativeButton("Corrigir", (d, w) -> etCpf.requestFocus())
                    .show();
            return;
        }
        enviar();
    }

    private void enviar() {
        ocupado = true;
        final JSONObject corpo = montarJson();
        final List<Foto> pendentes = new ArrayList<>();
        for (Foto f : fotos) if (f.jpeg != null) pendentes.add(f);
        final List<String> remover = new ArrayList<>(fotosRemovidas);
        final String[] idGravado = {id};
        final Dialog espera = carregando("Salvando...");
        emSegundoPlano(() -> {
            JSONObject r = Api.salvar(idGravado[0], corpo);
            idGravado[0] = r.getString("id");
            for (String fid : remover) {
                try {
                    Api.excluirFoto(fid);
                } catch (Api.ErroApi e) {
                    if (e.status != 404) throw e;
                }
                fotosRemovidas.remove(fid);
            }
            for (Foto f : pendentes) {
                f.idEnviado = Api.enviarFoto(idGravado[0], f.jpeg);
            }
            return Api.obter(idGravado[0]);
        }, ficha -> {
            espera.dismiss();
            ocupado = false;
            preencher(ficha);
            toast("Ficha salva.");
        }, () -> {
            // Falha parcial: guarda o que ja foi gravado para nao duplicar ao tentar de novo.
            espera.dismiss();
            ocupado = false;
            id = idGravado[0];
            for (Foto f : pendentes) {
                if (f.idEnviado != null) {
                    f.id = f.idEnviado;
                    f.jpeg = null;
                }
            }
            desenharFotos();
            invalidateOptionsMenu();
        });
    }

    private void excluir() {
        new AlertDialog.Builder(this)
                .setTitle("Excluir ficha")
                .setMessage("Excluir definitivamente esta ficha e todas as suas fotos?")
                .setPositiveButton("Excluir", (d, w) -> {
                    final Dialog espera = carregando("Excluindo...");
                    emSegundoPlano(() -> {
                        Api.excluir(id);
                        return true;
                    }, ok -> {
                        espera.dismiss();
                        toast("Ficha excluída.");
                        finish();
                    }, espera::dismiss);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    static boolean cpfValido(String cpf) {
        String d = cpf.replaceAll("\\D", "");
        if (d.length() != 11 || d.chars().distinct().count() == 1) return false;
        for (int t = 9; t < 11; t++) {
            int soma = 0;
            for (int i = 0; i < t; i++) soma += (d.charAt(i) - '0') * (t + 1 - i);
            int dv = (soma * 10) % 11 % 10;
            if (dv != d.charAt(t) - '0') return false;
        }
        return true;
    }

    // -- menu / voltar ---------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_SALVAR, 0, "Salvar").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        if (id != null && Sessao.admin()) menu.add(0, MENU_EXCLUIR, 1, "Excluir ficha");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        } else if (item.getItemId() == MENU_SALVAR) {
            salvar();
            return true;
        } else if (item.getItemId() == MENU_EXCLUIR) {
            excluir();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (!alterada()) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage("Há alterações não salvas. Descartar?")
                .setPositiveButton("Descartar", (d, w) -> finish())
                .setNegativeButton("Continuar editando", null)
                .show();
    }

    // -- auxiliares de texto ---------------------------------------------------

    interface AoMudar {
        void mudou(Editable s);
    }

    private static class Observador implements TextWatcher {
        private final AoMudar acao;

        Observador(AoMudar acao) {
            this.acao = acao;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            acao.mudou(s);
        }
    }

    /** Mascara simples: '#' recebe um digito, demais caracteres sao separadores. */
    private static class Mascara implements TextWatcher {
        private final String modelo;
        private boolean editando;

        Mascara(String modelo) {
            this.modelo = modelo;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            if (editando) return;
            String dig = s.toString().replaceAll("\\D", "");
            StringBuilder sb = new StringBuilder();
            int j = 0;
            for (int i = 0; i < modelo.length() && j < dig.length(); i++) {
                char m = modelo.charAt(i);
                if (m == '#') sb.append(dig.charAt(j++));
                else sb.append(m);
            }
            if (!sb.toString().equals(s.toString())) {
                editando = true;
                s.replace(0, s.length(), sb.toString());
                editando = false;
            }
        }
    }
}
