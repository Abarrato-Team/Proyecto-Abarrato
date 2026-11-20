package com.abarrato.app;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Tipo de producto según su nombre. Se elige la palabra que aparece primero en el nombre, porque el
 * primer tipo nombrado suele ser el producto y los siguientes, ingredientes o sabores
 * ("Leche sabor chocolate" es leche; "Galletas de leche" son galletas). Si dos tipos empiezan en la
 * misma posición, gana el que está antes en esta lista.
 */
enum CategoriaProducto {
    YOGURES("Yogures", "yogur(?:t|es)?|yoghurt|yogumix|yoguitos?"),
    MANTEQUILLAS("Mantequillas y untables", "mantequillas?|margarinas?|mantecas?"),
    MERMELADAS("Mermeladas y dulces untables", "mermeladas?|manjar|dulce de leche|cremas? de avellana"),
    CEREALES("Cereales, avena y granola", "cereal(?:es)?|avenas?|granolas?|hojuelas|corn flakes"),
    BEBES("Bebés", "panales?|toallitas? humedas?|compotas?|papillas?|formula infantil|leche infantil"),
    LECHES("Leches y bebidas vegetales", "leches?|bebidas? vegetales?"),
    QUESOS("Quesos y quesillos", "quesos?|quesillos?|ricotta|mozzarella|gouda"),
    FIAMBRES("Fiambres y embutidos", "fiambres?|embutidos?|jamon(?:es)?|salames?|salchichas?|vienesas?|"
            + "longanizas?|chorizos?|mortadelas?|tocinos?|pate"),
    HUEVOS("Huevos", "huevos?(?! de chocolate)"),
    ARROZ("Arroz y legumbres", "arroz|legumbres?|lentejas?|garbanzos?|porotos?|quinoa|quinua"),
    HIGIENE("Cuidado personal", "shampoo|champu|acondicionadores?|desodorantes?|jabon(?:es)?|dentifricos?|"
            + "pastas? dental(?:es)?|pastas? de dientes|aguas? micelar(?:es)?|papel higienico|"
            + "toallas? higienicas?|panuelos?|cepillos? de dientes|enjuagues? bucal(?:es)?"),
    SALSAS("Salsas y aderezos", "salsas?|ketchup|mayonesas?|mostazas?|aderezos?|pasta de tomate|"
            + "pure de tomate|tomates? triturados?"),
    PASTAS("Pastas y fideos", "pastas?|fideos?|spaghett?i|espaguetis?|tallarines?|lasanas?|ravioles"),
    ACEITES("Aceites y vinagres", "aceites?|vinagres?"),
    ENDULZANTES("Azúcar y endulzantes", "azucar(?:es)?|endulzantes?|sucralosa|stevia|sacarina"),
    CAFE("Café, té e infusiones", "cafes?|tes?|infusiones?|hierbas? para infusion"),
    PANADERIA("Panadería", "pan(?:es)?|panaderia|hallullas?|marraquetas?|baguettes?|tostadas?|"
            + "tortillas?|galletones?|pan de molde"),
    CONSERVAS("Conservas y enlatados", "conservas?|enlatados?|atun|jurel|sardinas?|palmitos?|aceitunas?|"
            + "choclo en conserva|duraznos? en conserva"),
    HELADOS("Helados", "helados?|paletas? heladas?|gelato"),
    MASCOTAS("Mascotas", "mascotas?|alimentos? para (?:perros?|gatos?)|comida para (?:perros?|gatos?)|"
            + "snacks? para (?:perros?|gatos?)|arena sanitaria|arena para gatos?|perros?|gatos?"),
    CARNES("Carnes y pescados", "pollos?|pavos?|vacunos?|cerdos?|carnes?|pescados?|salmon|merluza|reineta|"
            + "tilapia|trutros?|pechugas?|filetillos?|lomos?|posta|asado|sobrecostilla|plateada|"
            + "abastero|tapapecho|osobuco|punta de ganso|filete|chuletas?|costillar|malaya|"
            + "hamburguesas?|alitas?|mariscos?|camarones?"),
    FRUTAS("Frutas y verduras", "frutas?|verduras?|manzanas?|platanos?|paltas?|tomates?|lechugas?|zanahorias?|"
            + "papas?(?! fritas)|cebollas?|ajos?|limon(?:es)?|naranjas?|mandarinas?|peras?|uvas?|frutillas?|"
            + "pepinos?|pimentones?|zapallos?|zapallitos?|brocolis?|coliflor(?:es)?|espinacas?|"
            + "choclos?|kiwis?|arandanos?|cilantro|perejil"),
    BEBIDAS("Bebidas y jugos", "bebidas?|jugos?|aguas?|gaseosas?|nectares?|nectar"),
    DULCES("Dulces y snacks", "galletas?|chocolates?|bombones?|snacks?|papas fritas|chips|caramelos?|"
            + "chicles?|gomitas?|alfajores?|barras? de cereal"),
    LIMPIEZA("Limpieza", "detergentes?|suavizantes?|cloro|limpiadores?|lavalozas?|desinfectantes?|esponjas?|"
            + "lavavajillas|limpia pisos|toallas? de papel|bolsas? de basura"),
    OTROS("Otros productos", null);

    final String nombre;
    private final Pattern indicador;

    CategoriaProducto(String nombre, String palabras) {
        this.nombre = nombre;
        indicador = palabras == null ? null : Pattern.compile("\\b(?:" + palabras + ")\\b");
    }

    // Adivina la categoría de un producto según palabras de su nombre.
    static CategoriaProducto identificar(String nombre) {
        String normalizado = Normalizer.normalize(nombre, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ");
        CategoriaProducto elegida = OTROS;
        int posicion = Integer.MAX_VALUE;
        for (CategoriaProducto categoria : values()) {
            if (categoria.indicador != null) {
                var coincidencia = categoria.indicador.matcher(normalizado);
                // El primer tipo nombrado suele ser el producto y los siguientes, ingredientes o sabores.
                if (coincidencia.find() && coincidencia.start() < posicion) {
                    posicion = coincidencia.start();
                    elegida = categoria;
                }
            }
        }
        return elegida;
    }
}
