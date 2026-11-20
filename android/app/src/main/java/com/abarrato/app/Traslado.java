package com.abarrato.app;

/**
 * Costo y tiempo de ir y volver a un supermercado.
 *
 * Pasajes Red (Metro, Tren Nos y buses de Santiago) vigentes desde el 22 de febrero de 2026:
 * Metro horario bajo $735, valle $815, punta $895; bus $795 todo el día; estudiante TNE $260.
 * Bencina: promedio de la Región Metropolitana publicado para el 12 de noviembre de 2026
 * (93: $1.567, 95: $1.594, 97: $1.660 por litro). El usuario puede corregir el precio en Perfil.
 */
final class Traslado {
    static final String FECHA_BENCINA = "12-nov-2026";
    static final String FECHA_PASAJES = "22-feb-2026";

    // Cómo se mueve el usuario.
    enum Modo {
        PIE("🚶", "Caminando", "walking"),
        AUTO("🚗", "Auto", "driving"),
        PUBLICO("🚌", "Transporte público", "transit");

        final String icono;
        final String nombre;
        final String modoMapa;

        Modo(String icono, String nombre, String modoMapa) {
            this.icono = icono;
            this.nombre = nombre;
            this.modoMapa = modoMapa;
        }
    }

    // Medio de transporte público.
    enum Medio {
        METRO("🚇", "Metro"), MICRO("🚌", "Micro");

        final String icono;
        final String nombre;

        Medio(String icono, String nombre) {
            this.icono = icono;
            this.nombre = nombre;
        }
    }

    // Tarifas de Red según horario o tipo de pasajero.
    enum Tarifa {
        ESTUDIANTE("Estudiante", "TNE media y superior"),
        BAJO("Bajo", "6:00–6:59 y 20:45–23:00"),
        VALLE("Valle", "9:00–17:59 y 20:00–20:44"),
        ALTO("Alto", "Punta: 7:00–8:59 y 18:00–19:59");

        final String nombre;
        final String horario;

        Tarifa(String nombre, String horario) {
            this.nombre = nombre;
            this.horario = horario;
        }
    }

    // Tipos de bencina con precio de referencia por litro.
    enum Bencina {
        B93("93", 1567), B95("95", 1594), B97("97", 1660);

        final String octanaje;
        final int precioReferencia;

        Bencina(String octanaje, int precioReferencia) {
            this.octanaje = octanaje;
            this.precioReferencia = precioReferencia;
        }
    }

    // Valor del pasaje según medio y tarifa.
    static int pasaje(Medio medio, Tarifa tarifa) {
        if (tarifa == Tarifa.ESTUDIANTE) {
            return 260;
        }
        if (medio == Medio.MICRO) {
            return 795;
        }
        return switch (tarifa) {
            case BAJO -> 735;
            case VALLE -> 815;
            default -> 895;
        };
    }

    // Preferencias de traslado del usuario.
    static final class Configuracion {
        final Modo modo;
        final Medio medio;
        final Tarifa tarifa;
        final Bencina bencina;
        final int precioLitro;
        final double kilometrosPorLitro;
        final boolean priorizarTiempo;

        Configuracion(Modo modo, Medio medio, Tarifa tarifa, Bencina bencina, int precioLitro,
                double kilometrosPorLitro, boolean priorizarTiempo) {
            this.modo = modo;
            this.medio = medio;
            this.tarifa = tarifa;
            this.bencina = bencina;
            this.precioLitro = precioLitro;
            this.kilometrosPorLitro = kilometrosPorLitro;
            this.priorizarTiempo = priorizarTiempo;
        }

        // Pasaje con la configuración actual.
        int pasaje() {
            return Traslado.pasaje(medio, tarifa);
        }

        // Costo de bencina por kilómetro (precio por litro / rendimiento).
        int costoPorKm() {
            return (int) Math.round(precioLitro / kilometrosPorLitro);
        }

