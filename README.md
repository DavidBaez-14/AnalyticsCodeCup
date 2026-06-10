# AnalyticsCodeCup · MS4

Microservicio de analytics y reportes públicos del torneo Code Cup.

- **Puerto:** `8084`
- **Prefix:** `/api/analytics/**`
- **Stack:** Spring Boot 3.3.5 · Java 21 · PostgreSQL (Supabase #3)

Ver [`plan_ms4.md`](plan_ms4.md) para el plan completo de la primera entrega.

## Endpoints públicos

```
GET /api/analytics/torneos/{torneoId}/posiciones
GET /api/analytics/torneos/{torneoId}/goleadores?limit=10
GET /api/analytics/torneos/{torneoId}/porteros?limit=10
```

## Webhook (interno, requiere `X-Webhook-Secret`)

```
POST /api/analytics/webhooks/partido-cerrado
body: { "torneoId": "uuid", "partidoId": "uuid" }
```

## Variables de entorno

| Variable | Default | Uso |
|---|---|---|
| `SERVER_PORT` | `8084` | |
| `DB_URL` | `localhost:5432/analytics` | Supabase #3 PG URL |
| `DB_USERNAME` | `postgres` | |
| `DB_PASSWORD_MS4` | _(required)_ | |
| `MS2_BASE_URL` | `http://localhost:8082` | URL del MS2 para pedir datos |
| `ANALYTICS_WEBHOOK_SECRET` | `dev-webhook-secret-...` | Auth del webhook |
| `INTERNAL_API_SECRET` | `dev-internal-secret-...` | Header `X-Internal-Secret` al pegar MS2 |
| `FRONTEND_URL` | `http://localhost:5173` | CORS allow-origin |

## Schema inicial

Ejecutar [`db/supabase_schema_ms4.sql`](db/supabase_schema_ms4.sql) en la nueva BD Supabase.

## Arrancar local

```bash
./mvnw spring-boot:run
```

Smoke test:
```bash
curl http://localhost:8084/actuator/health
curl http://localhost:8084/api/analytics/torneos/<uuid>/posiciones
```
