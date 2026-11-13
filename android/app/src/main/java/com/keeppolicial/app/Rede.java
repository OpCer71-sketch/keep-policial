package com.keeppolicial.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Estado da conexao e gatilho de sincronizacao quando a internet volta. */
final class Rede {
    private static boolean observando;

    private Rede() {
    }

    static boolean disponivel(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    c.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (RuntimeException e) {
            return true;  // sem permissao/servico: deixa a tentativa de rede decidir
        }
    }

    /** Sincroniza sozinho quando a conexao volta (chamado uma vez por processo). */
    static synchronized void observar(Context c) {
        if (observando) return;
        final Context app = c.getApplicationContext();
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Sincronizador.agendar(app);
                }
            });
            observando = true;
        } catch (RuntimeException ignorado) {
            // sem monitoramento: a sincronizacao ocorre ao abrir o app e ao salvar
        }
    }
}
