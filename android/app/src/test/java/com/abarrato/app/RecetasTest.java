package com.abarrato.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Pruebas de "¿Qué puedo cocinar?". Corren en el computador, sin emulador:
 * {@code .\gradlew.bat :app:testDebugUnitTest}
 */
public class RecetasTest {

    private static Recetas.Resultado buscar(List<Recetas.Resultado> resultados, String nombre) {
        for (Recetas.Resultado resultado : resultados) {
            if (resultado.receta.nombre.equals(nombre)) {
                return resultado;
            }
        }
        throw new AssertionError("No existe la receta " + nombre);
    }

    // ───────────── Caja negra: despensa de entrada → recetas de salida ─────────────

    @Test
    public void conArrozHuevosYAceite_elArrozConHuevoEstaListoYVaPrimero() {
        List<Recetas.Resultado> resultados = Recetas.evaluarNombres(Arrays.asList(
                "Arroz grado 2 Tucapel 1 kg", "Huevos blancos 12 un", "Aceite maravilla Belmont 900 ml"));
        Recetas.Resultado arroz = buscar(resultados, "Arroz con huevo frito");
        assertTrue(arroz.lista());
        assertTrue(resultados.get(0).lista());
        assertEquals(100, arroz.avance());
    }

    @Test
    public void despensaVacia_ningunaRecetaEstaListaPeroSeMuestranTodas() {
        List<Recetas.Resultado> resultados = Recetas.evaluarNombres(Collections.<String>emptyList());
        assertEquals(Recetas.TODAS.length, resultados.size());
        assertEquals(0, Recetas.listas(resultados));
    }

    @Test
    public void indicaQueFaltaConNombresConTilde() {
        Recetas.Resultado sandwich = buscar(Recetas.evaluarNombres(Arrays.asList("Pan de molde blanco")),
                "Sándwich de atún");
        assertFalse(sandwich.lista());
        assertEquals("atún", sandwich.faltantes());
    }

    @Test
    public void ordenaPorMenosFaltantes() {
        List<Recetas.Resultado> resultados = Recetas.evaluarNombres(Arrays.asList("Fideos spaghetti 5 Carozzi"));
        for (int i = 1; i < resultados.size(); i++) {
            assertTrue(resultados.get(i - 1).faltan.size() <= resultados.get(i).faltan.size());
        }
    }

    @Test
    public void todasLasRecetasTienenPasosTiempoPorcionesYNombreUnico() {
        Set<String> nombres = new HashSet<>();
        for (Recetas.Receta receta : Recetas.TODAS) {
            assertTrue(receta.nombre, receta.pasos.length >= 2);
            assertTrue(receta.nombre, receta.minutos > 0 && receta.porciones > 0);
            assertTrue(receta.nombre, receta.ingredientes.length >= 2);
            assertTrue("Repetida: " + receta.nombre, nombres.add(receta.nombre));
        }
    }

    // ───────────── Caja blanca: ramas del reconocimiento de ingredientes ─────────────

    @Test
    public void reconocePluralesYTildes() {
        assertTrue(Recetas.reconoce("Huevos de campo", Recetas.HUEVOS));
        assertTrue(Recetas.reconoce("Tallarines N°5", Recetas.FIDEOS));
        assertTrue(Recetas.reconoce("ATÚN LOMITO EN AGUA", Recetas.ATUN));
        assertTrue(Recetas.reconoce("Papas", Recetas.PAPAS));
    }

    @Test
    public void noConfundePalabrasQueSoloEmpiezanIgual() {
        assertFalse(Recetas.reconoce("Papaya en conserva", Recetas.PAPAS));
        assertFalse(Recetas.reconoce("Panqueque congelado", Recetas.PAN));
        assertFalse(Recetas.reconoce("Lecheras de vidrio", Recetas.LECHE));
    }

    @Test
    public void respetaLasExcepcionesDeCadaIngrediente() {
        assertFalse(Recetas.reconoce("Salsa de tomate Pomarola", Recetas.TOMATE));
        assertTrue(Recetas.reconoce("Salsa de tomate Pomarola", Recetas.SALSA_TOMATE));
        assertFalse(Recetas.reconoce("Pasta de tomate Malloa", Recetas.FIDEOS));
        assertFalse(Recetas.reconoce("Leche condensada Nestlé", Recetas.LECHE));
        assertFalse(Recetas.reconoce("Pan rallado", Recetas.PAN));
        assertFalse(Recetas.reconoce("Caldo de pollo Maggi", Recetas.POLLO));
        assertFalse(Recetas.reconoce("Bebida sin azúcar", Recetas.AZUCAR));
    }

    @Test
    public void ignoraProductosQueNoSonComida() {
        assertFalse(Recetas.reconoce("Pasta dental Colgate", Recetas.FIDEOS));
        assertFalse(Recetas.reconoce("Alimento para perro sabor carne", Recetas.CARNE));
        assertFalse(Recetas.reconoce("Papel higiénico", Recetas.PAPAS));
    }

    @Test
    public void reconoceFrasesCompletas() {
        assertTrue(Recetas.reconoce("Carne molida 4% grasa", Recetas.CARNE));
        assertTrue(Recetas.reconoce("Pan de molde integral", Recetas.PAN));
    }

    @Test
    public void losOpcionalesNoBloqueanLaReceta() {
        Recetas.Resultado tostadas = buscar(Recetas.evaluarNombres(Arrays.asList("Marraqueta", "Queso gauda")),
                "Tostadas con queso");
        assertTrue(tostadas.lista());
        assertTrue(tostadas.opcionalesQueTienes.isEmpty());
    }

    @Test
    public void nombresVaciosONulosSeIgnoran() {
        List<Recetas.Resultado> resultados = Recetas.evaluarNombres(Arrays.asList("", "   ", null));
        assertEquals(0, Recetas.listas(resultados));
        assertFalse(Recetas.reconoce(null, Recetas.ARROZ));
    }
}
