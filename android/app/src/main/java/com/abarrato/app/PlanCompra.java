package com.abarrato.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elige en qué supermercados comprar cada producto.
 *
 * Se prueban todas las combinaciones de las cadenas cercanas (como máximo cuatro, es decir
 * quince combinaciones). Cada producto se asigna a la tienda más barata de la combinación y
 * cada tienda suma su viaje de ida y vuelta. Gana el plan que cubre más productos y, entre
 * ellos, el de menor costo total: compra + traslado + minutos × valor del minuto. El valor
 * del minuto depende de la prioridad elegida en el Perfil (ahorrar dinero o ahorrar tiempo).
 */
final class PlanCompra {
    static final Comparator<Long> COSTOS = Comparator.nullsLast(Comparator.naturalOrder());

    // Una cadena cercana con sus precios y el costo de llegar.
    static final class Tienda {
        final String id;
        final Map<String, Long> precios;
        final Traslado.Estimacion viaje;

        Tienda(String id, Map<String, Long> precios, Traslado.Estimacion viaje) {
            this.id = id;
            this.precios = precios;
            this.viaje = viaje;
        }
    }

    final Map<Tienda, List<String>> compras = new LinkedHashMap<>();
    final List<String> faltantes = new ArrayList<>();
    long subtotal;
    Long transporte = 0L;
    int minutos;

    // Arma un plan con un subconjunto de tiendas (máscara de bits): cada producto va a la más barata.
    private PlanCompra(Collection<String> productos, List<Tienda> tiendas, int seleccion) {
        for (String clave : productos) {
            Tienda elegida = null;
            long precio = Long.MAX_VALUE;
            for (int i = 0; i < tiendas.size(); i++) {
                Tienda tienda = tiendas.get(i);
                Long candidato = tienda.precios.get(clave);
                if ((seleccion & (1 << i)) != 0 && candidato != null && candidato < precio) {
                    elegida = tienda;
                    precio = candidato;
                }
            }
            if (elegida == null) {
                faltantes.add(clave);
            } else {
                compras.computeIfAbsent(elegida, ignorada -> new ArrayList<>()).add(clave);
                subtotal += precio;
            }
        }
        for (Tienda tienda : compras.keySet()) {
            Long costo = tienda.viaje.costoClp;
            transporte = transporte == null || costo == null ? null : transporte + costo;
            minutos += tienda.viaje.minutos;
        }
    }

    // Prueba todas las combinaciones de tiendas y se queda con el mejor plan.
    static PlanCompra elegir(Collection<String> productos, List<Tienda> tiendas, int valorMinuto) {
        Comparator<PlanCompra> orden = Comparator.comparingInt((PlanCompra plan) -> plan.faltantes.size())
                .thenComparing(plan -> plan.puntaje(valorMinuto), COSTOS)
                .thenComparingInt(plan -> plan.compras.size());
        PlanCompra mejor = new PlanCompra(productos, tiendas, 0);
        int limite = Math.min(tiendas.size(), 8);
        for (int seleccion = 1; seleccion < (1 << limite); seleccion++) {
            PlanCompra plan = new PlanCompra(productos, tiendas, seleccion);
            if (orden.compare(plan, mejor) < 0) {
                mejor = plan;
            }
        }
        return mejor;
    }

    /** Plan con una sola tienda, la más conveniente que tenga todos los productos. */
    static PlanCompra unaTienda(Collection<String> productos, List<Tienda> tiendas, int valorMinuto) {
        PlanCompra mejor = null;
        for (int i = 0; i < Math.min(tiendas.size(), 30); i++) {
            PlanCompra plan = new PlanCompra(productos, tiendas, 1 << i);
            if (plan.faltantes.isEmpty() && (mejor == null
                    || COSTOS.compare(plan.puntaje(valorMinuto), mejor.puntaje(valorMinuto)) < 0)) {
                mejor = plan;
            }
        }
        return mejor;
    }

    // Productos + transporte, o null si no se conoce el costo del viaje.
    Long totalConocido() {
        return transporte == null ? null : subtotal + transporte;
    }

    // Total que se compara entre planes: dinero + minutos valorizados.
    Long puntaje(int valorMinuto) {
        Long total = totalConocido();
        return total == null ? null : total + (long) minutos * valorMinuto;
    }
}
