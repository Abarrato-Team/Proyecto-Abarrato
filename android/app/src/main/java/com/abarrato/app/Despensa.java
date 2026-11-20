package com.abarrato.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Productos que el usuario ya tiene en casa. Se descuentan al optimizar la compra. */
final class Despensa {
    static final int META_PRODUCTOS = 12;
    static final int LIMITE_CANTIDAD = 99;
    private static final String CLAVE = "productos";
    private static final Pattern MEDIDA = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(kg|kilos?|grs?|gramos|g|lts?|litros?|l|ml|cc|un|und|unidades)\\b");

    // Un producto de la despensa con su cantidad.
    static final class Item {
        final Producto producto;
        final int cantidad;

        Item(Producto producto, int cantidad) {
            this.producto = producto;
            this.cantidad = cantidad;
        }

        // Porciones aproximadas de este producto según su formato y cantidad.
        int porciones() {
            return Despensa.porciones(producto.nombre) * cantidad;
        }

        // true si el usuario lo escribió a mano (no viene de una tienda).
        boolean manual() {
            return producto.clave.startsWith("manual:");
        }
    }

    private final SharedPreferences preferencias;

    /* Caché en memoria: HashMap por clave (O(1)) y aparte los productos anotados a mano. */
    private static List<Item> cache;
    private static java.util.Map<String, Item> indice;
    private static List<Item> manuales;

    Despensa(Context contexto) {
        preferencias = contexto.getSharedPreferences("abarrato_despensa", Context.MODE_PRIVATE);
    }

    // Productos de la despensa (desde la caché en memoria).
    List<Item> items() {
        synchronized (Despensa.class) {
            if (cache == null) {
                usar(leer());
            }
            return new ArrayList<>(cache);
        }
    }

    // Reemplaza la caché en memoria.
    private static void usar(List<Item> items) {
        cache = Collections.unmodifiableList(new ArrayList<>(items));
        java.util.Map<String, Item> mapa = new java.util.HashMap<>();
        List<Item> aMano = new ArrayList<>();
        for (Item item : items) {
            mapa.put(item.producto.clave, item);
            if (item.manual()) {
                aMano.add(item);
            }
        }
        indice = mapa;
        manuales = aMano;
    }

    // Lee la despensa guardada en el teléfono (SharedPreferences, JSON).
    private List<Item> leer() {
        List<Item> items = new ArrayList<>();
        try {
            JSONArray guardados = new JSONArray(preferencias.getString(CLAVE, "[]"));
            for (int i = 0; i < guardados.length(); i++) {
                JSONObject guardado = guardados.getJSONObject(i);
                int cantidad = guardado.getInt("cantidad");
                if (cantidad >= 1) {
                    items.add(new Item(Producto.desde(guardado.getJSONObject("producto")), cantidad));
                }
            }
        } catch (JSONException error) {
            android.util.Log.w("Abarrato", "No se pudo leer la despensa");
        }
        return items;
    }

    // Crea un producto escrito a mano por el usuario.
    static Producto manual(String nombre) {
        String limpio = nombre.trim();
        return new Producto("manual:" + Producto.normalizar(limpio), limpio, "", "", Collections.emptyList());
    }

    // Cantidad de un producto en la despensa (0 si no está).
    int cantidad(String clave) {
        synchronized (Despensa.class) {
            if (cache == null) {
                usar(leer());
            }
            Item item = indice.get(clave);
            return item == null ? 0 : item.cantidad;
        }
    }

    // Suma o resta unidades de un producto (entre 0 y 99).
    boolean cambiar(Producto producto, int diferencia) {
        List<Item> items = items();
        for (int i = 0; i < items.size(); i++) {
            Item actual = items.get(i);
            if (actual.producto.clave.equals(producto.clave)) {
                int cantidad = Math.min(LIMITE_CANTIDAD, actual.cantidad + diferencia);
                if (cantidad <= 0) {
                    items.remove(i);
                } else {
                    // Conserva la foto y los precios más recientes que se conozcan.
                    Producto datos = producto.ofertas.isEmpty() && !actual.producto.ofertas.isEmpty()
                            ? actual.producto : producto;
                    items.set(i, new Item(datos, cantidad));
                }
                return guardar(items);
            }
        }
        if (diferencia < 1) {
            return false;
        }
        items.add(0, new Item(producto, Math.min(LIMITE_CANTIDAD, diferencia)));
        return guardar(items);
    }

    // Saca un producto de la despensa.
    void quitar(String clave) {
        List<Item> items = items();
        items.removeIf(item -> item.producto.clave.equals(clave));
        guardar(items);
    }

