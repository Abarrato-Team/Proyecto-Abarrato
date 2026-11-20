package com.abarrato.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import com.abarrato.app.Traslado.Bencina;
import com.abarrato.app.Traslado.Configuracion;
import com.abarrato.app.Traslado.Medio;
import com.abarrato.app.Traslado.Modo;
import com.abarrato.app.Traslado.Tarifa;

/** Preferencias de traslado del Perfil: modo, bencina, rendimiento, pasaje y prioridad. */
final class AjustesTraslado {
    static final String URL_BENCINA = "https://www.bencinaenlinea.cl/";
    static final String URL_TARIFAS = "https://www.red.cl/tarifas-y-recargas/conoce-las-tarifas/";

    private final SharedPreferences opciones;

    AjustesTraslado(Context contexto) {
        opciones = contexto.getSharedPreferences("abarrato_traslado_v2", Context.MODE_PRIVATE);
    }

    // Lee la configuración de traslado guardada en el teléfono.
    Configuracion leer() {
        Bencina bencina = valor(Bencina.class, "bencina", Bencina.B93);
        return new Configuracion(valor(Modo.class, "modo", Modo.AUTO), valor(Medio.class, "medio", Medio.METRO),
                valor(Tarifa.class, "tarifa", Tarifa.VALLE), bencina,
                opciones.getInt("precio_litro_" + bencina.name(), bencina.precioReferencia),
                opciones.getFloat("kilometros_litro", 12f), opciones.getBoolean("priorizar_tiempo", false));
    }

    // Guarda cómo se mueve el usuario: a pie, en auto o en transporte público.
    void modo(Modo modo) {
        opciones.edit().putString("modo", modo.name()).apply();
    }

    // Guarda el medio de transporte público (micro o metro).
    void medio(Medio medio) {
        opciones.edit().putString("medio", medio.name()).apply();
    }

    // Guarda la tarifa del transporte público.
    void tarifa(Tarifa tarifa) {
        opciones.edit().putString("tarifa", tarifa.name()).apply();
    }

    // Guarda el tipo de bencina del auto.
    void bencina(Bencina bencina) {
        opciones.edit().putString("bencina", bencina.name()).apply();
    }

    // Guarda un precio por litro escrito por el usuario.
    void precioLitro(Bencina bencina, int pesos) {
        opciones.edit().putInt("precio_litro_" + bencina.name(), pesos).apply();
    }

    // Vuelve al precio de referencia de la bencina.
    void restablecerPrecio(Bencina bencina) {
        opciones.edit().remove("precio_litro_" + bencina.name()).apply();
    }

    // Guarda el rendimiento del auto en km/L.
    void rendimiento(double kilometrosPorLitro) {
        opciones.edit().putFloat("kilometros_litro", (float) kilometrosPorLitro).apply();
    }

    // Guarda si el usuario prefiere ahorrar tiempo o dinero.
    void priorizarTiempo(boolean tiempo) {
        opciones.edit().putBoolean("priorizar_tiempo", tiempo).apply();
    }

    /** Selector rápido usado en Optimizar para cambiar cómo se mueve el usuario. */
    void elegirRapido(Activity actividad, Runnable alCambiar) {
        Configuracion actual = leer();
        String[] nombres = {
                "🚶 Caminando · sin costo",
                "🚗 Auto · $" + actual.costoPorKm() + "/km",
                "🚇 Metro · estudiante $260", "🚇 Metro · horario bajo $735",
                "🚇 Metro · horario valle $815", "🚇 Metro · horario alto $895",
                "🚌 Micro · estudiante $260", "🚌 Micro · adulto $795"
        };
        int elegido = switch (actual.modo) {
            case PIE -> 0;
            case AUTO -> 1;
            case PUBLICO -> actual.medio == Medio.MICRO
                    ? (actual.tarifa == Tarifa.ESTUDIANTE ? 6 : 7)
                    : 2 + actual.tarifa.ordinal();
        };
        new AlertDialog.Builder(actividad).setTitle("¿Cómo vas al supermercado?")
                .setSingleChoiceItems(nombres, elegido, (dialogo, opcion) -> {
                    if (opcion == 0) {
                        modo(Modo.PIE);
                    } else if (opcion == 1) {
                        modo(Modo.AUTO);
                    } else {
                        modo(Modo.PUBLICO);
                        medio(opcion >= 6 ? Medio.MICRO : Medio.METRO);
                        if (opcion >= 6) {
                            tarifa(opcion == 6 ? Tarifa.ESTUDIANTE
                                    : actual.tarifa == Tarifa.ESTUDIANTE ? Tarifa.VALLE : actual.tarifa);
                        } else {
                            tarifa(Tarifa.values()[opcion - 2]);
                        }
                    }
                    dialogo.dismiss();
                    alCambiar.run();
                })
                .setNegativeButton("Cancelar", null).show();
    }

    // Lee un valor de opción guardado, o el predeterminado si no existe.
    private <T extends Enum<T>> T valor(Class<T> tipo, String clave, T predeterminado) {
        try {
            return Enum.valueOf(tipo, opciones.getString(clave, predeterminado.name()));
        } catch (IllegalArgumentException error) {
            return predeterminado;
        }
    }

    // Abre un enlace externo (por ejemplo, la fuente de las tarifas).
    static void abrir(Context contexto, Uri destino) {
        try {
            contexto.startActivity(new Intent(Intent.ACTION_VIEW, destino));
        } catch (ActivityNotFoundException ignored) {}
    }
}
