package com.abarrato.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.abarrato.app.NearbyStores.Result;
import com.abarrato.app.NearbyStores.Store;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

// Pantalla "Optimizar": mapa de supermercados a 2 km, costo de llegar a cada uno
// y el mejor plan de compra (precio de la lista + transporte).
public class NearbyStoresActivity extends Activity implements LocationListener {
    private static final int REQUEST_LOCATION = 1;
    private static final long QUERY_INTERVAL_MS = 30_000;
    private static final long MANUAL_INTERVAL_MS = 5_000;
    private static final long LOCATION_MAX_AGE_MS = 120_000;
    private static final long RUTAS_CACHE_MS = 300_000;
    private static final long COMPRA_CACHE_MS = 120_000;
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String MAP_URL = "https://" + ASSET_HOST + "/nearby/index.html";
    private static final String ATTRIBUTION = "https://www.openstreetmap.org/copyright";
    private static final Locale CHILE = new Locale("es", "CL");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService trasladosWorker = Executors.newFixedThreadPool(2);
    private final NumberFormat pesos = NumberFormat.getCurrencyInstance(CHILE);
    private final Runnable refresh = this::fetchStores;
    private final Runnable locationTimeout = this::showLocationTimeout;

    private LocationManager locationManager;
    private Location location;
    private Location requestedFrom;
    private Location fetchedFrom;
    private Future<?> request;
    private Future<?> consultaRutas;
    private Future<?> consultaCompra;
    private Location origenRutas;
    private String firmaRutas = "";
    private String carritoConsultado = "";
    private long rutasConsultadasEn;
    private long compraConsultadaEn;
    private int versionRutas;
    private int versionCompra;
    private Map<String, Traslado.Ruta> rutas = Collections.emptyMap();
    private Map<String, ListaCompra.Item> productosCompra = Collections.emptyMap();
    private List<ListaCompra.Item> yaEnCasa = Collections.emptyList();
    private long ahorroDespensa;
    private Map<String, Map<String, Long>> preciosWeb;
    private boolean preciosGuardados;
    private Ui vista;
    private PlanCompra planActual;
    private long ahorroActual;
    /** Con qué se compara el ahorro: la tienda más cara con la lista completa (null = precio más alto de cada producto). */
    private String referenciaTienda;
    private long referenciaProductos;
    private Long referenciaTraslado;
    /** Precio más caro conocido de cada producto comprado (por la cantidad), para el desglose. */
    private Map<String, Long> masCaro = Collections.emptyMap();
    private AjustesTraslado ajustes;
    private Traslado.Configuracion traslado;
    private List<Store> directory = Collections.emptyList();
    private List<Store> backup = Collections.emptyList();
    private List<Store> mapped = Collections.emptyList();
    private List<Result> results = Collections.emptyList();
    private WebView map;
    private TextView status;
    private Button retry;
    private Button transporte;
    private LinearLayout list;
    private PlanCompraDialog dialogoPlan;
    private boolean active;
    private boolean listening;
    private boolean asked;
    private boolean mapReady;
    private boolean fetchFailed;
    private long requestedAt;
    private int generation;

    // Un supermercado con su viaje estimado y los precios de la lista en esa cadena.
    private static final class OpcionTraslado {
        final Result resultado;
        final Traslado.Estimacion viaje;
        final Map<String, Long> precios;
        final Long compra;
        final Long total;

        OpcionTraslado(Result resultado, Traslado.Estimacion viaje, Map<String, Long> precios, int productos) {
            this.resultado = resultado;
            this.viaje = viaje;
            this.precios = precios;
            this.compra = productos > 0 && precios.size() == productos
                    ? precios.values().stream().mapToLong(Long::longValue).sum() : null;
            this.total = viaje.totalConCompra(compra);
        }
    }

