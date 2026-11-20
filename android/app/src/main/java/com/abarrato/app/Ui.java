package com.abarrato.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Componentes visuales de Abarrato, con los colores y formas de la presentación. */
final class Ui {
    static final int PAPEL = 0xFFEFF0E9;
    static final int BLANCO = 0xFFFFFFFF;
    static final int BOSQUE = 0xFF11281F;
    static final int BOSQUE_INTERIOR = 0xFF1D372C;
    static final int TEXTO = 0xFF14211B;
    static final int TINTA = 0xFF5B6B62;
    static final int TENUE_OSCURO = 0xFFB3C7BC;
    static final int VERDE = 0xFF1B6B4A;
    static final int VERDE_CLARO = 0xFF86D69B;
    static final int AMARILLO = 0xFFFFCC1A;
    static final int AMBAR_FONDO = 0xFFFFF6DB;
    static final int AMBAR_BORDE = 0xFFF2D58A;
    static final int NARANJA = 0xFFE07A1F;
    static final int GRIS = 0xFFF1F2EE;
    static final int GRIS_LINEA = 0xFFE3E5DF;
    static final int BORDE = 0x1411281F;
    static final int OLIVA = 0xFF26341F;
    static final int OLIVA_BORDE = 0xFF5F6327;

    static final int NORMAL = 0;
    static final int NEGRITA = 1;
    static final int GRUESA = 2;

    private static final Typeface NEGRA = Typeface.create("sans-serif-black", Typeface.NORMAL);
    private static final Typeface MEDIA = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    final Activity actividad;

    Ui(Activity actividad) {
        this.actividad = actividad;
    }

    // Convierte dp (medida independiente de la pantalla) a píxeles.
    int dp(float valor) {
        return Math.round(valor * actividad.getResources().getDisplayMetrics().density);
    }

    // Formatea un monto en pesos chilenos: $1.290.
    static String pesos(long valor) {
        String digitos = Long.toString(Math.abs(valor));
        StringBuilder texto = new StringBuilder();
        for (int i = 0; i < digitos.length(); i++) {
            if (i > 0 && (digitos.length() - i) % 3 == 0) {
                texto.append('.');
            }
            texto.append(digitos.charAt(i));
        }
        return (valor < 0 ? "-$" : "$") + texto;
    }

    // Contenedor vertical.
    LinearLayout columna() {
        LinearLayout vista = new LinearLayout(actividad);
        vista.setOrientation(LinearLayout.VERTICAL);
        return vista;
    }

    // Contenedor horizontal centrado.
    LinearLayout fila() {
        LinearLayout vista = new LinearLayout(actividad);
        vista.setOrientation(LinearLayout.HORIZONTAL);
        vista.setGravity(Gravity.CENTER_VERTICAL);
        return vista;
    }

    // Texto con tamaño, color y peso; se agrega al padre si no es null.
    TextView texto(ViewGroup padre, String contenido, float tamano, int color, int estilo) {
        TextView vista = new TextView(actividad);
        vista.setText(contenido);
        vista.setTextSize(tamano);
        vista.setTextColor(color);
        vista.setIncludeFontPadding(true);
        if (estilo == GRUESA) {
            vista.setTypeface(NEGRA);
        } else if (estilo == NEGRITA) {
            vista.setTypeface(MEDIA, Typeface.BOLD);
        }
        if (padre != null) {
            padre.addView(vista);
        }
        return vista;
    }

    // Texto pequeño en mayúsculas para títulos de sección.
    TextView etiqueta(ViewGroup padre, String contenido, int color) {
        TextView vista = texto(padre, contenido, 12, color, NEGRITA);
        vista.setLetterSpacing(0.06f);
        return vista;
    }

    // Título grande de pestaña con su bajada.
    void titulo(LinearLayout padre, String titulo, String bajada) {
        TextView vista = texto(padre, titulo, 30, TEXTO, GRUESA);
        vista.setPadding(0, dp(18), 0, 0);
        if (Build.VERSION.SDK_INT >= 28) {
            vista.setAccessibilityHeading(true);
        }
        if (bajada != null) {
            texto(padre, bajada, 15, TINTA, NORMAL).setPadding(0, dp(2), 0, dp(6));
        }
    }

