package terminus.co.edu.ufps.analytics.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import terminus.co.edu.ufps.analytics.model.WebhookLog;

import java.util.UUID;

public interface WebhookLogRepository extends JpaRepository<WebhookLog, UUID> {
}
