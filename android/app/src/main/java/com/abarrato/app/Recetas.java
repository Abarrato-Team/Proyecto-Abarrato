package com.abarrato.app;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "¿Qué puedo cocinar?": recetas caseras que se pueden preparar con lo que hay en la despensa.
 *
 * <p>Todo se calcula en el teléfono, sin enviar la despensa a ningún servidor (privacidad, ISO 27002 8.11).
 * La lógica no usa clases de Android, así que se prueba con JUnit en el computador (RecetasTest).
 */
final class Recetas {

    /** Un ingrediente con las palabras que lo reconocen en el nombre de un producto. */
    static final class Ingrediente {
        /** Nombre que ve el usuario, con tildes: "atún". */
        final String nombre;
        /** Texto que se busca en las tiendas si falta: "atún en lata". */
        final String busqueda;
        /** Palabras o frases (sin tildes, en singular) que delatan el ingrediente. */
        final String[] claves;
        /** Frases que descartan el producto aunque tenga una clave: "salsa" para el tomate fresco. */
        final String[] excepto;

        Ingrediente(String nombre, String busqueda, String[] claves, String... excepto) {
            this.nombre = nombre;
            this.busqueda = busqueda;
            this.claves = claves;
            this.excepto = excepto;
        }
    }

    // Una receta: tiempo, porciones, ingredientes obligatorios, opcionales y pasos.
    static final class Receta {
        final String icono;
        final String nombre;
        final int minutos;
        final int porciones;
        final Ingrediente[] ingredientes;
        final Ingrediente[] opcionales;
        final String[] pasos;

        Receta(String icono, String nombre, int minutos, int porciones, Ingrediente[] ingredientes,
                Ingrediente[] opcionales, String... pasos) {
            this.icono = icono;
            this.nombre = nombre;
            this.minutos = minutos;
            this.porciones = porciones;
            this.ingredientes = ingredientes;
            this.opcionales = opcionales;
            this.pasos = pasos;
        }
    }

    // Resultado de comparar una receta con la despensa.
    static final class Resultado {
        final Receta receta;
        final List<Ingrediente> tienes;
        final List<Ingrediente> faltan;
        final List<Ingrediente> opcionalesQueTienes;

        Resultado(Receta receta, List<Ingrediente> tienes, List<Ingrediente> faltan,
                List<Ingrediente> opcionalesQueTienes) {
            this.receta = receta;
            this.tienes = Collections.unmodifiableList(tienes);
            this.faltan = Collections.unmodifiableList(faltan);
            this.opcionalesQueTienes = Collections.unmodifiableList(opcionalesQueTienes);
        }

        boolean lista() {
            return faltan.isEmpty();
        }

        /** Porcentaje de ingredientes obligatorios que ya están en la despensa. */
        int avance() {
            int total = receta.ingredientes.length;
            return total == 0 ? 100 : Math.round(100f * tienes.size() / total);
        }

        /** Nombres de lo que falta, separados por coma: "atún, cebolla". */
        String faltantes() {
            List<String> nombres = new ArrayList<>();
            for (Ingrediente ingrediente : faltan) {
                nombres.add(ingrediente.nombre);
            }
            return String.join(", ", nombres);
        }
    }

    // ───────────── Ingredientes ─────────────

    // Ayuda para escribir listas de palabras clave.
    private static String[] claves(String... claves) {
        return claves;
    }

