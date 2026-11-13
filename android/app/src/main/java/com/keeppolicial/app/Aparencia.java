package com.keeppolicial.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * Tema do app: automatico (segue o sistema), claro ou escuro. O escuro usa cinzas
 * profundos e preto para preservar a visao noturna do operador.
 */
final class Aparencia {
    static final int AUTO = 0, CLARO = 1, ESCURO = 2;
    private static final String PREFS = "aparencia";

    private Aparencia() {
    }

    static int modo(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("modo", AUTO);
    }

    static void definir(Context c, int modo) {
        SharedPreferences.Editor e = c.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putInt("modo", modo).apply();
    }

    static String rotulo(int modo) {
        return modo == CLARO ? "Claro" : modo == ESCURO ? "Escuro" : "Automático (segue o celular)";
    }

    /** Configuracao com o modo noturno forcado, ou null no automatico (usa a do sistema). */
    static Configuration sobrescrita(Context base) {
        int modo = modo(base);
        if (modo == AUTO) return null;
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.uiMode = (c.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (modo == ESCURO ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return c;
    }
}
