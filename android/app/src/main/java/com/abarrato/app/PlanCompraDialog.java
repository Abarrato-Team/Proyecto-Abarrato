package com.abarrato.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Diálogo con el detalle del plan de compra: qué comprar en cada tienda y cuánto cuesta.
final class PlanCompraDialog extends Dialog {
    private final Activity actividad;
    private final int bosque;
    private final int tinta;
    private final int papel;
    private final int menta;
    private final int amarillo;
    private final NumberFormat pesos = NumberFormat.getCurrencyInstance(new Locale("es", "CL"));

    PlanCompraDialog(Activity actividad, PlanCompra plan, Map<String, NearbyStores.Store> sucursales,
            Map<String, ListaCompra.Item> productos) {
        super(actividad);
        this.actividad = actividad;
        bosque = actividad.getColor(R.color.forest);
        tinta = actividad.getColor(R.color.ink);
        papel = actividad.getColor(R.color.paper);
        menta = actividad.getColor(R.color.mint);
        amarillo = actividad.getColor(R.color.accent);
        pesos.setMaximumFractionDigits(0);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = columna();
        panel.setBackground(fondo(papel, 24));
        panel.setClipToOutline(true);
        encabezado(panel);

        ScrollView scroll = new ScrollView(actividad);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout contenido = columna();
        contenido.setPadding(dp(16), 0, dp(16), dp(20));
        scroll.addView(contenido);

        resumen(contenido, plan, productos.size());
        if (!plan.compras.isEmpty()) {
            TextView titulo = texto(contenido, "Lista por tienda", 19, bosque, true);
            titulo.setPadding(0, dp(16), 0, 0);
            encabezadoAccesible(titulo);
        }
        int numero = 1;
        for (Map.Entry<PlanCompra.Tienda, List<String>> compra : plan.compras.entrySet()) {
            PlanCompra.Tienda tienda = compra.getKey();
            tarjetaTienda(contenido, numero++, sucursales.get(tienda.id), tienda, compra.getValue(), productos);
        }
        if (!plan.faltantes.isEmpty()) {
            LinearLayout pendientes = tarjeta(contenido, actividad.getColor(R.color.amber), 10);
            encabezadoAccesible(texto(pendientes, "Productos pendientes", 17, bosque, true));
            texto(pendientes, "No aparecen en la web de las tiendas cercanas", 13, tinta, false);
            for (String clave : plan.faltantes) {
                filaProducto(pendientes, productos.get(clave), null);
            }
        }
        texto(contenido, "Precios web de cada cadena, por la cantidad indicada. El stock de la sucursal puede variar. Lo que ya tienes en tu despensa no aparece aquí.", 12, tinta, false)
                .setPadding(0, dp(14), 0, 0);
        setTitle("Tu plan de compra");
        setContentView(panel);
        setCanceledOnTouchOutside(true);
    }

    // Muestra el diálogo a pantalla completa.
    @Override
    public void show() {
        super.show();
        Window ventana = getWindow();
        ventana.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        ventana.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        ventana.setDimAmount(0.55f);
        View pantalla = actividad.findViewById(android.R.id.content);
        ventana.setLayout(Math.min(pantalla.getWidth() - dp(28), dp(550)),
                Math.min(pantalla.getHeight() - dp(36), dp(780)));
    }

