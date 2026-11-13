package cl.abarrato.precios;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty("abarrato.importar")
public class ImportadorTexto implements ApplicationRunner {
    private static final String CABECERA =
            "comercio\tproducto_id\tubicacion\tnombre\tprecio_texto\turl_fuente\tobservado_en";
    private static final String CABECERA_CON_GTIN = CABECERA + "\tcodigo_barras";
    private final ServicioPrecios servicio;

    public ImportadorTexto(ServicioPrecios servicio) {
        this.servicio = servicio;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> archivos = args.getOptionValues("abarrato.importar");
        if (archivos == null || archivos.size() != 1) {
            throw new IllegalArgumentException("Indica un archivo con --abarrato.importar=archivo.tsv");
        }
        Path archivo = Path.of(archivos.get(0));
        if (Files.size(archivo) > 5 * 1024 * 1024) {
            throw new IllegalArgumentException("El archivo supera 5 MiB");
        }
        servicio.importar(leer(Files.readAllLines(archivo, StandardCharsets.UTF_8)));
    }

    static List<ObservacionPrecio> leer(List<String> lineas) {
        if (lineas.size() < 2) {
            throw new IllegalArgumentException("Se requiere la cabecera TSV y al menos una observación");
        }
        String cabecera = lineas.get(0).replaceFirst("^\\uFEFF", "");
        boolean incluyeGtin = CABECERA_CON_GTIN.equals(cabecera);
        if (!incluyeGtin && !CABECERA.equals(cabecera)) {
            throw new IllegalArgumentException("Cabecera TSV inválida");
        }
        return IntStream.range(1, lineas.size()).mapToObj(indice -> {
            try {
                return interpretar(lineas.get(indice), incluyeGtin);
            } catch (IllegalArgumentException | DateTimeParseException ex) {
                throw new IllegalArgumentException("Observación inválida en línea " + (indice + 1));
            }
        }).toList();
    }

    private static ObservacionPrecio interpretar(String linea, boolean incluyeGtin) {
        String[] campos = linea.split("\t", -1);
        if (campos.length != (incluyeGtin ? 8 : 7) || campos[1].isBlank() || campos[1].length() > 120
                || campos[2].isBlank() || campos[2].length() > 120 || campos[3].isBlank()) {
            throw new IllegalArgumentException("Campos inválidos");
        }
        Comercio comercio = Comercio.valueOf(campos[0]);
        URI url = URI.create(campos[5]);
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getUserInfo() != null
                || !comercio.aceptaDominio(url.getHost())) {
            throw new IllegalArgumentException("URL ajena al comercio");
        }
        int clp = PrecioClp.normalizar(campos[4]);
        String clave = null;
        if (incluyeGtin && !campos[7].isBlank()) {
            clave = IdentidadProducto.codigoBarras(campos[7].strip());
            if (clave == null) {
                throw new IllegalArgumentException("Código de barras inválido");
            }
        }
        return new ObservacionPrecio(comercio, campos[1], campos[2], campos[3],
                campos[4], clp, campos[5], OffsetDateTime.parse(campos[6]), clave);
    }
}
