package cl.abarrato.precios;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Calcula cuánto cuesta el carrito completo en cada tienda o sucursal, y la compra elegida por el usuario.
@Service
final class ComparadorCarrito {
    static final String WEB = "ONLINE_SIN_UBICACION";

    // Formatos de la petición y la respuesta de /comparar-carrito (viajan como JSON).
    record Item(String clave, JsonNode cantidad, String busqueda, Comercio comercio, String productoId,
                String pasillo, Integer pagina) {
        Item(String clave, JsonNode cantidad) {
            this(clave, cantidad, null, null, null, null, null);
        }
    }
    record Sucursal(Comercio comercio, String ubicacion) {}
    record Solicitud(List<Item> productos, List<Sucursal> sucursales) {}
    record Linea(String clave, int cantidad, String nombre, Integer precioUnitarioClp,
                 Long subtotalClp, String estado, String urlFuente, OffsetDateTime observadoEn) {}
    record Opcion(Comercio comercio, String ubicacion, int productosConPrecio,
                  Long totalClp, Long ahorroClp, List<Linea> productos) {}
    record CompraElegida(Long totalClp, long lineasSinTienda, List<Opcion> tiendas) {}
    record Resultado(String alcance, OffsetDateTime consultadoEn, String vigenciaMaxima,
                     List<Opcion> opciones, List<CatalogoEnVivo.Fuente> fuentes, CompraElegida compraElegida) {}
    private record ProductoElegido(String clave, String productoId) {}

    private final CatalogoEnVivo catalogo;
    private final RepositorioPrecios repositorio;
    private final Duration vigenciaWeb;
    private final Duration vigenciaSucursal;

    ComparadorCarrito(CatalogoEnVivo catalogo, RepositorioPrecios repositorio,
            @Value("${abarrato.comparacion.vigencia-web}") String vigenciaWeb,
            @Value("${abarrato.comparacion.vigencia-sucursal}") String vigenciaSucursal) {
        this.catalogo = catalogo;
        this.repositorio = repositorio;
        this.vigenciaWeb = duracion(vigenciaWeb);
        this.vigenciaSucursal = duracion(vigenciaSucursal);
    }

    // Punto de entrada: valida el carrito, relee los precios y cotiza en cada tienda.
    Resultado comparar(Solicitud solicitud) {
        if (solicitud == null) {
            throw new SolicitudInvalidaException("Indica los productos del carrito");
        }
        Map<String, Integer> carrito = carrito(solicitud.productos);
        boolean web = solicitud.sucursales == null;
        var elecciones = elecciones(solicitud.productos, web);
        List<Sucursal> destinos = web
                ? Arrays.stream(Comercio.values()).map(comercio -> new Sucursal(comercio, WEB)).toList()
                : sucursales(solicitud.sucursales);
        List<CatalogoEnVivo.Fuente> fuentes = List.of();
        if (web) {
            var rutas = rutas(solicitud.productos);
            var consultas = busquedas(solicitud.productos);
            fuentes = rutas.isEmpty() ? catalogo.actualizarFuentes(consultas)
                    : catalogo.actualizarFuentes(consultas, rutas);
        }
        Duration vigencia = web ? vigenciaWeb : vigenciaSucursal;
        OffsetDateTime instante = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime desde = instante.minus(vigencia);

        List<Opcion> opciones = destinos.stream()
                .map(destino -> cotizar(destino, carrito, instante, desde))
                .sorted(Comparator.comparing(Opcion::totalClp, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Comparator.comparingInt(Opcion::productosConPrecio).reversed())
                        .thenComparing(opcion -> opcion.comercio.name())
                        .thenComparing(Opcion::ubicacion))
                .toList();

        List<Long> completas = opciones.stream().map(Opcion::totalClp).filter(total -> total != null).toList();
        if (completas.size() >= 2) {
            long referencia = completas.get(completas.size() - 1);
            opciones = opciones.stream().map(opcion -> new Opcion(opcion.comercio, opcion.ubicacion,
                    opcion.productosConPrecio, opcion.totalClp,
                    opcion.totalClp == null ? null : referencia - opcion.totalClp, opcion.productos)).toList();
        }
        CompraElegida elegida = elecciones.isEmpty() ? null : cotizarElecciones(elecciones,
                solicitud.productos.stream().filter(item -> item.comercio == null).count(), instante, desde);
        return new Resultado(web ? "WEB" : "SUCURSAL", instante, vigencia.toString(), opciones, fuentes, elegida);
    }

