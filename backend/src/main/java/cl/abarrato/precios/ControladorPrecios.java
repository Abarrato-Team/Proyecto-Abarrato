package cl.abarrato.precios;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Capa de presentación de la API REST v1 ({@code /api/v1/precios}).
 *
 * <p>Controlador "flaco": traduce HTTP a llamadas de negocio y nada más. No consulta la base de
 * datos ni arma respuestas de error; si algo no calza, la capa de negocio lanza una excepción y
 * {@link ManejadorErrores} la convierte en el código HTTP y el JSON de error que corresponden.
 * El contrato completo está en {@code docs/API.md}.
 */
@RestController
@RequestMapping("/api/v1/precios")
public class ControladorPrecios {
    // Lo que devuelve GET /categorias para cada rubro.
    record Categoria(Comercio.Rubro id, String nombre) {}

    /** Largo permitido para una búsqueda de productos. */
    static final int MIN_CONSULTA = 2;
    static final int MAX_CONSULTA = 80;

    private final ServicioPrecios servicio;
    private final CatalogoEnVivo catalogoEnVivo;
    private final ComparadorCarrito comparador;
    private final ExploradorCatalogos explorador;

    // Spring entrega las dependencias (inyección por constructor).
    public ControladorPrecios(ServicioPrecios servicio, CatalogoEnVivo catalogoEnVivo,
            ComparadorCarrito comparador, ExploradorCatalogos explorador) {
        this.servicio = servicio;
        this.catalogoEnVivo = catalogoEnVivo;
        this.comparador = comparador;
        this.explorador = explorador;
    }

    /** GET /tiendas → los supermercados que compara la app. */
    @GetMapping("/tiendas")
    public Map<String, List<Comercio>> tiendas() {
        return Map.of("tiendas", List.of(Comercio.values()));
    }

    /** GET /categorias → rubros para los filtros de la app. */
    @GetMapping("/categorias")
    public Map<String, List<Categoria>> categorias() {
        return Map.of("categorias", Arrays.stream(Comercio.Rubro.values())
                .map(rubro -> new Categoria(rubro, rubro.nombre)).toList());
    }

    /** GET /categorias/{rubro} → productos de un rubro, opcionalmente de una sola tienda. */
    @GetMapping("/categorias/{rubro}")
    public CatalogoEnVivo.Consulta categoria(@PathVariable Comercio.Rubro rubro,
            @RequestParam(required = false) Comercio comercio) {
        return catalogoEnVivo.consultarRubro(rubro, comercio);
    }

    /** GET /tiendas/{comercio} → pasillos (secciones) del catálogo web de la tienda. */
    @GetMapping("/tiendas/{comercio}")
    public ExploradorCatalogos.Menu pasillos(@PathVariable Comercio comercio) {
        return explorador.menu(comercio);
    }

    /** GET /tiendas/{comercio}/productos?pasillo=&pagina= → una página de un pasillo. 502 si la tienda no responde. */
    @GetMapping("/tiendas/{comercio}/productos")
    public ExploradorCatalogos.Pagina productos(@PathVariable Comercio comercio, @RequestParam String pasillo,
            @RequestParam(defaultValue = "1") int pagina) throws IOException {
        return explorador.cargar(new ExploradorCatalogos.Ruta(comercio, pasillo, pagina));
    }

    /** POST /comparar-carrito → precio del carrito en cada sucursal pedida. */
    @PostMapping("/comparar-carrito")
    public ComparadorCarrito.Resultado compararCarrito(@RequestBody ComparadorCarrito.Solicitud solicitud) {
        return comparador.comparar(solicitud);
    }

    /** GET /sucursales → sucursales físicas con precios guardados. */
    @GetMapping("/sucursales")
    public List<RepositorioPrecios.Sucursal> sucursales(@RequestParam(required = false) Comercio comercio) {
        return servicio.sucursales(comercio);
    }

    /** GET /fotos?clave=…&clave=… → foto de cada producto (máximo 50). */
    @GetMapping("/fotos")
    public Map<String, List<RepositorioPrecios.Foto>> fotos(@RequestParam("clave") List<String> claves) {
        return Map.of("productos", servicio.fotos(claves));
    }

    /**
     * GET /en-vivo?q= → búsqueda de productos en las tiendas.
     * Con {@code guardado=true} responde al instante con lo guardado en la base de datos.
     */
    @GetMapping("/en-vivo")
    public CatalogoEnVivo.Consulta enVivo(@RequestParam String q,
            @RequestParam(required = false) Comercio comercio,
            @RequestParam(required = false) List<Comercio> comercios,
            @RequestParam(defaultValue = "1") int pagina,
            @RequestParam(defaultValue = "false") boolean guardado) {
        String consulta = validarConsulta(q);
        if (pagina < 1) {
            throw new SolicitudInvalidaException("La página parte en 1");
        }
        List<Comercio> elegidas = comercios == null || comercios.isEmpty() ? List.of()
                : List.copyOf(new LinkedHashSet<>(comercios));
        if (guardado) {
            // Respuesta rápida con precios guardados; la app la muestra mientras llega la búsqueda en vivo.
            List<Comercio> tiendas = comercio != null ? List.of(comercio)
                    : !elegidas.isEmpty() ? elegidas : List.of(Comercio.values());
            return catalogoEnVivo.consultarGuardado(consulta, tiendas);
        }
        if (comercio == null && !elegidas.isEmpty()) {
            return catalogoEnVivo.consultar(consulta, elegidas, pagina);
        }
        return catalogoEnVivo.consultar(consulta, comercio);
    }

    /** GET /?comercio=&productoId=&ubicacion= → último precio guardado. 404 si no hay. */
    @GetMapping
    public ObservacionPrecio buscarUltimo(
            @RequestParam Comercio comercio, @RequestParam String productoId, @RequestParam String ubicacion) {
        return servicio.buscarUltimo(comercio, productoId, ubicacion);
    }

    /** GET /catalogo?q=&comercio=&ubicacion= → catálogo guardado (web o de una sucursal). */
    @GetMapping("/catalogo")
    public List<ObservacionPrecio> catalogo(@RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) Comercio comercio, @RequestParam(required = false) String ubicacion) {
        return servicio.buscarCatalogo(q, comercio, ubicacion);
    }

    /** Guard clause de formato: lo que no es una búsqueda razonable se descarta antes de tocar las tiendas. */
    static String validarConsulta(String q) {
        String consulta = q == null ? "" : q.strip();
        if (consulta.length() < MIN_CONSULTA || consulta.length() > MAX_CONSULTA) {
            throw new SolicitudInvalidaException(
                    "La búsqueda debe tener entre " + MIN_CONSULTA + " y " + MAX_CONSULTA + " caracteres");
        }
        if (consulta.codePoints().noneMatch(Character::isLetterOrDigit)) {
            throw new SolicitudInvalidaException("La búsqueda debe incluir letras o números");
        }
        return consulta;
    }
}