        /** Pesos que se le asignan a cada minuto de traslado al comparar planes. */
        int valorMinuto() {
            return priorizarTiempo ? 150 : 20;
        }

        // Texto corto del costo: "Sin costo", "$120/km" o "$820/tramo".
        String resumenCosto() {
            return switch (modo) {
                case PIE -> "Sin costo";
                case AUTO -> "$" + costoPorKm() + "/km";
                case PUBLICO -> "$" + pasaje() + "/tramo";
            };
        }

        // Texto con modo y costo, para la pantalla Optimizar.
        String resumen() {
            return switch (modo) {
                case PIE -> "🚶 Caminando · sin costo";
                case AUTO -> "🚗 Auto · bencina " + bencina.octanaje + " a $" + precioLitro + "/L · "
                        + formatoDecimal(kilometrosPorLitro) + " km/L";
                case PUBLICO -> medio.icono + " " + medio.nombre + " · tarifa " + tarifa.nombre.toLowerCase()
                        + " · $" + pasaje() + " por tramo";
            };
        }
    }

    // Distancia y tiempo reales por calles (de OSRM).
    static final class Ruta {
        final double metrosIdaVuelta;
        final double segundosIdaVuelta;

        Ruta(double metrosIdaVuelta, double segundosIdaVuelta) {
            this.metrosIdaVuelta = metrosIdaVuelta;
            this.segundosIdaVuelta = segundosIdaVuelta;
        }
    }

    // Estimación del viaje: km, minutos, costo y si fue por calles o en línea recta.
    static final class Estimacion {
        final double kilometros;
        final int minutos;
        final Long costoClp;
        final double litros;
        final boolean porCalles;

        Estimacion(double kilometros, int minutos, Long costoClp, double litros, boolean porCalles) {
            this.kilometros = kilometros;
            this.minutos = minutos;
            this.costoClp = costoClp;
            this.litros = litros;
            this.porCalles = porCalles;
        }

        // Compra + transporte (null si no se conoce el transporte).
        Long totalConCompra(Long compraClp) {
            return compraClp == null || costoClp == null ? null : compraClp + costoClp;
        }
    }

    private Traslado() {}

    // Estima el viaje con la ruta real o, si no hay, con la distancia en línea recta.
    static Estimacion estimar(Configuracion ajustes, double metrosEnLineaRecta, Ruta ruta) {
        // Sin ruta por calles se usa la distancia en línea recta, con un factor de recorrido urbano.
        double kilometros = (ruta == null ? metrosEnLineaRecta * 2.6 : ruta.metrosIdaVuelta) / 1000;
        double minutos = switch (ajustes.modo) {
            case PIE -> ruta == null ? kilometros * 60 / 4.5 : ruta.segundosIdaVuelta / 60;
            case AUTO -> (ruta == null ? kilometros * 60 / 25 : ruta.segundosIdaVuelta / 60) + 6;
            // Velocidad comercial urbana más la espera y la caminata de ida y de vuelta.
            case PUBLICO -> kilometros * 60 / (ajustes.medio == Medio.METRO ? 30 : 18)
                    + (ajustes.medio == Medio.METRO ? 22 : 16);
        };
        double litros = ajustes.modo == Modo.AUTO ? kilometros / ajustes.kilometrosPorLitro : 0;
        Long costo = switch (ajustes.modo) {
            case PIE -> 0L;
            case AUTO -> Math.round(litros * ajustes.precioLitro);
            case PUBLICO -> 2L * ajustes.pasaje();
        };
        return new Estimacion(kilometros, (int) Math.ceil(minutos), costo, litros, ruta != null);
    }

    // Número con coma decimal chilena.
    static String formatoDecimal(double valor) {
        return valor == Math.rint(valor) ? Integer.toString((int) valor)
                : String.format(java.util.Locale.ROOT, "%.1f", valor).replace('.', ',');
    }
}
