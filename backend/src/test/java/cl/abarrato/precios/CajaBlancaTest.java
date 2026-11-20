package cl.abarrato.precios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * QA · PRUEBAS DE CAJA BLANCA.
 *
 * <p>Se diseñan leyendo el código: cada prueba fuerza un camino distinto de un {@code if}, para que
 * <b>todas las ramas</b> se ejecuten al menos una vez (cobertura de decisiones). El comentario de
 * cada prueba indica qué rama recorre.
 */
class CajaBlancaTest {

    // ---------- ServicioPrecios.buscarCatalogo: 4 decisiones, 5 caminos ----------

    @Test
    void camino1_textoDemasiadoLargo_lanzaAntesDeTocarLaBase() {
        RepositorioPrecios repositorio = mock(RepositorioPrecios.class);
        ServicioPrecios servicio = new ServicioPrecios(repositorio);
        assertThatThrownBy(() -> servicio.buscarCatalogo("x".repeat(121), null, null))
                .isInstanceOf(SolicitudInvalidaException.class);
        verify(repositorio, never()).buscarCatalogo(anyString());
    }

    @Test
    void camino2_sinTiendaNiUbicacion_buscaEnLaWeb() {
        RepositorioPrecios repositorio = mock(RepositorioPrecios.class);
        new ServicioPrecios(repositorio).buscarCatalogo("  leche  ", null, null);
        verify(repositorio).buscarCatalogo("leche");
    }

    @Test
    void camino3_ubicacionSinTienda_esInvalido() {
        ServicioPrecios servicio = new ServicioPrecios(mock(RepositorioPrecios.class));
        assertThatThrownBy(() -> servicio.buscarCatalogo("leche", null, "SUCURSAL-1"))
                .isInstanceOf(SolicitudInvalidaException.class);
    }

    @Test
    void camino4_tiendaConUbicacionWeb_noEsSucursal() {
        ServicioPrecios servicio = new ServicioPrecios(mock(RepositorioPrecios.class));
        assertThatThrownBy(() -> servicio.buscarCatalogo("leche", Comercio.JUMBO, "ONLINE_SIN_UBICACION"))
                .isInstanceOf(SolicitudInvalidaException.class);
    }

    @Test
    void camino5_tiendaYSucursal_buscaEnLaSucursal() {
        RepositorioPrecios repositorio = mock(RepositorioPrecios.class);
        new ServicioPrecios(repositorio).buscarCatalogo("leche", Comercio.JUMBO, "SUCURSAL-1");
        verify(repositorio).buscarCatalogoSucursal(Comercio.JUMBO, "SUCURSAL-1", "leche");
        verify(repositorio, never()).buscarCatalogo(anyString());
    }

    // ---------- ControladorPrecios.validarConsulta: 3 ramas ----------

    @Test
    void validarConsulta_recortaEspacios() {
        assertThat(ControladorPrecios.validarConsulta("  arroz  ")).isEqualTo("arroz");
    }

    @Test
    void validarConsulta_ramaDeLargo_yRamaDeSimbolos() {
        assertThatThrownBy(() -> ControladorPrecios.validarConsulta(null))
                .hasMessageContaining("entre 2 y 80");
        assertThatThrownBy(() -> ControladorPrecios.validarConsulta("!!??"))
                .hasMessageContaining("letras o números");
    }

    // ---------- LimitePeticiones.cliente / pareceIp: 3 ramas ----------

    @Test
    void cliente_usaLaPrimeraIpDelEncabezadoSiEsValida() {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.addHeader("X-Forwarded-For", "190.5.6.7, 35.191.0.1");
        assertThat(LimitePeticiones.cliente(peticion)).isEqualTo("190.5.6.7");
    }

    @Test
    void cliente_ignoraUnEncabezadoConSaltosDeLinea_yUsaLaConexion() {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.addHeader("X-Forwarded-For", "1.2.3.4\nWARN falso: el admin entró");
        peticion.setRemoteAddr("10.1.2.3");
        assertThat(LimitePeticiones.cliente(peticion)).isEqualTo("10.1.2.3");
    }

    @Test
    void pareceIp_aceptaIpv4EIpv6_yRechazaTexto() {
        assertThat(LimitePeticiones.pareceIp("200.1.1.1")).isTrue();
        assertThat(LimitePeticiones.pareceIp("2800:150:abcd::1")).isTrue();
        assertThat(LimitePeticiones.pareceIp("hola")).isFalse();
        assertThat(LimitePeticiones.pareceIp("1".repeat(46))).isFalse();
        assertThat(LimitePeticiones.pareceIp("")).isFalse();
    }

    // ---------- ObservacionPrecio: reglas del constructor ----------

    @Test
    void observacion_descartaOfertasIncoherentes() {
        var hoy = java.time.OffsetDateTime.parse("2026-11-14T10:00:00-03:00");
        var ofertaMayor = new ObservacionPrecio(Comercio.LIDER, "1", "ONLINE_SIN_UBICACION", "Arroz", "$1.000",
                1000, "https://www.lider.cl/1", hoy, null, null, 900, 1200, "Con tarjeta");
        assertThat(ofertaMayor.precioAntesClp()).isNull();   // "antes" menor que el precio: se descarta
        assertThat(ofertaMayor.precioOfertaClp()).isNull();  // "oferta" mayor que el precio: se descarta
        assertThat(ofertaMayor.condicionOferta()).isNull();  // sin oferta no hay condición
    }
}
