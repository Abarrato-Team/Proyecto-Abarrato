package cl.abarrato.precios;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decide si dos nombres de distintas cadenas corresponden al mismo producto, por ejemplo
 * "Leche Loncoleche Entera 1 L" (Jumbo) y "Leche Natural Entera Lonco Leche 1 L" (Líder).
 *
 * Reglas: el formato (1 L = 1000 ml, 1 kg = 1000 g, 12 un.) debe ser idéntico y cada palabra
 * importante de un nombre debe aparecer en el otro, en ambos sentidos. Así no se mezclan una marca
 * propia con una marca conocida, ni "descremada" con "semidescremada", ni "sin lactosa" con la normal.
 */
final class EquivalenciaProductos {
    private static final Pattern MEDIDA = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(kg|kilos?|grs?|gramos|g|lts?|litros?|l|ml|cc|un|und|unidades)\\b");
    private static final Set<String> RELLENO = Set.of(
            "de", "del", "la", "el", "los", "las", "con", "en", "y", "a", "al", "para", "x",
            "natural", "caja", "bolsa", "botella", "tarro", "sachet", "envase", "lata", "frasco",
            "unidad", "unidades", "un", "und", "formato", "dp", "c", "u");

    record Firma(String medida, List<String> palabras, String compacto) {}

    private EquivalenciaProductos() {}

    static Firma firma(String nombre) {
        String texto = Normalizer.normalize(nombre, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replace('·', ' ');
        String medida = null;
        Matcher m = MEDIDA.matcher(texto);
        if (m.find()) {
            medida = medida(m.group(1), m.group(2));
            texto = texto.substring(0, m.start()) + " " + texto.substring(m.end());
        }
        List<String> palabras = new ArrayList<>();
        for (String palabra : texto.split("[^a-z0-9]+")) {
            if (!palabra.isEmpty() && !RELLENO.contains(palabra) && !palabra.matches("\\d+")) {
                palabras.add(palabra);
            }
        }
        return new Firma(medida, palabras, String.join("", palabras));
    }

    static boolean equivalentes(String a, String b) {
        return equivalentes(firma(a), firma(b));
    }

    static boolean equivalentes(Firma a, Firma b) {
        if (a.medida == null || !a.medida.equals(b.medida) || a.palabras.isEmpty() || b.palabras.isEmpty()) {
            return false;
        }
        return cubre(a, b) && cubre(b, a);
    }

    /** Cada palabra de {@code a} aparece en {@code b}: exacta, o dentro del nombre compacto si es larga. */
    private static boolean cubre(Firma a, Firma b) {
        for (String palabra : a.palabras) {
            boolean presente = b.palabras.contains(palabra)
                    || (palabra.length() >= 4 && b.compacto.contains(palabra) && !esVariante(palabra, b));
            if (!presente) {
                return false;
            }
        }
        return true;
    }

    /** Evita que "descremada" quede cubierta por "semidescremada". */
    private static boolean esVariante(String palabra, Firma b) {
        for (String otra : b.palabras) {
            if (!otra.equals(palabra) && otra.endsWith(palabra) && otra.length() > palabra.length()
                    && (otra.startsWith("semi") || otra.startsWith("des") || otra.startsWith("sin"))) {
                return true;
            }
        }
        return false;
    }

    private static String medida(String numero, String unidad) {
        double valor;
        try {
            valor = Double.parseDouble(numero.replace(',', '.'));
        } catch (NumberFormatException error) {
            return null;
        }
        String base;
        if (unidad.startsWith("k")) {
            valor *= 1000;
            base = "g";
        } else if (unidad.startsWith("g")) {
            base = "g";
        } else if (unidad.equals("ml") || unidad.equals("cc")) {
            base = "ml";
        } else if (unidad.startsWith("l")) {
            valor *= 1000;
            base = "ml";
        } else {
            base = "un";
        }
        return Math.round(valor) + base;
    }
}
