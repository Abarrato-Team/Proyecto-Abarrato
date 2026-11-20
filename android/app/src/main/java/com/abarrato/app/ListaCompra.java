package com.abarrato.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Lista de compras guardada en el teléfono. Cada producto conserva los precios de cada cadena y el supermercado elegido. */
final class ListaCompra {
    static final int LIMITE_PRODUCTOS = 60;
    static final int LIMITE_CANTIDAD = 99;
    private static final String CLAVE = "productos";

    // Un producto de la lista con su cantidad y la tienda que eligió el usuario (si eligió).
    static final class Item {
        final Producto producto;
        final int cantidad;
        /** Supermercado que eligió la persona. Vacío = el más barato. */
        final String comercio;

        Item(Producto producto, int cantidad) {
            this(producto, cantidad, "");
        }

        Item(Producto producto, int cantidad, String comercio) {
            this.producto = producto;
            this.cantidad = cantidad;
            this.comercio = comercio == null ? "" : comercio;
        }

        /** La oferta elegida por la persona o, si no eligió (o ya no existe), la más barata. */
        Producto.Oferta oferta() {
            Producto.Oferta elegida = comercio.isEmpty() ? null : producto.oferta(comercio);
            return elegida != null ? elegida : producto.mejor();
        }

        // Precio de la oferta elegida, o el más barato si no eligió tienda.
        int precio() {
            Producto.Oferta oferta = oferta();
            return oferta == null ? 0 : oferta.precio;
        }

        // true si el usuario eligió una tienda y esa tienda sigue teniendo precio.
        boolean tieneTiendaElegida() {
            return !comercio.isEmpty() && producto.oferta(comercio) != null;
        }
    }

    private final SharedPreferences preferencias;

    /*
     * Caché compartida en memoria: la lista se lee del teléfono una sola vez y se indexa por clave en
     * un HashMap, así cantidad() es O(1) en vez de volver a leer y convertir todo el JSON en cada consulta.
     */
    private static List<Item> cache;
    private static java.util.Map<String, Item> indice;

    ListaCompra(Context contexto) {
        preferencias = contexto.getSharedPreferences("abarrato_lista", Context.MODE_PRIVATE);
    }

    // Productos de la lista (desde la caché en memoria).
    List<Item> items() {
        synchronized (ListaCompra.class) {
            if (cache == null) {
                usar(leer());
            }
            return new ArrayList<>(cache);
        }
    }

    // Reemplaza la caché en memoria.
    private static void usar(List<Item> items) {
        cache = java.util.Collections.unmodifiableList(new ArrayList<>(items));
        java.util.Map<String, Item> mapa = new java.util.HashMap<>();
        for (Item item : items) {
            mapa.put(item.producto.clave, item);
        }
        indice = mapa;
    }

    // Lee la lista guardada en el teléfono.
    private List<Item> leer() {
        List<Item> items = new ArrayList<>();
        try {
            JSONArray guardados = new JSONArray(preferencias.getString(CLAVE, "[]"));
            for (int i = 0; i < guardados.length(); i++) {
                JSONObject guardado = guardados.getJSONObject(i);
                int cantidad = guardado.getInt("cantidad");
                if (cantidad >= 1) {
                    items.add(new Item(Producto.desde(guardado.getJSONObject("producto")),
                            Math.min(cantidad, LIMITE_CANTIDAD), guardado.optString("comercio", "")));
                }
            }
        } catch (JSONException error) {
            android.util.Log.w("Abarrato", "No se pudo leer la lista de compras");
        }
        return items;
    }

    // Cantidad de un producto en la lista (0 si no está).
    int cantidad(String clave) {
        synchronized (ListaCompra.class) {
            if (cache == null) {
                usar(leer());
            }
            Item item = indice.get(clave);
            return item == null ? 0 : item.cantidad;
        }
    }

    /** Supermercado elegido para un producto de la lista, o "" si no hay uno elegido. */
    String comercio(String clave) {
        synchronized (ListaCompra.class) {
            if (cache == null) {
                usar(leer());
            }
            Item item = indice.get(clave);
            return item == null ? "" : item.comercio;
        }
    }

