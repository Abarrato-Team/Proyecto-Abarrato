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

@Service
final class ComparadorCarrito {
    static final String WEB = "ONLINE_SIN_UBICACION";

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

    Resultado comparar(Solicitud solicitud) {
        if (solicitud == null) {
            throw new IllegalArgumentException("Indica los productos del carrito");
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

    private static Opcion opcion(Sucursal destino, List<Linea> lineas) {
        List<Long> subtotales = lineas.stream().map(Linea::subtotalClp).filter(java.util.Objects::nonNull).toList();
        Long total = subtotales.size() == lineas.size() ? subtotales.stream().mapToLong(Long::longValue).sum() : null;
        return new Opcion(destino.comercio, destino.ubicacion, subtotales.size(), total, null, lineas);
    }

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

    private Map<String, Integer> carrito(List<Item> productos) {
        if (productos == null || productos.isEmpty() || productos.size() > 50) {
            throw new IllegalArgumentException("El carrito debe tener de 1 a 50 líneas");
        }
        Map<String, Integer> carrito = new LinkedHashMap<>();
        for (Item item : productos) {
            if (item == null || !IdentidadProducto.esClaveValida(item.clave)
                    || item.cantidad == null || !item.cantidad.isIntegralNumber()
                    || !item.cantidad.canConvertToInt()
                    || item.cantidad.asInt() < 1 || item.cantidad.asInt() > 9999) {
                throw new IllegalArgumentException("Usa una clave de catálogo y una cantidad entera de 1 a 9999");
            }
            carrito.merge(item.clave, item.cantidad.asInt(), Integer::sum);
        }
        return carrito;
    }

    private Map<Comercio, Map<ProductoElegido, Integer>> elecciones(List<Item> productos, boolean web) {
        Map<Comercio, Map<ProductoElegido, Integer>> tiendas = new LinkedHashMap<>();
        Map<String, Integer> sinTienda = new LinkedHashMap<>();
        for (Item item : productos) {
            if (item.comercio == null && item.productoId == null) {
                if (sinTienda.merge(item.clave, item.cantidad.asInt(), Integer::sum) > 9999) {
                    throw new IllegalArgumentException("Cantidad acumulada superior a 9999");
                }
                continue;
            }
            if (!web || item.comercio == null || item.productoId == null || item.productoId.isBlank()
                    || item.productoId.length() > 120 || item.productoId.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Identifica la tienda y el producto de la oferta web");
            }
            var elegidos = tiendas.computeIfAbsent(item.comercio, ignorado -> new LinkedHashMap<>());
            var producto = new ProductoElegido(item.clave, item.productoId);
            if (elegidos.merge(producto, item.cantidad.asInt(), Integer::sum) > 9999) {
                throw new IllegalArgumentException("Cantidad acumulada superior a 9999");
            }
        }
        return tiendas;
    }

    private List<String> busquedas(List<Item> productos) {
        return productos.stream().map(Item::busqueda).filter(java.util.Objects::nonNull)
                .map(String::strip).filter(valor -> valor.length() >= 2 && valor.length() <= 80)
                .filter(valor -> valor.codePoints().anyMatch(Character::isLetterOrDigit))
                .distinct().limit(6).toList();
    }

    private List<ExploradorCatalogos.Ruta> rutas(List<Item> productos) {
        return productos.stream().filter(item -> item.pasillo != null || item.pagina != null)
                .map(item -> new ExploradorCatalogos.Ruta(item.comercio, item.pasillo, item.pagina == null ? 0 : item.pagina))
                .distinct().limit(6).toList();
    }

    static boolean esSucursal(String ubicacion) {
        return ubicacion != null && !ubicacion.isBlank() && ubicacion.length() <= 120
                && !WEB.equalsIgnoreCase(ubicacion.strip())
                && ubicacion.chars().noneMatch(Character::isISOControl);
    }

    private List<Sucursal> sucursales(List<Sucursal> solicitadas) {
        if (solicitadas.isEmpty() || solicitadas.size() > 20) {
            throw new IllegalArgumentException("Indica de 1 a 20 sucursales");
        }
        var destinos = new LinkedHashSet<Sucursal>();
        for (Sucursal destino : solicitadas) {
            if (destino == null || destino.comercio == null || !esSucursal(destino.ubicacion)) {
                throw new IllegalArgumentException("Cada sucursal requiere comercio y ubicación exacta");
            }
            destinos.add(destino);
        }
        return List.copyOf(destinos);
    }

    private Duration duracion(String valor) {
        Duration duracion = Duration.parse(valor);
        if (duracion.isNegative() || duracion.isZero()) {
            throw new IllegalArgumentException("La vigencia de un precio debe ser positiva");
        }
        return duracion;
    }
}
