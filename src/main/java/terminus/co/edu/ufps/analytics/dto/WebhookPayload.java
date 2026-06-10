package terminus.co.edu.ufps.analytics.dto;

import lombok.*;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebhookPayload {
    private UUID torneoId;
    private UUID partidoId;
}
