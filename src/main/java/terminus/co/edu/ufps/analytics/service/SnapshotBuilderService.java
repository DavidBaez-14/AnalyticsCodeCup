package terminus.co.edu.ufps.analytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import terminus.co.edu.ufps.analytics.client.Ms2InternalClient;
import terminus.co.edu.ufps.analytics.model.SnapshotGoleador;
import terminus.co.edu.ufps.analytics.model.SnapshotPortero;
import terminus.co.edu.ufps.analytics.model.SnapshotPosicion;
import terminus.co.edu.ufps.analytics.repository.SnapshotGoleadorRepository;
import terminus.co.edu.ufps.analytics.repository.SnapshotPorteroRepository;
import terminus.co.edu.ufps.analytics.repository.SnapshotPosicionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Recalcula los snapshots de un torneo pidiendo datos frescos a MS2.
 * Es idempotente: borra los snapshots viejos del torneo y mete los nuevos.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotBuilderService {

    private final Ms2InternalClient ms2;
    private final SnapshotPosicionRepository posicionRepo;
    private final SnapshotGoleadorRepository goleadorRepo;
    private final SnapshotPorteroRepository porteroRepo;

    @Async
    @Transactional
    public void reconstruirAsync(UUID torneoId) {
        reconstruir(torneoId);
    }

    @Transactional
    public void reconstruir(UUID torneoId) {
        log.info("Reconstruyendo snapshots del torneo {}", torneoId);
        reconstruirPosiciones(torneoId);
        reconstruirGoleadores(torneoId);
        reconstruirPorteros(torneoId);
        log.info("Snapshots torneo {} OK", torneoId);
    }

    private void reconstruirPosiciones(UUID torneoId) {
        Map<String, List<Map<String, Object>>> data = ms2.obtenerClasificacion(torneoId);
        if (data == null || data.isEmpty()) {
            log.warn("Clasificación vacía o nula para torneo {}", torneoId);
            return;
        }
        posicionRepo.deleteByTorneoId(torneoId);
        for (var entry : data.entrySet()) {
            String grupo = entry.getKey();
            for (Map<String, Object> row : entry.getValue()) {
                posicionRepo.save(SnapshotPosicion.builder()
                        .torneoId(torneoId)
                        .grupo(grupo)
                        .equipoTorneoId(UUID.fromString((String) row.get("equipoTorneoId")))
                        .equipoNombre(asString(row.get("equipoNombre")))
                        .posicion(asInt(row.get("posicion")))
                        .pts(asInt(row.get("pts")))
                        .pj(asInt(row.get("pj")))
                        .pg(asInt(row.get("pg")))
                        .pe(asInt(row.get("pe")))
                        .pp(asInt(row.get("pp")))
                        .gf(asInt(row.get("gf")))
                        .gc(asInt(row.get("gc")))
                        .dg(asInt(row.get("dg")))
                        .rojas(asInt(row.get("rojas")))
                        .form(serializeForm(row.get("form")))
                        .descalificado(Boolean.TRUE.equals(row.get("descalificado")))
                        .build());
            }
        }
    }

    private void reconstruirGoleadores(UUID torneoId) {
        List<Map<String, Object>> data = ms2.obtenerGoleadores(torneoId);
        goleadorRepo.deleteByTorneoId(torneoId);
        int pos = 1;
        for (Map<String, Object> row : data) {
            goleadorRepo.save(SnapshotGoleador.builder()
                    .torneoId(torneoId)
                    .cedula(asString(row.get("cedula")))
                    .jugadorNombre(asString(row.get("jugadorNombre")))
                    .equipoNombre(asString(row.get("equipoNombre")))
                    .goles(asInt(row.get("goles")))
                    .posicion(pos++)
                    .build());
        }
    }

    private void reconstruirPorteros(UUID torneoId) {
        List<Map<String, Object>> data = ms2.obtenerPorteros(torneoId);
        porteroRepo.deleteByTorneoId(torneoId);
        int pos = 1;
        for (Map<String, Object> row : data) {
            int gc = asInt(row.get("golesEnContra"));
            int pj = asInt(row.get("partidosJugados"));
            BigDecimal promedio = pj == 0
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf((double) gc / pj).setScale(2, RoundingMode.HALF_UP);
            porteroRepo.save(SnapshotPortero.builder()
                    .torneoId(torneoId)
                    .equipoTorneoId(UUID.fromString((String) row.get("equipoTorneoId")))
                    .equipoNombre(asString(row.get("equipoNombre")))
                    .golesEnContra(gc)
                    .partidosJugados(pj)
                    .promedioGc(promedio)
                    .posicion(pos++)
                    .build());
        }
    }

    private String serializeForm(Object form) {
        if (form instanceof List<?> list) return String.join(",", list.stream().map(String::valueOf).toList());
        return null;
    }

    private int asInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(o.toString());
    }

    private String asString(Object o) { return o == null ? null : o.toString(); }
}