    // Arma la pantalla, el mapa y los botones.
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_nearby_stores);
        map = findViewById(R.id.nearby_map);
        status = findViewById(R.id.nearby_status);
        retry = findViewById(R.id.nearby_retry);
        transporte = findViewById(R.id.nearby_transport);
        list = findViewById(R.id.nearby_results);
        ajustes = new AjustesTraslado(this);
        pesos.setMaximumFractionDigits(0);
        vista = new Ui(this);
        transporte.setOnClickListener(view -> ajustes.elegirRapido(this, () -> {
            actualizarTransporte();
            origenRutas = null;
            if (active && location != null) {
                render(false);
            }
        }));
        findViewById(R.id.nearby_route_source).setOnClickListener(view -> AjustesTraslado.abrir(this,
                Uri.parse("https://www.openstreetmap.org/fixthemap")));
        locationManager = getSystemService(LocationManager.class);
        asked = state != null && state.getBoolean("asked");
        findViewById(R.id.nearby_back).setOnClickListener(view -> finish());

        if (Build.VERSION.SDK_INT < 26) {
            getWindow().setNavigationBarColor(getColor(R.color.forest));
        }
        if (Build.VERSION.SDK_INT >= 35) {
            View root = findViewById(R.id.nearby_root);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
            root.requestApplyInsets();
        }
        try {
            directory = NearbyStores.loadDirectory(this, "directory.json");
        } catch (IOException | JSONException error) {
            android.util.Log.w("Abarrato", "No se pudo leer el directorio local", error);
        }
        configureMap();
        worker.execute(this::loadBackup);
    }

    // Al mostrarse: pide permiso de ubicación o empieza a seguirla.
    @Override
    protected void onStart() {
        super.onStart();
        active = true;
        actualizarTransporte();
        cargarCompra();
        map.onResume();
        if (hasLocation()) {
            watchLocation();
        } else if (!asked) {
            requestLocation();
        } else {
            showPermissionAction();
        }
    }

    // Al ocultarse: deja de usar el GPS para ahorrar batería.
    @Override
    protected void onStop() {
        active = false;
        if (listening) {
            locationManager.removeUpdates(this);
            listening = false;
        }
        ui.removeCallbacks(refresh);
        ui.removeCallbacks(locationTimeout);
        if (request != null) {
            request.cancel(true);
            request = null;
            requestedFrom = null;
            generation++;
        }
        if (consultaRutas != null) {
            consultaRutas.cancel(true);
            consultaRutas = null;
            origenRutas = null;
            versionRutas++;
        }
        map.onPause();
        super.onStop();
    }

    // Libera hebras y el mapa.
    @Override
    protected void onDestroy() {
        if (dialogoPlan != null) {
            dialogoPlan.dismiss();
        }
        worker.shutdownNow();
        trasladosWorker.shutdownNow();
        ((ViewGroup) map.getParent()).removeView(map);
        map.destroy();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("asked", asked);
        super.onSaveInstanceState(state);
    }

    // true si hay permiso de ubicación (aproximada o precisa).
    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || hasPreciseLocation();
    }

    // true si hay permiso de ubicación precisa.
    private boolean hasPreciseLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    // Pide el permiso de ubicación al usuario.
    private void requestLocation() {
        asked = true;
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
        }, REQUEST_LOCATION);
    }

    // Respuesta del usuario al permiso.
    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(code, permissions, grantResults);
        if (code == REQUEST_LOCATION && active) {
            if (hasLocation()) {
                watchLocation();
            } else {
                showPermissionAction();
            }
        }
    }

    // Mensaje con botón para dar el permiso desde Ajustes.
    private void showPermissionAction() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            showAction(R.string.nearby_permission_needed, R.string.nearby_grant, this::requestLocation);
        } else {
            showAction(R.string.nearby_permission_needed, R.string.nearby_location_settings, () ->
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null))));
        }
    }

    // Empieza a recibir la ubicación y usa la última conocida mientras tanto.
    private void watchLocation() {
        if (listening) {
            return;
        }
        status.setText(R.string.nearby_waiting);
        retry.setVisibility(View.GONE);
        Location cached = null;
        String[] providers = hasPreciseLocation()
                ? new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}
                : new String[]{LocationManager.NETWORK_PROVIDER};
        for (String provider : providers) {
            if (locationManager.isProviderEnabled(provider)) {
                try {
                    locationManager.requestLocationUpdates(provider, 4000, 10, this);
                    listening = true;
                    Location last = locationManager.getLastKnownLocation(provider);
                    if (last != null && age(last) <= LOCATION_MAX_AGE_MS
                            && (cached == null || last.getAccuracy() < cached.getAccuracy())) {
                        cached = last;
                    }
                } catch (SecurityException | IllegalArgumentException ignored) {
                    // El proveedor puede cambiar entre comprobarlo y registrarlo.
                }
            }
        }
        if (cached != null) {
            onLocationChanged(cached);
        } else if (!listening) {
            showAction(R.string.nearby_enable_location, R.string.nearby_location_settings,
                    this::openLocationSettings);
        } else {
            ui.postDelayed(locationTimeout, 20_000);
        }
    }

    // Antigüedad de una lectura de GPS.
    private long age(Location fix) {
        return (SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos()) / 1_000_000;
    }

    // Aviso si la ubicación tarda demasiado.
    private void showLocationTimeout() {
        if (location == null && listening) {
            showAction(R.string.nearby_slow_location, R.string.nearby_location_settings,
                    this::openLocationSettings);
        }
    }

    // Abre los ajustes de ubicación del teléfono.
    private void openLocationSettings() {
        startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
    }

    // Nueva ubicación: actualiza el mapa y, si se movió bastante, busca de nuevo.
    @Override
    public void onLocationChanged(Location fix) {
        if (!active || age(fix) > LOCATION_MAX_AGE_MS) {
            return;
        }
        if (location != null && age(location) < 30_000
                && fix.getAccuracy() > location.getAccuracy() + 100) {
            return;
        }
        ui.removeCallbacks(locationTimeout);
        location = new Location(fix);
        render(false);
        queueRefresh();
    }

    @Override
    public void onProviderEnabled(String provider) {
        if (active && !listening && hasLocation()) {
            watchLocation();
        }
    }

    @Override
    public void onProviderDisabled(String provider) {}

    @Override
    public void onStatusChanged(String provider, int providerStatus, Bundle extras) {}

    // Programa una nueva búsqueda solo si el usuario se movió más de 150 m.
    private void queueRefresh() {
        if (!active || request != null || location == null) {
            return;
        }
        if (requestedFrom == null
                || requestedFrom.distanceTo(location) >= NearbyStores.REFRESH_DISTANCE_METERS) {
            ui.removeCallbacks(refresh);
            long delay = requestedFrom == null ? 0
                    : Math.max(0, QUERY_INTERVAL_MS - (SystemClock.elapsedRealtime() - requestedAt));
            ui.postDelayed(refresh, delay);
        }
    }

    // Busca los supermercados cercanos en segundo plano.
    private void fetchStores() {
        if (!active || request != null || location == null) {
            return;
        }
        Location center = new Location(location);
        requestedFrom = center;
        requestedAt = SystemClock.elapsedRealtime();
        fetchFailed = false;
        int id = ++generation;
        request = worker.submit(() -> {
            try {
                List<Store> stores = NearbyStores.search(center);
                ui.post(() -> finishSearch(id, stores));
            } catch (IOException | JSONException error) {
                ui.post(() -> finishSearch(id, null));
            }
        });
        render(false);
    }

    // Recibe el resultado (ignora respuestas de búsquedas antiguas).
    private void finishSearch(int id, List<Store> stores) {
        if (!active || id != generation) {
            return;
        }
        request = null;
        fetchFailed = stores == null;
        if (!fetchFailed) {
            mapped = stores;
            fetchedFrom = requestedFrom;
        }
        render(false);
        queueRefresh();
    }

    // Botón "Actualizar".
    private void refreshManually() {
        if (consultaRutas == null && rutas.isEmpty()) {
            origenRutas = null;
        }
        cargarCompra();
        render(true);
        if (SystemClock.elapsedRealtime() - requestedAt >= MANUAL_INTERVAL_MS) {
            ui.removeCallbacks(refresh);
            fetchStores();
        }
    }

    // Lee la configuración de traslado y muestra su resumen.
    private void actualizarTransporte() {
        traslado = ajustes.leer();
        transporte.setText(traslado.resumen() + "  ·  Cambiar");
    }

    /** Prepara las cantidades por comprar (lista menos despensa) y pide los precios vigentes. */
    private void cargarCompra() {
        ListaCompra lista = new ListaCompra(this);
        Despensa despensa = new Despensa(this);
        List<ListaCompra.Item> todos = lista.items();
        Map<String, ListaCompra.Item> netos = new LinkedHashMap<>();
        List<ListaCompra.Item> cubiertos = new ArrayList<>();
        for (ListaCompra.Item item : todos) {
            int faltan = ListaCompra.porComprar(item, despensa);
            if (faltan > 0) {
                netos.put(item.producto.clave, new ListaCompra.Item(item.producto, faltan, item.comercio));
            }
            if (faltan < item.cantidad) {
                cubiertos.add(new ListaCompra.Item(item.producto, item.cantidad - faltan, item.comercio));
            }
        }
        yaEnCasa = cubiertos;
        ahorroDespensa = despensa.ahorroPotencial(todos);
        try {
            JSONObject solicitud = ListaCompra.solicitud(new ArrayList<>(netos.values()));
            String firma = solicitud.toString();
            if (firma.equals(carritoConsultado) && (consultaCompra != null
                    || (preciosWeb != null && SystemClock.elapsedRealtime() - compraConsultadaEn < COMPRA_CACHE_MS))) {
                return;
            }
            carritoConsultado = firma;
            int version = ++versionCompra;
            if (consultaCompra != null) {
                consultaCompra.cancel(true);
                consultaCompra = null;
            }
            productosCompra = netos;
            preciosWeb = null;
            if (netos.isEmpty()) {
                preciosWeb = Collections.emptyMap();
                return;
            }
            if (!ApiPrecios.configurada()) {
                preciosWeb = preciosDeLista(netos);
                preciosGuardados = true;
                return;
            }
            consultaCompra = trasladosWorker.submit(() -> {
                Map<String, Map<String, Long>> compra = null;
                try {
                    compra = leerPrecios(ApiPrecios.cotizar(solicitud), netos);
                } catch (IOException | JSONException error) {
                    android.util.Log.w("Abarrato", "No se pudo cotizar el plan de compra");
                }
                Map<String, Map<String, Long>> cotizacion = compra;
                ui.post(() -> {
                    if (version == versionCompra && !isDestroyed()) {
                        consultaCompra = null;
                        compraConsultadaEn = SystemClock.elapsedRealtime();
                        preciosGuardados = cotizacion == null;
                        preciosWeb = cotizacion == null ? preciosDeLista(netos) : cotizacion;
                        if (active && location != null) {
                            render(false);
                        }
                    }
                });
            });
        } catch (JSONException error) {
            android.util.Log.w("Abarrato", "No se pudo preparar la comparación con traslado");
        }
    }

    /** Precios de la última búsqueda, guardados con cada producto de la lista. */
    private static Map<String, Map<String, Long>> preciosDeLista(Map<String, ListaCompra.Item> productos) {
        Map<String, Map<String, Long>> compra = new HashMap<>();
        for (ListaCompra.Item item : productos.values()) {
            // Se guardan los precios de todas las cadenas, aunque la persona haya elegido una:
            // si su cadena no está cerca, el producto se puede comprar en otra (ver mostrarPlan).
            for (Producto.Oferta oferta : item.producto.ofertas) {
                compra.computeIfAbsent(oferta.comercio, ignorado -> new HashMap<>())
                        .put(item.producto.clave, (long) oferta.precio * item.cantidad);
            }
        }
        return compra;
    }

    // Convierte la respuesta de /comparar-carrito en precios por cadena y producto.
    private static Map<String, Map<String, Long>> leerPrecios(JSONObject respuesta,
            Map<String, ListaCompra.Item> productos) throws JSONException {
        Map<String, Map<String, Long>> compra = new HashMap<>();
        JSONArray opciones = respuesta.getJSONArray("opciones");
        for (int i = 0; i < opciones.length(); i++) {
            JSONObject opcion = opciones.getJSONObject(i);
            String comercio = opcion.getString("comercio");
            if (!Tiendas.incluida(comercio)) {
                continue;
            }
            Map<String, Long> precios = new HashMap<>();
            JSONArray lineas = opcion.getJSONArray("productos");
            for (int j = 0; j < lineas.length(); j++) {
                JSONObject linea = lineas.getJSONObject(j);
                String clave = linea.getString("clave");
                long subtotal = linea.isNull("subtotalClp") ? -1 : linea.optLong("subtotalClp", -1);
                ListaCompra.Item item = productos.get(clave);
                // El servidor cotiza con el precio normal; si la tienda tiene promoción, vale la promoción.
                Producto.Oferta conocida = item == null ? null : item.producto.oferta(comercio);
                if (subtotal >= 0 && conocida != null && conocida.precioOferta > 0) {
                    subtotal = Math.min(subtotal, (long) conocida.precio * item.cantidad);
                }
                if (subtotal >= 0 && item != null) {
                    precios.put(clave, subtotal);
                }
            }
            compra.put(comercio, precios);
        }
        // Si la cotización en vivo no encontró un producto, se usa el precio de su última búsqueda.
        Map<String, Map<String, Long>> guardados = preciosDeLista(productos);
        for (Map.Entry<String, Map<String, Long>> tienda : guardados.entrySet()) {
            Map<String, Long> vivos = compra.computeIfAbsent(tienda.getKey(), ignorado -> new HashMap<>());
            for (Map.Entry<String, Long> precio : tienda.getValue().entrySet()) {
                vivos.putIfAbsent(precio.getKey(), precio.getValue());
            }
        }
        return compra;
    }

    // Pide las distancias por calles a OSRM (con coordenadas redondeadas).
    private void cargarRutas(List<Result> candidatos) {
        if (candidatos.isEmpty() || traslado.modo == Traslado.Modo.PUBLICO) {
            return;
        }
        boolean aPie = traslado.modo == Traslado.Modo.PIE;
        String firma = (aPie ? "pie/" : "calles/") + candidatos.stream()
                .map(item -> item.store.id).sorted().collect(Collectors.joining(","));
        long ahora = SystemClock.elapsedRealtime();
        if (firma.equals(firmaRutas) && origenRutas != null
                && origenRutas.distanceTo(location) < NearbyStores.REFRESH_DISTANCE_METERS
                && (consultaRutas != null || ahora - rutasConsultadasEn < RUTAS_CACHE_MS)) {
            return;
        }
        if (consultaRutas != null) {
            consultaRutas.cancel(true);
        }
        Location origen = new Location(location);
        firmaRutas = firma;
        origenRutas = origen;
        rutas = Collections.emptyMap();
        int version = ++versionRutas;
        consultaRutas = trasladosWorker.submit(() -> {
            try {
                Map<String, Traslado.Ruta> calculadas = RutasTraslado.calcular(origen, candidatos, aPie);
                ui.post(() -> {
                    if (active && version == versionRutas) {
                        consultaRutas = null;
                        rutasConsultadasEn = SystemClock.elapsedRealtime();
                        rutas = calculadas;
                        render(false);
                    }
                });
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // Redibuja la lista y el mapa con lo que se sabe hasta ahora.
    private void render(boolean recenter) {
        boolean hasFreshCoverage = fetchedFrom != null
                && fetchedFrom.distanceTo(location) < NearbyStores.REFRESH_DISTANCE_METERS;
        List<Result> candidatos = new ArrayList<>();
        for (Result resultado : NearbyStores.rank(location, directory, hasFreshCoverage ? mapped : backup)) {
            // Solo las cadenas que compara Abarrato (Tiendas.IDS).
            if (Tiendas.incluida(resultado.store.comercio)) {
                candidatos.add(resultado);
            }
        }
        cargarRutas(candidatos);
        List<OpcionTraslado> opciones = new ArrayList<>();
        for (Result resultado : candidatos) {
            Traslado.Ruta ruta = traslado.modo == Traslado.Modo.PUBLICO ? null : rutas.get(resultado.store.id);
            Traslado.Estimacion viaje = Traslado.estimar(traslado, resultado.meters, ruta);
            Map<String, Long> precios = preciosWeb == null ? Collections.emptyMap()
                    : preciosWeb.getOrDefault(resultado.store.comercio, Collections.emptyMap());
            opciones.add(new OpcionTraslado(resultado, viaje, precios, productosCompra.size()));
        }
        int valorMinuto = traslado.valorMinuto();
        Comparator<OpcionTraslado> puntaje = Comparator.comparing((OpcionTraslado opcion) -> opcion.total == null
                ? null : opcion.total + (long) opcion.viaje.minutos * valorMinuto, PlanCompra.COSTOS);
        opciones.sort(Comparator.comparingInt((OpcionTraslado opcion) -> opcion.precios.size()).reversed()
                .thenComparing(puntaje)
                .thenComparingInt(opcion -> opcion.viaje.minutos)
                .thenComparingDouble(opcion -> opcion.resultado.meters));
        results = opciones.stream().map(opcion -> opcion.resultado).collect(Collectors.toList());
        list.removeAllViews();
        planActual = null;
        if (productosCompra.isEmpty() && !yaEnCasa.isEmpty()) {
            todoEnCasa();
        } else if (preciosWeb != null && !opciones.isEmpty()) {
            mostrarPlan(opciones);
        }
        for (int index = 0; index < opciones.size(); index++) {
            addResult(opciones.get(index), index);
        }

        String summary;
        if (fetchFailed) {
            summary = results.isEmpty() ? getString(R.string.nearby_failed)
                    : getString(R.string.nearby_partial, results.size());
        } else if (request != null || !hasFreshCoverage) {
            summary = results.isEmpty() ? getString(R.string.nearby_searching)
                    : getString(R.string.nearby_refreshing, results.size());
        } else if (results.isEmpty()) {
            summary = getString(R.string.nearby_empty);
        } else {
            summary = getString(R.string.nearby_count, results.size());
        }
        if (!hasPreciseLocation() || location.getAccuracy() > 300) {
            summary += " " + getString(R.string.nearby_approximate);
        }
        if (consultaCompra != null) {
            summary += "\nCotizando tu lista en vivo…";
        } else if (preciosGuardados && !productosCompra.isEmpty()) {
            summary += "\nSin conexión con el servidor: se usan los precios de tu última búsqueda.";
        }
        status.setText(summary);
        retry.setVisibility(View.VISIBLE);
        retry.setText(R.string.nearby_refresh);
        retry.setEnabled(request == null);
        retry.setOnClickListener(view -> refreshManually());
        updateMap(recenter);
    }

    // Carga la lista local de supermercados como respaldo.
    private void loadBackup() {
        try {
            List<Store> stores = NearbyStores.loadDirectory(this, "stores-cl.json");
            ui.post(() -> {
                backup = stores;
                if (active && location != null) {
                    render(false);
                }
            });
        } catch (IOException | JSONException error) {
            android.util.Log.w("Abarrato", "No se pudo leer el respaldo de supermercados");
        }
    }

    // Muestra un mensaje con un botón de acción.
    private void showAction(int message, int buttonText, Runnable action) {
        status.setText(message);
        retry.setText(buttonText);
        retry.setEnabled(true);
        retry.setVisibility(View.VISIBLE);
        retry.setOnClickListener(view -> action.run());
    }

    // Caso especial: todo lo de la lista ya está en la despensa.
    private void todoEnCasa() {
        LinearLayout tarjeta = vista.tarjeta(list, Ui.BOSQUE, 0);
        vista.etiqueta(tarjeta, "NO NECESITAS SALIR", Ui.AMARILLO);
        vista.texto(tarjeta, "Todo tu lista ya está en tu despensa", 20, Ui.BLANCO, Ui.GRUESA);
        vista.texto(tarjeta, "Ahorras " + Ui.pesos(ahorroDespensa) + " al no repetir la compra.", 14,
                Ui.TENUE_OSCURO, Ui.NORMAL);
    }

    // Tarjeta del mejor plan: tiendas, total, ahorro en el súper y gasto en transporte.
    private void mostrarPlan(List<OpcionTraslado> opciones) {
        Map<String, Store> sucursales = new LinkedHashMap<>();
        List<PlanCompra.Tienda> tiendas = new ArrayList<>();
        for (OpcionTraslado opcion : opciones) {
            Store tienda = opcion.resultado.store;
            // Las opciones ya vienen ordenadas: la primera sucursal de cada cadena es la más conveniente.
            if (!opcion.precios.isEmpty() && !sucursales.containsKey(tienda.comercio)) {
                sucursales.put(tienda.comercio, tienda);
                tiendas.add(new PlanCompra.Tienda(tienda.comercio, opcion.precios, opcion.viaje));
            }
        }
        // Respeta la tienda que la persona eligió para cada producto, pero solo si esa cadena está cerca
        // y tiene el producto. Si no, el producto se cotiza en las demás cadenas cercanas que lo tengan.
        List<String> sinSuTienda = new ArrayList<>();
        for (Map.Entry<String, ListaCompra.Item> producto : productosCompra.entrySet()) {
            ListaCompra.Item item = producto.getValue();
            if (item.comercio.isEmpty()) {
                continue;
            }
            boolean cerca = false;
            for (PlanCompra.Tienda tienda : tiendas) {
                cerca |= tienda.id.equals(item.comercio) && tienda.precios.containsKey(producto.getKey());
            }
            if (cerca) {
                for (int i = 0; i < tiendas.size(); i++) {
                    PlanCompra.Tienda tienda = tiendas.get(i);
                    if (!tienda.id.equals(item.comercio) && tienda.precios.containsKey(producto.getKey())) {
                        Map<String, Long> sinEste = new HashMap<>(tienda.precios);
                        sinEste.remove(producto.getKey());
                        tiendas.set(i, new PlanCompra.Tienda(tienda.id, sinEste, tienda.viaje));
                    }
                }
            } else {
                sinSuTienda.add(Tiendas.nombre(item.comercio));
            }
        }
        int valorMinuto = traslado.valorMinuto();
        PlanCompra plan = PlanCompra.elegir(productosCompra.keySet(), tiendas, valorMinuto);
        PlanCompra unaTienda = PlanCompra.unaTienda(productosCompra.keySet(), tiendas, valorMinuto);
        planActual = plan;

        // Ahorro en productos: para cada producto que se compra, su precio más caro conocido menos el
        // precio que se paga en el plan (aunque la persona haya elegido la tienda).
        masCaro = new LinkedHashMap<>();
        referenciaProductos = 0;
        for (Map.Entry<PlanCompra.Tienda, List<String>> compra : plan.compras.entrySet()) {
            for (String clave : compra.getValue()) {
                ListaCompra.Item item = productosCompra.get(clave);
                long maximo = (long) item.cantidad * item.producto.precioMax();
                for (PlanCompra.Tienda tienda : tiendas) {
                    Long precio = tienda.precios.get(clave);
                    if (precio != null) {
                        maximo = Math.max(maximo, precio);
                    }
                }
                Long pagado = compra.getKey().precios.get(clave);
                maximo = Math.max(maximo, pagado == null ? 0 : pagado);
                masCaro.put(clave, maximo);
                referenciaProductos += maximo;
            }
        }
        // Traslado de referencia: ir a la cadena cercana más cara que tenga toda la lista.
        referenciaTienda = null;
        referenciaTraslado = null;
        long masCaraTotal = -1;
        for (PlanCompra.Tienda tienda : tiendas) {
            long productos = 0;
            boolean completa = true;
            for (Map.Entry<String, ListaCompra.Item> producto : productosCompra.entrySet()) {
                Long precio = tienda.precios.get(producto.getKey());
                if (precio == null) {
                    Producto.Oferta guardada = producto.getValue().producto.oferta(tienda.id);
                    precio = guardada == null ? null : (long) guardada.precio * producto.getValue().cantidad;
                }
                if (precio == null) {
                    completa = false;
                    break;
                }
                productos += precio;
            }
            long totalTienda = productos + (tienda.viaje.costoClp == null ? 0 : tienda.viaje.costoClp);
            if (completa && totalTienda > masCaraTotal) {
                masCaraTotal = totalTienda;
                referenciaTienda = tienda.id;
                referenciaTraslado = tienda.viaje.costoClp;
            }
        }
        if (referenciaTienda == null) {
            referenciaTraslado = plan.transporte;
        }
        Long total = plan.totalConocido();
        // Ahorro en el supermercado: productos (más caro − lo que se paga) más lo que ya está en la
        // despensa. El gasto en transporte se muestra aparte y no se resta.
        ahorroActual = Math.max(0, referenciaProductos - plan.subtotal) + ahorroDespensa;

        LinearLayout tarjeta = vista.tarjeta(list, Ui.BOSQUE, 0);
        int viajes = plan.compras.size();
        boolean completo = plan.faltantes.isEmpty();
        vista.etiqueta(tarjeta, completo ? "MEJOR PLAN · LISTA COMPLETA" : "MEJOR PLAN · COBERTURA PARCIAL",
                Ui.AMARILLO);
        if (viajes == 0) {
            vista.texto(tarjeta, "Aún sin precios para tu lista en estas tiendas", 18, Ui.BLANCO, Ui.GRUESA);
        } else {
            vista.texto(tarjeta, Ui.pesos(total == null ? plan.subtotal : total), 34, Ui.BLANCO, Ui.GRUESA);
            String nombres = plan.compras.keySet().stream().map(tienda -> Tiendas.nombre(tienda.id))
                    .collect(Collectors.joining(" + "));
            vista.texto(tarjeta, nombres + " · " + viajes + (viajes == 1 ? " tienda · " : " tiendas · ")
                    + plan.minutos + " min de traslado", 14, Ui.TENUE_OSCURO, Ui.NORMAL);

            LinearLayout desglose = vista.fila();
            desglose.setBackground(vista.fondo(Ui.BOSQUE_INTERIOR, 12, 0));
            desglose.setPadding(vista.dp(12), vista.dp(10), vista.dp(12), vista.dp(10));
            vista.agregar(tarjeta, desglose, 12);
            celda(desglose, "Productos", Ui.pesos(plan.subtotal), Ui.BLANCO);
            celda(desglose, traslado.modo.icono + " Traslado",
                    plan.transporte == null ? "—" : Ui.pesos(plan.transporte), Ui.BLANCO);
            celda(desglose, "Ahorras en súper", Ui.pesos(ahorroActual), Ui.VERDE_CLARO);

            if (unaTienda != null && viajes > 1) {
                Long totalUna = unaTienda.totalConocido();
                String comercio = unaTienda.compras.keySet().iterator().next().id;
                vista.texto(tarjeta, "Comprando todo en " + Tiendas.nombre(comercio) + ": "
                        + (totalUna == null ? "—" : Ui.pesos(totalUna)) + " · " + unaTienda.minutos + " min",
                        13, Ui.TENUE_OSCURO, Ui.NORMAL).setPadding(0, vista.dp(10), 0, 0);
            }
        }
        if (!completo) {
            int faltan = plan.faltantes.size();
            vista.texto(tarjeta, faltan == 1 ? "1 producto no aparece en las tiendas cercanas"
                    : faltan + " productos no aparecen en las tiendas cercanas", 13, Ui.AMARILLO, Ui.NORMAL)
                    .setPadding(0, vista.dp(8), 0, 0);
        }
        if (!sinSuTienda.isEmpty()) {
            String cadenas = sinSuTienda.stream().distinct().collect(Collectors.joining(", "));
            int cantidad = sinSuTienda.size();
            vista.texto(tarjeta, "📍 " + cadenas + (cadenas.contains(",") ? " no están" : " no está")
                    + " a 2 km o no tiene" + (cantidad == 1 ? " el producto" : " los productos")
                    + " que elegiste ahí: " + (cantidad == 1 ? "se cotiza" : "se cotizan")
                    + " en las tiendas cercanas.", 13, Ui.AMARILLO, Ui.NORMAL).setPadding(0, vista.dp(8), 0, 0);
        }
        if (!yaEnCasa.isEmpty()) {
            vista.texto(tarjeta, "📦 " + yaEnCasa.size() + (yaEnCasa.size() == 1 ? " producto" : " productos")
                    + " ya en tu despensa: no se compran (" + Ui.pesos(ahorroDespensa) + ")", 13,
                    Ui.TENUE_OSCURO, Ui.NORMAL).setPadding(0, vista.dp(8), 0, 0);
        }
        String prioridad = traslado.priorizarTiempo ? "Priorizas tu tiempo: cada minuto pesa más en el cálculo."
                : "Priorizas el ahorro: aceptas más paradas si conviene.";
        vista.texto(tarjeta, prioridad, 12, Ui.TENUE_OSCURO, Ui.NORMAL).setPadding(0, vista.dp(8), 0, 0);

        LinearLayout botones = vista.fila();
        vista.agregar(tarjeta, botones, 14);
        TextView detalle = vista.botonPequeno("Ver plan por tienda", Ui.BOSQUE_INTERIOR, Ui.BLANCO, () -> {
            dialogoPlan = new PlanCompraDialog(this, plan, sucursales, productosCompra);
            dialogoPlan.setOnDismissListener(dialogo -> dialogoPlan = null);
            dialogoPlan.show();
        });
        vista.flexible(botones, detalle);
        if (viajes > 0) {
            botones.addView(vista.espacio(10));
            TextView confirmar = vista.botonPequeno("✓ Confirmar", Ui.AMARILLO, Ui.BOSQUE, this::confirmarCompra);
            vista.flexible(botones, confirmar);
        }

        if (viajes > 0) {
            String principal = plan.compras.keySet().iterator().next().id;
            new Historial(this).guardarPlan(Historial.firma(new ListaCompra(this).items()),
                    new Historial.Compra(System.currentTimeMillis(), total == null ? plan.subtotal : total,
                            ahorroActual, principal, unidades(), viajes, plan.minutos));
        }
    }

    // Unidades por comprar en total.
    private int unidades() {
        int unidades = 0;
        for (ListaCompra.Item item : productosCompra.values()) {
            unidades += item.cantidad;
        }
        return unidades;
    }

    // Celda de la tabla de desglose.
    private void celda(LinearLayout fila, String etiqueta, String valor, int color) {
        LinearLayout celda = vista.columna();
        vista.flexible(fila, celda);
        vista.texto(celda, etiqueta, 12, Ui.TENUE_OSCURO, Ui.NORMAL);
        vista.texto(celda, valor, 16, color, Ui.NEGRITA);
    }

    // Botón "Confirmar": abre el resumen de la compra.
    private void confirmarCompra() {
        PlanCompra plan = planActual;
        if (plan == null || plan.compras.isEmpty()) {
            return;
        }
        new ConfirmacionDialog(plan).show();
    }

    // Guarda la compra en el historial, mueve los productos a la despensa y vacía la lista.
    private void registrarCompra(PlanCompra plan) {
        Long total = plan.totalConocido();
        Despensa despensa = new Despensa(this);
        ListaCompra lista = new ListaCompra(this);
        for (List<String> claves : plan.compras.values()) {
            for (String clave : claves) {
                ListaCompra.Item item = productosCompra.get(clave);
                despensa.cambiar(item.producto, item.cantidad);
            }
        }
        String principal = plan.compras.keySet().iterator().next().id;
        new Historial(this).registrar(new Historial.Compra(System.currentTimeMillis(),
                total == null ? plan.subtotal : total, ahorroActual, principal, unidades(),
                plan.compras.size(), plan.minutos));
        if (plan.faltantes.isEmpty()) {
            lista.vaciar();
        } else {
            // Quedan en la lista solo los productos que no se encontraron.
            for (ListaCompra.Item item : lista.items()) {
                if (!plan.faltantes.contains(item.producto.clave)) {
                    lista.quitar(item.producto.clave);
                }
            }
        }
        android.widget.Toast.makeText(this, "Compra confirmada. ¡Ahorraste "
                + Ui.pesos(ahorroActual) + "!", android.widget.Toast.LENGTH_LONG).show();
        finish();
    }

    /**
     * Ventana inferior para confirmar la compra. Muestra de dónde sale el ahorro: por cada producto,
     * el precio más caro menos el que se paga; luego la diferencia de traslado y lo que ya estaba en
     * la despensa.
     */
    private final class ConfirmacionDialog extends android.app.Dialog {
        private static final int VERDE_SUAVE = 0xFFE5F4E4;

        ConfirmacionDialog(PlanCompra plan) {
            super(NearbyStoresActivity.this);
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            Long total = plan.totalConocido();

            LinearLayout panel = vista.columna();
            GradientDrawable fondo = new GradientDrawable();
            fondo.setColor(Ui.PAPEL);
            float radio = vista.dp(26);
            fondo.setCornerRadii(new float[]{radio, radio, radio, radio, 0, 0, 0, 0});
            panel.setBackground(fondo);

            android.widget.ScrollView scroll = new android.widget.ScrollView(getContext());
            scroll.setVerticalScrollBarEnabled(false);
            panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout contenido = vista.columna();
            contenido.setPadding(vista.dp(20), vista.dp(20), vista.dp(20), vista.dp(8));
            scroll.addView(contenido);

            // Cabecera: ahorro en el supermercado y gasto en transporte, por separado.
            LinearLayout cabecera = vista.tarjeta(contenido, Ui.BOSQUE, 0);
            cabecera.setPadding(vista.dp(18), vista.dp(18), vista.dp(18), vista.dp(18));
            LinearLayout montos = vista.fila();
            montos.setGravity(android.view.Gravity.TOP);
            cabecera.addView(montos);
            LinearLayout ahorroCol = vista.columna();
            vista.flexible(montos, ahorroCol);
            vista.etiqueta(ahorroCol, "AHORRAS EN EL SUPERMERCADO", Ui.AMARILLO);
            vista.texto(ahorroCol, Ui.pesos(ahorroActual), 30, Ui.BLANCO, Ui.GRUESA).setPadding(0, vista.dp(4), 0, 0);
            LinearLayout transporteCol = vista.columna();
            transporteCol.setPadding(vista.dp(12), 0, 0, 0);
            vista.flexible(montos, transporteCol);
            vista.etiqueta(transporteCol, "GASTAS EN TRANSPORTE", Ui.TENUE_OSCURO);
            String gastoViaje = plan.transporte == null ? "—" : Ui.pesos(plan.transporte);
            vista.texto(transporteCol, gastoViaje, 30, Ui.BLANCO, Ui.GRUESA).setPadding(0, vista.dp(4), 0, 0);
            int tiendas = plan.compras.size();
            vista.texto(cabecera, "Pagas " + Ui.pesos(total == null ? plan.subtotal : total) + " en total · " + tiendas
                    + (tiendas == 1 ? " tienda" : " tiendas")
                    + (plan.minutos > 0 ? " · " + plan.minutos + " min de traslado" : ""),
                    14, Ui.TENUE_OSCURO, Ui.NORMAL).setPadding(0, vista.dp(10), 0, 0);

            // Productos.
            vista.seccion(contenido, "PRODUCTOS · MÁS CARO VS LO QUE PAGAS");
            LinearLayout productos = vista.tarjeta(contenido, Ui.BLANCO, 8);
            productos.setPadding(vista.dp(14), vista.dp(8), vista.dp(14), vista.dp(12));
            boolean primero = true;
            for (Map.Entry<PlanCompra.Tienda, List<String>> compra : plan.compras.entrySet()) {
                for (String clave : compra.getValue()) {
                    Long pagado = compra.getKey().precios.get(clave);
                    Long maximo = masCaro.get(clave);
                    ListaCompra.Item item = productosCompra.get(clave);
                    if (pagado == null || maximo == null || item == null) {
                        continue;
                    }
                    if (!primero) {
                        productos.addView(vista.separador());
                    }
                    primero = false;
                    filaProducto(productos, compra.getKey().id, item, maximo, pagado);
                }
            }
            productos.addView(vista.separador());
            filaTotal(productos, "Ahorro en productos", referenciaProductos - plan.subtotal);

            // Traslado y despensa.
            vista.seccion(contenido, "TRANSPORTE Y DESPENSA");
            LinearLayout extras = vista.tarjeta(contenido, Ui.BLANCO, 8);
            extras.setPadding(vista.dp(14), vista.dp(12), vista.dp(14), vista.dp(12));
            int tiendasViaje = plan.compras.size();
            String destino = "Ida y vuelta a " + tiendasViaje + (tiendasViaje == 1 ? " tienda" : " tiendas");
            if (plan.transporte == null) {
                filaDetalle(extras, traslado.modo.icono + "  " + traslado.modo.nombre,
                        destino + " · sin costo estimado", null);
            } else {
                String detalle = switch (traslado.modo) {
                    case PIE -> destino + " · caminando no cuesta";
                    case AUTO -> destino + " · bencina a $" + traslado.costoPorKm() + "/km";
                    case PUBLICO -> destino + " · " + traslado.medio.nombre.toLowerCase() + " a $"
                            + traslado.pasaje() + " por tramo";
                };
                filaDetalle(extras, traslado.modo.icono + "  " + traslado.modo.nombre, detalle, null);
                // El gasto se muestra como monto, no como ahorro ni descuento.
                TextView gasto = vista.texto(null, Ui.pesos(plan.transporte), 15, Ui.TEXTO, Ui.GRUESA);
                ((LinearLayout) extras.getChildAt(extras.getChildCount() - 1)).addView(gasto);
            }
            if (ahorroDespensa > 0) {
                extras.addView(vista.separador());
                filaDetalle(extras, "📦  Ya en tu despensa", "Productos que no vuelves a comprar", ahorroDespensa);
            }

            vista.texto(contenido, "Al confirmar, la compra se guarda en tu historial, los productos pasan a tu "
                    + "despensa y la lista queda vacía.", 12, Ui.TINTA, Ui.NORMAL)
                    .setPadding(vista.dp(2), vista.dp(14), vista.dp(2), 0);

            // Botones.
            LinearLayout botones = vista.fila();
            botones.setPadding(vista.dp(20), vista.dp(10), vista.dp(20), vista.dp(18));
            panel.addView(botones);
            TextView cancelar = vista.botonPequeno("Cancelar", Ui.GRIS, Ui.TEXTO, this::dismiss);
            cancelar.setMinHeight(vista.dp(52));
            vista.flexible(botones, cancelar);
            botones.addView(vista.espacio(12));
            TextView confirmar = vista.boton("✓ Confirmar compra", Ui.AMARILLO, Ui.BOSQUE, () -> {
                dismiss();
                registrarCompra(plan);
            });
            botones.addView(confirmar, new LinearLayout.LayoutParams(0, -2, 2));

            setContentView(panel);
            setCanceledOnTouchOutside(true);
        }

        // Muestra el resumen con ahorro y transporte por separado.
        @Override
        public void show() {
            super.show();
            android.view.Window ventana = getWindow();
            if (ventana == null) {
                return;
            }
            ventana.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            ventana.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            ventana.setDimAmount(0.45f);
            ventana.setGravity(android.view.Gravity.BOTTOM);
            int alto = getContext().getResources().getDisplayMetrics().heightPixels;
            ventana.setLayout(-1, Math.round(alto * 0.86f));
        }

        // Fila de un producto en el resumen, con lo que se ahorra frente a la tienda más cara.
        private void filaProducto(LinearLayout padre, String comercio, ListaCompra.Item item, long maximo, long pagado) {
            LinearLayout fila = vista.fila();
            padre.addView(fila);
            fila.addView(vista.logo(comercio, 48, 34));
            LinearLayout datos = vista.columna();
            datos.setPadding(vista.dp(12), 0, vista.dp(8), 0);
            vista.flexible(fila, datos);
            TextView nombre = vista.texto(datos, item.producto.nombre + (item.cantidad > 1 ? " ×" + item.cantidad : ""),
                    14, Ui.TEXTO, Ui.NEGRITA);
            nombre.setMaxLines(2);
            LinearLayout precios = vista.fila();
            datos.addView(precios);
            if (maximo > pagado) {
                TextView antes = vista.texto(precios, Ui.pesos(maximo), 13, Ui.TINTA, Ui.NORMAL);
                Ui.tachar(antes);
                vista.texto(precios, "  →  ", 13, Ui.TINTA, Ui.NORMAL);
            }
            vista.texto(precios, Ui.pesos(pagado), 13, Ui.VERDE, Ui.NEGRITA);
            vista.texto(precios, "  en " + Tiendas.nombre(comercio), 13, Ui.TINTA, Ui.NORMAL);
            fila.addView(chip(maximo - pagado));
        }

        // Fila de detalle con monto.
        private void filaDetalle(LinearLayout padre, String titulo, String detalle, Long monto) {
            LinearLayout fila = vista.fila();
            padre.addView(fila);
            LinearLayout datos = vista.columna();
            vista.flexible(fila, datos);
            vista.texto(datos, titulo, 15, Ui.TEXTO, Ui.NEGRITA);
            vista.texto(datos, detalle, 12, Ui.TINTA, Ui.NORMAL);
            if (monto != null) {
                fila.addView(chip(monto));
            }
        }

        // Fila de total.
        private void filaTotal(LinearLayout padre, String titulo, long monto) {
            LinearLayout fila = vista.fila();
            padre.addView(fila);
            TextView etiqueta = vista.texto(null, titulo, 15, Ui.TEXTO, Ui.GRUESA);
            vista.flexible(fila, etiqueta);
            vista.texto(fila, Ui.pesos(monto), 18, monto >= 0 ? Ui.VERDE : Ui.NARANJA, Ui.GRUESA);
        }

        /** Insignia con el monto: verde si ahorras, naranja si pagas más, gris si es igual. */
        private TextView chip(long monto) {
            String texto = monto > 0 ? "−" + Ui.pesos(monto) : monto < 0 ? "+" + Ui.pesos(-monto) : "Igual";
            int color = monto > 0 ? Ui.VERDE : monto < 0 ? Ui.NARANJA : Ui.TINTA;
            int fondo = monto > 0 ? VERDE_SUAVE : monto < 0 ? Ui.AMBAR_FONDO : Ui.GRIS;
            TextView chip = vista.texto(null, texto, 13, color, Ui.NEGRITA);
            chip.setBackground(vista.fondo(fondo, 8, 0));
            chip.setPadding(vista.dp(8), vista.dp(4), vista.dp(8), vista.dp(4));
            return chip;
        }
    }

    // Fila de un supermercado cercano: distancia, viaje y cobertura de la lista.
    private void addResult(OpcionTraslado opcion, int index) {
        Result result = opcion.resultado;
        LinearLayout row = vista.tarjeta(list, Ui.BLANCO, 10);
        LinearLayout cabecera = vista.fila();
        row.addView(cabecera);
        cabecera.addView(vista.logo(result.store.comercio, 58, 42));
        LinearLayout nombres = vista.columna();
        nombres.setPadding(vista.dp(12), 0, 0, 0);
        vista.flexible(cabecera, nombres);
        vista.texto(nombres, (index + 1) + ". " + result.store.name, 16, Ui.TEXTO, Ui.NEGRITA);
        String address = result.store.address.isEmpty() ? "" : result.store.address + " · ";
        vista.texto(nombres, address + formatDistance(result.meters) + " en línea recta", 12, Ui.TINTA, Ui.NORMAL);
        vista.tocable(row, vista.fondo(Ui.BLANCO, 20, Ui.BORDE), () -> {
            if (mapReady) {
                map.evaluateJavascript("selectNearbyStore(" + index + ")", null);
            }
        });

        Traslado.Estimacion viaje = opcion.viaje;
        String costo = viaje.costoClp == null ? "costo pendiente" : viaje.costoClp == 0 ? "$0" : Ui.pesos(viaje.costoClp);
        String detalle = switch (traslado.modo) {
            case PIE -> "🚶 Ida y vuelta " + String.format(CHILE, "%.1f km", viaje.kilometros) + " · " + viaje.minutos
                    + " min · sin costo";
            case AUTO -> "🚗 Ida y vuelta " + String.format(CHILE, "%.1f km", viaje.kilometros) + " · " + viaje.minutos
                    + " min · bencina " + costo + String.format(CHILE, " (%.2f L)", viaje.litros);
            case PUBLICO -> traslado.medio.icono + " " + viaje.minutos + " min aprox. · 2 pasajes "
                    + traslado.tarifa.nombre.toLowerCase() + " · " + costo;
        };
        vista.texto(row, detalle, 13, Ui.TEXTO, Ui.NORMAL).setPadding(0, vista.dp(10), 0, 0);
        if (traslado.modo != Traslado.Modo.PUBLICO) {
            vista.texto(row, viaje.porCalles ? "Distancia por calles, sin tráfico en vivo" : "Distancia aproximada",
                    11, Ui.TINTA, Ui.NORMAL);
        }
        if (preciosWeb != null && !productosCompra.isEmpty()) {
            mostrarCobertura(row, opcion);
        }
        TextView ruta = vista.texto(row, "Ver ruta en el mapa ›", 13, Ui.VERDE, Ui.NEGRITA);
        ruta.setPadding(0, vista.dp(8), 0, vista.dp(2));
        vista.tocable(ruta, vista.fondo(Ui.BLANCO, 8, 0), () -> abrirRuta(result.store));
    }

    // Indica cuántos productos de la lista tiene esa cadena.
    private void mostrarCobertura(LinearLayout row, OpcionTraslado opcion) {
        int total = productosCompra.size();
        int encontrados = opcion.precios.size();
        LinearLayout fila = vista.fila();
        fila.setPadding(0, vista.dp(8), 0, 0);
        row.addView(fila);
        TextView cobertura = vista.texto(null, encontrados == total ? "✓ Tiene toda tu lista"
                : encontrados + " de " + total + " productos", 13, encontrados == total ? Ui.VERDE : Ui.NARANJA, Ui.NEGRITA);
        vista.flexible(fila, cobertura);
        if (opcion.compra != null) {
            vista.texto(fila, Ui.pesos(opcion.total == null ? opcion.compra : opcion.total), 17, Ui.TEXTO, Ui.GRUESA);
        }
        TextView ver = vista.texto(row, "Ver precios de esta tienda ›", 13, Ui.TINTA, Ui.NEGRITA);
        ver.setPadding(0, vista.dp(6), 0, 0);
        vista.tocable(ver, vista.fondo(Ui.BLANCO, 8, 0), () -> {
            StringBuilder cotizados = new StringBuilder();
            StringBuilder pendientes = new StringBuilder();
            for (Map.Entry<String, ListaCompra.Item> producto : productosCompra.entrySet()) {
                ListaCompra.Item item = producto.getValue();
                Long precio = opcion.precios.get(producto.getKey());
                String linea = item.cantidad + " × " + item.producto.nombre;
                if (precio == null) {
                    pendientes.append(linea).append('\n');
                } else {
                    cotizados.append(linea).append(" · ").append(Ui.pesos(precio)).append('\n');
                }
            }
            String mensaje = (cotizados.length() > 0 ? "Con precio\n" + cotizados + "\n" : "")
                    + (pendientes.length() > 0 ? "No aparece en su web\n" + pendientes + "\n" : "")
                    + "Precios web de la cadena. El stock de la sucursal puede variar.";
            new AlertDialog.Builder(this).setTitle(opcion.resultado.store.name).setMessage(mensaje)
                    .setPositiveButton("Cerrar", null).show();
        });
    }

    // Abre Google Maps con la ruta a la tienda (solo si el usuario lo pide).
    private void abrirRuta(Store tienda) {
        Uri mapa = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
                .appendQueryParameter("api", "1")
                .appendQueryParameter("origin", location.getLatitude() + "," + location.getLongitude())
                .appendQueryParameter("destination", tienda.latitude + "," + tienda.longitude)
                .appendQueryParameter("travelmode", traslado.modo.modoMapa).build();
        AjustesTraslado.abrir(this, mapa);
    }

    // Distancia legible: 850 m o 1,20 km.
    private String formatDistance(float meters) {
        return meters < 1000 ? String.format(CHILE, "%.0f m", meters)
                : String.format(CHILE, "%.2f km", meters / 1000);
    }

    // Configura el mapa (WebView): sin acceso a archivos y solo dominios permitidos.
    private void configureMap() {
        map.setBackgroundColor(getColor(R.color.mint));
        map.getSettings().setJavaScriptEnabled(true);
        map.getSettings().setAllowFileAccess(false);
        map.getSettings().setAllowContentAccess(false);
        map.getSettings().setUserAgentString(NearbyStores.USER_AGENT);
        map.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if ("https".equals(url.getScheme()) && "tile.openstreetmap.org".equals(url.getHost())) {
                    return null;
                }
                if ("https".equals(url.getScheme()) && ASSET_HOST.equals(url.getHost())) {
                    String path = url.getPath();
                    String mimeType = null;
                    if ("/nearby/index.html".equals(path)) {
                        mimeType = "text/html";
                    } else if ("/nearby/map.css".equals(path) || "/nearby/leaflet.css".equals(path)) {
                        mimeType = "text/css";
                    } else if ("/nearby/map.js".equals(path) || "/nearby/leaflet.js".equals(path)) {
                        mimeType = "text/javascript";
                    }
                    if (mimeType != null) {
                        try {
                            return new WebResourceResponse(mimeType, "UTF-8", getAssets().open(path.substring(1)));
                        } catch (IOException ignored) {
                            // No se carga un sustituto remoto si falta un recurso de la aplicación.
                        }
                    }
                }
                return new WebResourceResponse("text/plain", "UTF-8", null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request.hasGesture() && ATTRIBUTION.equals(request.getUrl().toString())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                    } catch (ActivityNotFoundException ignored) {}
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                mapReady = MAP_URL.equals(url);
                updateMap(true);
            }
        });
        map.loadUrl(MAP_URL);
    }

    // Envía la ubicación y los supermercados al mapa como JSON.
    private void updateMap(boolean recenter) {
        if (!mapReady || location == null) {
            return;
        }
        JSONArray points = new JSONArray();
        try {
            for (Result result : results) {
                Store store = result.store;
                points.put(new JSONObject().put("id", store.id).put("name", store.name)
                        .put("lat", store.latitude).put("lon", store.longitude)
                        .put("distance", formatDistance(result.meters))
                        .put("comercio", store.comercio));
            }
        } catch (JSONException invalidData) {
            return;
        }
        map.evaluateJavascript("updateNearbyMap(" + location.getLatitude() + ","
                + location.getLongitude() + "," + NearbyStores.RADIUS_METERS + ","
                + points + "," + recenter + ")", null);
    }

    // Convierte dp a píxeles.
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
