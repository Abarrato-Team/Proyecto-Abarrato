# Estructuras de datos · síntesis de 4 libros recientes aplicada a Abarrato

> **Para usar con IA:** adjunta este archivo y pide: *"¿Qué estructura de datos de la tabla de decisión conviene para este problema? Justifica con la complejidad O(·) y muestra el cambio en Java."*

## Libros combinados

| Libro | Edición | Para qué sirve |
|---|---|---|
| Aditya Bhargava, *Grokking Algorithms*, 2.ª ed. (Manning) | Marzo de 2024 | Introducción visual: arreglos, listas, tablas hash, árboles (agrega BST, árboles balanceados y B-trees) y rendimiento en CPUs modernas |
| Daniel Zingaro, *Algorithmic Thinking*, 2.ª ed. (No Starch Press) | Enero de 2024 | Elegir la estructura correcta resolviendo problemas: hash, heaps, union-find, grafos, programación dinámica |
| Cormen, Leiserson, Rivest y Stein, *Introduction to Algorithms* (CLRS), 4.ª ed. (MIT Press) | 2022 | La referencia formal: análisis de complejidad, demostraciones y árboles balanceados |
| Martin Kleppmann y Chris Riccomini, *Designing Data-Intensive Applications*, 2.ª ed. (O'Reilly) | Marzo de 2026 | Estructuras dentro de las bases de datos: B-trees, LSM-trees, índices secundarios y de texto |

Lo que sigue es una síntesis propia de las ideas comunes de los cuatro libros; no reproduce su texto.

## 1. Idea central de los cuatro libros

1. **No hay una estructura "mejor"**: cada una optimiza una operación (buscar, insertar, recorrer en orden, sacar el mínimo) a costa de otra.
2. **Primero se pregunta qué operación es la frecuente**, y luego se elige.
3. **La notación O grande describe cómo crece el costo** con *n*, pero en máquinas reales la memoria caché importa: un arreglo contiguo suele ganarle a una lista enlazada aunque la O sea igual (Grokking 2.ª ed. agrega este punto).
4. **En disco y en bases de datos manda el B-tree**, porque cada nodo ocupa una página completa y reduce las lecturas (CLRS y DDIA).

## 2. Tabla de decisión

| Necesito… | Estructura | Buscar | Insertar | Orden | Java |
|---|---|---|---|---|---|
| Acceso por posición | Arreglo / lista dinámica | O(1) por índice | O(1) amortizado al final | Inserción | `ArrayList`, `int[]` |
| Saber si algo existe o buscar por clave | Tabla hash | O(1) promedio | O(1) promedio | Ninguno | `HashMap`, `HashSet` |
| Lo mismo, desde varios hilos | Tabla hash concurrente | O(1) promedio | O(1) promedio | Ninguno | `ConcurrentHashMap` |
| Recorrer por clave ordenada o buscar rangos | Árbol balanceado (rojo-negro) | O(log n) | O(log n) | Por clave | `TreeMap`, `TreeSet` |
| Sacar siempre el mínimo o el máximo | Heap / cola de prioridad | O(1) ver, O(log n) sacar | O(log n) | Parcial | `PriorityQueue` |
| Caché con expulsión del más antiguo (LRU) | Hash + lista doblemente enlazada | O(1) | O(1) | De uso | `LinkedHashMap(accessOrder=true)` |
| Cola FIFO o pila | Deque | O(1) en los extremos | O(1) | De llegada | `ArrayDeque` |
| Rutas y conexiones | Grafo (lista de adyacencia) | — | — | — | `Map<Nodo, List<Arista>>` |
| Índice en disco | B-tree / B+tree | O(log n) en páginas | O(log n) | Por clave | Índice de PostgreSQL |
| Búsqueda por fragmento de texto | Índice invertido de trigramas (GIN) | ~O(k) por coincidencia | Más costoso | — | `pg_trgm` |

## 3. Dónde las usa Abarrato (y por qué)

| Lugar | Estructura | Por qué es la correcta |
|---|---|---|
| `LimitePeticiones.clientes` | `ConcurrentHashMap<String, Ventana>` | Buscar el contador de una IP en O(1) desde muchos hilos del servidor a la vez |
| `Buscador.CACHE` (app) | `LinkedHashMap` con `removeEldestEntry` (LRU de 40) | Repetir una búsqueda reciente sin red; expulsa la más antigua en O(1) |
| `ServicioPrecios.fotos` | `Set.copyOf(claves)` | Elimina claves repetidas antes de ir a la base (lo verifica `CajaGrisTest`) |
| `EquivalenciaProductos` | `Set` de palabras y variantes | Comparar nombres de productos como conjuntos: el orden de las palabras no importa |
| `CatalogoEnVivo` | `EnumMap<Comercio, …>` y `LinkedHashMap` con orden de acceso | `EnumMap` es un arreglo indexado por el enum (lo más rápido para claves enum); el `LinkedHashMap` recuerda búsquedas en curso para no repetirlas |
| `RepositorioPrecios.buscarPreciosAnteriores` | `Map<ProductoTienda, Integer>` con clave `record` | Unir los precios de ayer con los de hoy en O(1) por producto; el `record` trae `equals` y `hashCode` correctos |
| PostgreSQL, PK y `observaciones_precio_ultima_por_ubicacion` | B-tree | Encontrar la última lectura de un producto sin leer toda la tabla |
| PostgreSQL, `observaciones_precio_nombre_trgm` | GIN de trigramas | `LIKE '%arroz%'` usa el índice en vez de recorrer todo |
| Plan de compra (`PlanCompra.elegir`) | **Máscara de bits** (`int`) para recorrer los 2ⁿ subconjuntos de tiendas | Con 8 cadenas son 256 combinaciones × productos: fuerza bruta exacta y rápida. Con 20 o más tiendas habría que pasar a un algoritmo voraz o a programación dinámica (Zingaro, CLRS) |
| Rutas a las tiendas | Grafo vial (en el servicio OSRM) | Dijkstra y variantes sobre un grafo de calles (Grokking, cap. 9) |

## 4. Errores comunes que los libros advierten (y cómo evitarlos en Java)

1. **Usar una lista para preguntar "¿existe?"**: `list.contains` es O(n); un `HashSet` es O(1).
2. **Claves mutables en un `HashMap`**: si cambia el `hashCode`, la entrada "se pierde". Hay que usar `record` o clases inmutables.
3. **`HashMap` compartido entre hilos**: se corrompe. Se usa `ConcurrentHashMap` o se sincroniza.
4. **Ordenar todo para sacar solo el mínimo**: se usa `PriorityQueue`.
5. **Guardar dinero en `double`**: no es una estructura, pero los cuatro libros insisten en elegir bien la representación. Se usan enteros en la unidad mínima (pesos).
6. **Olvidar el costo de memoria**: un cache sin límite es una fuga de memoria; por eso la LRU tiene 40 entradas y el limitador limpia las ventanas viejas.

## Fuentes

- Grokking Algorithms, 2.ª ed.: <https://www.oreilly.com/library/view/-/9781633438538>
- Algorithmic Thinking, 2.ª ed.: <https://www.oreilly.com/library/view/algorithmic-thinking/9781098168766/>
- Designing Data-Intensive Applications, 2.ª ed.: <https://oreilly.com/library/view/designing-data-intensive-applications/9781098119058/>