    // Encabezado de sección (marcado como encabezado para TalkBack).
    TextView seccion(LinearLayout padre, String titulo) {
        TextView vista = etiqueta(padre, titulo, TINTA);
        vista.setPadding(0, dp(20), 0, dp(2));
        if (Build.VERSION.SDK_INT >= 28) {
            vista.setAccessibilityHeading(true);
        }
        return vista;
    }

    // Fondo redondeado con borde opcional.
    GradientDrawable fondo(int color, float radio, int borde) {
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(color);
        fondo.setCornerRadius(dp(radio));
        if (borde != 0) {
            fondo.setStroke(dp(1), borde);
        }
        return fondo;
    }

    // Fondo circular.
    GradientDrawable circulo(int color) {
        GradientDrawable fondo = new GradientDrawable();
        fondo.setShape(GradientDrawable.OVAL);
        fondo.setColor(color);
        return fondo;
    }

    // Tarjeta con fondo y márgenes.
    LinearLayout tarjeta(LinearLayout padre, int color, int margenSuperior) {
        LinearLayout tarjeta = columna();
        tarjeta.setBackground(fondo(color, 20, color == BLANCO ? BORDE : 0));
        tarjeta.setPadding(dp(18), dp(16), dp(18), dp(16));
        agregar(padre, tarjeta, margenSuperior);
        return tarjeta;
    }

    // Agrega una vista a lo ancho, con margen superior.
    void agregar(LinearLayout padre, View hijo, int margenSuperior) {
        LinearLayout.LayoutParams espacio = new LinearLayout.LayoutParams(-1, -2);
        espacio.topMargin = dp(margenSuperior);
        padre.addView(hijo, espacio);
    }

    // Agrega una vista que ocupa el espacio sobrante de una fila.
    void flexible(LinearLayout padre, View hijo) {
        padre.addView(hijo, new LinearLayout.LayoutParams(0, -2, 1));
    }

