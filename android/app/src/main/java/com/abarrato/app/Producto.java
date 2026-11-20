package com.abarrato.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Producto encontrado en los catálogos web, con el precio publicado por cada cadena. */
final class Producto {
    private static final Pattern FORMATO = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(kg|kilos?|grs?|gramos|g|lts?|litros?|l|ml|cc|un|und|unidades)\\b");

    // Precio de un producto en una tienda: final (con promoción si es menor), normal, antes, oferta y ayer.
    static final class Oferta {
        final String comercio;
        final String productoId;
        /**
         * Precio final: el de la promoción si la tienda publica uno más bajo, o el normal. Es el que se
         * usa para comparar, elegir el más barato y calcular totales.
         */
        final int precio;
        /** Precio normal publicado, sin promociones. */
        final int precioNormal;
        final String urlFuente;
        final String observadoEn;
        final boolean vigente;
        /** Precio normal tachado cuando {@code precio} es una rebaja (0 = no hay). */
        final int precioAntes;
        /** Precio especial con condición, por ejemplo con tarjeta (0 = no hay). */
        final int precioOferta;
        final String condicionOferta;
        /** Último precio leído antes de hoy (0 = no se conoce). */
        final int precioAyer;

        Oferta(String comercio, String productoId, int precio, String urlFuente, String observadoEn, boolean vigente) {
            this(comercio, productoId, precio, urlFuente, observadoEn, vigente, 0, 0, "", 0);
        }

        Oferta(String comercio, String productoId, int precio, String urlFuente, String observadoEn, boolean vigente,
                int precioAntes, int precioOferta, String condicionOferta, int precioAyer) {
            this.comercio = comercio;
            this.productoId = productoId;
            this.precioNormal = precio;
            this.urlFuente = urlFuente;
            this.observadoEn = observadoEn;
            this.vigente = vigente;
            this.precioAntes = precioAntes > precio ? precioAntes : 0;
            this.precioOferta = precioOferta > 0 && precioOferta < precio ? precioOferta : 0;
            this.precio = this.precioOferta > 0 ? this.precioOferta : precio;
            this.condicionOferta = this.precioOferta > 0 && condicionOferta != null ? condicionOferta : "";
            this.precioAyer = Math.max(0, precioAyer);
        }

        // true si esta tienda muestra un precio rebajado o una promoción.
        boolean enOferta() {
            return precioAntes > 0 || precioOferta > 0;
        }

        /** Diferencia con el día anterior: negativa si bajó, positiva si subió, 0 si no cambió o no se sabe. */
        int cambioDesdeAyer() {
            // El historial guarda el precio normal, así que se compara con ese.
            return precioAyer > 0 ? precioNormal - precioAyer : 0;
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("comercio", comercio).put("productoId", productoId)
                    .put("precioClp", precioNormal).put("urlFuente", urlFuente)
                    .put("observadoEn", observadoEn).put("vigente", vigente)
                    .put("precioAntesClp", precioAntes).put("precioOfertaClp", precioOferta)
                    .put("condicionOferta", condicionOferta).put("precioAyerClp", precioAyer);
        }

        // Lee una oferta desde el JSON de la API.
        static Oferta desde(JSONObject json) {
            return new Oferta(json.optString("comercio"), json.optString("productoId"),
                    json.optInt("precioClp"), json.optString("urlFuente"),
                    json.optString("observadoEn"), json.optBoolean("vigente", true),
                    json.isNull("precioAntesClp") ? 0 : json.optInt("precioAntesClp"),
                    json.isNull("precioOfertaClp") ? 0 : json.optInt("precioOfertaClp"),
                    json.isNull("condicionOferta") ? "" : json.optString("condicionOferta"),
                    json.isNull("precioAyerClp") ? 0 : json.optInt("precioAyerClp"));
        }
    }

    final String clave;
    final String nombre;
    final String urlImagen;
    final String busqueda;
    final List<Oferta> ofertas;

    Producto(String clave, String nombre, String urlImagen, String busqueda, List<Oferta> ofertas) {
        this.clave = clave;
        this.nombre = nombre;
        this.urlImagen = urlImagen == null ? "" : urlImagen;
        this.busqueda = busqueda == null ? "" : busqueda;
        List<Oferta> ordenadas = new ArrayList<>(ofertas);
        ordenadas.sort(Comparator.comparingInt(oferta -> oferta.precio));
        this.ofertas = Collections.unmodifiableList(ordenadas);
    }

    /** Lee un grupo de la API /en-vivo. Devuelve null si ninguna de las cadenas de Abarrato lo publica. */
    static Producto desdeApi(JSONObject grupo, String busqueda) {
        JSONArray lista = grupo.optJSONArray("ofertas");
        if (lista == null) {
            return null;
        }
        List<Oferta> ofertas = new ArrayList<>();
        for (int i = 0; i < lista.length(); i++) {
            JSONObject json = lista.optJSONObject(i);
            if (json == null) {
                continue;
            }
            Oferta oferta = Oferta.desde(json);
            if (Tiendas.incluida(oferta.comercio) && oferta.precio > 0 && oferta(ofertas, oferta.comercio) == null) {
                ofertas.add(oferta);
            }
        }
        String clave = grupo.optString("claveCarrito");
        String nombre = grupo.optString("nombre").trim();
        if (ofertas.isEmpty() || clave.isEmpty() || nombre.isEmpty()) {
            return null;
        }
        String imagen = grupo.isNull("urlImagen") ? "" : grupo.optString("urlImagen");
        return new Producto(clave, nombre, imagen.startsWith("https://") ? imagen : "", busqueda, ofertas);
    }

