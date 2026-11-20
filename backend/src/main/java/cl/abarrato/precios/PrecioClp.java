package cl.abarrato.precios;

import java.util.regex.Pattern;

// Convierte textos de precio ("$1.290", "1290 CLP") en pesos enteros.
final class PrecioClp {
    private static final Pattern PRECIO = Pattern.compile("\\$[ \\u00a0]?(?:[1-9][0-9]*|[1-9][0-9]{0,2}(?:\\.[0-9]{3})+)");

    private PrecioClp() {
    }

    // Valida el formato del precio y deja solo sus dígitos.
    static int normalizar(String texto) {
        if (!PRECIO.matcher(texto).matches()) {
            throw new IllegalArgumentException("Precio individual en CLP inválido");
        }
        return Integer.parseInt(texto.replaceAll("[^0-9]", ""));
    }
}
