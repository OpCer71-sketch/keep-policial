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
    private static final int REQ_CAMERA = 10, REQ_GALERIA = 11, REQ_SCAN_DOC = 12, REQ_SCAN_PLACA = 13;
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
    /** Campo de placa que recebera o resultado do escaneamento da placa. */
    private EditText placaAlvo;

    private LinearLayout llFotos, llPassagens, llVeiculos;
    private TextView tvFotosTitulo, tvContObs, tvRodape, tvCnhAlerta;
    private EditText etNome, etAlcunha, etCpf, etRg, etEndereco, etPai, etMae, etObs;
    private EditText etCnhNumero, etCnhValidade;
    private RadioGroup rgComp;
    /** Categoria de CNH e status operacional marcados ("" = nenhum). */
    private String categoria = "", statusOp = "";
    private final List<TextView> chipsCategoria = new ArrayList<>(), chipsStatus = new ArrayList<>();
    private static final String[] STATUS_VALORES = {"", "EM_ACOMPANHAMENTO", "PRESO", "FORAGIDO", "INATIVO"};
    private static final String[] STATUS_ROTULOS = {"Nenhum", "Em acompanhamento", "Preso", "Foragido", "Inativo"};

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
        etCnhNumero = findViewById(R.id.etCnhNumero);
        etCnhValidade = findViewById(R.id.etCnhValidade);
        tvCnhAlerta = findViewById(R.id.tvCnhAlerta);
        montarChips((android.widget.GridLayout) findViewById(R.id.gradeCategorias),
                Cnh.CATEGORIAS, Cnh.CATEGORIAS, chipsCategoria, v -> categoria = v, 5);
        montarChips((android.widget.GridLayout) findViewById(R.id.gradeStatus),
                STATUS_ROTULOS, STATUS_VALORES, chipsStatus, v -> statusOp = v, 2);
        etCnhValidade.addTextChangedListener(new Mascara("##/##/####"));
        etCnhValidade.addTextChangedListener(new Observador(x -> atualizarAlertaCnh()));
        etCnhNumero.addTextChangedListener(new Observador(x -> {
            String n = x.toString();
            etCnhNumero.setError(n.length() == 11 && !Cnh.numeroValido(n)
                    ? "Dígitos verificadores não conferem" : null);
        }));
        findViewById(R.id.btnHistorico).setOnClickListener(v -> startActivity(
                new Intent(this, HistoricoActivity.class).putExtra("id", id)));
        findViewById(R.id.secaoHistorico).setVisibility(id == null ? View.GONE : View.VISIBLE);
        configurarScanner();
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

    /** Abre a ficha da copia local (funciona offline). So consulta o servidor se nao existir aqui. */
    private void carregar() {
        JSONObject local = Repositorio.obter(this, id);
        if (local != null) {
            preencher(local);
            Repositorio.registrarConsulta(this, id);
            return;
        }
        if (!Rede.disponivel(this) || Sessao.offline) {
            toast("Ficha não disponível no aparelho. Conecte-se à internet para baixá-la.");
            finish();
            return;
        }
        final Dialog espera = carregando("Abrindo ficha...");
        emSegundoPlano(() -> Api.obter(id), ficha -> {
            espera.dismiss();
            preencher(ficha);
            Repositorio.registrarConsulta(this, id);
        }, () -> {
            espera.dismiss();
            finish();
        });
    }

    // -- CNH / status (selecao rapida) -------------------------------------------------

    interface AoEscolher {
        void escolheu(String valor);
    }

    /** Botoes grandes de selecao unica (categoria da CNH e status operacional). */
    private void montarChips(android.widget.GridLayout grade, String[] rotulos, String[] valores,
                             final List<TextView> chips, final AoEscolher ao, int colunas) {
        grade.setColumnCount(colunas);
        for (int i = 0; i < rotulos.length; i++) {
            final String valor = valores[i];
            TextView t = new TextView(this);
            t.setText(rotulos[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(16);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            t.setTag(valor);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.height = dp(52);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            t.setLayoutParams(lp);
            t.setOnClickListener(v -> {
                String atual = (String) v.getTag();
                // tocar de novo na categoria marcada desmarca
                boolean marcado = chips == chipsCategoria ? atual.equals(categoria) : atual.equals(statusOp);
                ao.escolheu(marcado && !atual.isEmpty() ? "" : atual);
                pintarChips();
            });
            chips.add(t);
            grade.addView(t);
        }
        pintarChips();
    }

    private void pintarChips() {
        for (TextView t : chipsCategoria) pintarChip(t, t.getTag().equals(categoria) && !categoria.isEmpty());
        for (TextView t : chipsStatus) pintarChip(t, t.getTag().equals(statusOp));
    }

    private void pintarChip(TextView t, boolean marcado) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(getColor(marcado ? R.color.botao : R.color.campo_fundo));
        g.setStroke(dp(2), getColor(marcado ? R.color.coyote : R.color.borda));
        t.setBackground(g);
        t.setTextColor(marcado ? 0xFFFFFFFF : getColor(R.color.texto));
    }

    /** Alerta visual da validade da CNH: vencida (vermelho), a vencer em 30 dias (ambar) ou valida. */
    private void atualizarAlertaCnh() {
        String texto = etCnhValidade.getText().toString().trim();
        if (texto.isEmpty()) {
            tvCnhAlerta.setVisibility(View.GONE);
            return;
        }
        if (texto.length() < 10) {
            tvCnhAlerta.setVisibility(View.GONE);
            return;
        }
        String iso = Cnh.paraIso(texto);
        if (iso.isEmpty()) {
            tvCnhAlerta.setText("Data inválida");
            tvCnhAlerta.setTextColor(getColor(R.color.vencida));
        } else {
            long dias = Cnh.diasParaVencer(iso);
            if (dias < 0) {
                tvCnhAlerta.setText("⚠ CNH VENCIDA há " + (-dias) + (dias == -1 ? " dia" : " dias"));
                tvCnhAlerta.setTextColor(getColor(R.color.vencida));
            } else if (dias <= 30) {
                tvCnhAlerta.setText("⚠ CNH vence " + (dias == 0 ? "hoje" : "em " + dias + " dias"));
                tvCnhAlerta.setTextColor(getColor(R.color.alerta));
            } else {
                tvCnhAlerta.setText("CNH válida");
                tvCnhAlerta.setTextColor(getColor(R.color.obediente));
            }
        }
        tvCnhAlerta.setVisibility(View.VISIBLE);
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
        etCnhNumero.setText(f.optString("cnh_numero"));
        etCnhValidade.setText(Cnh.paraTela(f.optString("cnh_validade")));
        categoria = f.optString("cnh_categoria");
        statusOp = f.optString("status");
        pintarChips();
        atualizarAlertaCnh();
        findViewById(R.id.secaoHistorico).setVisibility(View.VISIBLE);
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
        if (req == REQ_SCAN_DOC || req == REQ_SCAN_PLACA) {
            final File arq = CameraProvider.arquivo(this);
            if (res != RESULT_OK || arq.length() == 0) {
                Diag.w("Camera", "escaneamento sem foto (resultado=" + res + ", bytes=" + arq.length() + ")", null);
                //noinspection ResultOfMethodCallIgnored
                arq.delete();
                // RESULT_CANCELED = o policial desistiu (sem aviso); OK sem arquivo = a camera falhou
                if (res == RESULT_OK) toast("A câmera não devolveu a foto. Tente de novo.");
                return;
            }
            processarEscaneamento(arq, req == REQ_SCAN_PLACA);
            return;
        }
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
        View btnScan = linha.findViewById(R.id.btnScanPlaca);
        btnScan.setVisibility(LeitorOcr.disponivel() ? View.VISIBLE : View.GONE);
        btnScan.setOnClickListener(v -> {
            placaAlvo = etPlaca;
            abrirCameraEscaneamento(REQ_SCAN_PLACA);
        });
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

    // -- escanear documento / placa (OCR) ---------------------------------------------

    /** O botao so aparece se o motor de OCR estiver incluido no app. */
    private void configurarScanner() {
        View b = findViewById(R.id.btnScanDoc);
        b.setVisibility(LeitorOcr.disponivel() ? View.VISIBLE : View.GONE);
        b.setOnClickListener(v -> abrirCameraEscaneamento(REQ_SCAN_DOC));
        if (LeitorOcr.disponivel()) LeitorOcr.aquecer();
    }

    private void abrirCameraEscaneamento(int req) {
        //noinspection ResultOfMethodCallIgnored
        CameraProvider.arquivo(this).delete();
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, CameraProvider.URI);
        i.setClipData(ClipData.newRawUri("", CameraProvider.URI));
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            Diag.d("Camera", "abrindo a camera para escanear (" + (req == REQ_SCAN_PLACA ? "placa" : "documento") + ")");
            startActivityForResult(i, req);
        } catch (ActivityNotFoundException e) {
            Diag.e("Camera", "nenhum app de camera instalado", e);
            alerta("Câmera", "Nenhum aplicativo de câmera encontrado neste celular.");
        } catch (SecurityException e) {
            Diag.e("Camera", "permissao negada ao abrir a camera", e);
            alerta("Câmera", "O aplicativo não tem permissão para abrir a câmera. "
                    + "Libere em Configurações do Android > Aplicativos > Keep Policial > Permissões.");
        } catch (RuntimeException e) {
            Diag.e("Camera", "falha inesperada ao abrir a camera", e);
            alerta("Câmera", "Não foi possível abrir a câmera. Tente de novo.");
        }
    }

    /** Foto -> texto (OCR offline) -> campos. Falhas viram mensagens claras; nada e preenchido sem confirmar. */
    private void processarEscaneamento(final File arq, final boolean soPlaca) {
        final Dialog espera = carregando(soPlaca ? "Lendo a placa..." : "Lendo o documento...");
        EXEC.execute(() -> {
            byte[] jpeg = null;
            String erro = null;
            try {
                jpeg = Fotos.prepararJpeg(() -> new FileInputStream(arq));
            } catch (OutOfMemoryError e) {
                Diag.e("Camera", "memoria insuficiente ao preparar a foto", e);
                erro = "O celular ficou sem memória para abrir a foto. Feche outros aplicativos e tente de novo.";
            } catch (Exception e) {
                Diag.e("Camera", "foto tirada ilegivel", e);
                erro = "Não foi possível abrir a foto tirada.";
            } finally {
                //noinspection ResultOfMethodCallIgnored
                arq.delete();
            }
            if (jpeg == null) {
                final String msg = erro;
                ui.post(() -> {
                    espera.dismiss();
                    if (!isDestroyed()) alerta("Escanear", msg);
                });
                return;
            }
            LeitorOcr.ler(getApplicationContext(), jpeg, soPlaca, new LeitorOcr.Ouvinte() {
                @Override
                public void texto(LeitorOcr.Leitura leitura) {
                    final ExtratorDocumento.Resultado r = ExtratorDocumento.extrair(leitura.texto);
                    ui.post(() -> {
                        espera.dismiss();
                        if (isDestroyed()) return;
                        if (soPlaca) aplicarPlaca(r, leitura);
                        else confirmarDadosLidos(r, leitura);
                    });
                }

                @Override
                public void erro(String mensagem) {
                    ui.post(() -> {
                        espera.dismiss();
                        if (!isDestroyed()) alerta("Escanear", mensagem
                                + "\n\nVocê pode digitar os dados manualmente.");
                    });
                }
            });
        });
    }

    /** Orientacao para o operador quando a leitura falha (foto escura, tremida ou cortada). */
    private static String dicaDeLeitura(LeitorOcr.Leitura l) {
        return (l.escura ? "A foto ficou ESCURA. Acenda a luz, use a lanterna do celular ou aproxime-se de "
                + "uma fonte de luz.\n\n" : "")
                + "Dicas: documento inteiro no quadro, câmera parada, sem reflexo.";
    }

    private void aplicarPlaca(ExtratorDocumento.Resultado r, LeitorOcr.Leitura leitura) {
        if (r.placa.isEmpty()) {
            alerta("Placa não reconhecida", "Não foi possível ler a placa.\n\n" + dicaDeLeitura(leitura)
                    + "\n\nVocê pode digitar a placa manualmente.");
            return;
        }
        if (placaAlvo != null) {
            placaAlvo.setText(r.placa);
        } else {
            adicionarVeiculo(r.placa, "", "", "");  // sem linha de destino: cria o veiculo
        }
        toast("Placa lida: " + r.placa + ". Confira.");
    }

    /** Mostra o que foi lido; o policial marca o que quer aplicar (evita erro de OCR na ficha). */
    private void confirmarDadosLidos(final ExtratorDocumento.Resultado r, LeitorOcr.Leitura leitura) {
        if (r.vazio()) {
            alerta("Nada reconhecido", "Não foi possível ler os dados do documento.\n\n"
                    + dicaDeLeitura(leitura) + "\n\nVocê pode digitar os dados manualmente.");
            return;
        }
        final List<String> rotulos = new ArrayList<>();
        final List<Runnable> acoes = new ArrayList<>();
        adicionarLido(rotulos, acoes, "Nome", r.nome, texto(etNome), () -> etNome.setText(r.nome));
        adicionarLido(rotulos, acoes, "CPF", r.cpf, texto(etCpf), () -> etCpf.setText(r.cpf));
        adicionarLido(rotulos, acoes, "RG", r.rg, texto(etRg), () -> etRg.setText(r.rg));
        adicionarLido(rotulos, acoes, "Pai", r.pai, texto(etPai), () -> etPai.setText(r.pai));
        adicionarLido(rotulos, acoes, "Mãe", r.mae, texto(etMae), () -> etMae.setText(r.mae));
        adicionarLido(rotulos, acoes, "Nº da CNH", r.cnhNumero, texto(etCnhNumero),
                () -> etCnhNumero.setText(r.cnhNumero));
        adicionarLido(rotulos, acoes, "Categoria CNH", r.cnhCategoria, categoria, () -> {
            categoria = r.cnhCategoria;
            pintarChips();
        });
        adicionarLido(rotulos, acoes, "Validade CNH", Cnh.paraTela(r.cnhValidade),
                texto(etCnhValidade), () -> etCnhValidade.setText(Cnh.paraTela(r.cnhValidade)));
        if (!r.placa.isEmpty()) {
            adicionarLido(rotulos, acoes, "Placa", r.placa, "", () -> {
                View linha = adicionarVeiculo(r.placa, "", "", "");
                linha.requestFocus();
            });
        }
        final boolean[] marcados = new boolean[rotulos.size()];
        java.util.Arrays.fill(marcados, true);
        new AlertDialog.Builder(this)
                .setTitle(leitura.realcada ? "Dados lidos (foto escura, realçada)" : "Dados lidos do documento")
                .setMultiChoiceItems(rotulos.toArray(new String[0]), marcados,
                        (d, pos, marcado) -> marcados[pos] = marcado)
                .setPositiveButton("Preencher", (d, w) -> {
                    int n = 0;
                    for (int i = 0; i < acoes.size(); i++) {
                        if (marcados[i]) {
                            acoes.get(i).run();
                            n++;
                        }
                    }
                    toast(n + " campo(s) preenchido(s). Confira antes de salvar.");
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private static void adicionarLido(List<String> rotulos, List<Runnable> acoes, String campo,
                                      String valor, String atual, Runnable aplicar) {
        if (valor == null || valor.isEmpty()) return;
        String extra = !atual.isEmpty() && !atual.equals(valor) ? "  (substitui: " + atual + ")" : "";
        rotulos.add(campo + ": " + valor + extra);
        acoes.add(aplicar);
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
            o.put("cnh_numero", texto(etCnhNumero));
            o.put("cnh_categoria", categoria);
            String validade = texto(etCnhValidade);
            o.put("cnh_validade", validade.isEmpty() ? "" : Cnh.paraIso(validade));
            o.put("status", statusOp);
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
                    .setPositiveButton("Salvar", (d, w) -> conferirCnhEEnviar())
                    .setNegativeButton("Corrigir", (d, w) -> placaRuim.requestFocus())
                    .show();
            return;
        }
        conferirCnhEEnviar();
    }

    /** CNH: data valida (bloqueia) e digitos verificadores (pergunta). */
    private void conferirCnhEEnviar() {
        String validade = texto(etCnhValidade);
        if (!validade.isEmpty() && Cnh.paraIso(validade).isEmpty()) {
            etCnhValidade.setError("Data inválida (dia/mês/ano)");
            etCnhValidade.requestFocus();
            return;
        }
        String cnh = texto(etCnhNumero);
        if (!cnh.isEmpty() && !Cnh.numeroValido(cnh)) {
            new AlertDialog.Builder(this)
                    .setTitle("CNH inválida")
                    .setMessage("O número da CNH não confere (11 dígitos e dígitos verificadores). "
                            + "Salvar mesmo assim?")
                    .setPositiveButton("Salvar", (d, w) -> conferirCpfEEnviar())
                    .setNegativeButton("Corrigir", (d, w) -> etCnhNumero.requestFocus())
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

    /**
     * Salva NO APARELHO e coloca na fila de envio: funciona sem internet e nao perde dados
     * se a conexao cair. O servidor recebe assim que houver rede (veja Sincronizador).
     */
    private void enviar() {
        ocupado = true;
        final JSONObject corpo = montarJson();
        final List<byte[]> novas = new ArrayList<>();
        for (Foto f : fotos) if (f.jpeg != null) novas.add(f.jpeg);
        final List<String> remover = new ArrayList<>(fotosRemovidas);
        final String idAtual = id;
        final Dialog espera = carregando("Salvando...");
        emSegundoPlano(() -> Repositorio.salvar(this, idAtual, corpo, novas, remover), ficha -> {
            espera.dismiss();
            ocupado = false;
            fotosRemovidas.clear();
            preencher(ficha);
            toast(Rede.disponivel(this) && !Sessao.offline ? "Ficha salva."
                    : "Ficha salva no aparelho. Será enviada quando houver internet.");
        }, () -> {
            espera.dismiss();
            ocupado = false;
        });
    }

    private void excluir() {
        new AlertDialog.Builder(this)
                .setTitle("Excluir ficha")
                .setMessage("Excluir definitivamente esta ficha e todas as suas fotos?"
                        + (Rede.disponivel(this) && !Sessao.offline ? ""
                        : "\n\nSem internet: a exclusão será enviada ao servidor quando a conexão voltar."))
                .setPositiveButton("Excluir", (d, w) -> {
                    final Dialog espera = carregando("Excluindo...");
                    emSegundoPlano(() -> {
                        Repositorio.excluir(this, id);
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
