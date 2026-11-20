package cl.abarrato.precios;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EquivalenciaProductosTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Bebida Coca-Cola Original 1 L | Coca Cola Botella 1 Lt",
            "Bebida 7UP Pink Berry Pet 1.25 L | 7 Up Pink Berry 1,25 L",
            "Leche Loncoleche Entera 1 L | Leche Natural Entera Lonco Leche 1 L",
            "Arroz Tucapel Grado 1 1 kg | Arroz Grado 1 Tucapel Largo Ancho 1 kg",
            "Aceite Vegetal Belmont 900 ml | Aceite Belmont Vegetal Botella 900 cc",
            "Leche Soprole Semidescremada 1 L | Leche Semidescremada Soprole Caja 1 Lt",
            "Detergente Líquido Omo Matic 3 L | Omo Detergente Liquido Matic 3 lt",
            "Huevos Blancos Grandes 12 un | Huevo Blanco Grande 12 unidades",
            "Fideos Spaghetti 5 Carozzi 400 g | Spaghetti N°5 Carozzi 400 gr",
            "Coca-Cola Zero 1.5 L | Bebida Coca Cola Zero Desechable 1,5 L"
    })
    void uneElMismoProductoConNombresDistintos(String a, String b) {
        assertThat(EquivalenciaProductos.equivalentes(a, b)).isTrue();
        assertThat(EquivalenciaProductos.equivalentes(b, a)).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Coca-Cola 1.5 L | Coca-Cola Zero 1.5 L",
            "Leche Soprole Entera 1 L | Leche Soprole Descremada 1 L",
            "Leche Soprole Descremada 1 L | Leche Soprole Semidescremada 1 L",
            "Leche Colun 1 L | Leche Colun Chocolate 1 L",
            "Arroz Tucapel 1 kg | Arroz Miraflores 1 kg",
            "Arroz Tucapel Grado 1 1 kg | Arroz Tucapel Grado 2 1 kg",
            "Cerveza Cristal 350 cc | Cerveza Cristal Pack 6 x 350 cc",
            "Nescafé 170 g | Café Nescafé Gold 170 g",
            "Yogurt Soprole Frutilla 125 g | Yogurt Soprole Vainilla 125 g",
            "Leche Colun Sin Lactosa 1 L | Leche Colun 1 L",
            "Coca-Cola Retornable 2 L | Coca-Cola 2 L",
            "Aceite Belmont 900 ml | Aceite Belmont 1 L",
            "Pan Ideal Blanco 500 g | Pan Ideal Integral 500 g",
            "Leche 1 L | Leche Soprole 1 L"
    })
    void noMezclaVariantesNiMarcasDistintas(String a, String b) {
        assertThat(EquivalenciaProductos.equivalentes(a, b)).isFalse();
        assertThat(EquivalenciaProductos.equivalentes(b, a)).isFalse();
    }
}
