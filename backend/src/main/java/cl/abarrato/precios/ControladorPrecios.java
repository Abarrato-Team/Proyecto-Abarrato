package cl.abarrato.precios;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/precios")
public class ControladorPrecios {
    record Categoria(Comercio.Rubro id, String nombre) {}

    private final ServicioPrecios servicio;
    private final CatalogoEnVivo catalogoEnVivo;
    private final ComparadorCarrito comparador;
    private final RepositorioPrecios repositorio;
    private final ExploradorCatalogos explorador;

    public ControladorPrecios(ServicioPrecios servicio, CatalogoEnVivo catalogoEnVivo,
            ComparadorCarrito comparador, RepositorioPrecios repositorio, ExploradorCatalogos explorador) {
        this.servicio = servicio;
        this.catalogoEnVivo = catalogoEnVivo;
        this.comparador = comparador;
        this.repositorio = repositorio;
        this.explorador = explorador;
    }

    @GetMapping("/tiendas")
    public Map<String, List<Comercio>> tiendas() {
        return Map.of("tiendas", List.of(Comercio.values()));
    }

    @GetMapping("/categorias")
    public Map<String, List<Categoria>> categorias() {
        return Map.of("categorias", java.util.Arrays.stream(Comercio.Rubro.values())
                .map(rubro -> new Categoria(rubro, rubro.nombre)).toList());
    }

    @GetMapping("/categorias/{rubro}")
    public CatalogoEnVivo.Consulta categoria(@PathVariable Comercio.Rubro rubro,
            @RequestParam(required = false) Comercio comercio) {
        return catalogoEnVivo.consultarRubro(rubro, comercio);
    }

    @GetMapping("/tiendas/{comercio}")
    public ExploradorCatalogos.Menu pasillos(@PathVariable Comercio comercio) {
        return explorador.menu(comercio);
    }

    @GetMapping("/tiendas/{comercio}/productos")
    public ExploradorCatalogos.Pagina productos(@PathVariable Comercio comercio, @RequestParam String pasillo,
            @RequestParam(defaultValue = "1") int pagina) {
        try {
            return explorador.cargar(new ExploradorCatalogos.Ruta(comercio, pasillo, pagina));
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pasillo o página inválidos");
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Página del comercio temporalmente inaccesible");
        }
    }

    @PostMapping("/comparar-carrito")
    public ResponseEntity<?> compararCarrito(@RequestBody ComparadorCarrito.Solicitud solicitud) {
        try {
            return ResponseEntity.ok(comparador.comparar(solicitud));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", error.getMessage()));
        }
    }

    @GetMapping("/sucursales")
    public java.util.List<RepositorioPrecios.Sucursal> sucursales(@RequestParam(required = false) Comercio comercio) {
        return repositorio.buscarSucursales(comercio);
    }

    @GetMapping("/fotos")
    public ResponseEntity<?> fotos(@RequestParam("clave") java.util.List<String> claves) {
        if (claves.isEmpty() || claves.size() > 50 || claves.stream().anyMatch(clave -> !IdentidadProducto.esClaveValida(clave))) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(java.util.Map.of("productos", repositorio.buscarFotos(java.util.Set.copyOf(claves))));
    }

    @GetMapping("/en-vivo")
    public ResponseEntity<CatalogoEnVivo.Consulta> enVivo(@RequestParam String q,
            @RequestParam(required = false) Comercio comercio,
            @RequestParam(required = false) List<Comercio> comercios) {
        String consulta = q.strip();
        if (consulta.length() < 2 || consulta.length() > 80
                || consulta.codePoints().noneMatch(Character::isLetterOrDigit)) {
            return ResponseEntity.badRequest().build();
        }
        if (comercio == null && comercios != null && !comercios.isEmpty()) {
            // La app Android consulta solo Jumbo, Santa Isabel, Líder y Del Pedregal.
            return ResponseEntity.ok(catalogoEnVivo.consultar(consulta, List.copyOf(new java.util.LinkedHashSet<>(comercios))));
        }
        return ResponseEntity.ok(catalogoEnVivo.consultar(consulta, comercio));
    }

    @GetMapping
    public ResponseEntity<ObservacionPrecio> buscarUltimo(
            @RequestParam Comercio comercio, @RequestParam String productoId, @RequestParam String ubicacion) {
        if (productoId.isBlank() || productoId.length() > 120 || ubicacion.isBlank() || ubicacion.length() > 120) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.of(servicio.buscarUltimo(comercio, productoId, ubicacion));
    }

    @GetMapping("/catalogo")
    public ResponseEntity<java.util.List<ObservacionPrecio>> catalogo(@RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) Comercio comercio, @RequestParam(required = false) String ubicacion) {
        if (q.length() > 120 || ((comercio != null || ubicacion != null)
                && (comercio == null || !ComparadorCarrito.esSucursal(ubicacion)))) {
            return ResponseEntity.badRequest().build();
        }
        if (comercio != null) {
            return ResponseEntity.ok(repositorio.buscarCatalogoSucursal(comercio, ubicacion, q.strip()));
        }
        return ResponseEntity.ok(servicio.buscarCatalogo(q.strip()));
    }
}