    /** Deja el producto con esa cantidad, comprado en el supermercado elegido. */
    boolean elegir(Producto producto, int cantidad, String comercio) {
        List<Item> items = items();
        int unidades = Math.max(1, Math.min(LIMITE_CANTIDAD, cantidad));
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).producto.clave.equals(producto.clave)) {
                items.set(i, new Item(producto, unidades, comercio));
                return guardar(items);
            }
        }
        if (items.size() >= LIMITE_PRODUCTOS) {
            return false;
        }
        items.add(new Item(producto, unidades, comercio));
        return guardar(items);
    }

    /** Suma o resta unidades. Si el producto ya estaba, se actualizan también sus precios. */
    boolean cambiar(Producto producto, int diferencia) {
        List<Item> items = items();
        for (int i = 0; i < items.size(); i++) {
            Item actual = items.get(i);
            if (actual.producto.clave.equals(producto.clave)) {
                int cantidad = Math.min(LIMITE_CANTIDAD, actual.cantidad + diferencia);
                if (cantidad <= 0) {
                    items.remove(i);
                } else {
                    items.set(i, new Item(producto, cantidad, actual.comercio));
                }
                return guardar(items);
            }
        }
        if (diferencia < 1 || items.size() >= LIMITE_PRODUCTOS) {
            return false;
        }
        items.add(new Item(producto, Math.min(LIMITE_CANTIDAD, diferencia)));
        return guardar(items);
    }

    // Fija la cantidad de un producto (0 lo quita).
    void fijar(Producto producto, int cantidad) {
        cambiar(producto, cantidad - cantidad(producto.clave));
    }

    // Saca un producto de la lista.
    void quitar(String clave) {
        List<Item> items = items();
        items.removeIf(item -> item.producto.clave.equals(clave));
        guardar(items);
    }

    // Borra toda la lista (al confirmar una compra).
    void vaciar() {
        synchronized (ListaCompra.class) {
            usar(new ArrayList<>());
        }
        preferencias.edit().remove(CLAVE).apply();
    }

    // Total de unidades en la lista.
    int unidades() {
        int unidades = 0;
        for (Item item : items()) {
            unidades += item.cantidad;
        }
        return unidades;
    }

    /** Total con el supermercado elegido de cada producto (o el más barato), sin contar el traslado. */
    long totalDesde() {
        long total = 0;
        for (Item item : items()) {
            total += (long) item.cantidad * item.precio();
        }
        return total;
    }

    /** Total si cada producto se compra donde está más caro. Sirve para medir el ahorro. */
    long totalHasta() {
        long total = 0;
        for (Item item : items()) {
            total += (long) item.cantidad * item.producto.precioMax();
        }
        return total;
    }

    /** Cuántas unidades faltan comprar después de descontar lo que ya hay en la despensa. */
    static int porComprar(Item item, Despensa despensa) {
        return Math.max(0, item.cantidad - despensa.cubre(item.producto));
    }

    /** Solicitud para /comparar-carrito con las cantidades que de verdad hay que comprar. */
    static JSONObject solicitud(List<Item> items) throws JSONException {
        JSONArray productos = new JSONArray();
        for (Item item : items) {
            JSONObject linea = new JSONObject().put("clave", item.producto.clave).put("cantidad", item.cantidad);
            if (!item.producto.busqueda.isEmpty()) {
                linea.put("busqueda", item.producto.busqueda);
            }
            Producto.Oferta elegida = item.oferta();
            if (elegida != null && !elegida.productoId.isEmpty()) {
                linea.put("comercio", elegida.comercio).put("productoId", elegida.productoId);
            }
            productos.put(linea);
        }
        return new JSONObject().put("productos", productos);
    }

    // Guarda la lista en el teléfono.
    private boolean guardar(List<Item> items) {
        JSONArray datos = new JSONArray();
        try {
            for (Item item : items) {
                datos.put(new JSONObject().put("producto", item.producto.json()).put("cantidad", item.cantidad)
                        .put("comercio", item.comercio));
            }
        } catch (JSONException error) {
            return false;
        }
        synchronized (ListaCompra.class) {
            usar(items);
        }
        preferencias.edit().putString(CLAVE, datos.toString()).apply();
        return true;
    }
}
