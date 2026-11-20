package com.abarrato.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.abarrato.app.Traslado.Bencina;
import com.abarrato.app.Traslado.Configuracion;
import com.abarrato.app.Traslado.Medio;
import com.abarrato.app.Traslado.Modo;
import com.abarrato.app.Traslado.Tarifa;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

// Pantalla principal con 4 pestañas: Inicio, Compra, Despensa y Perfil.
// Cada pestaña se dibuja por código con la clase Ui.
public class MainActivity extends Activity {
    private static final String ESTADO_PESTANA = "pestana";
    private static final String ESTADO_CONSULTA = "consulta";
    private static final int PAGINA_RESULTADOS = 30;

    private Ui ui;
    private ListaCompra lista;
    private Despensa despensa;
    private Historial historial;
    private AjustesTraslado ajustes;
    private final Buscador buscador = new Buscador();

    private RadioGroup navegacion;
    private ScrollView contenido;
    private LinearLayout cuerpo;
    private LinearLayout pie;

    private String consulta = "";
    private ApiPrecios.Busqueda resultados;
    /** Se muestran precios guardados mientras se espera la búsqueda en vivo. */
    private boolean actualizandoEnVivo;
    private String errorBusqueda;
    private boolean buscando;
    private Categorias.Categoria categoria;
    private int subIndice;
    private String tiendaFiltro = "";
    private Categorias.Orden orden = Categorias.Orden.RELEVANCIA;
    private int mostrados = PAGINA_RESULTADOS;
    private int paginaServidor = 1;
    private boolean cargandoMas;
    private boolean sinMasEnTiendas;
    private EditText campoBusqueda;
    private LinearLayout compraDinamica;
    private LinearLayout tarjetaLista;

    // Arranque: prepara datos locales, la barra de pestañas y restaura la pestaña anterior.
    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        ApiPrecios.despertar();
        setContentView(R.layout.activity_main);
        ui = new Ui(this);
        lista = new ListaCompra(this);
        despensa = new Despensa(this);
        historial = new Historial(this);
        ajustes = new AjustesTraslado(this);
        contenido = findViewById(R.id.content);
        cuerpo = findViewById(R.id.section_body);
        pie = findViewById(R.id.shop_footer);
        navegacion = findViewById(R.id.navigation);

        if (Build.VERSION.SDK_INT < 26) {
            getWindow().setNavigationBarColor(Ui.BOSQUE);
        }
        if (Build.VERSION.SDK_INT >= 35) {
            View raiz = findViewById(R.id.root);
            raiz.setOnApplyWindowInsetsListener((vista, bordes) -> {
                Insets barras = bordes.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets teclado = bordes.getInsets(WindowInsets.Type.ime());
                vista.setPadding(barras.left, barras.top, barras.right, Math.max(barras.bottom, teclado.bottom));
                return bordes;
            });
            raiz.requestApplyInsets();
        }

