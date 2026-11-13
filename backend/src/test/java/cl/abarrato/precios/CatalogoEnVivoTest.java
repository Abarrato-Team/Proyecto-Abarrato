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