    // Total del carrito en una tienda: suma los productos que tienen precio vigente.
    private Opcion cotizar(Sucursal destino, Map<String, Integer> carrito,
            OffsetDateTime instante, OffsetDateTime desde) {
        Map<String, ObservacionPrecio> observaciones = new LinkedHashMap<>();
        for (ObservacionPrecio precio : repositorio.buscarComparables(
                destino.comercio, destino.ubicacion, carrito.keySet(), instante)) {
            observaciones.putIfAbsent(IdentidadProducto.clave(precio), precio);
        }

        List<Linea> lineas = carrito.entrySet().stream()
                .map(item -> linea(item.getKey(), item.getValue(), observaciones.get(item.getKey()), desde))
                .toList();
        return opcion(destino, lineas);
    }

    // Total de la compra con la tienda que el usuario eligió para cada producto.
    private CompraElegida cotizarElecciones(Map<Comercio, Map<ProductoElegido, Integer>> elecciones,
            long lineasSinTienda, OffsetDateTime instante, OffsetDateTime desde) {
        List<Opcion> tiendas = new ArrayList<>();
        for (var entrada : elecciones.entrySet()) {
            Comercio comercio = entrada.getKey();
            var elegidos = entrada.getValue();
            var claves = elegidos.keySet().stream().map(ProductoElegido::clave)
                    .collect(java.util.stream.Collectors.toSet());
            Map<ProductoElegido, ObservacionPrecio> observaciones = new LinkedHashMap<>();
            for (var precio : repositorio.buscarComparables(comercio, WEB, claves, instante)) {
                observaciones.putIfAbsent(new ProductoElegido(IdentidadProducto.clave(precio), precio.productoId()),
                        precio);
            }
            List<Linea> lineas = elegidos.entrySet().stream()
                    .map(item -> linea(item.getKey().clave, item.getValue(), observaciones.get(item.getKey()), desde))
                    .toList();
            tiendas.add(opcion(new Sucursal(comercio, WEB), lineas));
        }
        Long total = lineasSinTienda == 0 && tiendas.stream().allMatch(tienda -> tienda.totalClp != null)
                ? tiendas.stream().mapToLong(Opcion::totalClp).sum() : null;
        return new CompraElegida(total, lineasSinTienda, tiendas);
    }

    // Arma la opción de una tienda a partir de sus líneas.
    private static Opcion opcion(Sucursal destino, List<Linea> lineas) {
        List<Long> subtotales = lineas.stream().map(Linea::subtotalClp).filter(java.util.Objects::nonNull).toList();
        Long total = subtotales.size() == lineas.size() ? subtotales.stream().mapToLong(Long::longValue).sum() : null;
        return new Opcion(destino.comercio, destino.ubicacion, subtotales.size(), total, null, lineas);
    }

    // Una línea del carrito: producto, cantidad y precio unitario si sigue vigente.
    private static Linea linea(String clave, int cantidad, ObservacionPrecio precio, OffsetDateTime desde) {
        if (precio == null) {
            return new Linea(clave, cantidad, null, null, null, "SIN_PRECIO", null, null);
        }
        if (precio.observadoEn().isBefore(desde)) {
            return new Linea(clave, cantidad, precio.nombre(), null, null, "VENCIDO",
                    precio.urlFuente(), precio.observadoEn());
        }
        return new Linea(clave, cantidad, precio.nombre(), precio.precioClp(), (long) precio.precioClp() * cantidad,
                "DISPONIBLE", precio.urlFuente(), precio.observadoEn());
    }