        int pestana = R.id.tab_home;
        if (estado != null) {
            pestana = estado.getInt(ESTADO_PESTANA, R.id.tab_home);
            consulta = estado.getString(ESTADO_CONSULTA, "");
        }
        navegacion.setOnCheckedChangeListener((grupo, id) -> {
            if (id != View.NO_ID) {
                mostrar(id);
                contenido.scrollTo(0, 0);
            }
        });
        navegacion.check(pestana);
        if (!consulta.isEmpty()) {
            buscar(consulta);
        }
    }

    // Al volver a la app, refresca la pestaña visible (por ejemplo, tras una compra).
    @Override
    protected void onResume() {
        super.onResume();
        // La lista, la despensa o el historial pueden cambiar en Optimizar.
        int actual = navegacion.getCheckedRadioButtonId();
        if (actual == R.id.tab_shop) {
            refrescarCompra();
        } else if (actual != View.NO_ID) {
            mostrar(actual);
        }
    }

    // Guarda la pestaña y la búsqueda si Android cierra la app en segundo plano.
    @Override
    protected void onSaveInstanceState(Bundle estado) {
        estado.putInt(ESTADO_PESTANA, navegacion.getCheckedRadioButtonId());
        estado.putString(ESTADO_CONSULTA, consulta);
        super.onSaveInstanceState(estado);
    }

    // Libera las hebras del buscador.
    @Override
    protected void onDestroy() {
        buscador.cerrar();
        super.onDestroy();
    }

    // Dibuja la pestaña elegida.
    private void mostrar(int pestana) {
        cuerpo.removeAllViews();
        ocultarTeclado();
        boolean compra = pestana == R.id.tab_shop;
        pie.setVisibility(compra ? View.VISIBLE : View.GONE);
        if (compra) {
            mostrarCompra();
        } else if (pestana == R.id.tab_pantry) {
            mostrarDespensa();
        } else if (pestana == R.id.tab_profile) {
            mostrarPerfil();
        } else {
            mostrarInicio();
        }
    }

    // Cambia de pestaña (o la redibuja si ya está abierta).
    private void irA(int pestana) {
        if (navegacion.getCheckedRadioButtonId() == pestana) {
            mostrar(pestana);
        } else {
            navegacion.check(pestana);
        }
    }

    // ───────────────────────────── Inicio ─────────────────────────────

    // Inicio: gasto y ahorro de la semana, próxima compra y accesos rápidos.
    private void mostrarInicio() {
        ui.texto(cuerpo, "Hola 👋", 15, Ui.TINTA, Ui.NORMAL).setPadding(0, ui.dp(16), 0, 0);
        TextView titulo = ui.texto(cuerpo, "¿Qué vamos a comprar hoy?", 30, Ui.TEXTO, Ui.GRUESA);
        titulo.setPadding(0, ui.dp(6), 0, ui.dp(4));

        LinearLayout ahorro = ui.tarjeta(cuerpo, Ui.BOSQUE, 14);
        ahorro.setPadding(ui.dp(18), ui.dp(20), ui.dp(18), ui.dp(18));
        ui.etiqueta(ahorro, "GASTO DE ESTA SEMANA", Ui.TENUE_OSCURO);
        TextView monto = ui.texto(ahorro, Ui.pesos(historial.gastoSemana()), 40, Ui.BLANCO, Ui.GRUESA);
        monto.setPadding(0, ui.dp(6), 0, 0);
        TextView ahorroSemana = ui.texto(ahorro, "Ahorraste " + Ui.pesos(historial.ahorroSemana()) + " esta semana",
                16, Ui.VERDE_CLARO, Ui.NEGRITA);
        ahorroSemana.setPadding(0, ui.dp(2), 0, ui.dp(10));

        Historial.Compra ultima = historial.ultima();
        LinearLayout resumen = ui.fila();
        resumen.setBackground(ui.fondo(Ui.BOSQUE_INTERIOR, 12, 0));
        resumen.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        ahorro.addView(resumen);
        LinearLayout izquierda = ui.columna();
        LinearLayout derecha = ui.columna();
        derecha.setGravity(Gravity.END);
        ui.flexible(resumen, izquierda);
        ui.flexible(resumen, derecha);
        ui.texto(izquierda, "Última compra", 13, Ui.TENUE_OSCURO, Ui.NORMAL);
        ui.texto(izquierda, ultima == null ? "—" : Ui.pesos(ultima.total), 17, Ui.BLANCO, Ui.NEGRITA);
        ui.texto(derecha, "Ahorro logrado", 13, Ui.TENUE_OSCURO, Ui.NORMAL);
        ui.texto(derecha, ultima == null ? "—" : Ui.pesos(ultima.ahorro), 17, Ui.VERDE_CLARO, Ui.NEGRITA);
        TextView crear = ui.boton("+ Crear nueva compra", Ui.AMARILLO, Ui.BOSQUE, () -> irA(R.id.tab_shop));
        ui.agregar(ahorro, crear, 16);

        proximaCompra();

        LinearLayout despensaFila = ui.fila();
        despensaFila.setPadding(0, ui.dp(24), 0, ui.dp(8));
        cuerpo.addView(despensaFila);
        TextView tituloDespensa = ui.texto(null, "Tu despensa", 18, Ui.TEXTO, Ui.GRUESA);
        ui.flexible(despensaFila, tituloDespensa);
        int cobertura = despensa.cobertura();
        ui.texto(despensaFila, cobertura + "%", 17, Ui.VERDE, Ui.GRUESA);
        cuerpo.addView(ui.barra(cobertura, Ui.VERDE, Ui.GRIS_LINEA));
        int registrados = despensa.items().size();
        TextView detalle = ui.texto(cuerpo, registrados + (registrados == 1 ? " producto registrado" : " productos registrados")
                + " en casa", 14, Ui.TINTA, Ui.NORMAL);
        detalle.setPadding(0, ui.dp(10), 0, 0);
        ui.tocable(despensaFila, ui.fondo(Ui.PAPEL, 8, 0), () -> irA(R.id.tab_pantry));

        LinearLayout accesos = ui.fila();
        accesos.setGravity(Gravity.TOP);
        ui.agregar(cuerpo, accesos, 16);
        acceso(accesos, "💳", "Modo presupuesto", historial.presupuesto() == 0 ? "Arma tu compra con un tope"
                : "Tope: " + Ui.pesos(historial.presupuesto()), this::editarPresupuesto);
        accesos.addView(ui.espacio(12));
        acceso(accesos, "🧑‍🍳", "¿Qué puedo cocinar?", "Con lo que tienes en casa", this::mostrarRecetas);
    }

    // Tarjeta con el resumen de la lista actual.
    private void proximaCompra() {
        List<ListaCompra.Item> items = lista.items();
        LinearLayout tarjeta = ui.tarjeta(cuerpo, Ui.BLANCO, 16);
        LinearLayout fila = ui.fila();
        tarjeta.addView(fila);
        if (items.isEmpty()) {
            fila.addView(ui.insignia("", 52));
            LinearLayout datos = ui.columna();
            datos.setPadding(ui.dp(16), 0, 0, 0);
            ui.flexible(fila, datos);
            ui.etiqueta(datos, "TU PRÓXIMA COMPRA", Ui.TINTA);
            ui.texto(datos, "Tu lista está vacía", 18, Ui.TEXTO, Ui.GRUESA);
            ui.texto(datos, "Busca productos y compara precios", 13, Ui.TINTA, Ui.NORMAL);
        } else {
            Historial.Compra plan = historial.plan(Historial.firma(items));
            String comercio;
            String total;
            String detalle;
            int unidades = 0;
            for (ListaCompra.Item item : items) {
                unidades += item.cantidad;
            }
            if (plan != null) {
                comercio = plan.comercio;
                total = Ui.pesos(plan.total);
                detalle = unidades + " productos · " + plan.tiendas + (plan.tiendas == 1 ? " tienda · " : " tiendas · ")
                        + plan.minutos + " min";
            } else {
                comercio = cadenaMasConveniente(items);
                total = Ui.pesos(lista.totalDesde());
                detalle = unidades + " productos · toca Optimizar para ver la ruta";
            }
            fila.addView(ui.insignia(comercio, 52));
            LinearLayout datos = ui.columna();
            datos.setPadding(ui.dp(16), 0, 0, 0);
            ui.flexible(fila, datos);
            ui.etiqueta(datos, "TU PRÓXIMA COMPRA", Ui.TINTA);
            ui.texto(datos, total, 22, Ui.TEXTO, Ui.GRUESA);
            ui.texto(datos, detalle, 13, Ui.TINTA, Ui.NORMAL);
        }
        ui.texto(fila, "›", 24, Ui.TINTA, Ui.NORMAL);
        ui.tocable(tarjeta, ui.fondo(Ui.BLANCO, 20, Ui.BORDE), () -> irA(R.id.tab_shop));
    }

    /** Cadena que tiene más productos de la lista y, entre ellas, el menor total. */
    private static String cadenaMasConveniente(List<ListaCompra.Item> items) {
        String mejor = Tiendas.IDS.get(0);
        int cobertura = -1;
        long total = Long.MAX_VALUE;
        for (String comercio : Tiendas.IDS) {
            int cubiertos = 0;
            long suma = 0;
            for (ListaCompra.Item item : items) {
                Producto.Oferta oferta = item.producto.oferta(comercio);
                if (oferta != null) {
                    cubiertos++;
                    suma += (long) oferta.precio * item.cantidad;
                }
            }
            if (cubiertos > cobertura || (cubiertos == cobertura && suma < total)) {
                mejor = comercio;
                cobertura = cubiertos;
                total = suma;
            }
        }
        return mejor;
    }

    // Botón de acceso rápido del inicio.
    private void acceso(LinearLayout fila, String icono, String titulo, String detalle, Runnable accion) {
        LinearLayout tarjeta = ui.columna();
        tarjeta.setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(18));
        tarjeta.setMinimumHeight(ui.dp(140));
        ui.texto(tarjeta, icono, 24, Ui.TEXTO, Ui.NORMAL).setPadding(0, 0, 0, ui.dp(10));
        ui.texto(tarjeta, titulo, 16, Ui.TEXTO, Ui.GRUESA);
        ui.texto(tarjeta, detalle, 13, Ui.TINTA, Ui.NORMAL).setPadding(0, ui.dp(4), 0, 0);
        ui.tocable(tarjeta, ui.fondo(Ui.BLANCO, 20, Ui.BORDE), accion);
        fila.addView(tarjeta, new LinearLayout.LayoutParams(0, -2, 1));
    }

    // ───────────────────────────── Compra ─────────────────────────────

    // Compra: buscador, categorías, resultados y la lista.
    private void mostrarCompra() {
        ui.titulo(cuerpo, "Crear compra", "Agrega productos a tu lista");

        LinearLayout caja = ui.fila();
        caja.setBackground(ui.fondo(Ui.BLANCO, 16, Ui.GRIS_LINEA));
        caja.setPadding(ui.dp(16), 0, ui.dp(6), 0);
        ui.agregar(cuerpo, caja, 12);
        ui.texto(caja, "🔍", 17, Ui.TEXTO, Ui.NORMAL);
        campoBusqueda = new EditText(this);
        campoBusqueda.setHint("Buscar leche, arroz, detergente...");
        campoBusqueda.setText(categoria == null ? consulta : "");
        campoBusqueda.setBackground(null);
        campoBusqueda.setSingleLine();
        campoBusqueda.setTextSize(16);
        campoBusqueda.setTextColor(Ui.TEXTO);
        campoBusqueda.setHintTextColor(Ui.TINTA);
        campoBusqueda.setInputType(InputType.TYPE_CLASS_TEXT);
        campoBusqueda.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        campoBusqueda.setMinHeight(ui.dp(56));
        campoBusqueda.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        campoBusqueda.setOnEditorActionListener((vista, accion, evento) -> {
            boolean enter = evento != null && evento.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && evento.getAction() == KeyEvent.ACTION_DOWN;
            if (accion == EditorInfo.IME_ACTION_SEARCH || enter) {
                buscarDesdeCampo();
                return true;
            }
            return false;
        });
        ui.flexible(caja, campoBusqueda);
        TextView limpiar = ui.texto(caja, "✕", 16, Ui.TINTA, Ui.NEGRITA);
        limpiar.setGravity(Gravity.CENTER);
        limpiar.setContentDescription("Borrar búsqueda y volver a las categorías");
        limpiar.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        ui.tocable(limpiar, ui.fondo(Ui.BLANCO, 22, 0), this::volverACategorias);

        compraDinamica = ui.columna();
        cuerpo.addView(compraDinamica);
        refrescarCompra();
    }

    // Valida el texto escrito (mínimo 2 letras) y busca.
    private void buscarDesdeCampo() {
        String texto = campoBusqueda.getText().toString().trim();
        if (texto.length() < 2) {
            campoBusqueda.setError("Escribe al menos dos letras");
            return;
        }
        ocultarTeclado();
        categoria = null;
        reiniciarResultados();
        buscar(texto);
    }

    // Limpia la búsqueda y vuelve al explorador de categorías.
    private void volverACategorias() {
        if (campoBusqueda != null) {
            campoBusqueda.setText("");
        }
        consulta = "";
        categoria = null;
        resultados = null;
        errorBusqueda = null;
        buscando = false;
        buscador.cancelar();
        reiniciarResultados();
        refrescarCompra();
        contenido.scrollTo(0, 0);
    }

    // Vuelve los filtros y la paginación a su estado inicial.
    private void reiniciarResultados() {
        mostrados = PAGINA_RESULTADOS;
        tiendaFiltro = "";
        orden = Categorias.Orden.RELEVANCIA;
    }

    // Abre una categoría y busca su primera subcategoría.
    private void elegirCategoria(Categorias.Categoria elegida) {
        categoria = elegida;
        subIndice = 0;
        campoBusqueda.setText("");
        reiniciarResultados();
        buscar(elegida.subs[0].consulta);
        contenido.scrollTo(0, 0);
    }

    // Cambia de subcategoría.
    private void elegirSub(int indice) {
        subIndice = indice;
        reiniciarResultados();
        buscar(categoria.subs[indice].consulta);
    }

    // Lanza la búsqueda: primero llegan los precios guardados y después los en vivo.
    private void buscar(String texto) {
        consulta = texto;
        paginaServidor = 1;
        cargandoMas = false;
        sinMasEnTiendas = false;
        buscando = true;
        actualizandoEnVivo = false;
        errorBusqueda = null;
        refrescarCompra();
        buscador.buscar(texto, new Buscador.Respuesta() {
            @Override
            public void previo(ApiPrecios.Busqueda busqueda) {
                buscando = false;
                actualizandoEnVivo = true;
                resultados = busqueda;
                refrescarCompra();
            }

            @Override
            public void listo(ApiPrecios.Busqueda busqueda) {
                buscando = false;
                actualizandoEnVivo = false;
                resultados = busqueda;
                refrescarCompra();
            }

            @Override
            public void error(String mensaje) {
                buscando = false;
                if (actualizandoEnVivo && resultados != null) {
                    // Se quedan los precios guardados que ya se están mostrando.
                    actualizandoEnVivo = false;
                    Toast.makeText(MainActivity.this, "No se pudieron actualizar los precios en vivo. "
                            + "Se muestran los últimos guardados.", Toast.LENGTH_LONG).show();
                    refrescarCompra();
                    return;
                }
                actualizandoEnVivo = false;
                resultados = null;
                errorBusqueda = mensaje;
                refrescarCompra();
            }
        });
    }

    // Redibuja solo la zona de resultados (estado: cargando, error, vacío o resultados).
    private void refrescarCompra() {
        actualizarPie();
        if (compraDinamica == null || navegacion.getCheckedRadioButtonId() != R.id.tab_shop) {
            return;
        }
        compraDinamica.removeAllViews();
        List<ListaCompra.Item> items = lista.items();
        avisoDespensa(items);
        if (consulta.isEmpty() && categoria == null) {
            mostrarCategorias();
        } else {
            mostrarNavegacion();
            mostrarResultados();
        }

        ui.seccion(compraDinamica, items.isEmpty() ? "TU LISTA" : "TU LISTA (" + items.size() + ")");
        tarjetaLista = ui.tarjeta(compraDinamica, Ui.BLANCO, 6);
        if (items.isEmpty()) {
            ui.texto(tarjetaLista, "Aún no agregas productos", 16, Ui.TEXTO, Ui.NEGRITA);
            ui.texto(tarjetaLista, "Elige una categoría o busca un producto. Verás su foto y el precio en "
                    + "8 supermercados: Jumbo, Santa Isabel, Líder, Unimarc, Tottus, Alvi, Cugat y Del Pedregal.", 13, Ui.TINTA, Ui.NORMAL);
        }
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                tarjetaLista.addView(ui.separador());
            }
            filaLista(tarjetaLista, items.get(i));
        }

        int presupuesto = historial.presupuesto();
        if (presupuesto > 0 && lista.totalDesde() > presupuesto) {
            LinearLayout alerta = ui.tarjeta(compraDinamica, Ui.AMBAR_FONDO, 12);
            alerta.setBackground(ui.fondo(Ui.AMBAR_FONDO, 18, Ui.AMBAR_BORDE));
            ui.texto(alerta, "Te pasas de tu tope por " + Ui.pesos(lista.totalDesde() - presupuesto), 15,
                    Ui.TEXTO, Ui.NEGRITA);
            ui.texto(alerta, "Modo presupuesto: " + Ui.pesos(presupuesto) + ". Quita o reduce productos.", 13,
                    Ui.NARANJA, Ui.NORMAL);
        }
    }

    /** Cuadrícula de categorías con íconos, de cuatro por fila. */
    private void mostrarCategorias() {
        ui.seccion(compraDinamica, "EXPLORAR POR CATEGORÍA");
        Categorias.Categoria[] todas = Categorias.Categoria.values();
        for (int inicio = 0; inicio < todas.length; inicio += 4) {
            LinearLayout fila = ui.fila();
            fila.setGravity(Gravity.TOP);
            ui.agregar(compraDinamica, fila, 8);
            for (int i = inicio; i < inicio + 4; i++) {
                if (i > inicio) {
                    fila.addView(ui.espacio(8));
                }
                if (i >= todas.length) {
                    fila.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
                    continue;
                }
                Categorias.Categoria opcion = todas[i];
                LinearLayout tile = ui.columna();
                tile.setGravity(Gravity.CENTER_HORIZONTAL);
                tile.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(10));
                tile.setMinimumHeight(ui.dp(96));
                TextView icono = ui.texto(tile, opcion.icono, 26, Ui.TEXTO, Ui.NORMAL);
                icono.setGravity(Gravity.CENTER);
                TextView nombre = ui.texto(tile, opcion.nombre, 12, Ui.TEXTO, Ui.NEGRITA);
                nombre.setGravity(Gravity.CENTER);
                nombre.setMaxLines(2);
                nombre.setPadding(0, ui.dp(6), 0, 0);
                tile.setContentDescription("Categoría " + opcion.nombre);
                ui.tocable(tile, ui.fondo(Ui.BLANCO, 16, Ui.BORDE), () -> elegirCategoria(opcion));
                fila.addView(tile, new LinearLayout.LayoutParams(0, -2, 1));
            }
        }
    }

    /** Ruta "‹ Categorías › Lácteos" y subcategorías, o la búsqueda escrita. */
    private void mostrarNavegacion() {
        LinearLayout ruta = ui.fila();
        ui.agregar(compraDinamica, ruta, 16);
        TextView volver = ui.texto(ruta, "‹ Categorías", 14, Ui.VERDE, Ui.NEGRITA);
        volver.setPadding(ui.dp(4), ui.dp(8), ui.dp(8), ui.dp(8));
        ui.tocable(volver, ui.fondo(Ui.PAPEL, 8, 0), this::volverACategorias);
        String titulo = categoria != null ? "›  " + categoria.icono + " " + categoria.nombre
                : "›  Resultados para “" + consulta + "”";
        TextView actual = ui.texto(null, titulo, 15, Ui.TEXTO, Ui.GRUESA);
        actual.setMaxLines(1);
        ui.flexible(ruta, actual);

        if (categoria != null) {
            HorizontalScrollView desplazable = new HorizontalScrollView(this);
            desplazable.setHorizontalScrollBarEnabled(false);
            ui.agregar(compraDinamica, desplazable, 6);
            LinearLayout subs = ui.fila();
            desplazable.addView(subs);
            for (int i = 0; i < categoria.subs.length; i++) {
                int indice = i;
                TextView chip = ui.chip(categoria.subs[i].nombre, i == subIndice, () -> elegirSub(indice));
                LinearLayout.LayoutParams separacion = new LinearLayout.LayoutParams(-2, -2);
                separacion.setMarginEnd(ui.dp(8));
                subs.addView(chip, separacion);
            }
        }
    }

    // Aviso cuando hay productos de la lista que ya están en la despensa.
    private void avisoDespensa(List<ListaCompra.Item> items) {
        List<ListaCompra.Item> enCasa = despensa.enLista(items);
        if (enCasa.isEmpty()) {
            return;
        }
        long ahorro = despensa.ahorroPotencial(items);
        LinearLayout aviso = ui.tarjeta(compraDinamica, Ui.AMBAR_FONDO, 16);
        aviso.setBackground(ui.fondo(Ui.AMBAR_FONDO, 18, Ui.AMBAR_BORDE));
        String titulo = enCasa.size() == 1
                ? "Ya tienes " + enCasa.get(0).producto.nombre.toLowerCase(java.util.Locale.ROOT) + " en casa."
                : "Ya tienes " + enCasa.size() + " productos de tu lista en casa.";
        ui.texto(aviso, titulo, 16, Ui.TEXTO, Ui.GRUESA);
        ui.texto(aviso, "Si lo sacas de la lista ahorras " + Ui.pesos(ahorro) + ".", 14, Ui.NARANJA, Ui.NORMAL)
                .setPadding(0, ui.dp(2), 0, ui.dp(12));
        LinearLayout acciones = ui.fila();
        aviso.addView(acciones);
        acciones.addView(ui.botonPequeno("Quitar de la lista", Ui.BOSQUE, Ui.BLANCO, () -> {
            for (ListaCompra.Item item : enCasa) {
                lista.cambiar(item.producto, -Math.min(item.cantidad, despensa.cubre(item.producto)));
            }
            Toast.makeText(this, "Ahorras " + Ui.pesos(ahorro), Toast.LENGTH_SHORT).show();
            refrescarCompra();
        }));
    }

    // Lista de resultados con filtros por tienda y orden.
    private void mostrarResultados() {
        if (buscando) {
            LinearLayout cargando = ui.fila();
            cargando.setPadding(0, ui.dp(16), 0, ui.dp(6));
            compraDinamica.addView(cargando);
            cargando.addView(new ProgressBar(this), new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
            ui.texto(cargando, "  Consultando 8 supermercados…", 14, Ui.TINTA, Ui.NORMAL);
            ui.texto(compraDinamica, "La primera búsqueda puede tardar hasta un minuto.", 12, Ui.TINTA, Ui.NORMAL);
            return;
        }
        if (errorBusqueda != null) {
            LinearLayout error = ui.tarjeta(compraDinamica, Ui.AMBAR_FONDO, 8);
            error.setBackground(ui.fondo(Ui.AMBAR_FONDO, 18, Ui.AMBAR_BORDE));
            ui.texto(error, errorBusqueda, 14, Ui.TEXTO, Ui.NEGRITA);
            TextView reintentar = ui.botonPequeno("Reintentar", Ui.BOSQUE, Ui.BLANCO, () -> buscar(consulta));
            ui.agregar(error, reintentar, 10);
            return;
        }
        if (resultados == null) {
            return;
        }
        if (actualizandoEnVivo) {
            LinearLayout actualizando = ui.fila();
            actualizando.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
            actualizando.setBackground(ui.fondo(Ui.AMBAR_FONDO, 12, Ui.AMBAR_BORDE));
            ui.agregar(compraDinamica, actualizando, 10);
            actualizando.addView(new ProgressBar(this), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
            ui.texto(actualizando, "  Actualizando…", 13, Ui.TEXTO, Ui.NEGRITA);
        }
        Categorias.Sub sub = categoria == null ? null : categoria.subs[subIndice];
        List<Producto> visibles = new ArrayList<>();
        for (Producto producto : resultados.productos) {
            if ((sub == null || sub.incluye(producto))
                    && (tiendaFiltro.isEmpty() || producto.oferta(tiendaFiltro) != null)) {
                visibles.add(producto);
            }
        }
        if (orden == Categorias.Orden.MENOR_PRECIO) {
            visibles.sort((a, b) -> Integer.compare(precioMostrado(a), precioMostrado(b)));
        }

        // Filtros: supermercado y orden.
        HorizontalScrollView desplazable = new HorizontalScrollView(this);
        desplazable.setHorizontalScrollBarEnabled(false);
        ui.agregar(compraDinamica, desplazable, 10);
        LinearLayout filtros = ui.fila();
        desplazable.addView(filtros);
        boolean barato = orden == Categorias.Orden.MENOR_PRECIO;
        agregarChip(filtros, barato ? "↓ Menor precio" : "↕ Relevancia", barato, () -> {
            orden = barato ? Categorias.Orden.RELEVANCIA : Categorias.Orden.MENOR_PRECIO;
            mostrados = PAGINA_RESULTADOS;
            refrescarCompra();
        });
        agregarChip(filtros, "Todas", tiendaFiltro.isEmpty(), () -> elegirTienda(""));
        for (String comercio : Tiendas.IDS) {
            agregarChip(filtros, Tiendas.nombre(comercio), comercio.equals(tiendaFiltro), () -> elegirTienda(comercio));
        }

        StringBuilder estado = new StringBuilder(visibles.size() + (visibles.size() == 1 ? " producto" : " productos"));
        if (resultados.guardada) {
            estado.append(" · precios guardados");
        } else {
            java.text.SimpleDateFormat horaChile = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT);
            horaChile.setTimeZone(java.util.TimeZone.getTimeZone("America/Santiago"));
            estado.append(" · precios en vivo a las ").append(horaChile.format(new java.util.Date(resultados.consultadaEn)));
        }
        List<String> sinLectura = new ArrayList<>();
        for (String comercio : resultados.guardada ? java.util.Collections.<String>emptyList() : Tiendas.IDS) {
            String fuente = resultados.fuentes.get(comercio);
            if (fuente != null && !"RECIENTE".equals(fuente)) {
                sinLectura.add(Tiendas.nombre(comercio));
            }
        }
        if (!sinLectura.isEmpty()) {
            estado.append(". Sin respuesta reciente: ").append(String.join(", ", sinLectura));
        }
        LinearLayout encabezado = ui.fila();
        encabezado.setPadding(0, ui.dp(10), 0, 0);
        compraDinamica.addView(encabezado);
        TextView resumen = ui.texto(null, estado.toString(), 12, Ui.TINTA, Ui.NORMAL);
        ui.flexible(encabezado, resumen);
        TextView actualizar = ui.texto(encabezado, "↻", 18, Ui.VERDE, Ui.NEGRITA);
        actualizar.setContentDescription("Actualizar precios");
        actualizar.setGravity(Gravity.CENTER);
        actualizar.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        ui.tocable(actualizar, ui.fondo(Ui.PAPEL, 20, 0), () -> buscar(consulta));

        if (visibles.isEmpty()) {
            LinearLayout vacio = ui.tarjeta(compraDinamica, Ui.BLANCO, 8);
            ui.texto(vacio, !tiendaFiltro.isEmpty() ? Tiendas.nombre(tiendaFiltro) + " no tiene resultados aquí. Toca “Todas”."
                    : "Sin coincidencias en los supermercados. Prueba con otra palabra.", 14, Ui.TINTA, Ui.NORMAL);
            return;
        }
        LinearLayout tarjeta = ui.tarjeta(compraDinamica, Ui.BLANCO, 8);
        int limite = Math.min(visibles.size(), mostrados);
        for (int i = 0; i < limite; i++) {
            if (i > 0) {
                tarjeta.addView(ui.separador());
            }
            filaResultado(tarjeta, visibles.get(i));
        }
        int restantes = visibles.size() - limite;
        if (restantes > 0) {
            TextView mas = ui.boton("Ver más productos (" + restantes + ")", Ui.BLANCO, Ui.VERDE, () -> {
                mostrados += PAGINA_RESULTADOS;
                refrescarCompra();
            });
            ui.agregar(compraDinamica, mas, 10);
        } else if (cargandoMas) {
            LinearLayout cargando = ui.fila();
            cargando.setPadding(0, ui.dp(14), 0, ui.dp(6));
            compraDinamica.addView(cargando);
            cargando.addView(new ProgressBar(this), new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
            ui.texto(cargando, "  Buscando más productos en las tiendas…", 14, Ui.TINTA, Ui.NORMAL);
        } else if (resultados.hayMas && !sinMasEnTiendas) {
            TextView mas = ui.boton("🔎 Buscar más productos en las tiendas", Ui.BLANCO, Ui.VERDE,
                    this::buscarMasEnTiendas);
            ui.agregar(compraDinamica, mas, 10);
        }
    }

    /** Pide al servidor la página siguiente de cada buscador y suma los productos nuevos. */
    private void buscarMasEnTiendas() {
        if (cargandoMas || resultados == null) {
            return;
        }
        int antes = resultados.productos.size();
        String buscada = consulta;
        cargandoMas = true;
        refrescarCompra();
        buscador.buscar(buscada, paginaServidor + 1, new Buscador.Respuesta() {
            @Override
            public void listo(ApiPrecios.Busqueda busqueda) {
                cargandoMas = false;
                if (!buscada.equals(consulta)) {
                    return;
                }
                paginaServidor = busqueda.paginas;
                sinMasEnTiendas = busqueda.productos.size() <= antes || !busqueda.hayMas;
                resultados = busqueda;
                mostrados += PAGINA_RESULTADOS;
                refrescarCompra();
            }

            @Override
            public void error(String mensaje) {
                cargandoMas = false;
                Toast.makeText(MainActivity.this, mensaje, Toast.LENGTH_LONG).show();
                refrescarCompra();
            }
        });
    }

    // Botón de filtro.
    private void agregarChip(LinearLayout fila, String texto, boolean activo, Runnable accion) {
        TextView chip = ui.chip(texto, activo, accion);
        chip.setMinHeight(ui.dp(36));
        chip.setTextSize(13);
        LinearLayout.LayoutParams separacion = new LinearLayout.LayoutParams(-2, -2);
        separacion.setMarginEnd(ui.dp(6));
        fila.addView(chip, separacion);
    }

    // Filtra los resultados por tienda.
    private void elegirTienda(String comercio) {
        tiendaFiltro = comercio;
        mostrados = PAGINA_RESULTADOS;
        refrescarCompra();
    }

    /** Precio que se muestra: el de la tienda filtrada o, si no hay filtro, el más bajo. */
    private int precioMostrado(Producto producto) {
        Producto.Oferta oferta = tiendaFiltro.isEmpty() ? null : producto.oferta(tiendaFiltro);
        return oferta != null ? oferta.precio : producto.precioMin();
    }

    // Tarjeta de un producto: foto, nombre, precio más bajo y etiquetas de oferta.
    private void filaResultado(LinearLayout tarjeta, Producto producto) {
        LinearLayout fila = ui.fila();
        tarjeta.addView(fila);
        fila.addView(ui.foto(producto.urlImagen, producto.nombre, producto.emoji(), 56));
        LinearLayout datos = ui.columna();
        datos.setPadding(ui.dp(14), 0, ui.dp(8), 0);
        ui.flexible(fila, datos);
        ui.texto(datos, producto.nombre, 15, Ui.TEXTO, Ui.NEGRITA).setMaxLines(2);
        ui.texto(datos, producto.detalle(), 12, Ui.TINTA, Ui.NORMAL);
        LinearLayout precios = ui.fila();
        datos.addView(precios);
        if (tiendaFiltro.isEmpty()) {
            ui.texto(precios, "desde " + Ui.pesos(producto.precioMin()), 16, Ui.VERDE, Ui.GRUESA);
            Producto.Oferta mejor = producto.mejor();
            if (mejor != null && producto.ofertas.size() > 1) {
                ui.texto(precios, "  en " + Tiendas.nombre(mejor.comercio), 12, Ui.TINTA, Ui.NORMAL);
            }
        } else {
            ui.texto(precios, Ui.pesos(precioMostrado(producto)), 16, Ui.VERDE, Ui.GRUESA);
            ui.texto(precios, "  en " + Tiendas.nombre(tiendaFiltro), 12, Ui.TINTA, Ui.NORMAL);
        }
        // Etiquetas: baja de precio desde ayer y ofertas.
        int baja = producto.mayorBajaDesdeAyer();
        if (baja < 0 || producto.enOferta()) {
            LinearLayout etiquetas = ui.fila();
            etiquetas.setPadding(0, ui.dp(4), 0, 0);
            datos.addView(etiquetas);
            if (baja < 0) {
                etiquetaPrecio(etiquetas, "↓ Bajó " + Ui.pesos(-baja) + " desde ayer", Ui.VERDE, 0xFFE5F4E4);
            }
            if (producto.enOferta()) {
                etiquetaPrecio(etiquetas, "% Oferta", Ui.NARANJA, Ui.AMBAR_FONDO);
            }
        }
        int enLista = lista.cantidad(producto.clave);
        if (enLista > 0) {
            // Ya está en la lista: − y + cambian la cantidad; con − en 1 se quita de la lista.
            fila.addView(ui.stepper(enLista, producto.nombre, () -> {
                lista.cambiar(producto, -1);
                refrescarCompra();
            }, () -> {
                if (!lista.cambiar(producto, 1)) {
                    Toast.makeText(this, "Tu lista llegó al máximo", Toast.LENGTH_SHORT).show();
                }
                refrescarCompra();
            }));
        } else {
            TextView agregar = ui.texto(fila, "+", 18, Ui.BOSQUE, Ui.GRUESA);
            agregar.setGravity(Gravity.CENTER);
            agregar.setContentDescription("Agregar " + producto.nombre + " a la lista");
            agregar.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
            ui.tocable(agregar, ui.circulo(Ui.AMARILLO), () -> {
                if (!lista.cambiar(producto, 1)) {
                    Toast.makeText(this, "Tu lista llegó al máximo", Toast.LENGTH_SHORT).show();
                }
                refrescarCompra();
            });
        }
        ui.tocable(fila, ui.fondo(Ui.BLANCO, 12, 0), () -> abrirProducto(producto, ProductoDialog.Destino.LISTA));
    }

    // Etiqueta de color: "% Oferta" o "↓ Bajó desde ayer".
    private void etiquetaPrecio(LinearLayout fila, String texto, int color, int fondo) {
        TextView etiqueta = ui.texto(fila, texto, 11, color, Ui.NEGRITA);
        etiqueta.setBackground(ui.fondo(fondo, 6, 0));
        etiqueta.setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2));
        LinearLayout.LayoutParams margen = (LinearLayout.LayoutParams) etiqueta.getLayoutParams();
        margen.setMarginEnd(ui.dp(6));
        etiqueta.setLayoutParams(margen);
    }

    // Fila de un producto de la lista, con su cantidad y tienda elegida.
    private void filaLista(LinearLayout tarjeta, ListaCompra.Item item) {
        Producto producto = item.producto;
        LinearLayout fila = ui.fila();
        tarjeta.addView(fila);
        fila.addView(ui.foto(producto.urlImagen, producto.nombre, producto.emoji(), 48));
        LinearLayout datos = ui.columna();
        datos.setPadding(ui.dp(14), 0, ui.dp(8), 0);
        ui.flexible(fila, datos);
        ui.texto(datos, producto.nombre, 16, Ui.TEXTO, Ui.NEGRITA).setMaxLines(2);
        Producto.Oferta elegida = item.oferta();
        String formato = producto.formato();
        String detalle = (elegida == null ? "" : (item.tieneTiendaElegida() ? "Elegido: " : "")
                + Tiendas.nombre(elegida.comercio)) + (formato.isEmpty() ? "" : " · " + formato);
        ui.texto(datos, detalle, 13, Ui.TINTA, Ui.NORMAL);
        // La tienda elegida ya no lo tiene: se avisa y se muestra dónde sí está (el precio pasa al más barato).
        if (!item.comercio.isEmpty() && !item.tieneTiendaElegida()) {
            String otras = producto.disponibleEn(item.comercio);
            ui.texto(datos, otras.isEmpty() ? "Ya no aparece en " + Tiendas.nombre(item.comercio)
                    : "No disponible en " + Tiendas.nombre(item.comercio) + " · sí en " + otras,
                    12, Ui.NARANJA, Ui.NEGRITA);
        }
        LinearLayout precios = ui.fila();
        datos.addView(precios);
        ui.texto(precios, Ui.pesos(item.precio()), 17, Ui.VERDE, Ui.GRUESA);
        if (producto.precioMax() > item.precio()) {
            TextView alto = ui.texto(precios, Ui.pesos(producto.precioMax()), 13, Ui.TINTA, Ui.NORMAL);
            alto.setPadding(ui.dp(8), 0, 0, 0);
            Ui.tachar(alto);
        }
        int enCasa = despensa.cubre(producto);
        if (enCasa > 0) {
            ui.texto(datos, "📦 " + enCasa + " en tu despensa", 12, Ui.NARANJA, Ui.NEGRITA);
        }
        fila.addView(ui.stepper(item.cantidad, producto.nombre, () -> {
            lista.cambiar(producto, -1);
            refrescarCompra();
        }, () -> {
            lista.cambiar(producto, 1);
            refrescarCompra();
        }));
        ui.tocable(datos, ui.fondo(Ui.BLANCO, 10, 0), () -> abrirProducto(producto, ProductoDialog.Destino.LISTA));
    }

    // Abre la ficha del producto para elegir tienda y cantidad.
    private void abrirProducto(Producto producto, ProductoDialog.Destino destino) {
        new ProductoDialog(this, producto, destino, () -> {
            if (navegacion.getCheckedRadioButtonId() == R.id.tab_shop) {
                refrescarCompra();
            } else {
                mostrar(navegacion.getCheckedRadioButtonId());
            }
        }).show();
    }

    // Pie de la pestaña Compra: total de la lista y botón Optimizar.
    private void actualizarPie() {
        pie.removeAllViews();
        pie.setBackground(ui.fondo(Ui.BOSQUE, 18, 0));
        int unidades = lista.unidades();
        LinearLayout textos = ui.columna();
        ui.flexible(pie, textos);
        ui.texto(textos, unidades + (unidades == 1 ? " producto" : " productos") + " · desde", 13,
                Ui.TENUE_OSCURO, Ui.NORMAL);
        ui.texto(textos, Ui.pesos(lista.totalDesde()), 21, Ui.BLANCO, Ui.GRUESA);
        textos.setContentDescription("Ver tu lista de compras");
        textos.setClickable(true);
        textos.setOnClickListener(vista -> {
            if (tarjetaLista != null && compraDinamica != null) {
                int y = compraDinamica.getTop() + tarjetaLista.getTop() - ui.dp(48);
                contenido.smoothScrollTo(0, Math.max(0, y));
            }
        });
        TextView optimizar = ui.botonPequeno("⚡ Optimizar", Ui.AMARILLO, Ui.BOSQUE, this::abrirOptimizar);
        optimizar.setMinHeight(ui.dp(46));
        optimizar.setTextSize(15);
        optimizar.setEnabled(unidades > 0);
        optimizar.setAlpha(unidades > 0 ? 1f : 0.5f);
        pie.addView(optimizar);
    }

    // Abre la pantalla de supermercados cercanos y plan de compra.
    private void abrirOptimizar() {
        if (lista.unidades() == 0) {
            Toast.makeText(this, "Agrega productos a tu lista para optimizar", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, NearbyStoresActivity.class));
    }

    // ───────────────────────────── Despensa ─────────────────────────────

    // Despensa: lo que hay en casa, cuánto alcanza y acceso a recetas.
    private void mostrarDespensa() {
        ui.titulo(cuerpo, "Mi despensa", "Lo que ya tienes en casa");
        List<Despensa.Item> items = despensa.items();

        LinearLayout resumen = ui.tarjeta(cuerpo, Ui.BOSQUE, 14);
        resumen.setPadding(ui.dp(18), ui.dp(20), ui.dp(18), ui.dp(18));
        ui.etiqueta(resumen, "CON LO QUE TIENES", Ui.TENUE_OSCURO);
        LinearLayout comidas = ui.fila();
        comidas.setGravity(Gravity.BOTTOM);
        comidas.setPadding(0, ui.dp(8), 0, 0);
        resumen.addView(comidas);
        ui.texto(comidas, "~" + despensa.comidas(), 38, Ui.BLANCO, Ui.GRUESA);
        ui.texto(comidas, despensa.comidas() == 1 ? " comida" : " comidas", 26, Ui.BLANCO, Ui.NORMAL);
        ui.texto(resumen, items.size() + (items.size() == 1 ? " producto registrado" : " productos registrados"),
                14, Ui.TENUE_OSCURO, Ui.NORMAL);

        List<ListaCompra.Item> listaActual = lista.items();
        List<ListaCompra.Item> enCasa = despensa.enLista(listaActual);
        if (!enCasa.isEmpty()) {
            LinearLayout aviso = ui.columna();
            aviso.setBackground(ui.fondo(Ui.OLIVA, 12, Ui.OLIVA_BORDE));
            aviso.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
            ui.agregar(resumen, aviso, 16);
            ui.texto(aviso, enCasa.size() == 1 ? "1 producto de tu lista ya está en casa"
                    : enCasa.size() + " productos de tu lista ya están en casa", 14, Ui.AMARILLO, Ui.NEGRITA);
            ui.texto(aviso, "Ahorro potencial: " + Ui.pesos(despensa.ahorroPotencial(listaActual)), 13,
                    Ui.BLANCO, Ui.NORMAL);
            ui.tocable(aviso, ui.fondo(Ui.OLIVA, 12, Ui.OLIVA_BORDE), () -> irA(R.id.tab_shop));
        }

        TextView agregar = ui.boton("+ Agregar producto a la despensa", Ui.BLANCO, Ui.TEXTO,
                () -> new BuscarDialog(this, () -> mostrar(R.id.tab_pantry)).show());
        ui.agregar(cuerpo, agregar, 16);

        for (Despensa.Item item : items) {
            filaDespensa(item);
        }
        if (items.isEmpty()) {
            ui.texto(cuerpo, "Registra lo que tienes en casa. Al optimizar, Abarrato descuenta esas unidades "
                    + "de tu lista para que no compres de más.", 14, Ui.TINTA, Ui.NORMAL).setPadding(0, ui.dp(14), 0, 0);
        }

        TextView cocinar = ui.boton("🧑‍🍳 ¿Qué puedo cocinar?", Ui.BLANCO, Ui.TEXTO, this::mostrarRecetas);
        ui.agregar(cuerpo, cocinar, 20);
    }

    // Fila de un producto de la despensa con su cantidad.
    private void filaDespensa(Despensa.Item item) {
        Producto producto = item.producto;
        LinearLayout tarjeta = ui.tarjeta(cuerpo, Ui.BLANCO, 10);
        tarjeta.setPadding(ui.dp(14), ui.dp(12), ui.dp(10), ui.dp(12));
        LinearLayout fila = ui.fila();
        tarjeta.addView(fila);
        fila.addView(ui.foto(producto.urlImagen, producto.nombre, producto.emoji(), 46));
        LinearLayout datos = ui.columna();
        datos.setPadding(ui.dp(14), 0, ui.dp(6), 0);
        ui.flexible(fila, datos);
        ui.texto(datos, producto.nombre, 16, Ui.TEXTO, Ui.NEGRITA).setMaxLines(2);
        ui.texto(datos, Despensa.formatoYPorciones(item), 13, Ui.TINTA, Ui.NORMAL);
        Producto.Oferta mejor = producto.mejor();
        if (mejor != null) {
            ui.texto(datos, "Ref. " + Ui.pesos(mejor.precio) + " en " + Tiendas.nombre(mejor.comercio), 12,
                    Ui.VERDE, Ui.NEGRITA);
        }
        if (!item.manual()) {
            ui.tocable(datos, ui.fondo(Ui.BLANCO, 10, 0), () -> abrirProducto(producto, ProductoDialog.Destino.DESPENSA));
        }

        LinearLayout controles = ui.fila();
        controles.setPadding(0, ui.dp(10), 0, 0);
        tarjeta.addView(controles);
        TextView unidades = ui.texto(null, item.cantidad + (item.cantidad == 1 ? " unidad en casa" : " unidades en casa"),
                13, Ui.TINTA, Ui.NORMAL);
        ui.flexible(controles, unidades);
        controles.addView(ui.stepper(item.cantidad, producto.nombre, () -> {
            if (item.cantidad == 1) {
                confirmarQuitar(producto);
            } else {
                despensa.cambiar(producto, -1);
                mostrar(R.id.tab_pantry);
            }
        }, () -> {
            despensa.cambiar(producto, 1);
            mostrar(R.id.tab_pantry);
        }));
        controles.addView(ui.espacio(6));
        TextView borrar = ui.texto(controles, "🗑", 18, Ui.TINTA, Ui.NORMAL);
        borrar.setGravity(Gravity.CENTER);
        borrar.setContentDescription("Quitar " + producto.nombre + " de la despensa");
        borrar.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        ui.tocable(borrar, ui.fondo(Ui.BLANCO, 22, 0), () -> confirmarQuitar(producto));
    }

    // Pide confirmación antes de quitar un producto.
    private void confirmarQuitar(Producto producto) {
        new AlertDialog.Builder(this).setTitle("¿Quitar de la despensa?")
                .setMessage(producto.nombre)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Quitar", (dialogo, boton) -> {
                    despensa.quitar(producto.clave);
                    mostrar(R.id.tab_pantry);
                }).show();
    }

    // ───────────────────────── ¿Qué puedo cocinar? ─────────────────────────
    // Todo se calcula en el teléfono con la despensa local: no se envía nada al servidor.

    private void mostrarRecetas() {
        LinearLayout contenido = ui.columna();
        contenido.setPadding(ui.dp(20), ui.dp(6), ui.dp(20), ui.dp(10));
        List<Despensa.Item> items = despensa.items();
        List<Recetas.Resultado> resultados = Recetas.evaluar(items);
        int listas = Recetas.listas(resultados);
        AlertDialog[] dialogo = new AlertDialog[1];

        if (items.isEmpty()) {
            ui.texto(contenido, "Tu despensa está vacía. Agrega lo que tienes en casa y te diremos qué puedes "
                    + "cocinar.", 14, Ui.TINTA, Ui.NORMAL);
            ui.agregar(contenido, ui.botonPequeno("Ir a la despensa", Ui.AMARILLO, Ui.BOSQUE, () -> {
                dialogo[0].dismiss();
                irA(R.id.tab_pantry);
            }), 10);
        } else {
            ui.texto(contenido, listas == 0 ? "Aún te falta algo para cada receta. Estas son las más cercanas:"
                    : listas == 1 ? "Puedes preparar 1 receta con lo que tienes."
                    : "Puedes preparar " + listas + " recetas con lo que tienes.", 14, Ui.TINTA, Ui.NORMAL);
        }

        for (Recetas.Resultado resultado : resultados) {
            Recetas.Receta receta = resultado.receta;
            LinearLayout tarjeta = ui.columna();
            tarjeta.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
            ui.texto(tarjeta, receta.icono + "  " + receta.nombre, 16, Ui.TEXTO, Ui.NEGRITA);
            ui.texto(tarjeta, "⏱ " + receta.minutos + " min · " + porciones(receta.porciones), 12, Ui.TINTA,
                    Ui.NORMAL);
            if (resultado.lista()) {
                ui.texto(tarjeta, "¡Tienes todo para prepararlo!", 13, Ui.VERDE, Ui.NEGRITA);
            } else {
                ui.texto(tarjeta, "Te falta: " + resultado.faltantes(), 13, Ui.TINTA, Ui.NORMAL);
                ui.agregar(tarjeta, ui.barra(resultado.avance(), Ui.VERDE, Ui.GRIS_LINEA), 6);
            }
            // Accesibilidad (ISO 25010, inclusividad): TalkBack lee la tarjeta completa en una frase.
            tarjeta.setContentDescription(receta.nombre + ". " + receta.minutos + " minutos. "
                    + (resultado.lista() ? "Tienes todos los ingredientes." : "Te falta " + resultado.faltantes() + ".")
                    + " Toca para ver la preparación.");
            ui.tocable(tarjeta, ui.fondo(resultado.lista() ? 0xFFE6F0E9 : Ui.BLANCO, 16, Ui.BORDE),
                    () -> mostrarReceta(resultado, dialogo[0]));
            ui.agregar(contenido, tarjeta, 8);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(contenido);
        dialogo[0] = new AlertDialog.Builder(this).setTitle("¿Qué puedo cocinar?").setView(scroll)
                .setPositiveButton("Listo", null).create();
        dialogo[0].show();
    }

    private void mostrarReceta(Recetas.Resultado resultado, AlertDialog listaDeRecetas) {
        Recetas.Receta receta = resultado.receta;
        LinearLayout contenido = ui.columna();
        contenido.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(12));
        AlertDialog[] dialogo = new AlertDialog[1];

        ui.texto(contenido, "⏱ " + receta.minutos + " min · " + porciones(receta.porciones), 13, Ui.TINTA,
                Ui.NORMAL);

        ui.seccion(contenido, "INGREDIENTES");
        for (Recetas.Ingrediente ingrediente : receta.ingredientes) {
            boolean tiene = resultado.tienes.contains(ingrediente);
            ui.texto(contenido, (tiene ? "✓  " : "✗  ") + ingrediente.nombre + (tiene ? "" : "  · te falta"), 15,
                    tiene ? Ui.VERDE : Ui.TEXTO, tiene ? Ui.NORMAL : Ui.NEGRITA).setPadding(0, ui.dp(3), 0, 0);
        }
        for (Recetas.Ingrediente ingrediente : receta.opcionales) {
            boolean tiene = resultado.opcionalesQueTienes.contains(ingrediente);
            ui.texto(contenido, (tiene ? "✓  " : "○  ") + ingrediente.nombre + " (opcional)", 15,
                    tiene ? Ui.VERDE : Ui.TINTA, Ui.NORMAL).setPadding(0, ui.dp(3), 0, 0);
        }

        ui.seccion(contenido, "PREPARACIÓN");
        for (int paso = 0; paso < receta.pasos.length; paso++) {
            ui.texto(contenido, (paso + 1) + ".  " + receta.pasos[paso], 15, Ui.TEXTO, Ui.NORMAL)
                    .setPadding(0, ui.dp(6), 0, 0);
        }

        if (!resultado.faltan.isEmpty()) {
            ui.seccion(contenido, "COMPRA LO QUE FALTA");
            ui.texto(contenido, "Toca un ingrediente para comparar su precio en los supermercados.", 13, Ui.TINTA,
                    Ui.NORMAL);
            for (Recetas.Ingrediente ingrediente : resultado.faltan) {
                ui.agregar(contenido, ui.chip("🔍  Buscar " + ingrediente.nombre, false, () -> {
                    dialogo[0].dismiss();
                    if (listaDeRecetas != null) {
                        listaDeRecetas.dismiss();
                    }
                    buscarIngrediente(ingrediente.busqueda);
                }), 8);
            }
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(contenido);
        dialogo[0] = new AlertDialog.Builder(this).setTitle(receta.icono + "  " + receta.nombre).setView(scroll)
                .setPositiveButton("Volver", null).create();
        dialogo[0].show();
    }

    /** Lleva a la pestaña Compra y busca el ingrediente, con el mismo flujo que una búsqueda escrita. */
    private void buscarIngrediente(String termino) {
        categoria = null;
        reiniciarResultados();
        consulta = termino;
        irA(R.id.tab_shop);
        buscar(termino);
    }

    private static String porciones(int cantidad) {
        return cantidad == 1 ? "1 porción" : cantidad + " porciones";
    }

    // ───────────────────────────── Perfil ─────────────────────────────

    // Perfil: cómo se mueve el usuario y qué prioriza (dinero o tiempo).
    private void mostrarPerfil() {
        ui.titulo(cuerpo, "Tu perfil", "Estas preferencias cambian el algoritmo");
        Configuracion actual = ajustes.leer();

        ui.seccion(cuerpo, "CÓMO TE MUEVES");
        for (Modo modo : Modo.values()) {
            String detalle = switch (modo) {
                case PIE -> "Sin costo";
                case AUTO -> "$" + actual.costoPorKm() + "/km · bencina " + actual.bencina.octanaje;
                case PUBLICO -> "$" + actual.pasaje() + "/tramo · " + actual.medio.nombre.toLowerCase()
                        + " " + actual.tarifa.nombre.toLowerCase();
            };
            opcion(modo.icono, modo.nombre, detalle, actual.modo == modo, () -> {
                ajustes.modo(modo);
                mostrar(R.id.tab_profile);
            });
        }
        if (actual.modo == Modo.AUTO) {
            ajustesAuto(actual);
        } else if (actual.modo == Modo.PUBLICO) {
            ajustesPublico(actual);
        }

        ui.seccion(cuerpo, "QUÉ PRIORIZAS");
        radio("Ahorrar dinero", "Acepto moverme más si el ahorro lo justifica", !actual.priorizarTiempo, () -> {
            ajustes.priorizarTiempo(false);
            mostrar(R.id.tab_profile);
        });
        radio("Ahorrar tiempo", "Prefiero una sola parada aunque cueste algo más", actual.priorizarTiempo, () -> {
            ajustes.priorizarTiempo(true);
            mostrar(R.id.tab_profile);
        });

        ui.seccion(cuerpo, "MODO PRESUPUESTO");
        LinearLayout presupuesto = ui.tarjeta(cuerpo, Ui.BLANCO, 8);
        LinearLayout fila = ui.fila();
        presupuesto.addView(fila);
        LinearLayout datos = ui.columna();
        ui.flexible(fila, datos);
        ui.texto(datos, "Tope de compra", 16, Ui.TEXTO, Ui.NEGRITA);
        ui.texto(datos, historial.presupuesto() == 0 ? "Sin tope" : Ui.pesos(historial.presupuesto()), 14,
                Ui.TINTA, Ui.NORMAL);
        fila.addView(ui.botonPequeno("Cambiar", Ui.GRIS, Ui.TEXTO, this::editarPresupuesto));

        TextView nota = ui.texto(cuerpo, "Servidor de precios: " + (ApiPrecios.configurada()
                ? BuildConfig.PRECIOS_API_URL : "sin configurar"), 12, Ui.TINTA, Ui.NORMAL);
        nota.setPadding(0, ui.dp(18), 0, 0);
    }

    // Ajustes del auto: bencina, precio por litro y rendimiento.
    private void ajustesAuto(Configuracion actual) {
        LinearLayout tarjeta = ui.tarjeta(cuerpo, Ui.BLANCO, 10);
        ui.texto(tarjeta, "Bencina", 15, Ui.TEXTO, Ui.NEGRITA);
        LinearLayout octanos = ui.fila();
        octanos.setPadding(0, ui.dp(8), 0, ui.dp(4));
        tarjeta.addView(octanos);
        for (Bencina bencina : Bencina.values()) {
            TextView chip = ui.chip(bencina.octanaje, bencina == actual.bencina, () -> {
                ajustes.bencina(bencina);
                mostrar(R.id.tab_profile);
            });
            LinearLayout.LayoutParams separacion = new LinearLayout.LayoutParams(-2, -2);
            separacion.setMarginEnd(ui.dp(8));
            octanos.addView(chip, separacion);
        }
        dato(tarjeta, "Precio por litro", Ui.pesos(actual.precioLitro), () ->
                editarNumero("Precio de la bencina " + actual.bencina.octanaje,
                        "Pesos por litro. Referencia: promedio RM " + Ui.pesos(actual.bencina.precioReferencia)
                                + " (" + Traslado.FECHA_BENCINA + ").",
                        actual.precioLitro, 5, 300, valor -> {
                            ajustes.precioLitro(actual.bencina, valor);
                            mostrar(R.id.tab_profile);
                        }));
        dato(tarjeta, "Rendimiento del auto", Traslado.formatoDecimal(actual.kilometrosPorLitro) + " km/L", () ->
                editarNumero("Rendimiento", "Kilómetros por litro en ciudad (1 a 40).",
                        (int) Math.round(actual.kilometrosPorLitro), 2, 1, valor -> {
                            ajustes.rendimiento(Math.min(40, valor));
                            mostrar(R.id.tab_profile);
                        }));
        TextView fuente = ui.texto(tarjeta, "Precio de referencia: promedio de la Región Metropolitana desde el "
                + Traslado.FECHA_BENCINA + ". Revisa tu bencinera en Bencina en Línea ›", 12, Ui.VERDE, Ui.NORMAL);
        fuente.setPadding(0, ui.dp(10), 0, 0);
        ui.tocable(fuente, ui.fondo(Ui.BLANCO, 8, 0),
                () -> AjustesTraslado.abrir(this, Uri.parse(AjustesTraslado.URL_BENCINA)));
        if (actual.precioLitro != actual.bencina.precioReferencia) {
            TextView restablecer = ui.texto(tarjeta, "Usar precio de referencia", 13, Ui.TINTA, Ui.NEGRITA);
            restablecer.setPadding(0, ui.dp(10), 0, ui.dp(4));
            ui.tocable(restablecer, ui.fondo(Ui.BLANCO, 8, 0), () -> {
                ajustes.restablecerPrecio(actual.bencina);
                mostrar(R.id.tab_profile);
            });
        }
    }

    // Ajustes del transporte público: medio y tarifa.
    private void ajustesPublico(Configuracion actual) {
        LinearLayout tarjeta = ui.tarjeta(cuerpo, Ui.BLANCO, 10);
        ui.texto(tarjeta, "Medio", 15, Ui.TEXTO, Ui.NEGRITA);
        LinearLayout medios = ui.fila();
        medios.setPadding(0, ui.dp(8), 0, ui.dp(12));
        tarjeta.addView(medios);
        for (Medio medio : Medio.values()) {
            TextView chip = ui.chip(medio.icono + " " + medio.nombre, medio == actual.medio, () -> {
                ajustes.medio(medio);
                mostrar(R.id.tab_profile);
            });
            LinearLayout.LayoutParams separacion = new LinearLayout.LayoutParams(-2, -2);
            separacion.setMarginEnd(ui.dp(8));
            medios.addView(chip, separacion);
        }
        ui.texto(tarjeta, "Tarifa", 15, Ui.TEXTO, Ui.NEGRITA);
        HorizontalScrollView desplazable = new HorizontalScrollView(this);
        desplazable.setHorizontalScrollBarEnabled(false);
        tarjeta.addView(desplazable);
        LinearLayout tarifas = ui.fila();
        tarifas.setPadding(0, ui.dp(8), 0, ui.dp(8));
        desplazable.addView(tarifas);
        for (Tarifa tarifa : Tarifa.values()) {
            TextView chip = ui.chip(tarifa.nombre + " $" + Traslado.pasaje(actual.medio, tarifa),
                    tarifa == actual.tarifa, () -> {
                        ajustes.tarifa(tarifa);
                        mostrar(R.id.tab_profile);
                    });
            LinearLayout.LayoutParams separacion = new LinearLayout.LayoutParams(-2, -2);
            separacion.setMarginEnd(ui.dp(8));
            tarifas.addView(chip, separacion);
        }
        String horario = actual.medio == Medio.MICRO && actual.tarifa != Tarifa.ESTUDIANTE
                ? "La micro cobra lo mismo a toda hora."
                : actual.tarifa.horario + ".";
        ui.texto(tarjeta, horario + " Ida y vuelta: " + Ui.pesos(2L * actual.pasaje()) + ".", 13, Ui.TINTA, Ui.NORMAL);
        TextView fuente = ui.texto(tarjeta, "Tarifas Red vigentes desde el " + Traslado.FECHA_PASAJES
                + ". Ver tarifas en red.cl ›", 12, Ui.VERDE, Ui.NORMAL);
        fuente.setPadding(0, ui.dp(8), 0, 0);
        ui.tocable(fuente, ui.fondo(Ui.BLANCO, 8, 0),
                () -> AjustesTraslado.abrir(this, Uri.parse(AjustesTraslado.URL_TARIFAS)));
    }

    // Fila "nombre: valor" que se puede tocar para editar.
    private void dato(LinearLayout tarjeta, String nombre, String valor, Runnable editar) {
        tarjeta.addView(ui.separador());
        LinearLayout fila = ui.fila();
        tarjeta.addView(fila);
        TextView etiqueta = ui.texto(null, nombre, 14, Ui.TINTA, Ui.NORMAL);
        ui.flexible(fila, etiqueta);
        ui.texto(fila, valor, 16, Ui.TEXTO, Ui.GRUESA);
        TextView lapiz = ui.texto(fila, "  ✏️", 15, Ui.TEXTO, Ui.NORMAL);
        lapiz.setContentDescription("Editar " + nombre);
        ui.tocable(fila, ui.fondo(Ui.BLANCO, 8, 0), editar);
    }

    // Opción seleccionable con ícono.
    private void opcion(String icono, String titulo, String detalle, boolean elegida, Runnable accion) {
        LinearLayout tarjeta = ui.tarjeta(cuerpo, elegida ? Ui.BOSQUE : Ui.BLANCO, 10);
        LinearLayout fila = ui.fila();
        tarjeta.addView(fila);
        TextView emoji = ui.texto(fila, icono, 22, Ui.TEXTO, Ui.NORMAL);
        emoji.setPadding(0, 0, ui.dp(16), 0);
        LinearLayout datos = ui.columna();
        ui.flexible(fila, datos);
        ui.texto(datos, titulo, 16, elegida ? Ui.BLANCO : Ui.TEXTO, Ui.NEGRITA);
        ui.texto(datos, detalle, 13, elegida ? Ui.TENUE_OSCURO : Ui.TINTA, Ui.NORMAL);
        ui.tocable(tarjeta, ui.fondo(elegida ? Ui.BOSQUE : Ui.BLANCO, 20, elegida ? 0 : Ui.BORDE), accion);
        tarjeta.setSelected(elegida);
    }

    // Opción tipo radio (una entre varias).
    private void radio(String titulo, String detalle, boolean elegida, Runnable accion) {
        LinearLayout tarjeta = ui.tarjeta(cuerpo, Ui.BLANCO, 10);
        LinearLayout fila = ui.fila();
        tarjeta.addView(fila);
        TextView marca = ui.texto(fila, elegida ? "◉" : "○", 22, elegida ? Ui.VERDE : Ui.TINTA, Ui.NEGRITA);
        marca.setPadding(0, 0, ui.dp(14), 0);
        LinearLayout datos = ui.columna();
        ui.flexible(fila, datos);
        ui.texto(datos, titulo, 16, Ui.TEXTO, Ui.NEGRITA);
        ui.texto(datos, detalle, 13, Ui.TINTA, Ui.NORMAL);
        ui.tocable(tarjeta, ui.fondo(Ui.BLANCO, 20, Ui.BORDE), accion);
        tarjeta.setSelected(elegida);
    }

    // Edita el tope de gasto (modo presupuesto).
    private void editarPresupuesto() {
        editarNumero("Modo presupuesto", "Tope en pesos para tu compra. Usa 0 para comprar sin tope.",
                historial.presupuesto(), 7, 0, valor -> {
                    historial.fijarPresupuesto(valor);
                    mostrar(navegacion.getCheckedRadioButtonId());
                });
    }

    // Diálogo genérico para editar un número con validación de rango.
    private void editarNumero(String titulo, String ayuda, int valor, int digitos, int minimo, IntConsumer guardar) {
        LinearLayout formulario = ui.columna();
        formulario.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        TextView instrucciones = ui.texto(formulario, ayuda, 14, Ui.TINTA, Ui.NORMAL);
        EditText campo = new EditText(this);
        campo.setInputType(InputType.TYPE_CLASS_NUMBER);
        campo.setFilters(new InputFilter[]{new InputFilter.LengthFilter(digitos)});
        campo.setText(String.valueOf(valor));
        campo.setSelectAllOnFocus(true);
        campo.setId(View.generateViewId());
        instrucciones.setLabelFor(campo.getId());
        formulario.addView(campo);
        AlertDialog dialogo = new AlertDialog.Builder(this).setTitle(titulo).setView(formulario)
                .setNegativeButton("Cancelar", null).setPositiveButton("Guardar", null).create();
        dialogo.setOnShowListener(ventana -> dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int numero;
            try {
                numero = Integer.parseInt(campo.getText().toString().trim());
            } catch (NumberFormatException error) {
                campo.setError("Ingresa un número");
                return;
            }
            if (numero < minimo) {
                campo.setError("El mínimo es " + minimo);
                return;
            }
            dialogo.dismiss();
            guardar.accept(numero);
        }));
        dialogo.show();
    }

    // Esconde el teclado en pantalla.
    private void ocultarTeclado() {
        View foco = getCurrentFocus();
        InputMethodManager teclado = getSystemService(InputMethodManager.class);
        if (foco != null && teclado != null) {
            teclado.hideSoftInputFromWindow(foco.getWindowToken(), 0);
        }
    }
}
