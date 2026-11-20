package cl.abarrato.precios;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Verifica el contrato de la API v1 sin base de datos ni internet: códigos de estado correctos y el
 * mismo formato JSON de error en todas las rutas. Usa el servicio real con un repositorio simulado,
 * para probar también que las reglas viven en la capa de negocio y no en el controlador.
 */
class ContratoApiTest {
    private static final String BASE = "/api/v1/precios";
    private static final String TOKEN = "token-de-prueba-123456";

    private RepositorioPrecios repositorio;
    private CatalogoEnVivo catalogo;
    private ExploradorCatalogos explorador;
    private MockMvc api;

    @BeforeEach
    void preparar() {
        repositorio = mock(RepositorioPrecios.class);
        catalogo = mock(CatalogoEnVivo.class);
        explorador = mock(ExploradorCatalogos.class);
        ComparadorCarrito comparador = mock(ComparadorCarrito.class);
        ServicioPrecios servicio = new ServicioPrecios(repositorio);
        api = MockMvcBuilders
                .standaloneSetup(new ControladorPrecios(servicio, catalogo, comparador, explorador),
                        new TareaDiaria(catalogo, TOKEN, 25))
                .setControllerAdvice(new ManejadorErrores())
                .build();
    }

    @Test
    void listarTiendasResponde200ConLasOchoCadenas() throws Exception {
        api.perform(get(BASE + "/tiendas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiendas.length()").value(Comercio.values().length))
                .andExpect(jsonPath("$.tiendas[0]").value("JUMBO"));
    }

    @Test
    void unaBusquedaDemasiadoCortaResponde400ConMensajeYNoConsultaLasTiendas() throws Exception {
        api.perform(get(BASE + "/en-vivo").param("q", "a"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.estado").value(400))
                .andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"))
                .andExpect(jsonPath("$.mensaje").value(containsString("entre 2 y 80")))
                .andExpect(jsonPath("$.ruta").value(BASE + "/en-vivo"))
                .andExpect(jsonPath("$.fecha").exists());
        verifyNoInteractions(catalogo);
    }

    @Test
    void sinElParametroQResponde400ParametroFaltante() throws Exception {
        api.perform(get(BASE + "/en-vivo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PARAMETRO_FALTANTE"))
                .andExpect(jsonPath("$.mensaje").value(containsString("'q'")));
    }

    @Test
    void unaTiendaInexistenteResponde400YListaLasValidas() throws Exception {
        api.perform(get(BASE + "/tiendas/FALABELLA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALOR_INVALIDO"))
                .andExpect(jsonPath("$.mensaje").value(containsString("JUMBO")));
    }

    @Test
    void unProductoSinPreciosGuardadosResponde404() throws Exception {
        when(repositorio.buscarUltimo(any(), anyString(), anyString())).thenReturn(Optional.empty());
        api.perform(get(BASE).param("comercio", "LIDER").param("productoId", "123").param("ubicacion", "web"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    void masDeCincuentaFotosResponde400SinTocarLaBaseDeDatos() throws Exception {
        var peticion = get(BASE + "/fotos");
        for (int i = 0; i < 51; i++) {
            peticion.param("clave", "sku:JUMBO:" + i);
        }
        api.perform(peticion)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(containsString("50")));
        verifyNoInteractions(repositorio);
    }

    @Test
    void catalogoDeSucursalSinUbicacionResponde400() throws Exception {
        api.perform(get(BASE + "/catalogo").param("comercio", "JUMBO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("SOLICITUD_INVALIDA"));
    }

    @Test
    void unCarritoConJsonRotoResponde400JsonInvalido() throws Exception {
        api.perform(post(BASE + "/comparar-carrito").contentType(MediaType.APPLICATION_JSON).content("{malo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("JSON_INVALIDO"));
    }

    @Test
    void siLaTiendaNoRespondeLaApiResponde502() throws Exception {
        when(explorador.cargar(any())).thenThrow(new IOException("timeout"));
        api.perform(get(BASE + "/tiendas/JUMBO/productos").param("pasillo", "lacteos").param("pagina", "1"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.codigo").value("TIENDA_INACCESIBLE"));
    }

    @Test
    void unErrorInesperadoResponde500SinFiltrarDetallesInternos() throws Exception {
        when(catalogo.consultar(anyString(), (Comercio) isNull()))
                .thenThrow(new IllegalStateException("SELECT * FROM secreto"));
        api.perform(get(BASE + "/en-vivo").param("q", "leche"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.codigo").value("ERROR_INTERNO"))
                .andExpect(jsonPath("$.mensaje").value(not(containsString("SELECT"))));
    }

    @Test
    void laTareaDiariaSinTokenOConTokenIncorrectoResponde403() throws Exception {
        api.perform(post(BASE + "/actualizar"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
        api.perform(post(BASE + "/actualizar").header("X-Abarrato-Token", "otro-token-cualquiera"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(catalogo);
    }

    @Test
    void laTareaDiariaSinTokenConfiguradoNoExiste() throws Exception {
        MockMvc sinToken = MockMvcBuilders.standaloneSetup(new TareaDiaria(catalogo, "", 25))
                .setControllerAdvice(new ManejadorErrores()).build();
        sinToken.perform(post(BASE + "/actualizar").header("X-Abarrato-Token", "x"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(catalogo);
    }

    @Test
    void laTareaDiariaConTokenCorrectoRecorreLasTiendas() throws Exception {
        when(catalogo.actualizarTodo(anyInt())).thenReturn(java.util.Map.of());
        api.perform(post(BASE + "/actualizar").header("X-Abarrato-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preciosLeidos").value(0));
    }
}
