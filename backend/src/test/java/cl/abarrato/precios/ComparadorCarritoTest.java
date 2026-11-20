package cl.abarrato.precios;

import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.IntNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ComparadorCarritoTest {
    private static final String LECHE = IdentidadProducto.SOPROLE_ENTERA_1_L;
    private static final String OTRO = "cencosud:42";

    @Test
    void calculaAhorroSoloEntreCarritosCompletosDeLasSucursalesSolicitadas() {
        RepositorioPrecios repositorio = mock(RepositorioPrecios.class);
        CatalogoEnVivo catalogo = mock(CatalogoEnVivo.class);
        OffsetDateTime ahora = OffsetDateTime.now();
        when(repositorio.buscarComparables(eq(Comercio.JUMBO), eq("local-a"), anySet(), any()))
                .thenReturn(List.of(precio(Comercio.JUMBO, "local-a", LECHE, 1200, ahora),
                        precio(Comercio.JUMBO, "local-a", OTRO, 800, ahora)));
        when(repositorio.buscarComparables(eq(Comercio.SANTA_ISABEL), eq("local-b"), anySet(), any()))
                .thenReturn(List.of(precio(Comercio.SANTA_ISABEL, "local-b", LECHE, 1300, ahora),
                        precio(Comercio.SANTA_ISABEL, "local-b", OTRO, 1000, ahora)));
        when(repositorio.buscarComparables(eq(Comercio.JUMBO), eq("local-c"), anySet(), any()))
                .thenReturn(List.of(precio(Comercio.JUMBO, "local-c", LECHE, 1000, ahora.minusDays(2))));
        ComparadorCarrito comparador = new ComparadorCarrito(catalogo, repositorio, "PT15M", "PT24H");

        var resultado = comparador.comparar(new ComparadorCarrito.Solicitud(
                List.of(new ComparadorCarrito.Item(LECHE, IntNode.valueOf(2)),
                        new ComparadorCarrito.Item(OTRO, IntNode.valueOf(1))),
                List.of(new ComparadorCarrito.Sucursal(Comercio.JUMBO, "local-c"),
                        new ComparadorCarrito.Sucursal(Comercio.SANTA_ISABEL, "local-b"),
                        new ComparadorCarrito.Sucursal(Comercio.JUMBO, "local-a"))));

        assertEquals("SUCURSAL", resultado.alcance());
        assertEquals(3200L, resultado.opciones().get(0).totalClp());
        assertEquals(400L, resultado.opciones().get(0).ahorroClp());
        assertEquals(3600L, resultado.opciones().get(1).totalClp());
        assertNull(resultado.opciones().get(2).totalClp());
        assertNull(resultado.opciones().get(2).ahorroClp());
        assertEquals("VENCIDO", resultado.opciones().get(2).productos().get(0).estado());
        verifyNoInteractions(catalogo);
    }

    @Test
    void rechazaCantidadesFraccionariasYUbicacionWebComoSucursal() {
        RepositorioPrecios repositorio = mock(RepositorioPrecios.class);
        CatalogoEnVivo catalogo = mock(CatalogoEnVivo.class);
        ComparadorCarrito comparador = new ComparadorCarrito(catalogo, repositorio, "PT15M", "PT24H");

        assertThrows(IllegalArgumentException.class, () -> comparador.comparar(new ComparadorCarrito.Solicitud(
                List.of(new ComparadorCarrito.Item(LECHE, DecimalNode.valueOf(new BigDecimal("1.5")))), null)));
        assertThrows(IllegalArgumentException.class, () -> comparador.comparar(new ComparadorCarrito.Solicitud(
                List.of(new ComparadorCarrito.Item(LECHE, IntNode.valueOf(1))),
                List.of(new ComparadorCarrito.Sucursal(Comercio.JUMBO, ComparadorCarrito.WEB)))));
        verifyNoInteractions(catalogo, repositorio);
    }

    @Test
    void cotizaLaOfertaElegidaDeCadaTiendaAunqueOtraFichaTengaLaMismaClave() {
        RepositorioPrecios repositorio = mock(RepositorioPrecios.class);
        CatalogoEnVivo catalogo = mock(CatalogoEnVivo.class);
        OffsetDateTime ahora = OffsetDateTime.now();
        String web = ComparadorCarrito.WEB;
        when(repositorio.buscarComparables(eq(Comercio.JUMBO), eq(web), anySet(), any()))
                .thenReturn(List.of(oferta(Comercio.JUMBO, "alternativa", 990, ahora),
                        oferta(Comercio.JUMBO, "elegida", 1200, ahora)));
        when(repositorio.buscarComparables(eq(Comercio.SANTA_ISABEL), eq(web), anySet(), any()))
                .thenReturn(List.of(oferta(Comercio.SANTA_ISABEL, "sisa", 1300, ahora)));
        var comparador = new ComparadorCarrito(catalogo, repositorio, "PT15M", "PT24H");

        var resultado = comparador.comparar(new ComparadorCarrito.Solicitud(List.of(
                new ComparadorCarrito.Item(LECHE, IntNode.valueOf(2), null, Comercio.JUMBO, "elegida", null, null),
                new ComparadorCarrito.Item(LECHE, IntNode.valueOf(1), null, Comercio.SANTA_ISABEL, "sisa", null, null)
        ), null));

        assertEquals(3700L, resultado.compraElegida().totalClp());
        assertEquals(2400L, resultado.compraElegida().tiendas().get(0).totalClp());
        assertEquals(1300L, resultado.compraElegida().tiendas().get(1).totalClp());
        assertEquals(2970L, resultado.opciones().get(0).totalClp());
    }

    private ObservacionPrecio oferta(Comercio comercio, String sku, int monto, OffsetDateTime ahora) {
        return new ObservacionPrecio(comercio, sku, ComparadorCarrito.WEB, "Leche", "$" + monto, monto,
                "https://example.test/", ahora, LECHE);
    }

    private ObservacionPrecio precio(Comercio comercio, String local, String clave,
            int monto, OffsetDateTime observadoEn) {
        return new ObservacionPrecio(comercio, clave, local, "Producto de prueba", "$" + monto, monto,
                "https://example.test/", observadoEn, clave);
    }
}