    // Hace tocable una vista con efecto de onda.
    void tocable(View vista, Drawable fondo, Runnable accion) {
        vista.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2211281F), fondo, null));
        vista.setClickable(true);
        vista.setFocusable(true);
        vista.setOnClickListener(clic -> accion.run());
    }

    // Botón grande.
    TextView boton(String contenido, int fondo, int color, Runnable accion) {
        TextView boton = texto(null, contenido, 16, color, NEGRITA);
        boton.setGravity(Gravity.CENTER);
        boton.setMinHeight(dp(52));
        boton.setPadding(dp(16), dp(10), dp(16), dp(10));
        tocable(boton, fondo(fondo, 14, fondo == BLANCO ? BORDE : 0), accion);
        return boton;
    }

    // Botón mediano.
    TextView botonPequeno(String contenido, int fondo, int color, Runnable accion) {
        TextView boton = texto(null, contenido, 14, color, NEGRITA);
        boton.setGravity(Gravity.CENTER);
        boton.setMinHeight(dp(40));
        boton.setPadding(dp(16), dp(6), dp(16), dp(6));
        tocable(boton, fondo(fondo, 10, 0), accion);
        return boton;
    }

    // Botón de filtro, activo o inactivo.
    TextView chip(String contenido, boolean activo, Runnable accion) {
        TextView chip = texto(null, contenido, 14, activo ? BLANCO : TEXTO, NEGRITA);
        chip.setGravity(Gravity.CENTER);
        chip.setMinHeight(dp(42));
        chip.setPadding(dp(16), dp(6), dp(16), dp(6));
        tocable(chip, fondo(activo ? BOSQUE : BLANCO, 22, activo ? 0 : GRIS_LINEA), accion);
        chip.setSelected(activo);
        return chip;
    }

    /** Selector "- 1 +" de la presentación. */
    LinearLayout stepper(int cantidad, String nombre, Runnable menos, Runnable mas) {
        LinearLayout caja = fila();
        caja.setBackground(fondo(GRIS, 10, 0));
        TextView restar = texto(caja, "−", 20, TEXTO, NEGRITA);
        TextView valor = texto(caja, Integer.toString(cantidad), 17, TEXTO, GRUESA);
        TextView sumar = texto(caja, "+", 20, TEXTO, NEGRITA);
        valor.setGravity(Gravity.CENTER);
        valor.setMinWidth(dp(22));
        for (TextView boton : new TextView[]{restar, sumar}) {
            boton.setGravity(Gravity.CENTER);
            boton.setMinWidth(dp(40));
            boton.setMinHeight(dp(40));
        }
        tocable(restar, fondo(GRIS, 10, 0), menos);
        tocable(sumar, fondo(GRIS, 10, 0), mas);
        restar.setContentDescription("Quitar una unidad de " + nombre);
        sumar.setContentDescription("Agregar una unidad de " + nombre);
        valor.setContentDescription(cantidad + (cantidad == 1 ? " unidad" : " unidades"));
        return caja;
    }

    /** Foto circular del producto con el emoji de su categoría mientras carga. */
    FrameLayout foto(String url, String nombre, String emoji, int tamano) {
        FrameLayout marco = FotosProducto.vista(actividad, url, nombre, emoji, Math.max(14, tamano / 3));
        marco.setBackground(circulo(GRIS));
        marco.setClipToOutline(true);
        int borde = Math.max(2, tamano / 10);
        marco.setPadding(dp(borde), dp(borde), dp(borde), dp(borde));
        marco.setLayoutParams(new LinearLayout.LayoutParams(dp(tamano), dp(tamano)));
        return marco;
    }

    // Logo de una cadena de supermercados.
    ImageView logo(String comercio, int ancho, int alto) {
        ImageView logo = new ImageView(actividad);
        logo.setImageResource(Tiendas.logo(comercio));
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setContentDescription(Tiendas.nombre(comercio));
        logo.setBackground(fondo(BLANCO, 10, GRIS_LINEA));
        logo.setPadding(dp(5), dp(4), dp(5), dp(4));
        logo.setLayoutParams(new LinearLayout.LayoutParams(dp(ancho), dp(alto)));
        return logo;
    }

    /** Insignia redonda con el nombre de la cadena, como "Lider" en la tarjeta de Inicio. */
    TextView insignia(String comercio, int tamano) {
        TextView vista = texto(null, Tiendas.insignia(comercio), 11, BLANCO, NEGRITA);
        vista.setGravity(Gravity.CENTER);
        vista.setBackground(circulo(Tiendas.color(comercio)));
        vista.setLayoutParams(new LinearLayout.LayoutParams(dp(tamano), dp(tamano)));
        return vista;
    }

    // Barra de progreso (con descripción para TalkBack).
    View barra(int porcentaje, int color, int fondo) {
        FrameLayout pista = new FrameLayout(actividad);
        pista.setBackground(this.fondo(fondo, 4, 0));
        LinearLayout relleno = fila();
        View avance = new View(actividad);
        avance.setBackground(this.fondo(color, 4, 0));
        relleno.addView(avance, new LinearLayout.LayoutParams(0, -1, Math.max(0, porcentaje)));
        relleno.addView(new View(actividad), new LinearLayout.LayoutParams(0, -1, Math.max(0, 100 - porcentaje)));
        pista.addView(relleno, new FrameLayout.LayoutParams(-1, -1));
        pista.setContentDescription(porcentaje + " por ciento");
        pista.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(6)));
        return pista;
    }

    // Línea divisoria.
    View separador() {
        View linea = new View(actividad);
        linea.setBackgroundColor(GRIS_LINEA);
        LinearLayout.LayoutParams espacio = new LinearLayout.LayoutParams(-1, dp(1));
        espacio.topMargin = dp(12);
        espacio.bottomMargin = dp(12);
        linea.setLayoutParams(espacio);
        return linea;
    }

    // Tacha un texto (precio anterior).
    static void tachar(TextView vista) {
        vista.setPaintFlags(vista.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
    }

    /** Espacio horizontal de separación. */
    View espacio(int ancho) {
        View vista = new View(actividad);
        vista.setLayoutParams(new LinearLayout.LayoutParams(dp(ancho), 1));
        return vista;
    }
}