    /**
     * Unidades de la despensa que corresponden a un producto de la lista. Coincide por la misma
     * identidad de catálogo o, para productos anotados a mano, cuando todas sus palabras aparecen
     * en el nombre del producto.
     */
    int cubre(Producto producto) {
        List<Item> aMano;
        int unidades;
        synchronized (Despensa.class) {
            if (cache == null) {
                usar(leer());
            }
            Item mismo = indice.get(producto.clave);
            unidades = mismo == null ? 0 : mismo.cantidad;
            aMano = manuales;
        }
        if (aMano.isEmpty()) {
            return unidades;
        }
        String nombre = " " + Producto.normalizar(producto.nombre).replaceAll("[^a-z0-9]+", " ") + " ";
        for (Item item : aMano) {
            if (!item.producto.clave.equals(producto.clave)) {
                boolean todas = true;
                for (String palabra : Producto.normalizar(item.producto.nombre).split("[^a-z0-9]+")) {
                    if (palabra.length() > 1 && !nombre.contains(" " + palabra + " ")) {
                        todas = false;
                        break;
                    }
                }
                if (todas) {
                    unidades += item.cantidad;
                }
            }
        }
        return unidades;
    }

    /** Productos de la lista que ya están, al menos en parte, en la despensa. */
    List<ListaCompra.Item> enLista(List<ListaCompra.Item> lista) {
        List<ListaCompra.Item> coincidencias = new ArrayList<>();
        for (ListaCompra.Item item : lista) {
            if (cubre(item.producto) > 0) {
                coincidencias.add(item);
            }
        }
        return coincidencias;
    }

    /** Lo que se deja de gastar al no comprar las unidades que ya están en casa. */
    long ahorroPotencial(List<ListaCompra.Item> lista) {
        long ahorro = 0;
        for (ListaCompra.Item item : lista) {
            int cubiertas = Math.min(item.cantidad, cubre(item.producto));
            ahorro += (long) cubiertas * item.producto.precioMin();
        }
        return ahorro;
    }

    // Comidas aproximadas que alcanza la despensa (2 porciones por comida).
    int comidas() {
        int porciones = 0;
        for (Item item : items()) {
            porciones += item.porciones();
        }
        return Math.round(porciones / 2f);
    }

    // Qué tan llena está la despensa respecto de la meta de productos (0 a 100 %).
    int cobertura() {
        return Math.min(100, Math.round(100f * items().size() / META_PRODUCTOS));
    }

    /**
     * Porciones aproximadas por envase, según el formato publicado:
     * 125 g por porción en secos, 50 ml en aceites, 250 ml en otros líquidos y 2 unidades por porción.
     */
    static int porciones(String nombre) {
        String texto = Producto.normalizar(nombre);
        Matcher medida = MEDIDA.matcher(texto);
        if (!medida.find()) {
            return 4;
        }
        double valor;
        try {
            valor = Double.parseDouble(medida.group(1).replace(',', '.'));
        } catch (NumberFormatException error) {
            return 4;
        }
        String unidad = medida.group(2);
        double porciones;
        if (unidad.startsWith("k")) {
            porciones = valor * 1000 / 125;
        } else if (unidad.startsWith("g")) {
            porciones = valor / 125;
        } else if (unidad.equals("ml") || unidad.equals("cc") || unidad.startsWith("l")) {
            double mililitros = unidad.equals("ml") || unidad.equals("cc") ? valor : valor * 1000;
            porciones = mililitros / (texto.contains("aceite") ? 50 : 250);
        } else {
            porciones = valor / 2;
        }
        return (int) Math.max(1, Math.min(200, Math.round(porciones)));
    }

    // Texto como "1 kg · rinde ~8 porciones".
    static String formatoYPorciones(Item item) {
        String formato = item.producto.formato();
        int porciones = Despensa.porciones(item.producto.nombre);
        String rinde = String.format(Locale.ROOT, "rinde ~%d %s", porciones, porciones == 1 ? "porción" : "porciones");
        return formato.isEmpty() ? rinde : formato + " · " + rinde;
    }

    // Guarda la despensa en el teléfono.
    private boolean guardar(List<Item> items) {
        JSONArray datos = new JSONArray();
        try {
            for (Item item : items) {
                datos.put(new JSONObject().put("producto", item.producto.json()).put("cantidad", item.cantidad));
            }
        } catch (JSONException error) {
            return false;
        }
        synchronized (Despensa.class) {
            usar(items);
        }
        preferencias.edit().putString(CLAVE, datos.toString()).apply();
        return true;
    }
}