    // Título y botón de cerrar.
    private void encabezado(LinearLayout panel) {
        LinearLayout fila = new LinearLayout(actividad);
        fila.setGravity(Gravity.CENTER_VERTICAL);
        fila.setPadding(dp(20), dp(12), dp(12), dp(12));
        panel.addView(fila);
        LinearLayout titulos = columna();
        fila.addView(titulos, new LinearLayout.LayoutParams(0, -2, 1));
        texto(titulos, "ABARRATO  /  OPTIMIZAR", 10, tinta, true).setLetterSpacing(0.12f);
        encabezadoAccesible(texto(titulos, "Tu plan de compra", 24, bosque, true));

        Button cerrar = new Button(actividad);
        cerrar.setText("×");
        cerrar.setTextSize(25);
        cerrar.setTextColor(bosque);
        cerrar.setContentDescription("Cerrar plan de compra");
        cerrar.setPadding(0, 0, 0, 0);
        cerrar.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22748F80), fondo(menta, 24), null));
        cerrar.setOnClickListener(vista -> dismiss());
        fila.addView(cerrar, new LinearLayout.LayoutParams(dp(48), dp(48)));
    }

    // Resumen: cobertura, total y recorrido.
    private void resumen(LinearLayout contenido, PlanCompra plan, int productos) {
        LinearLayout resumen = tarjeta(contenido, bosque, 0);
        GradientDrawable degradado = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{bosque, 0xFF24523D});
        degradado.setCornerRadius(dp(19));
        resumen.setBackground(degradado);
        int viajes = plan.compras.size();
        int cubiertos = productos - plan.faltantes.size();
        boolean completo = plan.faltantes.isEmpty();
        texto(resumen, completo ? "CARRITO COMPLETO" : "COBERTURA PARCIAL", 11, amarillo, true)
                .setLetterSpacing(0.09f);
        Long total = plan.totalConocido();
        String importe = viajes == 0 ? "Por cotizar" : Ui.pesos(total == null ? plan.subtotal : total);
        texto(resumen, importe, 34, Color.WHITE, true);
        String etiqueta = viajes == 0 ? "Faltan precios web para armar el plan"
                : total == null ? "Subtotal web · traslado pendiente"
                : completo ? "Total estimado · compra + traslado" : "Total parcial · compra + traslado";
        texto(resumen, etiqueta, 13, menta, false);
        if (viajes > 0) {
            LinearLayout costos = new LinearLayout(actividad);
            costos.setPadding(0, dp(12), 0, 0);
            resumen.addView(costos);
            costoResumen(costos, "Compra web", Ui.pesos(plan.subtotal));
            costoResumen(costos, "Traslado", plan.transporte == null ? "Pendiente" : Ui.pesos(plan.transporte));
        }
        String recorrido = viajes == 0 ? "" : " · " + viajes + (viajes == 1 ? " ida y vuelta" : " idas y vueltas")
                + " · " + plan.minutos + " min est.";
        texto(resumen, cubiertos + "/" + productos + " productos" + recorrido, 13, Color.WHITE, true)
                .setPadding(0, dp(11), 0, 0);
    }

    // Celda del resumen de costos.
    private void costoResumen(LinearLayout fila, String etiqueta, String valor) {
        LinearLayout celda = columna();
        fila.addView(celda, new LinearLayout.LayoutParams(0, -2, 1));
        texto(celda, etiqueta, 12, menta, false);
        texto(celda, valor, 16, Color.WHITE, true);
    }

    // Tarjeta de una tienda con sus productos y subtotal.
    private void tarjetaTienda(LinearLayout contenido, int numero, NearbyStores.Store sucursal, PlanCompra.Tienda tienda,
            List<String> claves, Map<String, ListaCompra.Item> productos) {
        LinearLayout tarjeta = tarjeta(contenido, Color.WHITE, 10);
        LinearLayout cabecera = new LinearLayout(actividad);
        cabecera.setGravity(Gravity.CENTER_VERTICAL);
        tarjeta.addView(cabecera);
        ImageView logo = new ImageView(actividad);
        logo.setImageResource(logo(tienda.id));
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        logo.setBackground(fondo(papel, 12));
        logo.setPadding(dp(7), dp(6), dp(7), dp(6));
        LinearLayout.LayoutParams tamano = new LinearLayout.LayoutParams(dp(70), dp(56));
        tamano.setMarginEnd(dp(12));
        cabecera.addView(logo, tamano);

        LinearLayout identidad = columna();
        cabecera.addView(identidad, new LinearLayout.LayoutParams(0, -2, 1));
        texto(identidad, "TIENDA " + numero, 10, tinta, true).setLetterSpacing(0.09f);
        encabezadoAccesible(texto(identidad, sucursal.name, 18, bosque, true));
        if (!sucursal.address.isEmpty()) {
            texto(identidad, sucursal.address, 12, tinta, false);
        }

        long subtotal = 0;
        for (String clave : claves) {
            long precio = tienda.precios.get(clave);
            filaProducto(tarjeta, productos.get(clave), Ui.pesos(precio));
            subtotal += precio;
        }
        separador(tarjeta);
        texto(tarjeta, "Subtotal web · " + Ui.pesos(subtotal), 15, bosque, true);
        String costo = tienda.viaje.costoClp == null ? "Costo de traslado pendiente"
                : "traslado " + Ui.pesos(tienda.viaje.costoClp);
        texto(tarjeta, "Ida y vuelta · " + tienda.viaje.minutos + " min est. · " + costo, 12, tinta, false);
    }

    // Fila de un producto con su precio.
    private void filaProducto(LinearLayout tarjeta, ListaCompra.Item producto, String precio) {
        separador(tarjeta);
        LinearLayout fila = new LinearLayout(actividad);
        fila.setGravity(Gravity.CENTER_VERTICAL);
        tarjeta.addView(fila);
        fila.addView(new Ui(actividad).foto(producto.producto.urlImagen, producto.producto.nombre,
                producto.producto.emoji(), 36));
        TextView cantidad = texto(fila, producto.cantidad + " ×", 12, bosque, true);
        cantidad.setBackground(fondo(menta, 8));
        cantidad.setPadding(dp(5), dp(3), dp(5), dp(3));
        LinearLayout.LayoutParams margen = new LinearLayout.LayoutParams(-2, -2);
        margen.setMarginStart(dp(8));
        cantidad.setLayoutParams(margen);
        TextView nombre = texto(fila, producto.producto.nombre, 14, bosque, false);
        LinearLayout.LayoutParams flexible = new LinearLayout.LayoutParams(0, -2, 1);
        flexible.setMarginStart(dp(8));
        flexible.setMarginEnd(dp(8));
        nombre.setLayoutParams(flexible);
        if (precio != null) {
            texto(fila, precio, 14, bosque, true);
        }
    }

    // Línea divisoria.
    private void separador(LinearLayout tarjeta) {
        View linea = new View(actividad);
        linea.setBackgroundColor(0x26748F80);
        LinearLayout.LayoutParams espacio = new LinearLayout.LayoutParams(-1, dp(1));
        espacio.topMargin = dp(8);
        espacio.bottomMargin = dp(8);
        tarjeta.addView(linea, espacio);
    }

    // Logo de la cadena.
    private int logo(String comercio) {
        return switch (comercio) {
            case "ALVI" -> R.drawable.logo_alvi;
            case "CUGAT" -> R.drawable.logo_cugat;
            case "DEL_PEDREGAL" -> R.drawable.logo_del_pedregal;
            case "JUMBO" -> R.drawable.logo_jumbo;
            case "LIDER" -> R.drawable.logo_lider;
            case "SANTA_ISABEL" -> R.drawable.logo_santa_isabel;
            case "TOTTUS" -> R.drawable.logo_tottus;
            case "UNIMARC" -> R.drawable.logo_unimarc;
            default -> R.drawable.ic_abarrato;
        };
    }

    // Ayudas de diseño propias de este diálogo.
    private LinearLayout tarjeta(LinearLayout padre, int color, int margen) {
        LinearLayout vista = columna();
        vista.setBackground(fondo(color, 19));
        vista.setPadding(dp(14), dp(13), dp(14), dp(13));
        LinearLayout.LayoutParams espacio = new LinearLayout.LayoutParams(-1, -2);
        espacio.topMargin = dp(margen);
        padre.addView(vista, espacio);
        return vista;
    }

    private LinearLayout columna() {
        LinearLayout vista = new LinearLayout(actividad);
        vista.setOrientation(LinearLayout.VERTICAL);
        return vista;
    }

    private TextView texto(LinearLayout padre, String contenido, int tamano, int color, boolean fuerte) {
        TextView vista = new TextView(actividad);
        vista.setText(contenido);
        vista.setTextSize(tamano);
        vista.setTextColor(color);
        if (fuerte) {
            vista.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        padre.addView(vista);
        return vista;
    }

    private void encabezadoAccesible(TextView vista) {
        if (Build.VERSION.SDK_INT >= 28) {
            vista.setAccessibilityHeading(true);
        }
    }

    private GradientDrawable fondo(int color, int radio) {
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(color);
        fondo.setCornerRadius(dp(radio));
        return fondo;
    }

    private int dp(int valor) {
        return Math.round(valor * actividad.getResources().getDisplayMetrics().density);
    }
}