    static final Ingrediente ARROZ = new Ingrediente("arroz", "arroz", claves("arroz"));
    static final Ingrediente FIDEOS = new Ingrediente("fideos", "fideos", claves("fideo", "spaghetti", "espagueti",
            "tallarin", "pasta", "corbata", "espiral", "cabellito", "pluma", "rigati", "penne"),
            "pasta de tomate", "pasta de dientes", "pasta dental");
    static final Ingrediente HUEVOS = new Ingrediente("huevos", "huevos", claves("huevo"),
            "chocolate", "pascua");
    static final Ingrediente ACEITE = new Ingrediente("aceite", "aceite", claves("aceite"),
            "motor", "bebe", "corporal", "almendra", "masaje");
    static final Ingrediente ATUN = new Ingrediente("atún", "atún en lata", claves("atun"));
    static final Ingrediente SALSA_TOMATE = new Ingrediente("salsa de tomate", "salsa de tomate",
            claves("salsa de tomate", "pomarola", "pomodoro", "salsa italiana", "salsa napolitana",
                    "tomate triturado", "tomate en cubo", "pure de tomate", "pasta de tomate"));
    static final Ingrediente TOMATE = new Ingrediente("tomate", "tomate", claves("tomate"),
            "salsa", "pure", "pasta", "triturado", "ketchup", "cubo", "jugo", "deshidratado");
    static final Ingrediente POLLO = new Ingrediente("pollo", "pollo", claves("pollo", "pechuga", "trutro"),
            "caldo", "sopa", "consome", "sabor", "cubo");
    static final Ingrediente CARNE = new Ingrediente("carne", "carne molida", claves("carne molida",
            "vacuno", "posta", "asiento", "osobuco", "plateada", "sobrecostilla", "carne"),
            "soya", "caldo", "sabor", "perro", "gato", "mascota");
    static final Ingrediente PAN = new Ingrediente("pan", "pan", claves("pan", "hallulla", "marraqueta",
            "pan de molde", "dobladita", "baguette"), "rallado");
    static final Ingrediente LECHE = new Ingrediente("leche", "leche", claves("leche"),
            "condensada", "coco", "almendra", "avena", "corporal", "limpiadora", "crema");
    static final Ingrediente AVENA = new Ingrediente("avena", "avena", claves("avena"),
            "leche de avena", "bebida", "shampoo", "jabon");
    static final Ingrediente LENTEJAS = new Ingrediente("lentejas", "lentejas", claves("lenteja"));
    static final Ingrediente POROTOS = new Ingrediente("porotos", "porotos", claves("poroto"),
            "verde");
    static final Ingrediente GARBANZOS = new Ingrediente("garbanzos", "garbanzos", claves("garbanzo"));
    static final Ingrediente QUESO = new Ingrediente("queso", "queso", claves("queso", "quesillo", "gauda",
            "mantecoso", "chanco", "parmesano", "mozzarella"), "crema");
    static final Ingrediente PAPAS = new Ingrediente("papas", "papas", claves("papa"),
            "frita", "chip", "lays", "pringles", "papel");
    static final Ingrediente HARINA = new Ingrediente("harina", "harina", claves("harina"));
    static final Ingrediente AZUCAR = new Ingrediente("azúcar", "azúcar", claves("azucar"),
            "sin azucar", "0 azucar", "cero azucar", "bebida", "jugo", "galleta");
    static final Ingrediente CEBOLLA = new Ingrediente("cebolla", "cebolla", claves("cebolla", "cebollin"),
            "frita", "sopa", "polvo");
    static final Ingrediente ZAPALLO = new Ingrediente("zapallo", "zapallo", claves("zapallo"),
            "italiano", "crema");
    static final Ingrediente MANTEQUILLA = new Ingrediente("mantequilla", "mantequilla",
            claves("mantequilla", "margarina"), "galleta", "mani");
    static final Ingrediente PLATANO = new Ingrediente("plátano", "plátano", claves("platano", "banana"),
            "chip", "deshidratado", "jugo", "nectar");
    static final Ingrediente JAMON = new Ingrediente("jamón", "jamón", claves("jamon"));
    static final Ingrediente PALTA = new Ingrediente("palta", "palta", claves("palta"));
    static final Ingrediente CHOCLO = new Ingrediente("choclo", "choclo", claves("choclo", "maiz"),
            "pop", "cabrita", "harina", "aceite", "corn flake", "cereal");
    static final Ingrediente ARVEJAS = new Ingrediente("arvejas", "arvejas", claves("arveja"));

    // Ayuda para escribir listas de ingredientes.
    private static Ingrediente[] de(Ingrediente... ingredientes) {
        return ingredientes;
    }

    private static final Ingrediente[] NINGUNO = {};

    // ───────────── Recetas ─────────────

