package terminus.co.edu.ufps.analytics.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import terminus.co.edu.ufps.analytics.dto.WebhookPayload;
import terminus.co.edu.ufps.analytics.model.WebhookLog;
import terminus.co.edu.ufps.analytics.repository.WebhookLogRepository;
import terminus.co.edu.ufps.analytics.service.SnapshotBuilderService;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class WebhookController {

    private final SnapshotBuilderService snapshotBuilder;
    private final WebhookLogRepository webhookLogRepo;
    private final ObjectMapper objectMapper;

    @Value("${analytics.webhook.secret}")
    private String expectedSecret;

    @PostMapping("/webhooks/partido-cerrado")
    public ResponseEntity<Void> partidoCerrado(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String secret,
            @RequestBody WebhookPayload payload) {
        if (expectedSecret == null || !expectedSecret.equals(secret)) {
            log.warn("Webhook recibido con secret inválido");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (payload == null || payload.getTorneoId() == null) {
            return ResponseEntity.badRequest().build();
        }
        WebhookLog logEntry = WebhookLog.builder()
                .tipo("partido-cerrado")
                .torneoId(payload.getTorneoId())
                .partidoId(payload.getPartidoId())
                .build();
        try {
            logEntry.setPayloadJson(objectMapper.writeValueAsString(payload));
        } catch (Exception ignore) {}
        webhookLogRepo.save(logEntry);

        // Reconstrucción asincrónica: respondemos 202 inmediatamente.
        snapshotBuilder.reconstruirAsync(payload.getTorneoId());

        logEntry.setProcesadoEn(LocalDateTime.now());
        webhookLogRepo.save(logEntry);
        return ResponseEntity.accepted().build();
    }

    /**
     * Endpoint debug: fuerza recompute manual. Útil cuando MS4 quedó atrás
     * por downtime y queremos resincronizar sin esperar el próximo cierre.
     */
    @PostMapping("/torneos/{torneoId}/recompute")
    public ResponseEntity<Void> recompute(
            @PathVariable UUID torneoId,
            @RequestHeader(value = "X-Webhook-Secret", required = false) String secret) {
        if (expectedSecret == null || !expectedSecret.equals(secret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        snapshotBuilder.reconstruirAsync(torneoId);
        return ResponseEntity.accepted().build();
    }
}
