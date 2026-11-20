package com.abarrato.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Cliente HTTP de la API de Abarrato (/api/v1/precios). Todas las llamadas van por HTTPS
// y los errores del servidor se convierten en ErrorApi con su mensaje en español.
final class ApiPrecios {
    private ApiPrecios() {}

    // true si el APK se compiló con la URL del servidor.
    static boolean configurada() {
        return !BuildConfig.PRECIOS_API_URL.isEmpty();
    }

    /** Resultado de una búsqueda en vivo, limitado a las cadenas de Abarrato. */
    static final class Busqueda {
        final List<Producto> productos;
        /** Estado de cada cadena: RECIENTE, ULTIMA_CAPTURA o NO_DISPONIBLE. */
        final Map<String, String> fuentes;
        final long consultadaEn;
        /** Páginas del buscador de cada tienda incluidas y si el servidor puede traer otra. */
        final int paginas;
        final boolean hayMas;
        /** true = respuesta rápida con precios guardados; luego llega la búsqueda en vivo. */
        final boolean guardada;

        Busqueda(List<Producto> productos, Map<String, String> fuentes, int paginas, boolean hayMas) {
            this(productos, fuentes, paginas, hayMas, false);
        }

        Busqueda(List<Producto> productos, Map<String, String> fuentes, int paginas, boolean hayMas,
                boolean guardada) {
            this.productos = productos;
            this.fuentes = fuentes;
            this.consultadaEn = System.currentTimeMillis();
            this.paginas = paginas;
            this.hayMas = hayMas;
            this.guardada = guardada;
        }
    }

    // Búsqueda en vivo en una tienda o en todas.
    static JSONObject buscar(String consulta, String comercio) throws IOException, JSONException {
        String filtro = comercio.isEmpty() ? "" : "&comercio=" + URLEncoder.encode(comercio, "UTF-8");
        return solicitar("/en-vivo?q=" + URLEncoder.encode(consulta, "UTF-8") + filtro, null);
    }

    /**
     * Pide al backend que consulte en este momento los catálogos de las cadenas de
     * {@link Tiendas#IDS}. El backend hace el scraping y reutiliza capturas de menos de dos minutos.
     */
    static Busqueda buscarProductos(String consulta) throws IOException, JSONException {
        return buscarProductos(consulta, 1);
    }

    /** {@code paginas}: cuántas páginas del buscador de cada tienda incluir (1 = la más rápida). */
    static Busqueda buscarProductos(String consulta, int paginas) throws IOException, JSONException {
        return buscarProductos(consulta, paginas, false);
    }

    /**
     * Precios guardados en el servidor (de los últimos 7 días), sin esperar a las tiendas. Responde en
     * alrededor de un segundo y se muestra mientras llega la búsqueda en vivo.
     */
    static Busqueda buscarGuardados(String consulta) throws IOException, JSONException {
        return buscarProductos(consulta, 1, true);
    }

    // Búsqueda que la app muestra: en vivo o, con guardado=true, desde la base de datos.
    private static Busqueda buscarProductos(String consulta, int paginas, boolean guardado)
            throws IOException, JSONException {
        JSONObject respuesta = solicitar("/en-vivo?q=" + URLEncoder.encode(consulta, "UTF-8")
                + "&comercios=" + String.join(",", Tiendas.IDS) + "&pagina=" + paginas
                + (guardado ? "&guardado=true" : ""), null);
        List<Producto> productos = new ArrayList<>();
        JSONArray grupos = respuesta.optJSONArray("productos");
        for (int i = 0; grupos != null && i < grupos.length(); i++) {
            JSONObject grupo = grupos.optJSONObject(i);
            Producto producto = grupo == null ? null : Producto.desdeApi(grupo, consulta);
            if (producto != null) {
                productos.add(producto);
            }
        }
        // Primero los que se pueden comparar en más cadenas; luego se alternan las cadenas para que
        // Líder y Del Pedregal no queden al final detrás de Jumbo y Santa Isabel.
        productos = ordenar(productos);
        Map<String, String> fuentes = new LinkedHashMap<>();
        JSONArray estados = respuesta.optJSONArray("fuentes");
        for (int i = 0; estados != null && i < estados.length(); i++) {
            JSONObject fuente = estados.optJSONObject(i);
            if (fuente != null && Tiendas.incluida(fuente.optString("comercio"))) {
                fuentes.put(fuente.optString("comercio"), fuente.optString("estado"));
            }
        }
        return new Busqueda(productos, fuentes, respuesta.optInt("paginas", paginas),
                !guardado && respuesta.optBoolean("hayMas", false), guardado);
    }

