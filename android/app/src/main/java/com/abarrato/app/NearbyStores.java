package com.abarrato.app;

import android.content.Context;
import android.location.Location;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Busca supermercados cercanos en OpenStreetMap (Overpass) y los ordena por distancia.
// Si Overpass no responde, usa una copia local (stores-cl.json).
final class NearbyStores {
    static final int RADIUS_METERS = 2000;
    static final int REFRESH_DISTANCE_METERS = 150;
    static final String USER_AGENT = "Abarrato/0.2 (Android; com.abarrato.app)";

    private static final String[] OVERPASS = {
            "https://overpass.openstreetmap.fr/api/interpreter",
            "https://overpass-api.de/api/interpreter"
    };
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    // Un supermercado: nombre, dirección, coordenadas y cadena.
    static final class Store {
        final String id;
        final String name;
        final String address;
        final String source;
        final String comercio;
        final double latitude;
        final double longitude;

        Store(String id, String name, String address, String source,
                double latitude, double longitude) {
            this(id, name, address, source, latitude, longitude, "");
        }

        Store(String id, String name, String address, String source,
                double latitude, double longitude, String brand) {
            this.id = id;
            this.name = name;
            this.address = address;
            this.source = source;
            this.latitude = latitude;
            this.longitude = longitude;
            String marca = comercioDe(brand);
            this.comercio = marca.isEmpty() ? comercioDe(name) : marca;
        }
    }

    // Un supermercado con su distancia al usuario.
    static final class Result {
        final Store store;
        final float meters;

        Result(Store store, float meters) {
            this.store = store;
            this.meters = meters;
        }
    }

    private NearbyStores() {}

    // Lee la lista local de supermercados incluida en la app.
    static List<Store> loadDirectory(Context context, String asset) throws IOException, JSONException {
        try (InputStream input = context.getAssets().open("nearby/" + asset)) {
            JSONArray entries = new JSONArray(readText(input));
            List<Store> stores = new ArrayList<>(entries.length());
            for (int index = 0; index < entries.length(); index++) {
                JSONObject entry = entries.getJSONObject(index);
                stores.add(new Store(entry.optString("id", "directory-" + index), entry.getString("name"),
                        entry.getString("address"), entry.getString("source"),
                        entry.getDouble("lat"), entry.getDouble("lon")));
            }
            return stores;
        }
    }

    /**
     * Privacidad (OWASP Mobile M6:2024, ISO 27002 8.11): a Overpass, un servicio de terceros, se le
     * envía la ubicación redondeada a 3 decimales (unos 100 m), no la posición exacta del GPS.
     * Para buscar supermercados en 2 km no cambia el resultado: el radio se agranda en
     * {@link #MARGEN_REDONDEO_METROS} y el filtro final por distancia se hace aquí, en el teléfono,
     * con la ubicación exacta (ver {@link #rank}).
     */
    static final int MARGEN_REDONDEO_METROS = 80;

    static double redondear(double grados) {
        return Math.round(grados * 1000.0) / 1000.0;
    }