    static final Receta[] TODAS = {
            new Receta("🍚", "Arroz con huevo frito", 20, 2, de(ARROZ, HUEVOS, ACEITE), de(CEBOLLA),
                    "Sofríe el arroz 1 minuto en una cucharada de aceite.",
                    "Agrega el doble de agua caliente y sal; cocina tapado a fuego bajo unos 15 minutos.",
                    "Fríe los huevos en otra sartén con un poco de aceite.",
                    "Sirve el arroz con un huevo encima."),
            new Receta("🐟", "Arroz con atún", 20, 2, de(ARROZ, ATUN, ACEITE), de(CEBOLLA, CHOCLO),
                    "Prepara el arroz como de costumbre.",
                    "Si tienes cebolla, pícala y dórala en el aceite.",
                    "Escurre el atún y mézclalo con el arroz caliente.",
                    "Ajusta la sal y sirve."),
            new Receta("🍝", "Fideos con salsa de tomate", 15, 2, de(FIDEOS, SALSA_TOMATE), de(QUESO, CEBOLLA),
                    "Cuece los fideos en abundante agua con sal según el envase.",
                    "Calienta la salsa en una olla pequeña.",
                    "Cuela los fideos, mézclalos con la salsa y, si tienes, agrega queso rallado."),
            new Receta("🍝", "Fideos con queso", 15, 2, de(FIDEOS, QUESO), de(MANTEQUILLA),
                    "Cuece los fideos en agua con sal.",
                    "Cuélalos y, todavía calientes, agrega la mantequilla y el queso.",
                    "Revuelve hasta que el queso se derrita."),
            new Receta("🍗", "Pollo con arroz", 40, 3, de(POLLO, ARROZ, ACEITE), de(CEBOLLA, ARVEJAS),
                    "Dora el pollo en trozos con aceite y sal.",
                    "Agrega la cebolla picada, si tienes, y luego el arroz; revuelve 1 minuto.",
                    "Cubre con el doble de agua que de arroz y cocina tapado unos 20 minutos.",
                    "Revisa que el pollo esté bien cocido por dentro antes de servir."),
            new Receta("🍛", "Arroz con carne molida", 30, 3, de(CARNE, ARROZ, CEBOLLA), de(SALSA_TOMATE),
                    "Prepara el arroz.",
                    "Sofríe la cebolla picada y agrega la carne; cocina hasta que no quede rosada.",
                    "Si tienes salsa de tomate, agrégala y deja 5 minutos más.",
                    "Sirve la carne sobre el arroz."),
            new Receta("🥘", "Lentejas guisadas", 45, 4, de(LENTEJAS, CEBOLLA, ACEITE), de(ARROZ, ZAPALLO),
                    "Lava las lentejas y cuécelas en agua unos 30 minutos.",
                    "Sofríe la cebolla en aceite y agrégala a la olla.",
                    "Si tienes zapallo, agrégalo en cubos; cocina hasta que esté blando.",
                    "Sirve solas o con arroz."),
            new Receta("🥘", "Porotos con riendas", 60, 4, de(POROTOS, FIDEOS, ZAPALLO), de(CEBOLLA),
                    "Remoja los porotos la noche anterior (si son secos) y cuécelos hasta que estén blandos.",
                    "Agrega el zapallo en cubos y la cebolla sofrita.",
                    "Cuando el zapallo se deshaga, agrega los tallarines y cocina 10 minutos más."),
            new Receta("🥙", "Garbanzos salteados", 20, 2, de(GARBANZOS, ACEITE), de(CEBOLLA, TOMATE),
                    "Escurre los garbanzos (si son secos, cuécelos antes).",
                    "Saltéalos en aceite con cebolla y sal hasta que estén dorados.",
                    "Sirve tibios, con tomate picado si tienes."),
            new Receta("🍳", "Tortilla de papas", 30, 3, de(PAPAS, HUEVOS, ACEITE), de(CEBOLLA),
                    "Pela las papas y córtalas en láminas finas.",
                    "Fríelas a fuego medio en aceite hasta que estén blandas; escúrrelas.",
                    "Bate los huevos con sal y mézclalos con las papas.",
                    "Cuaja en la sartén por ambos lados."),
            new Receta("🥔", "Puré con huevo frito", 30, 2, de(PAPAS, LECHE, HUEVOS), de(MANTEQUILLA),
                    "Cuece las papas peladas en agua con sal hasta que estén blandas.",
                    "Muélelas con leche caliente y mantequilla.",
                    "Fríe los huevos y sírvelos sobre el puré."),
            new Receta("🥘", "Charquicán", 45, 4, de(CARNE, PAPAS, ZAPALLO, CEBOLLA), de(CHOCLO, ARVEJAS),
                    "Sofríe la cebolla y dora la carne.",
                    "Agrega las papas y el zapallo en cubos, con un poco de agua.",
                    "Cocina tapado hasta que estén blandos y muele a medias.",
                    "Agrega choclo y arvejas si tienes; sirve con un huevo frito encima, si quieres."),
            new Receta("🥗", "Ensalada chilena", 10, 2, de(TOMATE, CEBOLLA, ACEITE), NINGUNO,
                    "Corta la cebolla en pluma y déjala unos minutos en agua para quitarle el picor.",
                    "Pela y corta el tomate en gajos.",
                    "Mezcla con la cebolla escurrida, aceite y sal."),
            new Receta("🥪", "Sándwich de atún", 5, 1, de(PAN, ATUN), de(PALTA, TOMATE),
                    "Escurre el atún.",
                    "Abre el pan y agrega el atún, y palta o tomate si tienes."),
            new Receta("🧀", "Tostadas con queso", 5, 1, de(PAN, QUESO), de(JAMON, MANTEQUILLA),
                    "Tuesta el pan.",
                    "Agrega el queso, y el jamón si tienes, mientras el pan está caliente."),
            new Receta("🍳", "Huevos revueltos con pan", 10, 1, de(HUEVOS, PAN), de(TOMATE, QUESO),
                    "Bate los huevos con sal.",
                    "Cocínalos a fuego bajo revolviendo hasta que cuajen.",
                    "Sirve con pan tostado."),
            new Receta("🥣", "Avena con leche", 5, 1, de(AVENA, LECHE), de(PLATANO, AZUCAR),
                    "Calienta la leche y agrega la avena.",
                    "Cocina 3 minutos revolviendo.",
                    "Agrega plátano en rodajas o azúcar, si tienes."),
            new Receta("🥞", "Panqueques", 25, 3, de(HARINA, LECHE, HUEVOS), de(AZUCAR, MANTEQUILLA),
                    "Bate la harina con la leche y los huevos hasta que no queden grumos.",
                    "Unta una sartén con mantequilla o aceite.",
                    "Vierte una capa delgada de mezcla y dórala por ambos lados."),
            new Receta("🍰", "Queque casero", 60, 8, de(HARINA, AZUCAR, HUEVOS, LECHE, ACEITE), NINGUNO,
                    "Precalienta el horno a 180 °C y engrasa un molde.",
                    "Bate los huevos con el azúcar; agrega la leche y el aceite.",
                    "Incorpora la harina (si no es con polvos de hornear, agrega una cucharadita).",
                    "Hornea unos 40 minutos, hasta que un palito salga seco."),
    };

