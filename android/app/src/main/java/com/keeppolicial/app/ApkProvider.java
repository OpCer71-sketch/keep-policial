package com.keeppolicial.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Entrega ao instalador do Android, somente para leitura, o APK da atualizacao
 * baixada (fica na pasta privada do app). Nao e exportado: o instalador so
 * recebe acesso temporario pela permissao concedida na Intent.
 */
public class ApkProvider extends ContentProvider {
    static final String AUTORIDADE = "com.keeppolicial.app.apk";
    private static final String NOME = "atualizacao.apk";
    static final Uri URI = Uri.parse("content://" + AUTORIDADE + "/" + NOME);

    private File arquivo() throws FileNotFoundException {
        File f = getContext() == null ? null : Atualizador.pendente(getContext());
        if (f == null) throw new FileNotFoundException("Sem atualização pendente");
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!NOME.equals(uri.getLastPathSegment()) || !"r".equals(mode)) {
            throw new FileNotFoundException();
        }
        return ParcelFileDescriptor.open(arquivo(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try {
            c.addRow(new Object[]{NOME, arquivo().length()});
        } catch (FileNotFoundException ignorado) {
        }
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
