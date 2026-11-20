package com.abarrato.app;

/** Categorías y subcategorías de la pantalla Compra. Cada subcategoría es una búsqueda en vivo. */
final class Categorias {
    // Subcategoría: nombre, texto que se busca y tipos de producto que acepta.
    static final class Sub {
        final String nombre;
        final String consulta;
        /** Si no está vacío, solo se muestran productos de estos tipos (evita "alfajor de leche" en Leche). */
        final CategoriaProducto[] tipos;

        Sub(String nombre, String consulta, CategoriaProducto... tipos) {
            this.nombre = nombre;
            this.consulta = consulta;
            this.tipos = tipos;
        }

        // true si el producto pertenece a esta subcategoría.
        boolean incluye(Producto producto) {
            if (tipos.length == 0) {
                return true;
            }
            CategoriaProducto tipo = CategoriaProducto.identificar(producto.nombre);
            for (CategoriaProducto permitido : tipos) {
                if (permitido == tipo) {
                    return true;
                }
            }
            return false;
        }
    }

    // Categorías del explorador de la pestaña Compra, con sus subcategorías.
    enum Categoria {
        LACTEOS("🥛", "Lácteos y huevos",
                new Sub("Leche", "leche", CategoriaProducto.LECHES),
                new Sub("Yogur", "yogur", CategoriaProducto.YOGURES),
                new Sub("Queso", "queso", CategoriaProducto.QUESOS),
                new Sub("Mantequilla", "mantequilla", CategoriaProducto.MANTEQUILLAS),
                new Sub("Huevos", "huevos", CategoriaProducto.HUEVOS),
                new Sub("Crema", "crema de leche")),
        CARNES("🍗", "Carnes y pescados",
                new Sub("Pollo", "pollo", CategoriaProducto.CARNES),
                new Sub("Vacuno", "vacuno", CategoriaProducto.CARNES),
                new Sub("Cerdo", "cerdo", CategoriaProducto.CARNES),
                new Sub("Pescado", "pescado", CategoriaProducto.CARNES),
                new Sub("Vienesas", "vienesas", CategoriaProducto.FIAMBRES),
                new Sub("Jamón", "jamon", CategoriaProducto.FIAMBRES)),
        ARROZ("🍚", "Arroz y legumbres",
                new Sub("Arroz", "arroz", CategoriaProducto.ARROZ),
                new Sub("Porotos", "porotos", CategoriaProducto.ARROZ),
                new Sub("Lentejas", "lentejas", CategoriaProducto.ARROZ),
                new Sub("Garbanzos", "garbanzos", CategoriaProducto.ARROZ)),
        PASTAS("🍝", "Pastas y salsas",
                new Sub("Fideos", "fideos", CategoriaProducto.PASTAS),
                new Sub("Spaghetti", "spaghetti", CategoriaProducto.PASTAS),
                new Sub("Salsa de tomate", "salsa tomate", CategoriaProducto.SALSAS)),
        ACEITES("🫗", "Aceites y aliños",
                new Sub("Aceite", "aceite", CategoriaProducto.ACEITES),
                new Sub("Vinagre", "vinagre", CategoriaProducto.ACEITES),
                new Sub("Mayonesa", "mayonesa", CategoriaProducto.SALSAS),
                new Sub("Ketchup", "ketchup", CategoriaProducto.SALSAS)),
        CONSERVAS("🥫", "Conservas",
                new Sub("Atún", "atun", CategoriaProducto.CONSERVAS),
                new Sub("Jurel", "jurel", CategoriaProducto.CONSERVAS),
                new Sub("Choclo", "choclo"),
                new Sub("Arvejas", "arvejas"),
                new Sub("Duraznos", "duraznos")),
        PANADERIA("🍞", "Panadería",
                new Sub("Pan de molde", "pan molde", CategoriaProducto.PANADERIA),
                new Sub("Tortillas", "tortillas", CategoriaProducto.PANADERIA),
                new Sub("Galletones", "galletones", CategoriaProducto.PANADERIA)),
        DESAYUNO("☕", "Desayuno",
                new Sub("Café", "cafe", CategoriaProducto.CAFE),
                new Sub("Té", "te", CategoriaProducto.CAFE),
                new Sub("Cereales", "cereal", CategoriaProducto.CEREALES),
                new Sub("Avena", "avena", CategoriaProducto.CEREALES),
                new Sub("Mermelada", "mermelada", CategoriaProducto.MERMELADAS),
                new Sub("Azúcar", "azucar", CategoriaProducto.ENDULZANTES)),
        FRUTAS("🍅", "Frutas y verduras",
                new Sub("Tomate", "tomate", CategoriaProducto.FRUTAS),
                new Sub("Papas", "papas", CategoriaProducto.FRUTAS),
                new Sub("Cebolla", "cebolla", CategoriaProducto.FRUTAS),
                new Sub("Plátano", "platano", CategoriaProducto.FRUTAS),
                new Sub("Manzana", "manzana", CategoriaProducto.FRUTAS),
                new Sub("Lechuga", "lechuga", CategoriaProducto.FRUTAS)),
        BEBIDAS("🧃", "Bebidas y jugos",
                new Sub("Bebidas", "bebida", CategoriaProducto.BEBIDAS),
                new Sub("Jugos", "jugo", CategoriaProducto.BEBIDAS),
                new Sub("Agua mineral", "agua mineral", CategoriaProducto.BEBIDAS),
                new Sub("Néctar", "nectar", CategoriaProducto.BEBIDAS)),
        SNACKS("🍪", "Snacks y dulces",
                new Sub("Galletas", "galletas", CategoriaProducto.DULCES),
                new Sub("Chocolate", "chocolate", CategoriaProducto.DULCES),
                new Sub("Papas fritas", "papas fritas", CategoriaProducto.DULCES)),
        CONGELADOS("🧊", "Congelados",
                new Sub("Helados", "helado", CategoriaProducto.HELADOS),
                new Sub("Hamburguesas", "hamburguesa", CategoriaProducto.CARNES),
                new Sub("Verduras", "congeladas")),
        LIMPIEZA("🧽", "Limpieza",
                new Sub("Detergente", "detergente", CategoriaProducto.LIMPIEZA),
                new Sub("Lavalozas", "lavaloza", CategoriaProducto.LIMPIEZA),
                new Sub("Cloro", "cloro", CategoriaProducto.LIMPIEZA),
                new Sub("Suavizante", "suavizante", CategoriaProducto.LIMPIEZA)),
        ASEO("🧻", "Aseo personal",
                new Sub("Papel higiénico", "papel higienico", CategoriaProducto.HIGIENE),
                new Sub("Shampoo", "shampoo", CategoriaProducto.HIGIENE),
                new Sub("Jabón", "jabon", CategoriaProducto.HIGIENE),
                new Sub("Pasta dental", "pasta dental", CategoriaProducto.HIGIENE),
                new Sub("Desodorante", "desodorante", CategoriaProducto.HIGIENE)),
        MASCOTAS("🐾", "Mascotas",
                new Sub("Perros", "alimento perro", CategoriaProducto.MASCOTAS),
                new Sub("Gatos", "alimento gato", CategoriaProducto.MASCOTAS),
                new Sub("Arena", "arena gato", CategoriaProducto.MASCOTAS)),
        BEBES("🍼", "Bebés",
                new Sub("Pañales", "pañales", CategoriaProducto.BEBES),
                new Sub("Toallitas", "toallitas humedas", CategoriaProducto.BEBES),
                new Sub("Compotas", "compota", CategoriaProducto.BEBES));

        final String icono;
        final String nombre;
        final Sub[] subs;

        Categoria(String icono, String nombre, Sub... subs) {
            this.icono = icono;
            this.nombre = nombre;
            this.subs = subs;
        }
    }

    // Orden de los resultados que el usuario puede elegir.
    enum Orden { RELEVANCIA, MENOR_PRECIO }

    private Categorias() {}
}
