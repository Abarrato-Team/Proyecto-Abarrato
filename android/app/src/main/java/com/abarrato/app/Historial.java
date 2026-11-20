package com.abarrato.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Compras confirmadas, último plan calculado y presupuesto. Todo se guarda en el teléfono. */
final class Historial {
    private static final long SEMANA_MS = 7L * 24 * 60 * 60 * 1000;

    static final class Compra {
        final long fecha;
        final long total;
        final long ahorro;
        final String comercio;
        final int productos;
        final int tiendas;
        final int minutos;

        Compra(long fecha, long total, long ahorro, String comercio, int productos, int tiendas, int minutos) {
            this.fecha = fecha;
            this.total = total;
            this.ahorro = ahorro;
            this.comercio = comercio;
            this.productos = productos;
            this.tiendas = tiendas;
            this.minutos = minutos;
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("fecha", fecha).put("total", total).put("ahorro", ahorro)
                    .put("comercio", comercio).put("productos", productos).put("tiendas", tiendas)
                    .put("minutos", minutos);
        }

        static Compra desde(JSONObject json) {
            return new Compra(json.optLong("fecha"), json.optLong("total"), json.optLong("ahorro"),
                    json.optString("comercio"), json.optInt("productos"), json.optInt("tiendas"),
                    json.optInt("minutos"));
        }
    }

    private final SharedPreferences preferencias;

    Historial(Context contexto) {
        preferencias = contexto.getSharedPreferences("abarrato_historial", Context.MODE_PRIVATE);
    }

    List<Compra> compras() {
        List<Compra> compras = new ArrayList<>();
        try {
            JSONArray guardadas = new JSONArray(preferencias.getString("compras", "[]"));
            for (int i = 0; i < guardadas.length(); i++) {
                compras.add(Compra.desde(guardadas.getJSONObject(i)));
            }
        } catch (JSONException error) {
            android.util.Log.w("Abarrato", "No se pudo leer el historial");
        }
        return compras;
    }

    void registrar(Compra compra) {
        List<Compra> compras = compras();
        compras.add(0, compra);
        JSONArray datos = new JSONArray();
        try {
            for (int i = 0; i < Math.min(50, compras.size()); i++) {
                datos.put(compras.get(i).json());
            }
        } catch (JSONException error) {
            return;
        }
        preferencias.edit().putString("compras", datos.toString()).remove("plan").apply();
    }

    Compra ultima() {
        List<Compra> compras = compras();
        return compras.isEmpty() ? null : compras.get(0);
    }

    /** Total gastado en las compras confirmadas de los últimos 7 días. */
    long gastoSemana() {
        long desde = System.currentTimeMillis() - SEMANA_MS;
        long gasto = 0;
        for (Compra compra : compras()) {
            if (compra.fecha >= desde) {
                gasto += compra.total;
            }
        }
        return gasto;
    }

    long ahorroSemana() {
        long desde = System.currentTimeMillis() - SEMANA_MS;
        long ahorro = 0;
        for (Compra compra : compras()) {
            if (compra.fecha >= desde) {
                ahorro += compra.ahorro;
            }
        }
        return ahorro;
    }

    /** Último plan calculado en Optimizar, válido mientras la lista no cambie. */
    void guardarPlan(String firmaLista, Compra plan) {
        try {
            preferencias.edit().putString("plan", plan.json().put("firma", firmaLista).toString()).apply();
        } catch (JSONException ignored) {
            // El resumen de Inicio usa la estimación de la lista si no hay plan guardado.
        }
    }

    Compra plan(String firmaLista) {
        try {
            JSONObject plan = new JSONObject(preferencias.getString("plan", "{}"));
            return firmaLista.equals(plan.optString("firma", null)) ? Compra.desde(plan) : null;
        } catch (JSONException error) {
            return null;
        }
    }

    int presupuesto() {
        return preferencias.getInt("presupuesto", 0);
    }

    void fijarPresupuesto(int pesos) {
        preferencias.edit().putInt("presupuesto", Math.max(0, pesos)).apply();
    }

    static String firma(List<ListaCompra.Item> items) {
        StringBuilder firma = new StringBuilder();
        for (ListaCompra.Item item : items) {
            firma.append(item.producto.clave).append('×').append(item.cantidad).append(';');
        }
        return firma.toString();
    }
}
