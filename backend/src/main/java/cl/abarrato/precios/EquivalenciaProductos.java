package cl.abarrato.precios;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decide si dos nombres de distintas cadenas corresponden al mismo producto, por ejemplo
 * "Bebida Coca-Cola Original 1 L" (Santa Isabel) y "Coca Cola Botella 1 Lt" (Líder).
 *
 * Reglas:
 * <ol>
 *   <li>El formato debe ser idéntico (1 L = 1000 ml, 1 kg = 1000 g) y también la cantidad del pack
 *       ("6 x 350 cc" no es lo mismo que "350 cc").</li>
 *   <li>Las palabras de variante deben coincidir: Zero, Light, Descremada, sin lactosa, sabores,
 *       retornable, etc. Así no se mezcla una Coca-Cola Zero con la normal.</li>
 *   <li>Cada palabra importante del nombre más corto aparece en el más largo (exacta, dentro del
 *       nombre sin espacios como "7 up" y "7up", o como abreviatura), y el nombre corto cubre al
 *       menos el 60 % de las palabras del largo. Se ignoran palabras de relleno como "bebida",
 *       "botella", "pet", "original" o "desechable".</li>
 * </ol>
 */
final class EquivalenciaProductos {
    private static final Pattern MEDIDA = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(kg|kilos?|grs?|gramos|g|lts?|litros?|l|ml|cc|un|und|unidades)\\b");
    /** "6 x 350 cc", "pack 6", "pack de 6", "x6", "6 unidades x". */
    private static final Pattern PACK = Pattern.compile(
            "\\bpack\\s*(?:de\\s*)?(\\d{1,2})\\b|\\b(\\d{1,2})\\s*x\\s*\\d|\\bx\\s*(\\d{1,2})\\b(?!\\s*(?:kg|g|gr|grs|ml|cc|l|lt|lts))");

    private static final Set<String> RELLENO = Set.of(
            "de", "del", "la", "el", "los", "las", "con", "en", "y", "a", "al", "para", "x", "por",
            "natural", "caja", "bolsa", "botella", "tarro", "sachet", "envase", "lata", "frasco", "pote",
            "unidad", "unidades", "un", "und", "formato", "dp", "c", "u", "pack", "bebida", "pet",
            "desechable", "original", "clasico", "clasica", "tradicional", "producto", "marca",
            "tetra", "tetrapack", "pak", "familiar", "nuevo", "nueva", "oferta", "sabor");

    private static final Set<String> UNIDADES = Set.of(
            "l", "lt", "lts", "litro", "litros", "ml", "cc", "g", "gr", "grs", "gramos", "kg", "kilo", "kilos");

    /** Palabras que distinguen variantes de un mismo producto: si una está en un nombre, debe estar en el otro. */
    private static final Set<String> VARIANTES = Set.of(
            "zero", "light", "diet", "descremada", "descremado", "semidescremada", "semidescremado",
            "entera", "entero", "deslactosada", "deslactosado", "chocolate", "frutilla", "vainilla",
            "durazno", "manjar", "naranja", "limon", "pina", "mango", "frambuesa", "mora", "platano",
            "coco", "menta", "integral", "retornable", "picante", "ahumado", "ahumada", "laminado",
            "rallado", "molido", "molida", "grano", "instantaneo", "descafeinado", "liquido", "polvo",
            "gel", "barra", "infantil", "adulto", "cachorro", "gatito", "senior", "grado1", "grado2",
            "grado3", "blanco", "blanca", "negro", "negra", "rojo", "roja", "verde", "tinto", "rose");

    // Huella de un nombre: medida, palabras clave, texto compacto y variantes (sabor, light…).
    record Firma(String medida, List<String> palabras, String compacto, Set<String> variantes) {}

    private EquivalenciaProductos() {}

