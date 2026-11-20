package com.abarrato.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Búsqueda para agregar productos a la despensa, con fotos y datos de los catálogos. */
final class BuscarDialog extends Dialog {
    private final Activity actividad;
    private final Ui ui;
    private final Runnable alCambiar;
    private final Buscador buscador = new Buscador();
    private final LinearLayout resultados;
    private final EditText campo;

    BuscarDialog(Activity actividad, Runnable alCambiar) {
        super(actividad, android.R.style.Theme_Material_Light_NoActionBar);
        this.actividad = actividad;
        this.ui = new Ui(actividad);
        this.alCambiar = alCambiar;
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = ui.columna();
        panel.setBackgroundColor(Ui.PAPEL);
        panel.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), 0);

        LinearLayout cabecera = ui.fila();
        panel.addView(cabecera);
        TextView volver = ui.texto(cabecera, "‹", 34, Ui.TEXTO, Ui.NEGRITA);
        volver.setGravity(Gravity.CENTER);
        volver.setContentDescription("Volver a la despensa");
        ui.tocable(volver, ui.fondo(Ui.PAPEL, 24, 0), this::dismiss);
        volver.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        ui.texto(cabecera, "Agregar a la despensa", 22, Ui.TEXTO, Ui.GRUESA);

        LinearLayout caja = ui.fila();
        caja.setBackground(ui.fondo(Ui.BLANCO, 16, Ui.GRIS_LINEA));
        caja.setPadding(ui.dp(14), 0, ui.dp(8), 0);
        ui.agregar(panel, caja, 10);
        ui.texto(caja, "🔍", 18, Ui.TEXTO, Ui.NORMAL);
        campo = new EditText(actividad);
        campo.setHint("Busca lo que tienes en casa");
        campo.setBackground(null);
        campo.setSingleLine();
        campo.setTextSize(16);
        campo.setTextColor(Ui.TEXTO);
        campo.setHintTextColor(Ui.TINTA);
        campo.setInputType(InputType.TYPE_CLASS_TEXT);
        campo.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        campo.setMinHeight(ui.dp(54));
        campo.setOnEditorActionListener((vista, accion, evento) -> {
            boolean enter = evento != null && evento.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && evento.getAction() == KeyEvent.ACTION_DOWN;
            if (accion == EditorInfo.IME_ACTION_SEARCH || enter) {
                buscar();
                return true;
            }
            return false;
        });
        ui.flexible(caja, campo);

        LinearLayout atajos = ui.fila();
        ui.agregar(panel, atajos, 10);
        TextView manual = ui.botonPequeno("✏️ Anotar sin buscar", Ui.BLANCO, Ui.TEXTO, this::anotar);
        atajos.addView(manual);

        ScrollView scroll = new ScrollView(actividad);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams alto = new LinearLayout.LayoutParams(-1, 0, 1);
        alto.topMargin = ui.dp(8);
        panel.addView(scroll, alto);
        resultados = ui.columna();
        resultados.setPadding(0, 0, 0, ui.dp(24));
        scroll.addView(resultados);
        mostrarDesdeLista();

        setContentView(panel);
        setOnDismissListener(dialogo -> buscador.cerrar());
    }

    // Muestra el diálogo con el teclado listo para escribir.
    @Override
    public void show() {
        super.show();
        Window ventana = getWindow();
        if (ventana != null) {
            ventana.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            ventana.setLayout(-1, -1);
        }
        campo.requestFocus();
    }

    /** Antes de buscar, sugiere los productos de la lista de compras para marcarlos como "ya lo tengo". */
    private void mostrarDesdeLista() {
        resultados.removeAllViews();
        List<ListaCompra.Item> lista = new ListaCompra(actividad).items();
        if (lista.isEmpty()) {
            ui.texto(resultados, "Escribe un producto, por ejemplo arroz, aceite o leche. Verás su foto y su precio "
                    + "en Jumbo, Santa Isabel, Líder, Unimarc, Tottus, Alvi, Cugat y Del Pedregal.", 14, Ui.TINTA, Ui.NORMAL)
                    .setPadding(0, ui.dp(12), 0, 0);
            return;
        }
        ui.seccion(resultados, "DE TU LISTA DE COMPRAS");
        LinearLayout tarjeta = ui.tarjeta(resultados, Ui.BLANCO, 6);
        for (int i = 0; i < lista.size(); i++) {
            if (i > 0) {
                tarjeta.addView(ui.separador());
            }
            fila(tarjeta, lista.get(i).producto);
        }
    }

    // Valida el texto y lanza la búsqueda.
    private void buscar() {
        String consulta = campo.getText().toString().trim();
        if (consulta.length() < 2) {
            campo.setError("Escribe al menos dos letras");
            return;
        }
        InputMethodManager teclado = actividad.getSystemService(InputMethodManager.class);
        if (teclado != null) {
            teclado.hideSoftInputFromWindow(campo.getWindowToken(), 0);
        }
        resultados.removeAllViews();
        LinearLayout cargando = ui.fila();
        cargando.setPadding(0, ui.dp(18), 0, 0);
        resultados.addView(cargando);
        cargando.addView(new ProgressBar(actividad), new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
        ui.texto(cargando, "  Consultando los supermercados en este momento…", 14, Ui.TINTA, Ui.NORMAL);
        buscador.buscar(consulta, new Buscador.Respuesta() {
            @Override
            public void listo(ApiPrecios.Busqueda busqueda) {
                resultados.removeAllViews();
                if (busqueda.productos.isEmpty()) {
                    ui.texto(resultados, "Sin resultados para “" + consulta + "”. Puedes anotarlo sin buscar.",
                            14, Ui.TINTA, Ui.NORMAL).setPadding(0, ui.dp(14), 0, 0);
                    return;
                }
                ui.seccion(resultados, busqueda.productos.size() + " PRODUCTOS");
                LinearLayout tarjeta = ui.tarjeta(resultados, Ui.BLANCO, 6);
                for (int i = 0; i < busqueda.productos.size(); i++) {
                    if (i > 0) {
                        tarjeta.addView(ui.separador());
                    }
                    fila(tarjeta, busqueda.productos.get(i));
                }
            }

            @Override
            public void error(String mensaje) {
                resultados.removeAllViews();
                ui.texto(resultados, mensaje, 14, Ui.NARANJA, Ui.NEGRITA).setPadding(0, ui.dp(14), 0, 0);
            }
        });
    }

    // Una fila de resultado con su precio más bajo.
    private void fila(LinearLayout tarjeta, Producto producto) {
        LinearLayout fila = ui.fila();
        tarjeta.addView(fila);
        fila.addView(ui.foto(producto.urlImagen, producto.nombre, producto.emoji(), 52));
        LinearLayout datos = ui.columna();
        datos.setPadding(ui.dp(12), 0, ui.dp(8), 0);
        ui.flexible(fila, datos);
        ui.texto(datos, producto.nombre, 15, Ui.TEXTO, Ui.NEGRITA).setMaxLines(2);
        ui.texto(datos, producto.detalle(), 12, Ui.TINTA, Ui.NORMAL);
        if (producto.precioMin() > 0) {
            ui.texto(datos, "desde " + Ui.pesos(producto.precioMin()), 14, Ui.VERDE, Ui.GRUESA);
        }
        int enCasa = new Despensa(actividad).cantidad(producto.clave);
        ui.texto(fila, enCasa > 0 ? "✓ " + enCasa : "+", 20, Ui.VERDE, Ui.GRUESA);
        ui.tocable(fila, ui.fondo(Ui.BLANCO, 12, 0), () ->
                new ProductoDialog(actividad, producto, ProductoDialog.Destino.DESPENSA, () -> {
                    alCambiar.run();
                    fila.post(() -> {
                        if (resultados.getChildCount() > 0 && campo.getText().length() == 0) {
                            mostrarDesdeLista();
                        }
                    });
                }).show());
    }

    // Anota en la despensa un producto escrito a mano (sin precio).
    private void anotar() {
        LinearLayout formulario = ui.columna();
        formulario.setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0);
        EditText nombre = new EditText(actividad);
        nombre.setHint("Ej.: arroz grado 2 1 kg");
        nombre.setSingleLine();
        nombre.setText(campo.getText());
        formulario.addView(nombre);
        ui.texto(formulario, "Si incluyes el formato (1 kg, 1 L, 12 un.) se estiman mejor las porciones.",
                12, Ui.TINTA, Ui.NORMAL);
        AlertDialog dialogo = new AlertDialog.Builder(actividad).setTitle("Anotar en la despensa")
                .setView(formulario).setNegativeButton("Cancelar", null).setPositiveButton("Agregar", null).create();
        dialogo.setOnShowListener(ventana -> dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String texto = nombre.getText().toString().trim();
            if (texto.length() < 2) {
                nombre.setError("Escribe el nombre del producto");
                return;
            }
            new Despensa(actividad).cambiar(Despensa.manual(texto), 1);
            alCambiar.run();
            dialogo.dismiss();
            dismiss();
        }));
        dialogo.show();
    }
}