    // Lee un producto desde el JSON (de la API o del teléfono).
    static Producto desde(JSONObject json) throws JSONException {
        JSONArray lista = json.getJSONArray("ofertas");
        List<Oferta> ofertas = new ArrayList<>();
        for (int i = 0; i < lista.length(); i++) {
            ofertas.add(Oferta.desde(lista.getJSONObject(i)));
        }
        return new Producto(json.getString("clave"), json.getString("nombre"),
                json.optString("urlImagen"), json.optString("busqueda"), ofertas);
    }

    JSONObject json() throws JSONException {
        JSONArray lista = new JSONArray();
        for (Oferta oferta : ofertas) {
            lista.put(oferta.json());
        }
        return new JSONObject().put("clave", clave).put("nombre", nombre).put("urlImagen", urlImagen)
                .put("busqueda", busqueda).put("ofertas", lista);
    }

    // Busca la oferta de una tienda en una lista.
    private static Oferta oferta(List<Oferta> ofertas, String comercio) {
        for (Oferta oferta : ofertas) {
            if (oferta.comercio.equals(comercio)) {
                return oferta;
            }
        }
        return null;
    }

    // Oferta de una tienda en particular.
    Oferta oferta(String comercio) {
        return oferta(ofertas, comercio);
    }

    /** Si alguna tienda tiene este producto en oferta. */
    boolean enOferta() {
        for (Oferta oferta : ofertas) {
            if (oferta.enOferta()) {
                return true;
            }
        }
        return false;
    }

    /** La mayor baja de precio desde ayer entre las tiendas (negativa), o 0 si ninguna bajó. */
    int mayorBajaDesdeAyer() {
        int baja = 0;
        for (Oferta oferta : ofertas) {
            baja = Math.min(baja, oferta.cambioDesdeAyer());
        }
        return baja;
    }

    /**
     * Supermercados donde sí está el producto, del más barato al más caro, sin contar uno:
     * "Líder $1.290, Unimarc $1.350 y 2 más". Vacío si no está en ningún otro.
     */
    String disponibleEn(String excepto) {
        List<Oferta> otras = new ArrayList<>();
        for (Oferta oferta : ofertas) {
            if (!oferta.comercio.equals(excepto)) {
                otras.add(oferta);
            }
        }
        otras.sort((a, b) -> Integer.compare(a.precio, b.precio));
        List<String> nombres = new ArrayList<>();
        for (int i = 0; i < Math.min(3, otras.size()); i++) {
            nombres.add(Tiendas.nombre(otras.get(i).comercio) + " " + Ui.pesos(otras.get(i).precio));
        }
        if (nombres.isEmpty()) {
            return "";
        }
        String texto = nombres.size() == 1 ? nombres.get(0)
                : String.join(", ", nombres.subList(0, nombres.size() - 1)) + " y " + nombres.get(nombres.size() - 1);
        return otras.size() > 3 ? String.join(", ", nombres) + " y " + (otras.size() - 3) + " más" : texto;
    }

    // Oferta más barata.
    Oferta mejor() {
        return ofertas.isEmpty() ? null : ofertas.get(0);
    }

    // Precio más bajo entre tiendas.
    int precioMin() {
        return ofertas.isEmpty() ? 0 : ofertas.get(0).precio;
    }

    // Precio más alto entre tiendas.
    int precioMax() {
        return ofertas.isEmpty() ? 0 : ofertas.get(ofertas.size() - 1).precio;
    }

    // Formato del envase (1 kg, 900 ml…) sacado del nombre.
    String formato() {
        return formato(nombre);
    }

    static String formato(String nombre) {
        Matcher medida = FORMATO.matcher(normalizar(nombre));
        if (!medida.find()) {
            return "";
        }
        String unidad = switch (medida.group(2)) {
            case "kg", "kilo", "kilos" -> "kg";
            case "g", "gr", "grs", "gramos" -> "g";
            case "l", "lt", "lts", "litro", "litros" -> "L";
            case "ml", "cc" -> "ml";
            default -> "un.";
        };
        return medida.group(1) + " " + unidad;
    }

    /** Detalle breve bajo el nombre, como "1 L · 4 tiendas". */
    String detalle() {
        String formato = formato();
        String tiendas = ofertas.size() == 1 ? "solo en " + Tiendas.nombre(ofertas.get(0).comercio)
                : ofertas.size() + " tiendas";
        return formato.isEmpty() ? tiendas : formato + " · " + tiendas;
    }

    // Emoji según la categoría del producto.
    String emoji() {
        return emoji(nombre);
    }

    static String emoji(String nombre) {
        String texto = normalizar(nombre);
        if (texto.contains("huevo")) return "🥚";
        if (texto.contains("aceite")) return "🫗";
        if (texto.contains("arroz")) return "🍚";
        return switch (CategoriaProducto.identificar(nombre)) {
            case LECHES -> "🥛";
            case YOGURES -> "🥣";
            case QUESOS -> "🧀";
            case CARNES -> "🍗";
            case FRUTAS -> "🍅";
            case PASTAS -> "🍝";
            case PANADERIA -> "🍞";
            case CONSERVAS -> "🥫";
            case LIMPIEZA -> "🧴";
            case BEBIDAS -> "🧃";
            case CAFE -> "☕";
            case CEREALES -> "🥣";
            case DULCES -> "🍪";
            case MASCOTAS -> "🐾";
            case ARROZ -> "🫘";
            case HUEVOS -> "🥚";
            case BEBES -> "🍼";
            case HIGIENE -> "🧼";
            case SALSAS -> "🥫";
            default -> "🛒";
        };
    }

    // Texto sin tildes y en minúsculas.
    static String normalizar(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).trim();
    }
}