    // Calcula la huella de un nombre de producto.
    static Firma firma(String nombre) {
        String texto = Normalizer.normalize(nombre, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replace('·', ' ').replace('-', ' ')
                .replaceAll("\\bgrado\\s*(\\d)\\b", "grado$1")
                .replaceAll("\\bg\\s*([123])\\b(?!\\s*(?:kg|g|gr|grs|ml|cc|l|lt|lts))", "grado$1")
                .replaceAll("\\bsin\\s+([a-z]{3,})", "sin$1");
        String medida = null;
        Matcher m = MEDIDA.matcher(texto);
        if (m.find()) {
            medida = medida(m.group(1), m.group(2));
        }
        Matcher pack = PACK.matcher(texto);
        if (medida != null && pack.find()) {
            String cantidad = pack.group(1) != null ? pack.group(1) : pack.group(2) != null ? pack.group(2) : pack.group(3);
            int unidades = Integer.parseInt(cantidad);
            if (unidades > 1) {
                medida = medida + "x" + unidades;
            }
        }
        // Algunas tiendas repiten el formato ("1L 1 L"): se quitan todas las medidas del nombre.
        texto = MEDIDA.matcher(texto).replaceAll(" ");
        // "7 up" y "7up": un número pegado a una palabra corta forma una sola marca.
        texto = texto.replaceAll("\\b(\\d{1,2})\\s+([a-z]{1,3})\\b", "$1$2");
        List<String> palabras = new ArrayList<>();
        Set<String> variantes = new TreeSet<>();
        for (String palabra : texto.split("[^a-z0-9]+")) {
            if (palabra.isEmpty() || RELLENO.contains(palabra) || UNIDADES.contains(palabra)
                    || palabra.matches("\\d+")) {
                continue;
            }
            palabra = singular(palabra);
            if (VARIANTES.contains(palabra) || (palabra.startsWith("sin") && palabra.length() > 5)) {
                variantes.add(palabra.replaceAll("(a|o)$", ""));
            }
            if (palabra.length() > 1 || palabra.matches("[a-z]")) {
                palabras.add(palabra);
            }
        }
        palabras.removeIf(palabra -> palabra.length() == 1);
        return new Firma(medida, palabras, String.join("", palabras), variantes);
    }

    /** "huevos" → "huevo", "blancos" → "blanco", "grandes" → "grande". */
    private static String singular(String palabra) {
        return palabra.length() > 4 && palabra.endsWith("s") && !palabra.endsWith("ss")
                && Character.isLetter(palabra.charAt(palabra.length() - 2))
                ? palabra.substring(0, palabra.length() - 1) : palabra;
    }

    // true si dos nombres son el mismo producto.
    static boolean equivalentes(String a, String b) {
        return equivalentes(firma(a), firma(b));
    }

    // Compara huellas: misma medida, mismas variantes y nombres que se cubren.
    static boolean equivalentes(Firma a, Firma b) {
        if (a.medida == null || !a.medida.equals(b.medida) || a.palabras.isEmpty() || b.palabras.isEmpty()
                || !a.variantes.equals(b.variantes)) {
            return false;
        }
        Firma corta = a.palabras.size() <= b.palabras.size() ? a : b;
        Firma larga = corta == a ? b : a;
        // Al menos dos palabras en común, salvo que el nombre corto tenga una sola palabra larga (una marca).
        if (corta.palabras.size() == 1 && corta.palabras.get(0).length() < 5) {
            return false;
        }
        if (corta.palabras.size() * 10 < larga.palabras.size() * 6) {
            return false;
        }
        for (String palabra : corta.palabras) {
            if (!presente(palabra, larga)) {
                return false;
            }
        }
        return true;
    }

    /** La palabra está en el otro nombre: exacta, dentro del nombre sin espacios, o como abreviatura. */
    private static boolean presente(String palabra, Firma otra) {
        if (otra.palabras.contains(palabra)) {
            return true;
        }
        if (palabra.length() >= 3 && otra.compacto.contains(palabra) && !esVariante(palabra, otra)) {
            return true;
        }
        for (String candidata : otra.palabras) {
            // "7up" frente a "7 up", "cocacola" frente a "coca cola".
            if (candidata.length() >= 3 && palabra.contains(candidata) && palabra.length() - candidata.length() <= 4
                    && compactoContiene(palabra, otra)) {
                return true;
            }
            // Abreviaturas: "semidesc" y "semidescremada", "choc" y "chocolate" (mínimo 4 letras).
            if (palabra.length() >= 4 && candidata.length() >= 4
                    && (candidata.startsWith(palabra) || palabra.startsWith(candidata))) {
                return true;
            }
        }
        return false;
    }

    // Busca una palabra en el nombre del otro producto, tolerando espacios.
    private static boolean compactoContiene(String palabra, Firma otra) {
        return otra.compacto.contains(palabra);
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

    /**
     * Raíz de una palabra buscada, para que "tomates" encuentre "Tomate" y "proteina" encuentre
     * "protein": se quita el plural y, en palabras largas, la vocal final.
     */
    static String raiz(String palabra) {
        String raiz = palabra;
        if (raiz.length() > 4 && raiz.endsWith("es") && !raiz.endsWith("ees")) {
            raiz = raiz.substring(0, raiz.length() - 2);
        } else if (raiz.length() > 4 && raiz.endsWith("s")) {
            raiz = raiz.substring(0, raiz.length() - 1);
        }
        if (raiz.length() >= 6 && "aeo".indexOf(raiz.charAt(raiz.length() - 1)) >= 0) {
            raiz = raiz.substring(0, raiz.length() - 1);
        }
        return raiz;
    }

    // Normaliza una medida a gramos o mililitros: 1 kg → 1000 g.
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
