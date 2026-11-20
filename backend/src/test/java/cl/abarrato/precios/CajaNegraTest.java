package cl.abarrato.precios;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * QA · PRUEBAS DE CAJA NEGRA.
 *
 * <p>Se diseñan solo con el contrato público ({@code docs/API.md}), sin mirar el código: se elige
 * una entrada, se llama a la API como lo haría la app y se compara el código HTTP con lo prometido.
 * Técnicas: <b>particiones de equivalencia</b> (grupos de entradas que deben comportarse igual) y
 * <b>valores límite</b> (justo antes, en y justo después de cada borde: 1, 2, 80 y 81 caracteres).
 */
class CajaNegraTest {
    private static final String BUSCAR = "/api/v1/precios/en-vivo";
    private MockMvc api;

    @BeforeEach
    void preparar() {
        api = MockMvcBuilders
                .standaloneSetup(new ControladorPrecios(new ServicioPrecios(mock(RepositorioPrecios.class)),
                        mock(CatalogoEnVivo.class), mock(ComparadorCarrito.class), mock(ExploradorCatalogos.class)))
                .setControllerAdvice(new ManejadorErrores())
                .build();
    }

    /** Valores límite del largo de la búsqueda: el contrato dice "de 2 a 80 caracteres". */
    @ParameterizedTest(name = "{0} caracteres → {1}")
    @CsvSource({"1, 400", "2, 200", "3, 200", "79, 200", "80, 200", "81, 400"})
    void largoDeLaBusqueda(int largo, int esperado) throws Exception {
        api.perform(get(BUSCAR).param("q", "a".repeat(largo))).andExpect(status().is(esperado));
    }

    /** Particiones de equivalencia del texto buscado. */
    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource(delimiter = '|', value = {
            "leche entera | 200",       // texto normal
            "7up          | 200",       // dígitos y letras
            "Ñandú café   | 200",       // tildes y eñe
            "'   '        | 400",       // solo espacios
            "%%%%         | 400",       // solo símbolos
            "'<script>'   | 200"        // símbolos con letras: se acepta, la respuesta es JSON y no se ejecuta
    })
    void tiposDeTexto(String texto, int esperado) throws Exception {
        api.perform(get(BUSCAR).param("q", texto)).andExpect(status().is(esperado));
    }

    @Test
    void paginaCeroEsInvalidaYPaginaUnoEsValida() throws Exception {
        api.perform(get(BUSCAR).param("q", "arroz").param("pagina", "0")).andExpect(status().isBadRequest());
        api.perform(get(BUSCAR).param("q", "arroz").param("pagina", "1")).andExpect(status().isOk());
    }

    @Test
    void paginaQueNoEsNumeroResponde400() throws Exception {
        api.perform(get(BUSCAR).param("q", "arroz").param("pagina", "dos"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALOR_INVALIDO"));
    }

    @Test
    void lasOchoTiendasDelContratoSonValidasYUnaNovenaNo() throws Exception {
        for (String tienda : new String[] {"JUMBO", "SANTA_ISABEL", "LIDER", "UNIMARC", "TOTTUS", "ALVI",
                "CUGAT", "DEL_PEDREGAL"}) {
            api.perform(get(BUSCAR).param("q", "arroz").param("comercio", tienda)).andExpect(status().isOk());
        }
        api.perform(get(BUSCAR).param("q", "arroz").param("comercio", "WALMART")).andExpect(status().isBadRequest());
    }

    @Test
    void unVerboQueLaRutaNoAceptaResponde405() throws Exception {
        api.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(BUSCAR))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.codigo").value("METODO_NO_PERMITIDO"));
    }
}
