package terminus.co.edu.ufps.analytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import terminus.co.edu.ufps.analytics.client.Ms2InternalClient;
import terminus.co.edu.ufps.analytics.model.SnapshotGoleador;
import terminus.co.edu.ufps.analytics.model.SnapshotPortero;
import terminus.co.edu.ufps.analytics.repository.SnapshotGoleadorRepository;
import terminus.co.edu.ufps.analytics.repository.SnapshotPorteroRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TorneoCerradoService {

    private final Ms2InternalClient ms2Client;
    private final SnapshotGoleadorRepository goleadorRepo;
    private final SnapshotPorteroRepository porteroRepo;

    @Async
    public void procesarCierre(UUID torneoId) {
        log.info("Procesando cierre de torneo {} para premios automáticos", torneoId);

        List<Map<String, Object>> premios = ms2Client.obtenerPremios(torneoId);
        if (premios.isEmpty()) {
            log.warn("No se encontraron premios para torneo {}; se omite asignación", torneoId);
            return;
        }

        for (Map<String, Object> premio : premios) {
            String codigo = asString(premio.get("codigoCatalogo"));
            UUID torneoPremioId = UUID.fromString(asString(premio.get("id")));
            if (torneoPremioId == null) continue;

            try {
                switch (codigo) {
                    case "GOLEADOR" -> asignarGoleador(torneoId, torneoPremioId);
                    case "PORTERO" -> asignarPortero(torneoId, torneoPremioId);
                    case "CAMPEON", "SUBCAMPEON", "TERCERO" ->
                            asignarPodio(torneoId, torneoPremioId, codigo);
                    default -> log.debug("Premio {} se omite (asignación manual o no automática)", codigo);
                }
            } catch (Exception e) {
                log.error("Error asignando premio {} para torneo {}: {}", codigo, torneoId, e.getMessage());
            }
        }

        log.info("Cierre de torneo {} procesado", torneoId);
    }

    private void asignarGoleador(UUID torneoId, UUID torneoPremioId) {
        List<SnapshotGoleador> top = goleadorRepo.findByTorneoIdOrderByGolesDescJugadorNombreAsc(
                torneoId, org.springframework.data.domain.PageRequest.of(0, 1));
        if (top.isEmpty()) {
            log.warn("No hay goleadores para torneo {}", torneoId);
            return;
        }
        SnapshotGoleador g = top.get(0);
        ms2Client.asignarPremio(torneoId, torneoPremioId, Map.of("cedula", g.getCedula()));
        log.info("Goleador asignado: {} ({} goles)", g.getCedula(), g.getGoles());
    }

    private void asignarPortero(UUID torneoId, UUID torneoPremioId) {
        List<SnapshotPortero> top = porteroRepo.findByTorneoIdOrderByPromedioGcAscEquipoNombreAsc(
                torneoId, org.springframework.data.domain.PageRequest.of(0, 1));
        if (top.isEmpty()) {
            log.warn("No hay porteros para torneo {}", torneoId);
            return;
        }
        SnapshotPortero p = top.get(0);
        ms2Client.asignarPremio(torneoId, torneoPremioId,
                Map.of("equipoTorneoId", p.getEquipoTorneoId().toString()));
        log.info("Portero asignado: equipo {} (promedio {})", p.getEquipoNombre(), p.getPromedioGc());
    }

    private void asignarPodio(UUID torneoId, UUID torneoPremioId, String codigo) {
        List<Map<String, Object>> partidos = ms2Client.obtenerPartidos(torneoId);
        if (partidos.isEmpty()) {
            log.warn("No hay partidos para determinar podio del torneo {}", torneoId);
            return;
        }

        String faseFinal = switch (codigo) {
            case "CAMPEON" -> "FINAL";
            case "SUBCAMPEON" -> "FINAL";
            case "TERCERO" -> "TERCER_PUESTO";
            default -> null;
        };
        if (faseFinal == null) return;

        boolean buscarPerdedor = "SUBCAMPEON".equals(codigo);

        for (Map<String, Object> p : partidos) {
            if (!faseFinal.equals(asString(p.get("fase")))) continue;
            String estado = asString(p.get("estado"));
            if (!"FINALIZADO".equals(estado) && !"WO".equals(estado)) continue;

            int golesLocal = asInt(p.get("golesLocal"));
            int golesVisitante = asInt(p.get("golesVisitante"));
            String equipoGanador, equipoPerdedor;

            if (golesLocal > golesVisitante) {
                equipoGanador = asString(p.get("equipoLocalNombre"));
                equipoPerdedor = asString(p.get("equipoVisitanteNombre"));
            } else if (golesVisitante > golesLocal) {
                equipoGanador = asString(p.get("equipoVisitanteNombre"));
                equipoPerdedor = asString(p.get("equipoLocalNombre"));
            } else {
                continue;
            }

            String equipoAsignar = buscarPerdedor ? equipoPerdedor : equipoGanador;
            if (equipoAsignar == null) continue;

            String equipoTorneoIdStr = buscarPerdedor
                    ? asString(p.get("equipoVisitanteTorneoId"))
                    : asString(p.get("equipoLocalTorneoId"));

            Map<String, Object> body = equipoTorneoIdStr != null
                    ? Map.of("equipoTorneoId", equipoTorneoIdStr)
                    : Map.of();
            if (!body.isEmpty()) {
                ms2Client.asignarPremio(torneoId, torneoPremioId, body);
                log.info("{} asignado: {}", codigo, equipoAsignar);
            }
            return;
        }

        log.warn("No se encontró partido de {} para torneo {}", faseFinal, torneoId);
    }

    private static int asInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(o.toString());
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