    private Recetas() {}

    // ───────────── Evaluación ─────────────

    /** Evalúa la despensa del usuario. Solo cuenta los productos con cantidad mayor que cero. */
    static List<Resultado> evaluar(List<Despensa.Item> despensa) {
        List<String> nombres = new ArrayList<>();
        for (Despensa.Item item : despensa) {
            if (item.cantidad > 0) {
                nombres.add(item.producto.nombre);
            }
        }
        return evaluarNombres(nombres);
    }

    /**
     * Ordena todas las recetas: primero las que se pueden preparar ya, luego las que tienen menos
     * faltantes y, a igual faltantes, las de mayor avance y menor tiempo.
     */
    static List<Resultado> evaluarNombres(Collection<String> productos) {
        List<Articulo> despensa = new ArrayList<>();
        for (String nombre : productos) {
            if (nombre != null && !nombre.trim().isEmpty()) {
                despensa.add(new Articulo(nombre));
            }
        }
        List<Resultado> resultados = new ArrayList<>();
        for (Receta receta : TODAS) {
            List<Ingrediente> tienes = new ArrayList<>();
            List<Ingrediente> faltan = new ArrayList<>();
            for (Ingrediente ingrediente : receta.ingredientes) {
                (hay(despensa, ingrediente) ? tienes : faltan).add(ingrediente);
            }
            List<Ingrediente> opcionales = new ArrayList<>();
            for (Ingrediente ingrediente : receta.opcionales) {
                if (hay(despensa, ingrediente)) {
                    opcionales.add(ingrediente);
                }
            }
            resultados.add(new Resultado(receta, tienes, faltan, opcionales));
        }
        resultados.sort(Comparator.comparingInt((Resultado r) -> r.faltan.size())
                .thenComparing(Comparator.comparingInt(Resultado::avance).reversed())
                .thenComparingInt(r -> r.receta.minutos));
        return resultados;
    }

