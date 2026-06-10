package terminus.co.edu.ufps.analytics.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cliente HTTP hacia MS2 para reconstruir snapshots. Usa shared secret en header
 * (`X-Internal-Secret`) configurado en RestClientConfig.
 *
 * MS2 debe exponer el endpoint /api/supercopa/internal/torneos/{id}/snapshot
 * (a implementar en el día 2 del plan).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Ms2InternalClient {

    private final RestClient ms2InternalClient;

    /**
     * Devuelve la clasificación por grupo de un torneo, calculada por MS2.
     * Forma: { "A": [PosicionDTO...], "B": [...] }  ó  { "GLOBAL": [...] }
     */
    public Map<String, List<Map<String, Object>>> obtenerClasificacion(UUID torneoId) {
        try {
            return ms2InternalClient.get()
                    .uri("/api/supercopa/internal/torneos/{id}/clasificacion", torneoId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.error("Error pidiendo clasificación a MS2 (torneo={}): {}", torneoId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Devuelve goleadores agregados del torneo (HU33).
     * Lista de { cedula, jugadorNombre, equipoNombre, goles }.
     */
    public List<Map<String, Object>> obtenerGoleadores(UUID torneoId) {
        try {
            return ms2InternalClient.get()
                    .uri("/api/supercopa/internal/torneos/{id}/goleadores", torneoId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.error("Error pidiendo goleadores a MS2 (torneo={}): {}", torneoId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Devuelve agregados de portero por equipo.
     * Lista de { equipoTorneoId, equipoNombre, golesEnContra, partidosJugados }.
     */
    public List<Map<String, Object>> obtenerPorteros(UUID torneoId) {
        try {
            return ms2InternalClient.get()
                    .uri("/api/supercopa/internal/torneos/{id}/porteros", torneoId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.error("Error pidiendo porteros a MS2 (torneo={}): {}", torneoId, e.getMessage());
            return List.of();
        }
    }
}