    static List<Store> search(Location center) throws IOException, JSONException {
        String around = String.format(Locale.US, "(around:%d,%.3f,%.3f)",
                RADIUS_METERS + REFRESH_DISTANCE_METERS + 20 + MARGEN_REDONDEO_METROS,
                redondear(center.getLatitude()), redondear(center.getLongitude()));
        String stores = "[\"shop\"~\"^(supermarket|discount|wholesale|frozen_food)$\"]";
        String specialtyNames = "^(hiper |super |supermercados? |tiendas? |alimentos (del )?|del |express de )?"
                + "(pedregal|jumbo|santa isabel|l[ií]der|unimarc|tottus|alvi|cugat)( |$)";
        String query = "[out:json][timeout:12];("
                + "nwr" + stores + around + ";"
                + "nwr[\"shop\"][~\"^(name|brand)$\"~\"" + specialtyNames + "\",i]" + around + ";"
                + ");out center;";

        String encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20");
        IOException lastError = null;
        for (String endpoint : OVERPASS) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Nearby store request interrupted");
            }
            try {
                return request(endpoint, encoded);
            } catch (IOException error) {
                lastError = error;
                Log.w("Abarrato", "Overpass " + new URL(endpoint).getHost() + ": "
                        + error.getClass().getSimpleName());
            }
        }
        throw new IOException("Nearby store service unavailable", lastError);
    }

    /**
     * La consulta (que lleva las coordenadas) viaja en el cuerpo de un POST por HTTPS y no en la URL:
     * los servidores y proxys suelen guardar las URL en sus registros, pero no los cuerpos.
     */
    private static List<Store> request(String endpoint, String query) throws IOException, JSONException {
        URL url = new URL(endpoint);
        byte[] cuerpo = ("data=" + query).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(23000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(cuerpo.length);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        try {
            try (java.io.OutputStream salida = connection.getOutputStream()) {
                salida.write(cuerpo);
            }
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                Log.w("Abarrato", "Overpass " + url.getHost() + " devolvió HTTP " + status);
                throw new IOException("Overpass HTTP " + status);
            }
            try (InputStream input = connection.getInputStream()) {
                return parseOverpass(new JSONObject(readText(input)));
            }
        } finally {
            connection.disconnect();
        }
    }

    // Convierte la respuesta de Overpass en supermercados, descartando cerrados o sin nombre.
    private static List<Store> parseOverpass(JSONObject response) throws JSONException, IOException {
        if (!response.optString("remark").isEmpty()) {
            throw new IOException("Overpass returned an incomplete response");
        }

        JSONArray elements = response.getJSONArray("elements");
        List<Store> stores = new ArrayList<>();
        for (int index = 0; index < elements.length(); index++) {
            JSONObject element = elements.getJSONObject(index);
            JSONObject tags = element.optJSONObject("tags");
            if (tags == null || "yes".equals(tags.optString("disused"))
                    || "yes".equals(tags.optString("abandoned"))
                    || "vacant".equals(tags.optString("shop"))) {
                continue;
            }
            JSONObject point = element.optJSONObject("center");
            if (point == null) {
                point = element;
            }
            double latitude = point.optDouble("lat");
            double longitude = point.optDouble("lon");
            if (!(latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180)) {
                continue;
            }
            String name = tags.optString("name", tags.optString("brand")).trim();
            if (name.isEmpty()) {
                if (!"supermarket".equals(tags.optString("shop"))) {
                    continue;
                }
                name = "Supermercado";
            }
            String id = element.getString("type") + "-" + element.getLong("id");
            if (esMass(id, name) || esMass(id, tags.optString("brand"))) {
                continue;
            }
            String address = (tags.optString("addr:street") + " "
                    + tags.optString("addr:housenumber")).trim();
            stores.add(new Store(id, name, address, "osm", latitude, longitude, tags.optString("brand")));
        }
        return stores;
    }

    // Junta las dos fuentes, deja solo los que están a 2 km y ordena por distancia.
    static List<Result> rank(Location location, List<Store> directory, List<Store> mapped) {
        List<Result> results = new ArrayList<>();
        Set<String> verifiedIds = new HashSet<>();
        for (Store store : directory) {
            verifiedIds.add(store.id);
        }
        List<Store> otherStores = new ArrayList<>();
        for (Store store : mapped) {
            if (!verifiedIds.contains(store.id)) {
                otherStores.add(store);
            }
        }
        addWithinRadius(results, location, otherStores);
        addWithinRadius(results, location, directory);
        results.sort(Comparator.comparingDouble((Result result) -> result.meters)
                .thenComparing(result -> result.store.name));
        return results;
    }

    // Agrega los supermercados dentro del radio que no estén repetidos.
    private static void addWithinRadius(List<Result> results, Location location, List<Store> stores) {
        float[] distance = new float[1];
        for (Store candidate : stores) {
            if (Math.abs(location.getLatitude() - candidate.latitude) > 0.02) {
                continue;
            }
            Location.distanceBetween(location.getLatitude(), location.getLongitude(),
                    candidate.latitude, candidate.longitude, distance);
            float meters = distance[0];
            if (meters <= RADIUS_METERS && !containsBranch(results, candidate)) {
                results.add(new Result(candidate, meters));
            }
        }
    }

    // true si la misma sucursal ya está en la lista (mismo nombre y muy cerca).
    private static boolean containsBranch(List<Result> results, Store candidate) {
        String name = matchingName(candidate.name);
        String address = matchingAddress(candidate.address);
        float[] distance = new float[1];
        for (Result result : results) {
            Store existing = result.store;
            if (name.equals(matchingName(existing.name))) {
                Location.distanceBetween(candidate.latitude, candidate.longitude,
                        existing.latitude, existing.longitude, distance);
                if (distance[0] <= 65 || (distance[0] <= 600 && !address.isEmpty()
                        && address.equals(matchingAddress(existing.address)))) {
                    return true;
                }
            }
        }
        return false;
    }

    // Nombre simplificado para comparar sucursales.
    private static String matchingName(String name) {
        String normalized = normalize(name)
                .replaceFirst("^supermercados? ", "").trim();
        if (normalized.matches("^(alimentos (del )?|del )?pedregal([^a-z].*)?$")) {
            return "pedregal";
        }
        return normalized;
    }

    // Descarta locales con el nombre antiguo "Mass".
    private static boolean esMass(String id, String nombre) {
        // Estas tres sucursales aún pueden aparecer con el nombre anterior en el mapa.
        return "node-6484524874".equals(id) || "node-9472633786".equals(id) || "way-158794288".equals(id)
                || normalize(nombre).matches("^(supermercados? |tiendas? )?mass([^a-z].*)?$");
    }

    // Dirección simplificada para comparar sucursales.
    private static String matchingAddress(String address) {
        return normalize(address.split(",", 2)[0]).replaceFirst("^av(enida)?\\.?\\s+", "")
                .replaceAll("[^a-z0-9]", "");
    }

    // Identifica la cadena (JUMBO, LIDER…) a partir del nombre del local.
    static String comercioDe(String nombre) {
        String marca = normalize(nombre)
                .replaceFirst("^(?:(?:hipermercados?|hiper|supermercados?|super|express de|express|alimentos|tiendas?) )+", "");
        for (String comercio : new String[]{"JUMBO", "SANTA_ISABEL", "LIDER", "UNIMARC", "TOTTUS", "ALVI", "CUGAT"}) {
            if (marca.matches("^" + comercio.toLowerCase(Locale.ROOT).replace('_', ' ') + "\\b.*")) {
                return comercio;
            }
        }
        return marca.matches("^(?:del )?pedregal\\b.*") ? "DEL_PEDREGAL" : "";
    }

    // Texto sin tildes y en minúsculas.
    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    // Lee la respuesta con un tamaño máximo de 2 MB.
    private static String readText(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (bytes.size() + read > MAX_BYTES) {
                throw new IOException("Nearby store response is too large");
            }
            bytes.write(buffer, 0, read);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
