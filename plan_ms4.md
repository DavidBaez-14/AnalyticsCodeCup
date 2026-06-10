# Plan · MS4 (Analytics) — Sprint 1 (vista pública del torneo activo)

> **Repo:** `AnalyticsCodeCup/` (nuevo, hermano de `AuthCodeCup/` y `supercopa/`)
> **Puerto:** `8084` · Prefix de rutas: `/api/analytics/**`
> **Stack:** Spring Boot 3.3.5 · Java 21 · PostgreSQL (Supabase nueva BD #3)
> **Estado:** propuesta, listo para arrancar.

> **Nota importante sobre el alcance:** este plan cubre **solo el Sprint 1**, enfocado en mover a MS4 la vista pública (clasificación, goleadores, porteros) del **torneo activo**. Todo lo relacionado con el perfil histórico del jugador, Salón de la Fama, estadísticas por semestre y la integración con MS1 se posterga al **Sprint 2** (sección 16 al final de este documento). MS2 sigue siendo dueño del perfil del jugador en Sprint 1 — su `PerfilService` ya agrega historial cross-torneo y no se toca.

---

## 1 · Contexto y alcance

El frontend público de Code Cup y el panel admin **hoy leen la clasificación directamente del MS2** (servicio `ClasificacionService` + endpoint `GET /api/supercopa/admin/torneos/{id}/clasificacion`). Eso funcionó para validar la lógica de desempates, pero rompe la decisión arquitectónica de que **la lectura pública vive en MS4** ([contexto_ms4.md](../.context/docs/contexto_ms4.md)).

Esta entrega cubre **solo lo crítico** para mover esa responsabilidad sin retrasar la demo:

### Alcance Sprint 1

- ✅ Proyecto Spring Boot independiente en `AnalyticsCodeCup/`.
- ✅ Schema propio en una BD Supabase nueva (`analytics.*`).
- ✅ Endpoint público `GET /api/analytics/torneos/{id}/posiciones` (lo que hoy hace MS2).
- ✅ Endpoint público `GET /api/analytics/torneos/{id}/goleadores` (datos crudos, UI después).
- ✅ Endpoint público `GET /api/analytics/torneos/{id}/porteros` (datos crudos, UI después).
- ✅ **Endpoint público `GET /api/analytics/torneos/{id}/premios`** — proyección de lectura desde MS2 (`supercopa.premios_torneo`). Sin schema propio, sin cache: pull on-demand a MS2 internal.
- ✅ Webhook MS2 → MS4 al cerrar partido → MS4 recalcula snapshot.
- ✅ **Webhook MS2 → MS4 al cerrar torneo** → MS4 calcula premios automáticos (Goleador, Portero, Campeón/Subcampeón/3°) y hace callback HTTP a MS2 con los ganadores. MS2 los persiste en `supercopa.premios_torneo`.
- ✅ Frontend admin migra su tab "Clasificación" a consumir MS4.
- ✅ Vista pública (`TorneoActivo.jsx`) migra de mock a MS4.
- ✅ **Vista pública de premios** consume `GET /premios` de MS4.

### Out of scope Sprint 1 (movido a Sprint 2 — ver sección 16)

- ❌ **HU48 — Perfil histórico del jugador.** MS2 ya lo cubre vía `PerfilService` (agrega goles, tarjetas, equipos, títulos a lo largo de toda la carrera). En Sprint 2 MS4 lo absorbe.
- ❌ **Cliente MS1 en MS4.** Para metadata académica (nombre, semestre, código universitario). Sprint 2.
- ❌ **HU36 — Salón de la Fama.** Snapshots de torneos cerrados con la edición completa congelada. Sprint 2.
- ❌ **Estadísticas demográficas por semestre / rol académico.** Sprint 2 (requiere MS1).
- ❌ HU31 (eventos del partido) — MS2 ya lo expone, frontend puede seguir con eso.
- ❌ HU35 (Reporte PDF) — Sprint 3 o Semana de Cierre.
- ❌ Notificaciones MS5.
- ❌ Auth fina para endpoints públicos (van sin token).
- ❌ **CRUD admin de premios en MS4.** Según decisión final, los premios viven en MS2 (`supercopa.premios_torneo`). MS4 solo los proyecta como lectura pública. La UI admin que los configura pega a MS2.
- ❌ Observabilidad avanzada (Actuator default es suficiente).

### Por qué MS2 sigue dueño del perfil del jugador en Sprint 1

Tras inspeccionar [`PerfilService.obtenerPerfil(cedula)`](../supercopa/src/main/java/terminus/co/edu/ufps/competicion/ms2supercopa/service/PerfilService.java):

- El método consulta **por cédula, no por torneo**: usa `findByCedula(...)` en repositorios de `JugadorEquipo`, `PartidoJugador`, `EventoPartido` y `Titulo`.
- Eso significa que el perfil **ya agrega TODA la carrera** del jugador (cross-torneo) sumando todos los eventos en BD viva.
- Modelo mental: la fuente de verdad son los eventos en `supercopa.eventos_partido` que viven para siempre. No hay momento de "freeze" — la suma se recalcula cada vez que alguien pide el perfil.

Eso ya funciona y no rompe nada. En Sprint 2 MS4 lo absorbe materializando snapshots por (cedula, torneo) para acelerar y desacoplar. Por ahora MS2 lo mantiene.

> **Bonus encontrado**: ya existe el campo `posicion` en la entidad `Jugador` (`PORTERO | DEFENSA | MEDIOCAMPISTA | DELANTERO`). No hace falta migración para identificar al portero — el algoritmo del premio "Portero menos vencido" funciona automático desde Sprint 1 si MS2 lo expone vía el endpoint interno.

---

## 2 · Decisiones arquitectónicas

### 2.1 ¿Cómo se entera MS4 de un cambio?

**Decidido: webhook async desde MS2 al cerrar partido.**

- Cuando `PartidoAdminService.cerrarPartido` termina con éxito en MS2, lanza un `@Async` HTTP `POST /api/analytics/webhooks/partido-cerrado` a MS4 con `{ torneoId, partidoId }`.
- Fire-and-forget: si MS4 está caído, MS2 sigue funcionando. El cierre del partido NO depende de MS4.
- MS4 al recibirlo, **vuelve a pedir a MS2** los datos frescos (partidos + clasificación + eventos del torneo) y reconstruye sus snapshots.

**Asunción**: en el demo MS2 y MS4 corren en localhost. Para producción, `MS4_BASE_URL` queda como variable de entorno en MS2.

### 2.2 ¿De dónde saca MS4 los datos?

**Decidido: MS4 llama a MS2 vía HTTP (Pattern de "vista materializada").**

- MS4 NO lee directo de la BD de MS2 (acopla schemas, baja la promesa del microservicio).
- MS4 NO duplica la lógica de cálculo de clasificación con desempates (sería divergencia inminente).
- En lugar de eso: **MS2 expone un endpoint interno** (autenticado con shared secret) que devuelve clasificación + estadísticas de jugador para un torneo. MS4 lo consume cuando recibe el webhook.
- Las llamadas son raras (1 por cada cierre de partido), así que el costo HTTP es trivial.

> **Lo que sí mantiene MS4 en su BD**: snapshots desnormalizados que las vistas públicas consumen directamente. Esto desacopla MS4 del tiempo de respuesta de MS2 cuando un visitante carga la vista pública.

### 2.3 ¿Validar JWT en endpoints públicos?

**Decidido: NO**. Los endpoints `/posiciones`, `/goleadores`, `/porteros` son públicos sin auth — coincide con `contexto_ms4.md` HU32/HU33.

El **webhook** sí requiere shared secret en header (`X-Webhook-Secret`). Si el header no coincide con la env var de MS4, devuelve 401.

### 2.4 ¿Schema separado o BD aparte?

**Decidido: BD aparte (Supabase #3) con schema `analytics`.**

El usuario lo confirmó. Cumple el principio "cada MS dueño de su data". `Hikari` con pool máximo 3, igual que MS2.

---

## 3 · Stack y dependencias

`pom.xml` (idéntico a MS2 menos lo de Auth interna, más `RestClient` para hablar con MS2):

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
</parent>
<groupId>terminus.co.edu.ufps</groupId>
<artifactId>analytics</artifactId>
<version>0.0.1-SNAPSHOT</version>

<properties>
    <java.version>21</java.version>
</properties>

<dependencies>
    <dependency> spring-boot-starter-web </dependency>
    <dependency> spring-boot-starter-data-jpa </dependency>
    <dependency> spring-boot-starter-validation </dependency>
    <dependency> spring-boot-starter-actuator </dependency>
    <dependency> postgresql (runtime) </dependency>
    <dependency> lombok (provided) </dependency>
    <dependency> springdoc-openapi-starter-webmvc-ui:2.6.0 </dependency>
</dependencies>
```

> Nota: NO necesitamos `spring-boot-starter-security` ni OAuth2 jose en esta primera entrega porque los endpoints son públicos y el webhook usa shared secret simple. Si en iteraciones siguientes agregamos endpoints admin (ej. premios) volvemos a meter security.

---

## 4 · Schema (BD Supabase #3, schema `analytics`)

### 4.1 Tablas

```sql
CREATE SCHEMA IF NOT EXISTS analytics;

-- Snapshot por equipo en un torneo (cubre vista pública y admin)
CREATE TABLE analytics.snapshot_posicion (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    torneo_id UUID NOT NULL,
    grupo VARCHAR(10) NOT NULL,             -- 'A', 'B', ..., o 'GLOBAL' para LIGA
    equipo_torneo_id UUID NOT NULL,
    equipo_nombre VARCHAR(150) NOT NULL,
    posicion INT NOT NULL,
    pts INT NOT NULL,
    pj INT NOT NULL,
    pg INT NOT NULL,
    pe INT NOT NULL,
    pp INT NOT NULL,
    gf INT NOT NULL,
    gc INT NOT NULL,
    dg INT NOT NULL,
    rojas INT NOT NULL,
    form VARCHAR(20),                       -- "W,L,W" últimos 3
    descalificado BOOLEAN NOT NULL DEFAULT FALSE,
    actualizado_en TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_snapshot_pos_torneo ON analytics.snapshot_posicion(torneo_id);
CREATE UNIQUE INDEX idx_snapshot_pos_unique
    ON analytics.snapshot_posicion(torneo_id, equipo_torneo_id);

-- Snapshot de goles por jugador (HU33)
CREATE TABLE analytics.snapshot_goleador (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    torneo_id UUID NOT NULL,
    cedula VARCHAR(20) NOT NULL,
    jugador_nombre VARCHAR(150),
    equipo_nombre VARCHAR(150),
    goles INT NOT NULL,
    posicion INT,                           -- ranking dentro del torneo
    actualizado_en TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_snapshot_gol_torneo ON analytics.snapshot_goleador(torneo_id, goles DESC);
CREATE UNIQUE INDEX idx_snapshot_gol_unique
    ON analytics.snapshot_goleador(torneo_id, cedula);

-- Snapshot de porteros (equipo entero por ahora, no jugador individual)
-- Tabla por equipo porque no sabemos quién es el portero a nivel de jugador
CREATE TABLE analytics.snapshot_portero (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    torneo_id UUID NOT NULL,
    equipo_torneo_id UUID NOT NULL,
    equipo_nombre VARCHAR(150),
    goles_en_contra INT NOT NULL,
    partidos_jugados INT NOT NULL,
    promedio_gc NUMERIC(5,2),
    posicion INT,
    actualizado_en TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX idx_snapshot_portero_unique
    ON analytics.snapshot_portero(torneo_id, equipo_torneo_id);

-- Audit log de webhooks recibidos
CREATE TABLE analytics.webhook_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tipo VARCHAR(40) NOT NULL,              -- 'partido-cerrado'
    torneo_id UUID,
    partido_id UUID,
    payload_json TEXT,
    recibido_en TIMESTAMP NOT NULL DEFAULT NOW(),
    procesado_en TIMESTAMP,
    error TEXT
);
```

> Cuando se agreguen HU48 (perfil jugador histórico), se añade `analytics.perfil_jugador` similar con agregados cross-torneo.

---

## 5 · Endpoints expuestos

### 5.1 Públicos (sin auth)

```
GET /api/analytics/torneos/{torneoId}/posiciones
  Response: { "A": [PosicionDTO...], "B": [...] }  ó  { "GLOBAL": [...] }
  Lee directo de analytics.snapshot_posicion.

GET /api/analytics/torneos/{torneoId}/goleadores?limit=10
  Response: [{ cedula, nombre, equipoNombre, goles, posicion }, ...]

GET /api/analytics/torneos/{torneoId}/porteros?limit=10
  Response: [{ equipoTorneoId, equipoNombre, golesEnContra, pj, promedio, posicion }, ...]

GET /api/analytics/torneos/{torneoId}/premios
  Response: [{ categoria, alcance, nombre, monto, orden,
               ganador: { cedula, nombre, equipoNombre } | null }, ...]
  Proyección de lectura desde MS2. MS4 hace pull on-demand a
  /api/supercopa/internal/torneos/{id}/premios (cache en memoria 60s opcional).
  Sin schema propio en analytics.*.
```

### 5.2 Webhooks (shared secret)

```
POST /api/analytics/webhooks/partido-cerrado
Headers: X-Webhook-Secret: <env value>
Body:    { "torneoId": "uuid", "partidoId": "uuid" }
Response: 202 Accepted (asincrono)
Acción:  recalcula snapshots de clasificación, goleadores, porteros.

POST /api/analytics/webhooks/torneo-cerrado
Headers: X-Webhook-Secret: <env value>
Body:    { "torneoId": "uuid" }
Response: 202 Accepted (asincrono)
Acción:  calcula automáticamente:
         - Goleador: jugador con más goles del torneo (excluye W.O.)
         - Portero menos vencido: jugador con posicion='PORTERO' del equipo con menor gc/pj
         - Campeón/Subcampeón/3°: lee resultado del bracket
         Luego hace callback HTTP a MS2 con cada ganador:
         POST /api/supercopa/internal/torneos/{id}/premios/{premioId}/asignar
         con X-Internal-Secret. MS2 persiste el ganador en supercopa.premios_torneo.
         MVP y premios OTRO no se tocan (asignación manual desde UI admin MS2).
```

### 5.3 Internos (debug, podríamos esconder en perfil dev)

```
POST /api/analytics/torneos/{torneoId}/recompute  ← fuerza un recompute manual
GET  /api/analytics/health                         ← actuator default
```

---

## 6 · Comunicación MS2 → MS4

### 6.1 Cambios en MS2

**Nueva clase** `supercopa/.../ms2supercopa/client/AnalyticsPublisher.java`:

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class AnalyticsPublisher {
    private final RestClient analyticsClient;   // bean configurado con MS4_BASE_URL
    @Value("${analytics.webhook.secret}") private String secret;

    @Async
    public void notificarPartidoCerrado(UUID torneoId, UUID partidoId) {
        try {
            analyticsClient.post()
                .uri("/api/analytics/webhooks/partido-cerrado")
                .header("X-Webhook-Secret", secret)
                .body(Map.of("torneoId", torneoId, "partidoId", partidoId))
                .retrieve()
                .toBodilessEntity();
        } catch (Exception e) {
            log.warn("MS4 webhook fail (no bloqueante): {}", e.getMessage());
        }
    }
}
```

**Modificación** en `PartidoAdminService.cerrarPartido`, al final (después del bracket auto-fill):

```java
analyticsPublisher.notificarPartidoCerrado(partido.getTorneo().getId(), partido.getId());
```

**`application.properties` MS2 (nuevas props)**:
```properties
analytics.base-url=${ANALYTICS_BASE_URL:http://localhost:8084}
analytics.webhook.secret=${ANALYTICS_WEBHOOK_SECRET:dev-secret-change-in-prod}
```

**Habilitar @Async**: agregar `@EnableAsync` en `CompeticionCodeCupApplication`.

### 6.2 Nuevo endpoint INTERNO en MS2 que consume MS4

MS4 necesita pedirle a MS2 los datos frescos para reconstruir. Reusa endpoint existente:

```
GET /api/supercopa/admin/torneos/{id}/clasificacion
GET /api/supercopa/admin/torneos/{id}/partidos
GET /api/supercopa/admin/torneos/{id}/eventos    ← agregar si no existe
```

> Problema: estos endpoints requieren rol ADMINISTRADOR o ARBITRO. MS4 no tiene JWT. **Solución**: agregar un endpoint paralelo `/api/supercopa/internal/torneos/{id}/snapshot` que acepta `X-Internal-Secret` (mismo patrón que el webhook). Solo expone datos READ. Esto se hace en el día 2.

---

## 7 · Cambios en el frontend

### 7.1 Admin: tab Clasificación

[`ClasificacionLiveTab.jsx`](../Frontend-CodeCup/codecup/src/components/supercopa/ClasificacionLiveTab.jsx) hoy llama `getClasificacion(torneoId, token)` que pega a MS2.

Cambiar a una nueva función `getPosicionesPublic(torneoId)` que pega a:
```
GET ${VITE_ANALYTICS_URL}/api/analytics/torneos/{id}/posiciones
```
Sin auth header (público). El frontend debe tener una env nueva `VITE_ANALYTICS_URL=http://localhost:8084`.

### 7.2 Vista pública del torneo

[`TorneoActivo.jsx`](../Frontend-CodeCup/codecup/src/pages/TorneoActivo.jsx) hoy usa mock de [`data/supercopa.js`](../Frontend-CodeCup/codecup/src/data/supercopa.js). Migrar el tab "Clasificación" para consumir el mismo endpoint público de MS4. El tab "Partidos" sigue con MS2 (es admin/árbitro-driven y ya funciona).

### 7.3 Sin frontend nuevo (para esta entrega)

Goleadores y porteros: solo el endpoint del backend. El frontend los conectará después.

---

## 8 · Estructura del proyecto

```
AnalyticsCodeCup/
├── pom.xml
├── mvnw, mvnw.cmd, .mvn/
├── .gitignore
├── README.md
├── plan_ms4.md                       ← este archivo
├── Dockerfile                         (idéntico a supercopa)
├── db/
│   └── supabase_schema_ms4.sql       ← script de creación
├── src/
│   └── main/
│       ├── java/terminus/co/edu/ufps/analytics/
│       │   ├── AnalyticsCodeCupApplication.java
│       │   ├── config/
│       │   │   ├── RestClientConfig.java         (cliente HTTP a MS2)
│       │   │   ├── WebhookAuthFilter.java        (valida X-Webhook-Secret)
│       │   │   └── CorsConfig.java
│       │   ├── controller/
│       │   │   ├── PosicionesController.java
│       │   │   ├── GoleadoresController.java
│       │   │   ├── PorterosController.java
│       │   │   └── WebhookController.java
│       │   ├── client/
│       │   │   ├── Ms2InternalClient.java        (HTTP hacia MS2 internal)
│       │   │   └── dto/                          (TorneoSnapshotDTO, ...)
│       │   ├── service/
│       │   │   ├── PosicionesService.java
│       │   │   ├── GoleadoresService.java
│       │   │   ├── PorterosService.java
│       │   │   └── SnapshotBuilderService.java   (orquesta el recompute)
│       │   ├── model/
│       │   │   ├── SnapshotPosicion.java
│       │   │   ├── SnapshotGoleador.java
│       │   │   ├── SnapshotPortero.java
│       │   │   └── WebhookLog.java
│       │   ├── repository/
│       │   │   ├── SnapshotPosicionRepository.java
│       │   │   ├── SnapshotGoleadorRepository.java
│       │   │   ├── SnapshotPorteroRepository.java
│       │   │   └── WebhookLogRepository.java
│       │   ├── dto/
│       │   │   ├── PosicionDTO.java
│       │   │   ├── GoleadorDTO.java
│       │   │   ├── PorteroDTO.java
│       │   │   └── WebhookPayload.java
│       │   └── exception/
│       │       └── GlobalExceptionHandler.java
│       └── resources/
│           ├── application.properties
│           └── application-local.properties
└── docs/
    └── (vacío inicialmente; doc específica se agrega después)
```

---

## 9 · Iteraciones día por día (~3 días estimados)

### Día 1 — Scaffold + BD + endpoints de lectura

- Crear `AnalyticsCodeCupApplication.java`, `pom.xml`, `application.properties`.
- Crear BD Supabase #3, ejecutar `supabase_schema_ms4.sql`.
- Modelos JPA, repositorios.
- Controllers + Services de lectura (devuelven lo que esté en BD; vacío al inicio).
- Smoke test: `curl localhost:8084/api/analytics/health`.

### Día 2 — Webhook + Snapshot builder

- Endpoint MS2 nuevo `GET /api/supercopa/internal/torneos/{id}/snapshot` (acepta secret).
- `Ms2InternalClient` en MS4 (RestClient con timeout 5s).
- `SnapshotBuilderService.reconstruir(torneoId)`: llama MS2, parsea, upsert en BD MS4.
- `WebhookController` recibe payload y dispara `@Async` el builder.
- Audit log en `webhook_log`.

### Día 3 — Integración MS2 publisher + frontend admin

- `AnalyticsPublisher` en MS2 con `@Async`.
- `@EnableAsync` en MS2 application.
- Frontend: nuevo `api/analytics.js` con `getPosicionesPublic(torneoId)`.
- Migrar `ClasificacionLiveTab` para usar MS4.
- Smoke test end-to-end: cerrar un partido en MS2 → ver snapshot en MS4 BD → ver actualización en tab admin.

### Día 4 (buffer) — Goleadores + Porteros

- Lógica de goleador en `SnapshotBuilderService` (cuenta GOL events, excluye W.O.).
- Idem porteros.
- Endpoints listos para el frontend cuando lo conecte.

---

## 10 · Verification (smoke test al final del día 3)

1. Crear partido en MS2, registrar 2 goles, cerrar.
2. Ver en logs de MS2: `"Sent webhook partido-cerrado to MS4"` (info).
3. Ver en logs de MS4: `"Recibido webhook → recompute torneo X"` (info).
4. `curl http://localhost:8084/api/analytics/torneos/{id}/posiciones` → JSON con grupos y posiciones, fechas `actualizado_en` recientes.
5. Refrescar admin "Fixture y cronograma" tab Clasificación → mismos datos visibles, ahora servidos por MS4.
6. Detener MS4 (Ctrl+C). Cerrar otro partido en MS2: el cierre funciona normal (NO bloquea), log de MS2 dice `"MS4 webhook fail (no bloqueante)"`. Volver a iniciar MS4 + hit `POST /recompute` → snapshot al día.

---

## 11 · Variables de entorno

### MS2 (nuevas)
```
ANALYTICS_BASE_URL=http://localhost:8084
ANALYTICS_WEBHOOK_SECRET=<random>
INTERNAL_API_SECRET=<random>   ← protege el endpoint /internal
```

### MS4
```
SERVER_PORT=8084
DB_URL=jdbc:postgresql://<supabase-3>:6543/postgres?prepareThreshold=0
DB_USERNAME=postgres.<id>
DB_PASSWORD_MS4=<password>
MS2_BASE_URL=http://localhost:8082
ANALYTICS_WEBHOOK_SECRET=<same as MS2>
INTERNAL_API_SECRET=<same as MS2>
```

### Frontend
```
VITE_ANALYTICS_URL=http://localhost:8084
```

---

## 12 · Asunciones que tomé (revisar)

1. **MS4 mantiene su propia BD** porque el usuario lo pidió explícito. Si después se decide volver más simple (sin BD, solo proxy), se borra todo `analytics.*` y se cambian los services a llamar MS2 directo en cada GET.
2. **Webhook es fire-and-forget**. Si MS4 está caído y se cierran varios partidos, esos eventos se pierden — habría que agregar outbox/queue después para garantías. Para el demo no aplica.
3. **No autenticamos los endpoints públicos**. Mantener consistente con `contexto_ms4.md`.
4. **El portero es a nivel de equipo, no jugador**. Como no hay campo "posición" en `JugadorEquipo`, el premio "Portero menos vencido" se interpreta como "equipo con menos goles en contra". Aprobado por usuario en el iter 2 de finanzas.
5. **Goleador excluye eventos de W.O.** (regla ya documentada en MS4 contexto). MS2 al exponer los datos vía `/internal/snapshot` filtra eso desde el origen.
6. **CORS abierto** para `http://localhost:5173` (frontend dev) y la URL de producción del frontend cuando se sepa.

---

## 13 · Preguntas abiertas

1. **¿`/internal/snapshot` en MS2 acepta solo `X-Internal-Secret` o además IP whitelist?** Para localhost basta el secret; en producción quizás IP whitelist también.
2. **¿La vista pública migra YA al MS4 o se queda con mock hasta que el snapshot tenga datos reales?** Recomiendo: en el día 3 hacer un toggle (`USE_MS4=true` en env), pero migrar de a poco para no romper la demo.
3. **¿Necesitamos un `POST /api/analytics/torneos/{id}/recompute` para forzar recompute manual desde la UI admin?** Útil cuando MS4 quedó atrás de MS2 por downtime. Sugiero sí, oculto en el admin como botón "Recargar datos".

---

## 14 · Riesgos y mitigaciones

| Riesgo | Mitigación |
|---|---|
| MS2 y MS4 tienen distintos UUID de equipos → snapshots inconsistentes | MS2 es la fuente de verdad. MS4 solo guarda los UUIDs recibidos vía `/internal/snapshot`. Si hay desync, `recompute` corrige. |
| El webhook se dispara muchas veces (race) | El service builder es **idempotente** (upsert por unique constraint `torneo_id + equipo_torneo_id`). |
| MS4 BD se llena (los snapshots son acumulativos) | Cada `reconstruir(torneoId)` borra los snapshots viejos de ese torneo antes de insertar. Mantenemos solo el más reciente por torneo. |
| Latencia entre cierre y vista pública refrescada | Frontend hace polling cada 30s o un botón "Refrescar". |
| Secret en variables de entorno se filtra | Pasar por `.env` local; en producción usar Secret Manager de DO/Supabase. |

---

## 15 · Mejoras técnicas no urgentes

- **WebSocket / SSE** para que el frontend reciba updates sin polling. ~2 días.
- **Cache layer (Redis)** delante de los endpoints públicos. Solo si hay tráfico real. ~1 día.
- **Tests E2E** que validen "cerrar partido en MS2 → snapshot correcto en MS4". ~1 día.
- **Cola de mensajería real** (RabbitMQ / SQS) reemplazando el webhook HTTP. Cuando MS4 crezca o el volumen suba. ~3 días.

---

## 16 · Sprint 2 — Perfil histórico, Salón de la Fama y datos MS1

> **Esta sección está como contexto activo**. NO se implementa en Sprint 1, pero se mantiene aquí para que el plan de MS4 sea coherente cuando se retome.

### 16.1 Objetivo

Cuando termine Sprint 1, MS4 cubre solo la vista pública del torneo activo. Sprint 2 amplía MS4 para que sea **el único microservicio que necesitas consultar para todo lo histórico**:

- Perfil del jugador con desglose por torneo + suma carrera total.
- Salón de la Fama (campeones, goleadores, porteros, premios por edición).
- Estadísticas demográficas (jugadores por semestre, por rol académico, por programa).

Ese último punto es lo que justifica conectar MS4 con MS1.

### 16.2 Modelo de datos nuevo

```sql
-- Perfil del jugador desglosado por torneo (cubre HU48 al estilo Sprint 2)
CREATE TABLE analytics.snapshot_perfil_jugador (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cedula VARCHAR(20) NOT NULL,
    torneo_id UUID NOT NULL,
    torneo_nombre VARCHAR(150),
    torneo_edicion INT,
    equipo_nombre VARCHAR(150),
    partidos_jugados INT NOT NULL DEFAULT 0,
    goles INT NOT NULL DEFAULT 0,
    amarillas INT NOT NULL DEFAULT 0,
    azules INT NOT NULL DEFAULT 0,
    rojas INT NOT NULL DEFAULT 0,
    titulo_obtenido VARCHAR(20),         -- CAMPEON | SUBCAMPEON | TERCERO | NULL
    es_torneo_cerrado BOOLEAN NOT NULL DEFAULT FALSE,
    actualizado_en TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX idx_snapshot_perfil_unique
    ON analytics.snapshot_perfil_jugador(cedula, torneo_id);

-- Salón de la Fama: una fila por edición cerrada
CREATE TABLE analytics.salon_fama (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    torneo_id UUID NOT NULL UNIQUE,
    torneo_nombre VARCHAR(150),
    edicion INT,
    cerrado_en TIMESTAMP NOT NULL,
    campeon_equipo_nombre VARCHAR(150),
    subcampeon_equipo_nombre VARCHAR(150),
    tercero_equipo_nombre VARCHAR(150),
    goleador_cedula VARCHAR(20),
    goleador_nombre VARCHAR(150),
    goleador_goles INT,
    portero_cedula VARCHAR(20),           -- NULL si fue premio colectivo
    portero_equipo_nombre VARCHAR(150),
    portero_promedio_gc NUMERIC(5,2),
    mvp_cedula VARCHAR(20),               -- asignación manual del admin
    mvp_nombre VARCHAR(150),
    premios_json TEXT                      -- copia desnormalizada del catálogo de premios + ganadores
);

-- Demográficos por semestre (HU académico)
CREATE TABLE analytics.snapshot_demografico (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    torneo_id UUID NOT NULL,
    semestre VARCHAR(10),                  -- '2026-1', '2026-2'
    rol_academico VARCHAR(30),             -- ESTUDIANTE | GRADUADO | PROFESOR | ADMINISTRATIVO
    cantidad_jugadores INT NOT NULL DEFAULT 0,
    actualizado_en TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX idx_snapshot_demografico_unique
    ON analytics.snapshot_demografico(torneo_id, semestre, rol_academico);
```

### 16.3 Endpoints nuevos en MS4

```
# Públicos
GET /api/analytics/torneos/{torneoId}/salon-fama-resumen      → resumen del torneo cerrado
GET /api/analytics/salon-fama                                 → lista de todas las ediciones
GET /api/analytics/torneos/{torneoId}/demograficos             → distribución por semestre/rol

# Autenticado JUGADOR
GET /api/analytics/jugadores/mi-perfil
  Response: {
    cedula, nombre, posicion, semestre,
    resumenTotal: { partidos, goles, amarillas, ... titulos },
    porTorneo: [{ torneo, partidos, goles, titulo }, ...]
  }
```

### 16.4 Webhook nuevo y trigger de freeze

MS2 dispara dos webhooks:

```
POST /api/analytics/webhooks/partido-cerrado     ← ya existe en Sprint 1
POST /api/analytics/webhooks/torneo-cerrado      ← NUEVO en Sprint 2
  Body: { torneoId }
```

Al recibir `torneo-cerrado`, MS4:
1. Pide a MS2 el estado final del torneo (campeón, subcampeón, tercero, goleador, portero).
2. Pide a MS1 el padrón completo de los jugadores que participaron (semestre, rol).
3. Inserta en `salon_fama` la edición.
4. Para cada jugador del torneo, upserta su fila en `snapshot_perfil_jugador` con `es_torneo_cerrado=true`.
5. Computa demográficos por semestre/rol y los inserta en `snapshot_demografico`.

Después de ese freeze, esa edición NO se recalcula nunca más (a menos que el admin la reabra explícitamente). El perfil del jugador en torneos futuros suma su fila vieja de 2026 + su fila viva de 2027.

### 16.5 Cliente MS1

Patrón idéntico al `Ms1JugadoresClient` que MS2 ya tiene:

```java
@Component
public class Ms1JugadoresClient {
    public Optional<JugadorPadronDTO> getJugadorPorCedula(String cedula);
    public List<JugadorPadronDTO> getJugadoresPorCedulas(List<String> cedulas);  // batch
}
```

Variables de entorno nuevas en MS4: `MS1_BASE_URL=http://localhost:8081`.

### 16.6 Cambios en MS2

- Nuevo endpoint interno: `GET /api/supercopa/internal/torneos/{id}/cierre-final` con datos del torneo cerrado (campeón, premios, etc.).
- En `TorneoAdminService.cerrar()` (cuando se implemente), tras todos los pasos actuales, llamar `analyticsPublisher.notificarTorneoCerrado(torneoId)`.
- La `PerfilService` actual queda obsoleta cuando el frontend del jugador migre a MS4. Mientras tanto convive.

### 16.7 Frontend

- Página "Mi perfil" del jugador: migrar de MS2 a MS4. Mostrar resumen total + desglose por torneo (gráfica simple).
- Página "Salón de la Fama" pública: nueva vista listando todas las ediciones cerradas.
- Página admin "Demográficos": tabla de jugadores por semestre, gráfico de torta por rol académico.

### 16.8 Estimación Sprint 2 (~5 días)

| Día | Tarea |
|---|---|
| 1 | Schema MS4 ampliado + entidades JPA + repos |
| 2 | `Ms1JugadoresClient` + endpoint interno MS2 `/cierre-final` |
| 3 | `SalonFamaBuilderService` + webhook `torneo-cerrado` |
| 4 | `PerfilBuilderService` cross-torneo + endpoint público `/mi-perfil` |
| 5 | Frontend: migración página "Mi perfil", nueva "Salón de la Fama" |

### 16.9 Decisiones tomadas que se conservan para Sprint 2

- Portero menos vencido se calcula **automático** desde `Jugador.posicion = 'PORTERO'` + GC del equipo. Sin migración.
- Una multa = un comprobante (regla de Sprint 1 de finanzas).
- Goleador excluye `cedula IS NULL` (goles dummy del W.O.).
- Comprobantes de inscripción usan Supabase Storage (Sprint 1 de finanzas).
- El admin decide manualmente MVP y premios "OTRO" al cerrar torneo.

### 16.10 Lo que NO se hace ni en Sprint 2

- Reporte PDF (HU35) — Sprint 3.
- Notificaciones automáticas a delegados/jugadores cuando un torneo cierra (MS5).
- Sistema de mensajería real (Rabbit/SQS) reemplazando webhooks HTTP.
- Multi-temporada, multi-país, multi-moneda.
