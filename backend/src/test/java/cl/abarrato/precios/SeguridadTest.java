package cl.abarrato.precios;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Pruebas de los filtros de seguridad: límite de peticiones, tamaño del cuerpo y cabeceras. */
class SeguridadTest {
    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);

    private static MockHttpServletRequest busqueda(String ip, boolean guardado) {
        MockHttpServletRequest peticion = new MockHttpServletRequest("GET", "/api/v1/precios/en-vivo");
        peticion.setParameter("q", "leche");
        if (guardado) {
            peticion.setParameter("guardado", "true");
        }
        peticion.addHeader("X-Forwarded-For", ip + ", 10.0.0.1");
        return peticion;
    }

    private static MockHttpServletResponse pasar(LimitePeticiones filtro, MockHttpServletRequest peticion)
            throws Exception {
        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        filtro.doFilter(peticion, respuesta, new MockFilterChain());
        return respuesta;
    }

    @Test
    void alPasarseDelLimiteDeBusquedasResponde429ConRetryAfterYJsonDeError() throws Exception {
        LimitePeticiones filtro = new LimitePeticiones(100, 3, 1000, RELOJ);
        for (int i = 0; i < 3; i++) {
            assertThat(pasar(filtro, busqueda("200.1.1.1", false)).getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse bloqueada = pasar(filtro, busqueda("200.1.1.1", false));

        assertThat(bloqueada.getStatus()).isEqualTo(429);
        assertThat(bloqueada.getHeader("Retry-After")).isEqualTo("60");
        assertThat(bloqueada.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(bloqueada.getContentAsString())
                .contains("\"codigo\":\"DEMASIADAS_PETICIONES\"")
                .contains("Espera 60 segundos");
    }

    @Test
    void lasBusquedasGuardadasNoGastanElCupoDeBusquedasEnVivo() throws Exception {
        LimitePeticiones filtro = new LimitePeticiones(100, 1, 1000, RELOJ);
        for (int i = 0; i < 10; i++) {
            assertThat(pasar(filtro, busqueda("200.1.1.1", true)).getStatus()).isEqualTo(200);
        }
        assertThat(pasar(filtro, busqueda("200.1.1.1", false)).getStatus()).isEqualTo(200);
    }

    @Test
    void cadaClienteTieneSuPropioCupo() throws Exception {
        LimitePeticiones filtro = new LimitePeticiones(100, 1, 1000, RELOJ);
        assertThat(pasar(filtro, busqueda("200.1.1.1", false)).getStatus()).isEqualTo(200);
        assertThat(pasar(filtro, busqueda("200.1.1.1", false)).getStatus()).isEqualTo(429);
        assertThat(pasar(filtro, busqueda("200.2.2.2", false)).getStatus()).isEqualTo(200);
    }

    @Test
    void elLimiteTotalFrenaAQuienCambiaDeIpEnCadaPeticion() throws Exception {
        LimitePeticiones filtro = new LimitePeticiones(100, 5, 3, RELOJ);
        for (int i = 0; i < 3; i++) {
            assertThat(pasar(filtro, busqueda("200.9.9." + i, false)).getStatus()).isEqualTo(200);
        }
        assertThat(pasar(filtro, busqueda("200.9.9.99", false)).getStatus()).isEqualTo(429);
    }

    @Test
    void alTerminarElMinutoElCupoSeRenueva() {
        LimitePeticiones filtro = new LimitePeticiones(100, 1, 1000, RELOJ);
        long ahora = RELOJ.millis();
        assertThat(filtro.registrar("200.1.1.1", true, ahora).permitida()).isTrue();
        assertThat(filtro.registrar("200.1.1.1", true, ahora + 1_000).permitida()).isFalse();
        assertThat(filtro.registrar("200.1.1.1", true, ahora + LimitePeticiones.VENTANA_MS).permitida()).isTrue();
    }

    @Test
    void laTareaDiariaNoSeLimita() throws Exception {
        LimitePeticiones filtro = new LimitePeticiones(1, 1, 1, RELOJ);
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest tarea = new MockHttpServletRequest("POST", "/api/v1/precios/actualizar");
            assertThat(pasar(filtro, tarea).getStatus()).isEqualTo(200);
        }
    }

    @Test
    void unCuerpoDemasiadoGrandeResponde413() throws Exception {
        LimitePeticiones filtro = new LimitePeticiones(100, 100, 1000, RELOJ);
        MockHttpServletRequest carrito = new MockHttpServletRequest("POST", "/api/v1/precios/comparar-carrito");
        carrito.setContent(new byte[LimitePeticiones.MAX_CUERPO + 1]);
        MockHttpServletResponse respuesta = pasar(filtro, carrito);
        assertThat(respuesta.getStatus()).isEqualTo(413);
        assertThat(respuesta.getContentAsString()).contains("CUERPO_DEMASIADO_GRANDE");
    }

    @Test
    void elTextoDelErrorNoPuedeRomperElJson() {
        assertThat(LimitePeticiones.textoJson("a\"b\\c\n")).isEqualTo("a\\\"b\\\\c\\u000a");
    }

    @Test
    void todasLasRespuestasLlevanLasCabecerasDeSeguridad() throws Exception {
        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        new CabecerasSeguridad().doFilter(new MockHttpServletRequest("GET", "/api/v1/precios/tiendas"),
                respuesta, new MockFilterChain());
        assertThat(respuesta.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(respuesta.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(respuesta.getHeader("Strict-Transport-Security")).startsWith("max-age=");
        assertThat(respuesta.getHeader("Access-Control-Allow-Origin")).isNull();
    }
}