    // Ordena los productos: primero los que están en más tiendas, agrupados por la cadena más barata.
    static List<Producto> ordenar(List<Producto> productos) {
        List<Producto> ordenados = new ArrayList<>();
        for (int tiendas = Tiendas.IDS.size(); tiendas >= 1; tiendas--) {
            Map<String, List<Producto>> porCadena = new LinkedHashMap<>();
            for (Producto producto : productos) {
                if (producto.ofertas.size() == tiendas) {
                    porCadena.computeIfAbsent(producto.mejor().comercio, c -> new ArrayList<>()).add(producto);
                }
            }
            boolean quedan = true;
            for (int i = 0; quedan; i++) {
                quedan = false;
                for (List<Producto> grupo : porCadena.values()) {
                    if (i < grupo.size()) {
                        ordenados.add(grupo.get(i));
                        quedan = true;
                    }
                }
            }
        }
        return ordenados;
    }

    /**
     * Despierta el servidor (Render duerme tras 15 min sin uso) apenas se abre la app, en segundo
     * plano: mientras el usuario mira el inicio, el servidor ya se está encendiendo.
     */
    static void despertar() {
        if (!configurada()) {
            return;
        }
        Thread hilo = new Thread(() -> {
            try {
                tiendas();
            } catch (Exception ignorado) {
                // Solo es un aviso para encender el servidor; la búsqueda real reintentará.
            }
        }, "abarrato-despertar");
        hilo.setDaemon(true);
        hilo.start();
    }

    // GET /tiendas: las 8 cadenas.
    static JSONObject tiendas() throws IOException, JSONException {
        return solicitar("/tiendas", null);
    }

    // GET /categorias: rubros para filtrar.
    static JSONObject categorias() throws IOException, JSONException {
        return solicitar("/categorias", null);
    }

    // GET /categorias/{rubro}: productos de un rubro.
    static JSONObject categoria(String id, String comercio) throws IOException, JSONException {
        String filtro = comercio.isEmpty() ? "" : "?comercio=" + URLEncoder.encode(comercio, "UTF-8");
        return solicitar("/categorias/" + URLEncoder.encode(id, "UTF-8") + filtro, null);
    }

    // GET /tiendas/{comercio}: pasillos de una tienda.
    static JSONObject pasillos(String comercio) throws IOException, JSONException {
        return solicitar("/tiendas/" + comercio, null);
    }

    // GET /tiendas/{comercio}/productos: una página de un pasillo.
    static JSONObject catalogo(String comercio, String pasillo, int pagina) throws IOException, JSONException {
        return solicitar("/tiendas/" + comercio + "/productos?pasillo=" + URLEncoder.encode(pasillo, "UTF-8")
                + "&pagina=" + pagina, null);
    }

    // POST /comparar-carrito: total del carrito en cada tienda.
    static JSONObject cotizar(JSONObject carrito) throws IOException, JSONException {
        return solicitar("/comparar-carrito", carrito);
    }

    // GET /fotos: foto de cada producto.
    static JSONObject fotos(List<String> claves) throws IOException, JSONException {
        StringBuilder ruta = new StringBuilder("/fotos?");
        for (String clave : claves) {
            if (ruta.charAt(ruta.length() - 1) != '?') {
                ruta.append('&');
            }
            ruta.append("clave=").append(URLEncoder.encode(clave, "UTF-8"));
        }
        return solicitar(ruta.toString(), null);
    }

    // Hace la petición HTTP y devuelve el JSON; si el servidor responde error, lanza ErrorApi.
    private static JSONObject solicitar(String ruta, JSONObject contenido) throws IOException, JSONException {
        String base = BuildConfig.PRECIOS_API_URL.replaceFirst("/+$", "");
        URL url = new URL(base + "/api/v1/precios" + ruta);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(120_000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (contenido != null) {
                byte[] datos = contenido.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(datos.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(datos);
                }
            }
            int estado = connection.getResponseCode();
            if (estado != HttpURLConnection.HTTP_OK) {
                // El servidor responde los errores con un JSON {codigo, mensaje}: se lee para mostrarlo.
                InputStream error = connection.getErrorStream();
                throw ErrorApi.desde(estado, error == null ? "" : leer(error, 64_000));
            }
            return new JSONObject(leer(connection.getInputStream(), MAX_RESPUESTA));
        } finally {
            connection.disconnect();
        }
    }

    /** Tamaño máximo aceptado de una respuesta, para no agotar la memoria del teléfono. */
    private static final int MAX_RESPUESTA = 4_000_000;

    // Lee la respuesta con un tamaño máximo, para no agotar la memoria.
    private static String leer(InputStream origen, int maximo) throws IOException {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(origen, StandardCharsets.UTF_8))) {
            StringBuilder texto = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (texto.length() + count > maximo) {
                    throw new IOException("Respuesta de precios demasiado grande");
                }
                texto.append(buffer, 0, count);
            }
            return texto.toString();
        }
    }

}