    // Valida y suma las cantidades por producto (enteros entre 1 y 9999).
    private Map<String, Integer> carrito(List<Item> productos) {
        if (productos == null || productos.isEmpty() || productos.size() > 50) {
            throw new SolicitudInvalidaException("El carrito debe tener de 1 a 50 líneas");
        }
        Map<String, Integer> carrito = new LinkedHashMap<>();
        for (Item item : productos) {
            if (item == null || !IdentidadProducto.esClaveValida(item.clave)
                    || item.cantidad == null || !item.cantidad.isIntegralNumber()
                    || !item.cantidad.canConvertToInt()
                    || item.cantidad.asInt() < 1 || item.cantidad.asInt() > 9999) {
                throw new SolicitudInvalidaException("Usa una clave de catálogo y una cantidad entera de 1 a 9999");
            }
            carrito.merge(item.clave, item.cantidad.asInt(), Integer::sum);
        }
        return carrito;
    }

    // Agrupa por tienda los productos que el usuario eligió.
    private Map<Comercio, Map<ProductoElegido, Integer>> elecciones(List<Item> productos, boolean web) {
        Map<Comercio, Map<ProductoElegido, Integer>> tiendas = new LinkedHashMap<>();
        Map<String, Integer> sinTienda = new LinkedHashMap<>();
        for (Item item : productos) {
            if (item.comercio == null && item.productoId == null) {
                if (sinTienda.merge(item.clave, item.cantidad.asInt(), Integer::sum) > 9999) {
                    throw new SolicitudInvalidaException("Cantidad acumulada superior a 9999");
                }
                continue;
            }
            if (!web || item.comercio == null || item.productoId == null || item.productoId.isBlank()
                    || item.productoId.length() > 120 || item.productoId.chars().anyMatch(Character::isISOControl)) {
                throw new SolicitudInvalidaException("Identifica la tienda y el producto de la oferta web");
            }
            var elegidos = tiendas.computeIfAbsent(item.comercio, ignorado -> new LinkedHashMap<>());
            var producto = new ProductoElegido(item.clave, item.productoId);
            if (elegidos.merge(producto, item.cantidad.asInt(), Integer::sum) > 9999) {
                throw new SolicitudInvalidaException("Cantidad acumulada superior a 9999");
            }
        }
        return tiendas;
    }

    // Textos a releer en las tiendas antes de cotizar.
    private List<String> busquedas(List<Item> productos) {
        return productos.stream().map(Item::busqueda).filter(java.util.Objects::nonNull)
                .map(String::strip).filter(valor -> valor.length() >= 2 && valor.length() <= 80)
                .filter(valor -> valor.codePoints().anyMatch(Character::isLetterOrDigit))
                .distinct().limit(6).toList();
    }

    // Páginas de pasillo a releer antes de cotizar.
    private List<ExploradorCatalogos.Ruta> rutas(List<Item> productos) {
        return productos.stream().filter(item -> item.pasillo != null || item.pagina != null)
                .map(item -> new ExploradorCatalogos.Ruta(item.comercio, item.pasillo, item.pagina == null ? 0 : item.pagina))
                .distinct().limit(6).toList();
    }

    // true si la ubicación es una sucursal física válida (no la web).
    static boolean esSucursal(String ubicacion) {
        return ubicacion != null && !ubicacion.isBlank() && ubicacion.length() <= 120
                && !WEB.equalsIgnoreCase(ubicacion.strip())
                && ubicacion.chars().noneMatch(Character::isISOControl);
    }

    // Valida las sucursales pedidas (de 1 a 20) y quita las repetidas.
    private List<Sucursal> sucursales(List<Sucursal> solicitadas) {
        if (solicitadas.isEmpty() || solicitadas.size() > 20) {
            throw new SolicitudInvalidaException("Indica de 1 a 20 sucursales");
        }
        var destinos = new LinkedHashSet<Sucursal>();
        for (Sucursal destino : solicitadas) {
            if (destino == null || destino.comercio == null || !esSucursal(destino.ubicacion)) {
                throw new SolicitudInvalidaException("Cada sucursal requiere comercio y ubicación exacta");
            }
            destinos.add(destino);
        }
        return List.copyOf(destinos);
    }

    // Lee una duración ISO-8601 de la configuración (por ejemplo PT15M).
    private Duration duracion(String valor) {
        Duration duracion = Duration.parse(valor);
        if (duracion.isNegative() || duracion.isZero()) {
            throw new IllegalStateException("La vigencia de un precio debe ser positiva");
        }
        return duracion;
    }
}
