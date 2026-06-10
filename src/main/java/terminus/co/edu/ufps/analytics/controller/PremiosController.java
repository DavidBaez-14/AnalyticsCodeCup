package terminus.co.edu.ufps.analytics.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import terminus.co.edu.ufps.analytics.client.Ms2InternalClient;
import terminus.co.edu.ufps.analytics.dto.PremioDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/analytics/torneos")
@RequiredArgsConstructor
public class PremiosController {

    private final Ms2InternalClient ms2Client;

    @GetMapping("/{torneoId}/premios")
    public ResponseEntity<List<PremioDTO>> premios(@PathVariable UUID torneoId) {
        List<Map<String, Object>> raw = ms2Client.obtenerPremios(torneoId);
        List<PremioDTO> result = new ArrayList<>();
        int orden = 1;
        for (Map<String, Object> p : raw) {
            result.add(PremioDTO.builder()
                    .categoria(asString(p.get("nombreCatalogo")))
                    .alcance(asString(p.get("codigoCatalogo")))
                    .nombre(asString(p.get("titulo")))
                    .monto(asInt(p.get("monto")))
                    .orden(orden++)
                    .ganador(buildGanador(p))
                    .build());
        }
        return ResponseEntity.ok(result);
    }

    private PremioDTO.GanadorDTO buildGanador(Map<String, Object> p) {
        String cedula = asString(p.get("ganadorCedula"));
        if (cedula == null) return null;
        return PremioDTO.GanadorDTO.builder()
                .cedula(cedula)
                .nombre(null)
                .equipoNombre(asString(p.get("ganadorEquipoNombre")))
                .build();
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