    /** Cantidad de recetas que se pueden preparar con lo que hay. */
    static int listas(List<Resultado> resultados) {
        int cantidad = 0;
        for (Resultado resultado : resultados) {
            if (resultado.lista()) {
                cantidad++;
            }
        }
        return cantidad;
    }

    // ───────────── Reconocimiento de ingredientes ─────────────

    /** Nombre de un producto ya normalizado, con sus palabras en un Set para buscar en O(1). */
    private static final class Articulo {
        final String texto;
        final Set<String> palabras = new HashSet<>();

        Articulo(String nombre) {
            texto = " " + normalizar(nombre) + " ";
            for (String palabra : texto.trim().split(" ")) {
                if (!palabra.isEmpty()) {
                    palabras.add(palabra);
                }
            }
        }
    }

    // true si algún producto de la despensa corresponde al ingrediente.
    private static boolean hay(List<Articulo> despensa, Ingrediente ingrediente) {
        for (Articulo producto : despensa) {
            if (reconoce(producto, ingrediente)) {
                return true;
            }
        }
        return false;
    }

    /** true si el nombre del producto corresponde al ingrediente. Visible en el paquete para las pruebas. */
    static boolean reconoce(String nombreProducto, Ingrediente ingrediente) {
        return nombreProducto != null && reconoce(new Articulo(nombreProducto), ingrediente);
    }

    // Revisa excepciones, productos no comestibles y luego las palabras clave.
    private static boolean reconoce(Articulo producto, Ingrediente ingrediente) {
        for (String excepcion : ingrediente.excepto) {
            if (producto.texto.contains(" " + excepcion + " ") || producto.palabras.contains(excepcion)
                    || producto.palabras.contains(excepcion + "s") || producto.palabras.contains(excepcion + "es")) {
                return false;
            }
        }
        if (esNoComestible(producto)) {
            return false;
        }
        for (String clave : ingrediente.claves) {
            if (clave.indexOf(' ') >= 0) {
                // Frase: "salsa de tomate", "carne molida".
                if (producto.texto.contains(" " + clave + " ")) {
                    return true;
                }
            } else if (producto.palabras.contains(clave) || producto.palabras.contains(clave + "s")
                    || producto.palabras.contains(clave + "es")) {
                // Palabra completa en singular o plural: "huevo", "huevos", "tallarines". No "papaya".
                return true;
            }
        }
        return false;
    }

    /** Productos de la despensa que no son comida aunque compartan una palabra ("pasta dental"). */
    private static final String[] NO_COMESTIBLES = {"dental", "dientes", "detergente", "lavaloza", "shampoo",
            "champu", "jabon", "cloro", "suavizante", "panal", "toalla", "papel", "perro", "gato", "mascota",
            "insecticida", "desodorante", "crema corporal", "limpiador"};

    // true para productos de aseo, limpieza o mascotas.
    private static boolean esNoComestible(Articulo producto) {
        for (String palabra : NO_COMESTIBLES) {
            if (palabra.indexOf(' ') >= 0 ? producto.texto.contains(" " + palabra + " ")
                    : producto.palabras.contains(palabra) || producto.palabras.contains(palabra + "s")
                            || producto.palabras.contains(palabra + "es")) {
                return true;
            }
        }
        return false;
    }

    /** Sin tildes, en minúsculas, solo letras, números y espacios simples. */
    static String normalizar(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }
}
