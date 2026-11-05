package com.keeppolicial.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Entrega ao app de camera um unico arquivo temporario, privado do Keep Policial,
 * onde a foto e gravada. O arquivo e apagado logo apos ser processado.
 */
public class CameraProvider extends ContentProvider {
    static final String AUTORIDADE = "com.keeppolicial.app.camera";
    private static final String NOME = "captura.jpg";
    static final Uri URI = Uri.parse("content://" + AUTORIDADE + "/" + NOME);

    static File arquivo(Context c) {
        File dir = new File(c.getCacheDir(), "camera");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, NOME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!NOME.equals(uri.getLastPathSegment())) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(arquivo(getContext()), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{NOME, arquivo(getContext()).length()});
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
