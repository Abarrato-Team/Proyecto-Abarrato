package cl.abarrato.precios;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "abarrato.recoleccion.habilitada", havingValue = "true")
public class BotPrecios {
    private static final Logger LOG = LoggerFactory.getLogger(BotPrecios.class);
    private final CatalogoEnVivo catalogo;

    public BotPrecios(CatalogoEnVivo catalogo) {
        this.catalogo = catalogo;
    }

    @Scheduled(cron = "${abarrato.recoleccion.cron}", zone = "America/Santiago")
    public void recolectar() {
        for (Comercio comercio : Comercio.values()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                LOG.info("{}: {} páginas con precios recientes en actualización programada",
                        comercio, catalogo.actualizarProgramado(comercio));
            } catch (RuntimeException ex) {
                LOG.error("{}: la captura no se guardó ({})", comercio, ex.getClass().getSimpleName());
            }
        }
    }

}
