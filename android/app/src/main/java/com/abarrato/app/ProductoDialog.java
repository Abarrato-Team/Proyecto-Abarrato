package com.abarrato.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ficha de un producto: foto, precio en cada supermercado y botón para agregarlo a la lista
 * de compras o a la despensa.
 */
final class ProductoDialog extends Dialog {
    // A dónde se agrega el producto desde la ficha.
    enum Destino { LISTA, DESPENSA }

    private final Ui ui;
    private final Producto producto;
    private final Destino destino;
    private final Runnable alCambiar;
    private final ListaCompra lista;
    private final Despensa despensa;
    private int cantidad;
    private LinearLayout acciones;
    private LinearLayout tarjetaPrecios;
    /** Supermercado elegido en la ficha. Se puede tocar cualquier fila que tenga precio. */
    private String elegido;
    /** Supermercado que el usuario había elegido antes en su lista ("" si no eligió). */
    private final String guardado;

    ProductoDialog(Activity actividad, Producto producto, Destino destino, Runnable alCambiar) {
        super(actividad);
        this.ui = new Ui(actividad);
        this.producto = producto;
        this.destino = destino;
        this.alCambiar = alCambiar;
        lista = new ListaCompra(actividad);
        despensa = new Despensa(actividad);
        int actual = destino == Destino.LISTA ? lista.cantidad(producto.clave) : despensa.cantidad(producto.clave);
        cantidad = Math.max(1, actual);
        guardado = destino == Destino.LISTA ? lista.comercio(producto.clave) : "";
        Producto.Oferta mejor = producto.mejor();
        elegido = producto.oferta(guardado) != null ? guardado : (mejor == null ? "" : mejor.comercio);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = ui.columna();
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(Ui.PAPEL);
        float radio = ui.dp(26);
        fondo.setCornerRadii(new float[]{radio, radio, radio, radio, 0, 0, 0, 0});
        panel.setBackground(fondo);

        View agarre = new View(actividad);
        agarre.setBackground(ui.fondo(0x3311281F, 3, 0));
        LinearLayout.LayoutParams posicion = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(5));
        posicion.gravity = Gravity.CENTER_HORIZONTAL;
        posicion.topMargin = ui.dp(10);
        panel.addView(agarre, posicion);

