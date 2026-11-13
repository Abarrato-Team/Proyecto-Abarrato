package cl.abarrato.precios;

final class IdentidadProducto {
    static final String SOPROLE_ENTERA_1_L = "gtin:07802900001308";

    private IdentidadProducto() {}

    static String clave(ObservacionPrecio precio) {
        return precio.claveComparacion() != null ? precio.claveComparacion()
                : "sku:" + precio.comercio().name() + ":" + precio.productoId();
    }

    static boolean esClaveValida(String clave) {
        if (clave == null || clave.length() > 160) {
            return false;
        }
        if (clave.startsWith("gtin:")) {
            return clave.equals(codigoBarras(clave.substring(5)));
        }
        if (clave.matches("cencosud:[0-9]{1,24}")) {
            return true;
        }
        String[] partes = clave.split(":", 3);
        if (partes.length != 3 || !partes[0].equals("sku") || partes[2].isBlank()
                || partes[2].length() > 120 || partes[2].chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        try {
            Comercio.valueOf(partes[1]);
            return true;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    static String codigoBarras(String codigo) {
        if (!codigo.matches("(?:[0-9]{8}|[0-9]{12,14})")) {
            return null;
        }
        int suma = 0;
        int peso = 3;
        for (int i = codigo.length() - 2; i >= 0; i--) {
            suma += (codigo.charAt(i) - '0') * peso;
            peso = peso == 3 ? 1 : 3;
        }
        int verificador = (10 - suma % 10) % 10;
        return codigo.charAt(codigo.length() - 1) - '0' == verificador
                ? "gtin:" + "0".repeat(14 - codigo.length()) + codigo : null;
    }
}
