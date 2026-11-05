package com.keeppolicial.app;

import java.util.Locale;

/** Listas e regras dos campos de veiculo (marca, modelo, cor e placa). */
final class Veiculos {
    static final int MAX = 20;

    static final String[] MARCAS = {
            "Chevrolet", "Fiat", "Volkswagen", "Ford", "Renault", "Toyota", "Honda", "Hyundai",
            "Jeep", "Nissan", "Peugeot", "Citroën", "Mitsubishi", "Kia", "Caoa Chery", "BYD",
            "BMW", "Mercedes-Benz", "Audi", "Volvo", "Land Rover", "Ram", "Suzuki", "Yamaha",
            "Dafra", "Shineray", "Haojue", "Kawasaki", "Harley-Davidson", "Triumph", "Bajaj",
            "Mottu", "Scania", "Iveco", "Agrale", "Troller", "JAC", "GWM", "Outra"
    };

    static final String[] CORES = {
            "Branca", "Preta", "Prata", "Cinza", "Vermelha", "Azul", "Verde", "Amarela",
            "Marrom", "Bege", "Dourada", "Laranja", "Vinho", "Rosa", "Roxa", "Fantasia"
    };

    private Veiculos() {
    }

    /** Placa so com letras e numeros, em maiusculas ("abc-1d23" -> "ABC1D23"). */
    static String normalizarPlaca(String placa) {
        return placa == null ? "" : placa.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    /** Padrao antigo (ABC1234) ou Mercosul (ABC1D23). */
    static boolean placaValida(String placa) {
        return normalizarPlaca(placa).matches("[A-Z]{3}[0-9][A-Z0-9][0-9]{2}");
    }
}