        ScrollView scroll = new ScrollView(actividad);
        scroll.setVerticalScrollBarEnabled(false);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout contenido = ui.columna();
        contenido.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(12));
        scroll.addView(contenido);

        cabecera(contenido);
        precios(contenido);

        acciones = ui.columna();
        acciones.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(18));
        panel.addView(acciones);
        dibujarAcciones();
        setContentView(panel);
        setCanceledOnTouchOutside(true);
    }

    // Muestra la ficha del producto.
    @Override
    public void show() {
        super.show();
        Window ventana = getWindow();
        if (ventana == null) {
            return;
        }
        ventana.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        ventana.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        ventana.setDimAmount(0.45f);
        ventana.setGravity(Gravity.BOTTOM);
        int alto = getContext().getResources().getDisplayMetrics().heightPixels;
        ventana.setLayout(-1, Math.round(alto * 0.86f));
    }

    // Foto, nombre y formato.
    private void cabecera(LinearLayout contenido) {
        FrameLayout foto = ui.foto(producto.urlImagen, producto.nombre, producto.emoji(), 150);
        LinearLayout.LayoutParams centro = new LinearLayout.LayoutParams(ui.dp(150), ui.dp(150));
        centro.gravity = Gravity.CENTER_HORIZONTAL;
        centro.topMargin = ui.dp(8);
        foto.setBackground(ui.fondo(Ui.BLANCO, 28, Ui.BORDE));
        contenido.addView(foto, centro);

        TextView nombre = ui.texto(contenido, producto.nombre, 22, Ui.TEXTO, Ui.GRUESA);
        nombre.setPadding(0, ui.dp(14), 0, 0);
        ui.texto(contenido, producto.detalle(), 14, Ui.TINTA, Ui.NORMAL);

        LinearLayout resumen = ui.fila();
        resumen.setPadding(0, ui.dp(8), 0, 0);
        contenido.addView(resumen);
        ui.texto(resumen, "Desde ", 15, Ui.TINTA, Ui.NORMAL);
        ui.texto(resumen, Ui.pesos(producto.precioMin()), 22, Ui.VERDE, Ui.GRUESA);
        if (producto.precioMax() > producto.precioMin()) {
            TextView ahorro = ui.texto(resumen, "  Ahorras hasta " + Ui.pesos(producto.precioMax() - producto.precioMin()),
                    13, Ui.NARANJA, Ui.NEGRITA);
            ahorro.setPadding(ui.dp(8), 0, 0, 0);
        }
    }

    // Sección de precios por tienda.
    private void precios(LinearLayout contenido) {
        ui.seccion(contenido, destino == Destino.LISTA
                ? "ELIGE DÓNDE COMPRARLO" : "PRECIOS EN LOS SUPERMERCADOS");
        // Si la tienda que el usuario había elegido ya no tiene el producto, se avisa dónde sí está.
        if (!guardado.isEmpty() && producto.oferta(guardado) == null) {
            String otras = producto.disponibleEn(guardado);
            TextView aviso = ui.texto(contenido, otras.isEmpty()
                    ? "Ya no aparece en " + Tiendas.nombre(guardado) + " ni en otro supermercado."
                    : "Ya no aparece en " + Tiendas.nombre(guardado) + ". Está disponible en " + otras + ".",
                    13, Ui.NARANJA, Ui.NEGRITA);
            aviso.setBackground(ui.fondo(Ui.AMBAR_FONDO, 10, 0));
            aviso.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        }
        tarjetaPrecios = ui.tarjeta(contenido, Ui.BLANCO, 8);
        tarjetaPrecios.setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6));
        dibujarPrecios();
        ui.texto(contenido, "Toca un supermercado para elegirlo. Precios web publicados por cada cadena, "
                + "en pesos, por unidad. El stock de cada sucursal puede variar.", 12, Ui.TINTA, Ui.NORMAL)
                .setPadding(0, ui.dp(10), 0, 0);
    }

    // Filas de precio por tienda: se tocan para elegir; muestra oferta y cambio desde ayer.
    private void dibujarPrecios() {
        tarjetaPrecios.removeAllViews();
        boolean primero = true;
        for (String comercio : Tiendas.IDS) {
            if (!primero) {
                tarjetaPrecios.addView(ui.separador());
            }
            primero = false;
            Producto.Oferta oferta = producto.oferta(comercio);
            boolean seleccionada = oferta != null && comercio.equals(elegido);
            LinearLayout fila = ui.fila();
            fila.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6));
            tarjetaPrecios.addView(fila);
            if (destino == Destino.LISTA) {
                TextView marca = ui.texto(fila, oferta == null ? " " : (seleccionada ? "◉" : "○"), 20,
                        seleccionada ? Ui.VERDE : Ui.TINTA, Ui.NEGRITA);
                marca.setPadding(0, 0, ui.dp(8), 0);
            }
            fila.addView(ui.logo(comercio, 58, 40));
            LinearLayout datos = ui.columna();
            datos.setPadding(ui.dp(12), 0, ui.dp(8), 0);
            ui.flexible(fila, datos);
            ui.texto(datos, Tiendas.nombre(comercio), 16, Ui.TEXTO, Ui.NEGRITA);
            if (oferta == null) {
                // No está en esta tienda: se indica en qué otros supermercados sí está.
                String otras = producto.disponibleEn(comercio);
                ui.texto(datos, otras.isEmpty() ? "No aparece en su catálogo web"
                        : "No disponible aquí · sí en " + otras, 12, Ui.TINTA, Ui.NORMAL);
                ui.texto(fila, "—", 18, Ui.TINTA, Ui.NEGRITA);
                continue;
            }
            String hora = hora(oferta.observadoEn);
            ui.texto(datos, (oferta.vigente ? "Consultado " : "Última lectura ") + hora, 12, Ui.TINTA, Ui.NORMAL);
            if (oferta.precioAntes > 0) {
                LinearLayout rebaja = ui.fila();
                datos.addView(rebaja);
                ui.texto(rebaja, "Oferta · antes ", 12, Ui.NARANJA, Ui.NEGRITA);
                Ui.tachar(ui.texto(rebaja, Ui.pesos(oferta.precioAntes), 12, Ui.NARANJA, Ui.NEGRITA));
            }
            if (oferta.precioOferta > 0) {
                // El precio grande ya es el de la promoción: aquí se muestra el normal y la condición.
                LinearLayout promocion = ui.fila();
                datos.addView(promocion);
                ui.texto(promocion, oferta.condicionOferta + " · normal ", 12, Ui.NARANJA, Ui.NEGRITA);
                Ui.tachar(ui.texto(promocion, Ui.pesos(oferta.precioNormal), 12, Ui.NARANJA, Ui.NEGRITA));
            }
            int cambio = oferta.cambioDesdeAyer();
            if (cambio != 0) {
                ui.texto(datos, (cambio < 0 ? "↓ Bajó " : "↑ Subió ") + Ui.pesos(Math.abs(cambio)) + " desde ayer",
                        12, cambio < 0 ? Ui.VERDE : Ui.NARANJA, Ui.NEGRITA);
            }
            if (oferta.urlFuente.startsWith("https://")) {
                TextView web = ui.texto(datos, "Ver en la web ›", 12, Ui.VERDE, Ui.NEGRITA);
                web.setMinHeight(ui.dp(28));
                web.setGravity(Gravity.CENTER_VERTICAL);
                ui.tocable(web, ui.fondo(seleccionada ? Ui.AMBAR_FONDO : Ui.BLANCO, 6, 0),
                        () -> AjustesTraslado.abrir(getContext(), Uri.parse(oferta.urlFuente)));
                web.setContentDescription("Abrir la página del producto en " + Tiendas.nombre(comercio));
            }
            LinearLayout precio = ui.columna();
            precio.setGravity(Gravity.END);
            fila.addView(precio);
            boolean masBarato = oferta.precio == producto.precioMin() && producto.ofertas.size() > 1;
            ui.texto(precio, Ui.pesos(oferta.precio), 18, masBarato ? Ui.VERDE : Ui.TEXTO, Ui.GRUESA);
            if (masBarato) {
                TextView marca = ui.texto(precio, "Más barato", 11, Ui.BOSQUE, Ui.NEGRITA);
                marca.setBackground(ui.fondo(Ui.AMARILLO, 6, 0));
                marca.setPadding(ui.dp(6), ui.dp(1), ui.dp(6), ui.dp(1));
            }
            if (destino == Destino.LISTA) {
                ui.tocable(fila, ui.fondo(seleccionada ? Ui.AMBAR_FONDO : Ui.BLANCO, 12,
                        seleccionada ? Ui.AMBAR_BORDE : 0), () -> {
                    elegido = comercio;
                    dibujarPrecios();
                    dibujarAcciones();
                });
                fila.setContentDescription(Tiendas.nombre(comercio) + ", " + Ui.pesos(oferta.precio)
                        + (seleccionada ? ". Elegido" : ". Toca para elegir"));
            }
        }
    }

    // Cantidad y botón para agregar a la lista o a la despensa.
    private void dibujarAcciones() {
        acciones.removeAllViews();
        int enLista = lista.cantidad(producto.clave);
        int enCasa = despensa.cantidad(producto.clave);
        if (destino == Destino.LISTA && enCasa > 0) {
            TextView aviso = ui.texto(acciones, "Ya tienes " + enCasa + " en tu despensa", 13, Ui.NARANJA, Ui.NEGRITA);
            aviso.setPadding(0, 0, 0, ui.dp(8));
        }
        LinearLayout fila = ui.fila();
        acciones.addView(fila);
        fila.addView(ui.stepper(cantidad, producto.nombre, () -> {
            if (cantidad > 1) {
                cantidad--;
                dibujarAcciones();
            }
        }, () -> {
            if (cantidad < ListaCompra.LIMITE_CANTIDAD) {
                cantidad++;
                dibujarAcciones();
            }
        }));
        fila.addView(ui.espacio(12));
        String texto;
        if (destino == Destino.LISTA) {
            texto = enLista > 0 ? "Actualizar lista" : "Agregar a la lista";
        } else {
            texto = enCasa > 0 ? "Guardar cantidad" : "Agregar a la despensa";
        }
        Producto.Oferta oferta = producto.oferta(elegido);
        int precio = oferta == null ? producto.precioMin() : oferta.precio;
        String tienda = oferta == null ? "" : " en " + Tiendas.nombre(oferta.comercio);
        TextView principal = ui.boton(texto + tienda + " · " + Ui.pesos((long) cantidad * precio),
                Ui.AMARILLO, Ui.BOSQUE, this::confirmar);
        if (destino == Destino.DESPENSA) {
            principal.setText(texto);
        }
        ui.flexible(fila, principal);

        if (destino == Destino.LISTA) {
            TextView secundario = ui.texto(acciones, "📦 Ya lo tengo en casa · agregar a la despensa", 14,
                    Ui.VERDE, Ui.NEGRITA);
            secundario.setGravity(Gravity.CENTER);
            secundario.setMinHeight(ui.dp(44));
            ui.tocable(secundario, ui.fondo(Ui.PAPEL, 12, 0), () -> {
                despensa.cambiar(producto, 1);
                Toast.makeText(getContext(), "Agregado a tu despensa", Toast.LENGTH_SHORT).show();
                alCambiar.run();
                dibujarAcciones();
            });
        } else if (enLista > 0) {
            ui.texto(acciones, "Está en tu lista de compras (" + enLista + ")", 13, Ui.TINTA, Ui.NORMAL)
                    .setPadding(0, ui.dp(8), 0, 0);
        }

        // Si el producto ya está guardado, se puede quitar por completo desde aquí.
        boolean yaGuardado = destino == Destino.LISTA ? enLista > 0 : enCasa > 0;
        if (yaGuardado) {
            String donde = destino == Destino.LISTA ? "la lista" : "la despensa";
            TextView quitar = ui.texto(acciones, "🗑  Quitar de " + donde, 14, ROJO, Ui.NEGRITA);
            quitar.setGravity(Gravity.CENTER);
            quitar.setMinHeight(ui.dp(44));
            quitar.setContentDescription("Quitar " + producto.nombre + " de " + donde);
            LinearLayout.LayoutParams margen = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            margen.topMargin = ui.dp(8);
            quitar.setLayoutParams(margen);
            ui.tocable(quitar, ui.fondo(ROJO_FONDO, 12, 0), () -> {
                if (destino == Destino.LISTA) {
                    lista.quitar(producto.clave);
                } else {
                    despensa.quitar(producto.clave);
                }
                Toast.makeText(getContext(), "Quitado de " + donde, Toast.LENGTH_SHORT).show();
                alCambiar.run();
                dismiss();
            });
        }
    }

    /** Rojo suave para la acción de quitar (no hay un rojo en Ui). */
    private static final int ROJO = 0xFFB3261E;
    private static final int ROJO_FONDO = 0xFFFCEAE8;

    // Guarda la cantidad y la tienda elegida.
    private void confirmar() {
        boolean guardado;
        if (destino == Destino.LISTA) {
            guardado = lista.elegir(producto, cantidad, elegido);
            if (!guardado) {
                Toast.makeText(getContext(), "Tu lista llegó al máximo de productos", Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            despensa.cambiar(producto, cantidad - despensa.cantidad(producto.clave));
        }
        alCambiar.run();
        dismiss();
    }

    private static final Pattern INSTANTE = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})(?::(\\d{2}))?(?:\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?");

    /** Convierte el instante ISO 8601 del servidor (en UTC) a la hora local del teléfono. */
    static String hora(String instante) {
        Matcher partes = INSTANTE.matcher(instante == null ? "" : instante);
        if (!partes.find()) {
            return "recién";
        }
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(Integer.parseInt(partes.group(1)), Integer.parseInt(partes.group(2)) - 1,
                Integer.parseInt(partes.group(3)), Integer.parseInt(partes.group(4)),
                Integer.parseInt(partes.group(5)), partes.group(6) == null ? 0 : Integer.parseInt(partes.group(6)));
        long milisegundos = utc.getTimeInMillis();
        String zona = partes.group(7);
        if (zona != null && !zona.equals("Z")) {
            String digitos = zona.replace(":", "");
            int minutos = Integer.parseInt(digitos.substring(1, 3)) * 60 + Integer.parseInt(digitos.substring(3, 5));
            milisegundos -= (zona.startsWith("-") ? -1L : 1L) * minutos * 60_000L;
        }
        SimpleDateFormat formato = new SimpleDateFormat("HH:mm", Locale.ROOT);
        // Siempre hora de Chile, aunque el teléfono o el emulador tengan otra zona horaria.
        formato.setTimeZone(TimeZone.getTimeZone("America/Santiago"));
        return formato.format(new Date(milisegundos));
    }
}
