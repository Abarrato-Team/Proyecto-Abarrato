package cl.abarrato.precios;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * QA · PRUEBAS DE CAJA GRIS.
 *
 * <p>Mezcla de las dos anteriores: las peticiones entran por la API, como en caja negra, pero los
 * casos se eligen conociendo el diseño interno (capas, esquema de la base, consultas SQL) y se
 * verifica también lo que pasa por dentro: qué llega a la capa de datos y qué no.
 * Ejemplos: que un texto con comodines de SQL llegue intacto al repositorio (que lo escapa), que
 * una petición inválida nunca toque PostgreSQL, que una respuesta no exponga columnas internas.
 */
class CajaGrisTest {
    private static final String BASE = "/api/v1/precios";
    private RepositorioPrecios repositorio;
    private MockMvc api;

    @BeforeEach
    void preparar() {
        repositorio = mock(RepositorioPrecios.class);
        api = MockMvcBuilders
                .standaloneSetup(new ControladorPrecios(new ServicioPrecios(repositorio), mock(CatalogoEnVivo.class),
                        mock(ComparadorCarrito.class), mock(ExploradorCatalogos.class)))
                .setControllerAdvice(new ManejadorErrores())
                .build();
    }

    /** Sabemos que el repositorio usa LIKE: '%' y '_' son comodines. Deben llegar como texto, no filtrarse. */
    @Test
    void losComodinesDeSqlLleganComoTextoAlRepositorio() throws Exception {
        when(repositorio.buscarCatalogo(anyString())).thenReturn(List.of());
        api.perform(get(BASE + "/catalogo").param("q", "100%_algodon")).andExpect(status().isOk());
        verify(repositorio).buscarCatalogo("100%_algodon");
    }

    /** Intento clásico de inyección SQL: la API lo trata como un texto más; nunca se arma SQL con él. */
    @Test
    void unaInyeccionSqlSeTrataComoTextoYNoRompeLaRespuesta() throws Exception {
        String ataque = "leche'; DROP TABLE observaciones_precio; --";
        when(repositorio.buscarCatalogo(anyString())).thenReturn(List.of());
        api.perform(get(BASE + "/catalogo").param("q", ataque))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        verify(repositorio).buscarCatalogo(ataque);
    }

    /** Conociendo las capas: una petición inválida se corta en negocio y no abre conexión a la base. */
    @Test
    void lasPeticionesInvalidasNuncaLleganAPostgresql() throws Exception {
        api.perform(get(BASE + "/catalogo").param("q", "x".repeat(121))).andExpect(status().isBadRequest());
        api.perform(get(BASE + "/catalogo").param("comercio", "LIDER")).andExpect(status().isBadRequest());
        api.perform(get(BASE + "/fotos").param("clave", "no es una clave")).andExpect(status().isBadRequest());
        api.perform(get(BASE).param("comercio", "LIDER").param("productoId", "").param("ubicacion", "web"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(repositorio);
    }

    /** Las claves de fotos repetidas se envían una sola vez a la base (el servicio usa un Set). */
    @Test
    void lasFotosRepetidasSeConsultanUnaSolaVez() throws Exception {
        String clave = "sku:JUMBO:123";
        when(repositorio.buscarFotos(anySet())).thenReturn(List.of());
        api.perform(get(BASE + "/fotos").param("clave", clave).param("clave", clave)).andExpect(status().isOk());
        verify(repositorio).buscarFotos(Set.of(clave));
    }

    /** Sabemos qué columnas tiene la tabla: la respuesta no debe incluir campos internos de auditoría. */
    @Test
    void elPrecioGuardadoNoExponeColumnasInternas() throws Exception {
        var observacion = new ObservacionPrecio(Comercio.LIDER, "123", "ONLINE_SIN_UBICACION", "Arroz 1 kg",
                "$1.290", 1290, "https://www.lider.cl/123", java.time.OffsetDateTime.parse("2026-11-14T09:00:00-03:00"),
                null, null);
        when(repositorio.buscarUltimo(any(), anyString(), anyString())).thenReturn(java.util.Optional.of(observacion));
        api.perform(get(BASE).param("comercio", "LIDER").param("productoId", "123")
                        .param("ubicacion", "ONLINE_SIN_UBICACION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precioClp").value(1290))
                .andExpect(jsonPath("$.confirmado_en").doesNotExist())
                .andExpect(jsonPath("$.confirmadoEn").doesNotExist());
        verify(repositorio, never()).buscarCatalogo(anyString());
    }
}
