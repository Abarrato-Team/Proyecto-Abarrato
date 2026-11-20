package cl.abarrato.precios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class CatalogoEnVivoTest {
    @ParameterizedTest
    @CsvSource({"yogures, Yoghurt natural", "cereales, Cereal de avena"})
    void filtraLaTiendaAntesDeConsultarYReconoceVariantes(String consulta, String nombre) throws Exception {
        var capturador = mock(CapturadorPrecios.class);
        var servicio = mock(ServicioPrecios.class);
        var catalogo = new CatalogoEnVivo(capturador, servicio, mock(ExploradorCatalogos.class));
        var tienda = Comercio.TOTTUS;
        String url = tienda.urlBusqueda(consulta);
        var producto = new ObservacionPrecio(tienda, "1", ComparadorCarrito.WEB, nombre,
                "$650", 650, url, OffsetDateTime.now(), null);
        var leche = new ObservacionPrecio(tienda, "2", ComparadorCarrito.WEB, "Leche entera",
                "$1200", 1200, tienda.catalogo(), OffsetDateTime.now(), null);
        when(capturador.capturar(tienda)).thenReturn(List.of(leche));
        when(capturador.capturarPagina(tienda, url)).thenReturn(List.of(producto));

        try {
            var resultado = catalogo.consultar(consulta, tienda);
            assertThat(resultado.productos()).extracting(CatalogoEnVivo.Producto::nombre)
                    .containsExactly(nombre);
            assertThat(resultado.fuentes()).extracting(CatalogoEnVivo.Fuente::comercio)
                    .containsExactly(tienda);
            verify(capturador).capturar(tienda);
            verify(capturador).capturarPagina(tienda, url);
            verifyNoMoreInteractions(capturador);
            // Los precios se guardan en segundo plano, pero se guardan.
            verify(servicio, timeout(2000).atLeastOnce()).importar(org.mockito.ArgumentMatchers.anyList());
        } finally {
            catalogo.cerrar();
        }
    }

    @Test
    void exploraUnaCategoriaPublicadaAunqueElNombreDelProductoNoContengaElRubro() throws Exception {
        var capturador = mock(CapturadorPrecios.class);
        var servicio = mock(ServicioPrecios.class);
        var catalogo = new CatalogoEnVivo(capturador, servicio, mock(ExploradorCatalogos.class));
        var tienda = Comercio.TOTTUS;
        var rubro = Comercio.Rubro.HELADO;
        String url = tienda.urlPagina(tienda.rutaRubro(rubro), 1);
        var producto = new ObservacionPrecio(tienda, "7", ComparadorCarrito.WEB, "Centella Frutilla 60 g",
                "$650", 650, url, OffsetDateTime.now(), null);
        when(capturador.capturarPagina(tienda, url)).thenReturn(List.of(producto));

        try {
            var resultado = catalogo.consultarRubro(rubro, tienda);
            assertThat(resultado.productos()).extracting(CatalogoEnVivo.Producto::nombre)
                    .containsExactly(producto.nombre());
            assertThat(resultado.fuentes()).extracting(CatalogoEnVivo.Fuente::comercio).containsExactly(tienda);
            verify(capturador).capturarPagina(tienda, url);
            verifyNoMoreInteractions(capturador);
        } finally {
            catalogo.cerrar();
        }
    }

    @Test
    void laBusquedaGuardadaRespondeConLaBaseDeDatosSinConsultarLasTiendas() throws Exception {
        var capturador = mock(CapturadorPrecios.class);
        var servicio = mock(ServicioPrecios.class);
        var catalogo = new CatalogoEnVivo(capturador, servicio, mock(ExploradorCatalogos.class));
        var leche = new ObservacionPrecio(Comercio.JUMBO, "1", ComparadorCarrito.WEB, "Leche Soprole Entera 1 L",
                "$1.190", 1190, "https://www.jumbo.cl/leche/p", OffsetDateTime.now().minusHours(3), null);
        var antigua = new ObservacionPrecio(Comercio.LIDER, "2", ComparadorCarrito.WEB, "Leche Colun Entera 1 L",
                "$1.090", 1090, "https://www.lider.cl/ip/leche", OffsetDateTime.now().minusDays(30), null);
        var otraTienda = new ObservacionPrecio(Comercio.TOTTUS, "3", ComparadorCarrito.WEB, "Leche Loncoleche 1 L",
                "$990", 990, "https://www.tottus.cl/leche", OffsetDateTime.now().minusHours(1), null);
        when(servicio.buscarCatalogo("leche")).thenReturn(List.of(leche, antigua, otraTienda));

        try {
            var resultado = catalogo.consultarGuardado("leche", List.of(Comercio.JUMBO, Comercio.LIDER));
            // Solo la de Jumbo: la de Líder es de hace 30 días y Tottus no se pidió.
            assertThat(resultado.productos()).extracting(CatalogoEnVivo.Producto::nombre)
                    .containsExactly("Leche Soprole Entera 1 L");
            assertThat(resultado.fuentes()).extracting(CatalogoEnVivo.Fuente::estado)
                    .containsExactly("ULTIMA_CAPTURA");
            verifyNoMoreInteractions(capturador);
        } finally {
            catalogo.cerrar();
        }
    }

    @Test
    void elHorarioDisparaLaActualizacionDeCatalogos() {
        var catalogo = mock(CatalogoEnVivo.class);
        try (var contexto = new AnnotationConfigApplicationContext()) {
            contexto.getEnvironment().getPropertySources().addFirst(new MapPropertySource("prueba", Map.of(
                    "abarrato.recoleccion.habilitada", "true",
                    "abarrato.recoleccion.cron", "*/1 * * * * *")));
            contexto.registerBean(CatalogoEnVivo.class, () -> catalogo);
            contexto.register(BotPrecios.class);
            contexto.refresh();

            verify(catalogo, timeout(4000).atLeastOnce()).actualizarProgramado(Comercio.TOTTUS);
            verify(catalogo, timeout(4000).atLeastOnce()).actualizarProgramado(Comercio.CUGAT);
        }
    }
}
