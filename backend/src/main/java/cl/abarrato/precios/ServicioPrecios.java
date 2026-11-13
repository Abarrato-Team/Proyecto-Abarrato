package cl.abarrato.precios;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicioPrecios {
    private final RepositorioPrecios repositorio;

    public ServicioPrecios(RepositorioPrecios repositorio) {
        this.repositorio = repositorio;
    }

    @Transactional
    public void importar(List<ObservacionPrecio> observaciones) {
        repositorio.guardar(observaciones);
    }

    public Optional<ObservacionPrecio> buscarUltimo(Comercio comercio, String productoId, String ubicacion) {
        return repositorio.buscarUltimo(comercio, productoId, ubicacion);
    }

    public List<ObservacionPrecio> buscarCatalogo(String texto) {
        return repositorio.buscarCatalogo(texto);
    }
}
