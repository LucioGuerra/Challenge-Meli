# challenge-meli — Infrastructure Metrics API

API REST que expone información de infraestructura (servicios, métricas, SLOs, alertas, deploys) al resto de la organización. Es el entregable `/api` del [challenge](../Challenge%20DevOps.pdf).

> Stack: **Java 21 · Spring Boot 3.5 · Maven · Docker multi-stage · JUnit + JaCoCo**. Fundamentación en [ADR-001](../decisiones/ADR-001-stack-api.md).

---

## TL;DR

```bash
cp .env.example .env
docker compose up --build
# → API en http://localhost:8080
```

```bash
curl -u admin:changeme http://localhost:8080/api/v1/services | jq
curl http://localhost:8080/health/ready
curl -u prometheus:prom-secret http://localhost:8080/metrics
```

---

## Dominio modelado

Por el escenario del PDF ("exponer métricas de infraestructura al resto de la organización") elegimos modelar lo que un equipo de infra de plataforma realmente necesita exponer:

| Recurso | Por qué | Detalle |
|---|---|---|
| **Services** | Catálogo de servicios productivos — base para todo lo demás | Status, ownership, ambientes activos |
| **Service metrics** | El "qué pasa ahora" — el panel que mira el oncall | Latencia (p50/p95/p99), request rate, error rate, availability |
| **SLOs** | El "qué prometemos" — contrato medible con los consumidores | Target, current, status (MET / AT_RISK / BREACHED), error budget restante, window |
| **Alerts** | El "qué está mal ahora" — feed de incidentes activos | Severity, status, serviceId, mensaje, timestamp |
| **Deploys** | El "qué cambió y cuándo" — base para correlación con incidentes | Status, serviceId, autor, commit, timestamps |

Decisión completa en [ADR-004](../decisiones/ADR-004-rest-vs-graphql.md) y [ADR-005](../decisiones/ADR-005-storage-in-memory.md).

---

## Endpoints

Todos bajo `/api/v1`. Todos `GET`. Todos requieren basic auth con rol `API_USER`. Todos responden el envelope `ApiResponse<T>` con `data` y `meta`.

| Método | Path | Query params (validados) | Respuesta |
|---|---|---|---|
| `GET` | `/api/v1/services` | `status`, `name`, `page≥0`, `1≤size≤100` | `List<Service>` paginado |
| `GET` | `/api/v1/services/{id}` | `id` regex `^[a-zA-Z0-9-]{1,64}$` | `ServiceDetail` |
| `GET` | `/api/v1/services/{id}/metrics` | `id` regex | `ServiceMetrics` |
| `GET` | `/api/v1/alerts` | `severity`, `status`, `serviceId`, `page`, `size` | `List<Alert>` paginado |
| `GET` | `/api/v1/slos` | `status`, `serviceId`, `page`, `size` | `List<Slo>` paginado |
| `GET` | `/api/v1/deploys` | `status`, `serviceId`, `page`, `size` | `List<Deploy>` paginado |

Operacionales (sin `/api/v1`):

| Método | Path | Auth | Para qué |
|---|---|---|---|
| `GET` | `/health/live` | público | Liveness probe (k8s + Docker HEALTHCHECK) |
| `GET` | `/health/ready` | público | Readiness probe — incluye chequeos a Prometheus, ArgoCD, Alertmanager |
| `GET` | `/metrics` | `MONITORING` role | Métricas en formato Prometheus (micrometer-prometheus) |

### Envelope de respuesta

```json
{
  "data": [ ... ],
  "meta": {
    "timestamp": "2026-05-12T10:15:30Z",
    "source": "prometheus",
    "pagination": { "page": 0, "size": 20, "totalElements": 5, "totalPages": 1 }
  }
}
```

`source` aparece sólo cuando los datos vienen de una fuente externa (`/metrics`, `/slos`). `pagination` aparece sólo en endpoints paginados.

### Manejo de errores

`GlobalExceptionHandler` cubre 6 casos y siempre devuelve el mismo formato:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Validation failed",
    "status": 400,
    "timestamp": "2026-05-12T10:15:30Z",
    "path": "/api/v1/services",
    "correlationId": "8b1f...c4",
    "errors": [
      {"field": "size", "message": "must be less than or equal to 100", "rejectedValue": 1000}
    ]
  }
}
```

| Excepción | Status | Code |
|---|---|---|
| `ApiException` (custom: `ServiceNotFoundException`, `MetricsUnavailableException`, etc.) | el de la excepción | el de la excepción |
| `MethodArgumentNotValidException` / `ConstraintViolationException` / `HandlerMethodValidationException` | 400 | `VALIDATION_ERROR` |
| `MethodArgumentTypeMismatchException` (ej. enum inválido en query) | 400 | `INVALID_PARAMETER` |
| `NoResourceFoundException` (ruta no existe) | 404 | `ROUTE_NOT_FOUND` |
| `Exception` (catch-all) | 500 | `INTERNAL_ERROR` |

El `correlationId` siempre viene en la respuesta. Si el cliente envía `X-Correlation-Id`, se respeta; si no, el `CorrelationIdFilter` genera un UUID. Va al MDC y a los logs JSON estructurados.

---

## Decisiones de diseño (resumen)

### Por qué Java 21 + Spring Boot 3.5
- **Java 21**: LTS, Virtual Threads habilitados (`spring.threads.virtual.enabled: true`) — concurrencia de bajo costo para una API I/O-bound que llama a Prometheus/ArgoCD/Alertmanager. ZGC generacional (default) con pausas <1 ms.
- **Spring Boot 3.5**: ecosistema Actuator + Micrometer + Validation listos para producción. Curva de adopción cero para un dev Java.
- Detalles y alternativas: [ADR-001](../decisiones/ADR-001-stack-api.md).

### Por qué REST y no GraphQL
5 recursos, lecturas planas, sin queries anidadas no triviales. GraphQL agregaría schema + resolvers + N+1 concerns sin beneficio. [ADR-004](../decisiones/ADR-004-rest-vs-graphql.md).

### Por qué in-memory (sin DB)
La sustancia del challenge es DevOps, no persistencia. Hay 4 clients con interfaces (`MetricsClient`, `SloClient`, `AlertingClient`, `DeploymentClient`, `ServiceCatalogClient`); las implementaciones actuales (`InMemory*Client`) son swap directo a clients HTTP reales contra Prometheus / Alertmanager / ArgoCD sin tocar controllers ni services. [ADR-005](../decisiones/ADR-005-storage-in-memory.md).

### Por qué Basic Auth ahora (con Cognito provisionado)
Basic Auth corre desde el primer minuto, sin dependencia externa, perfecto para correr local. Cognito M2M (`client_credentials`) ya está en el IaC ([`iac/modules/cognito`](../iac/modules/cognito/README.md)) listo para activarse cuando se enchufe API Gateway o validación de JWT en código. [ADR-006](../decisiones/ADR-006-auth.md).

### Por qué `/metrics` con rol separado
Prometheus scrape no debe usar las mismas credenciales que el cliente humano de la API. Dos cuentas (`API_USER` y `MONITORING`) → rotación independiente y blast radius separado. Si el secret de Prometheus se filtra, no compromete los endpoints de negocio.

### Por qué dos probes (live vs ready)
- `livenessProbe → /health/live`: sólo mira si la JVM está viva. Si falla, kubelet reinicia el pod.
- `readinessProbe → /health/ready`: incluye chequeos a Prometheus / ArgoCD / Alertmanager. Si falla, el pod sale del Service pero **no se reinicia**. Si Prometheus está caído, no queremos restartear todos los pods.
- Configuración en `application.yml`: `management.endpoint.health.group.{live,ready}`.
- [ADR-015](../decisiones/ADR-015-health-checks.md).

### Por qué Correlation ID
El filtro de orden `1` corre antes que cualquier otro. Si el cliente manda `X-Correlation-Id` lo respeta; si no, UUID. Va al MDC → aparece en cada línea de log JSON. Es lo que permite correlar logs en Loki con un request específico desde el ALB hasta el último log de la app. Base para tracing distribuido (futuro OTEL).

### Por qué validaciones agresivas en query params
- `@Pattern("^[a-zA-Z0-9-]{1,64}$")` en cualquier `serviceId`: previene path injection, log poisoning, ataques homográficos.
- `@Min(0) @Max(100)` en `size`: previene "give me a million rows".
- `@Size(max=100)` en `name`: previene queries malformadas.
- Las validaciones fallidas devuelven 400 con detalle por campo (`FieldError[]`) — no 500.

### Por qué JSON logs
Logback + Logstash encoder → JSON estructurado con campos `application`, `environment`, `correlationId`, `level`, `message`, `logger`, `thread`, `stackTrace`. Fluent Bit en el cluster lo parsea sin regex y lo manda a Loki con labels Kubernetes. [ADR-017](../decisiones/ADR-017-fluent-bit.md).

### Por qué Dockerfile multi-stage + layered jar
- **Stage 1 (builder, JDK 21 alpine):** dependencias cacheadas vía `dependency:go-offline` (la capa se reutiliza mientras `pom.xml` no cambie), luego `package -DskipTests`, luego `layertools extract`.
- **Stage 2 (runtime, JRE 21 alpine):** 4 capas (`dependencies` → `spring-boot-loader` → `snapshot-dependencies` → `application`) por frecuencia de cambio. Si sólo cambia tu código, Docker sólo reconstruye la última capa.
- **Imagen final**: JRE (no JDK, ~100 MB menos), alpine, usuario no-root UID/GID 1001, healthcheck wget a `/health/live`, `exec` para que `SIGTERM` llegue a la JVM y arranque el graceful shutdown de Spring.
- JVM flags via env `JVM_OPTS`: `MaxRAMPercentage=75`, `InitialRAMPercentage=50`, `UseZGC`, `ExitOnOutOfMemoryError`, `egd=/dev/./urandom`, `preferIPv4Stack=true`. Explicación de cada flag en el propio Dockerfile (comentarios largos a propósito para que sea didáctico).

### Por qué sin credenciales hardcodeadas
Todo viene de env vars (Spring `@Value` o `${VAR:default}`). Local: `.env` (en `.gitignore`). Cluster: ExternalSecret materializa un `Secret` desde `/meli/<env>/challenge-meli-api` en AWS Secrets Manager y se inyecta via `envFrom`. [ADR-012](../decisiones/ADR-012-cmk-por-servicio.md).

---

## Variables de entorno

Ver `.env.example` para la lista completa. Las que importan:

| Variable | Default | Para qué |
|---|---|---|
| `SERVER_PORT` | `8080` | Puerto HTTP |
| `ENVIRONMENT` | `local` | Aparece en logs JSON y métricas (`management.metrics.tags.environment`) |
| `API_USERNAME` / `API_PASSWORD` | `admin` / `changeme` | Credenciales del rol `API_USER` |
| `MONITORING_USERNAME` / `MONITORING_PASSWORD` | `prometheus` / `prom-secret` | Credenciales del rol `MONITORING` (scrape) |
| `PROMETHEUS_URL` | mock | URL del Prometheus real (cuando se enchufe) |
| `ARGOCD_URL` | mock | Idem ArgoCD |
| `ALERTMANAGER_URL` | mock | Idem Alertmanager |
| `API_PAGE_DEFAULT_SIZE` | `20` | Default de paginación |
| `LOG_LEVEL` | `DEBUG` | Level del logger `com.meli.challenge` |
| `JVM_OPTS` | ver Dockerfile | Tuning de JVM si hace falta |

---

## Estructura del código

```
src/main/java/com/meli/challenge/
├── ChallengeMeliApplication.java   # @SpringBootApplication
├── config/
│   └── SecurityConfig.java         # Basic auth + roles + BCrypt + stateless
├── controller/                     # 4 controllers REST (Services, Alerts, Slos, Deploys)
├── service/                        # 5 services (lógica + filtros + delega a clients)
├── client/                         # Interfaces + InMemory* implementations
├── model/                          # Entidades de dominio (records donde aplica)
├── dto/                            # ApiResponse, *Response, *Detail, Meta, PageInfo
├── dto/error/                      # ApiError, ApiErrorResponse, FieldError
├── exception/                      # ApiException + 4 subtypes + GlobalExceptionHandler
├── filter/                         # CorrelationIdFilter (Order=1)
└── util/                           # Paginator (subList + bounds)
```

Tests en `src/test/java/com/meli/challenge/`: controllers, services, filter, paginator + `SecurityAndIntegrationTest` end-to-end con MockMvc + Spring Security Test.

---

## Cómo correr y testear

### Local con Docker

```bash
cp .env.example .env
docker compose up --build
# logs en JSON; Ctrl+C para parar
```

### Local con Maven (sin Docker)

```bash
./mvnw spring-boot:run
# requiere Java 21 instalado
```

### Tests + cobertura

```bash
./mvnw verify
# Cobertura en target/site/jacoco/index.html
# El pipeline corta a 80% (ver cicd/01-test.yml)
```

### Smoke test manual

```bash
# Auth requerida
curl -u admin:changeme http://localhost:8080/api/v1/services

# Health (público)
curl http://localhost:8080/health/live
curl http://localhost:8080/health/ready

# Metrics (rol MONITORING)
curl -u prometheus:prom-secret http://localhost:8080/metrics | head -20

# Forzar validation error
curl -u admin:changeme "http://localhost:8080/api/v1/services?size=1000"
# → 400 VALIDATION_ERROR con FieldError[]

# Forzar 404 de recurso
curl -u admin:changeme http://localhost:8080/api/v1/services/no-existe
# → 404 SERVICE_NOT_FOUND

# Forzar 404 de ruta
curl -u admin:changeme http://localhost:8080/api/v1/nope
# → 404 ROUTE_NOT_FOUND

# Correlation ID
curl -u admin:changeme -H "X-Correlation-Id: deadbeef" \
     http://localhost:8080/api/v1/services -i | grep -i correlation
```

---

## Producción: cómo encaja

| Capa | Quién |
|---|---|
| Imagen | Pipeline (`cicd/03-build-and-scan.yml`) → ECR `IMMUTABLE` |
| Deploy | `manifests/app/base/rollout.yaml` con Argo Rollouts blue/green |
| Secrets | `ExternalSecret` lee `/meli/<env>/challenge-meli-api` de AWS Secrets Manager |
| Ingress | ALB target-type `ip` → pod 8080 (sin hop NodePort) |
| Probes | `/health/live` (liveness + startup) y `/health/ready` (readiness) |
| Metrics | Prometheus scrape via `ServiceMonitor` con basic auth |
| Logs | Fluent Bit lee stdout JSON → Loki con labels K8s |
| Rollback | `kubectl argo rollouts abort` durante rollout; revert de commit en manifest repo si ya promoteó |

---

## Limitaciones declaradas

1. **Datos en memoria.** Cinco servicios hardcoded en `InMemory*Client`. Swap a HTTP real es transparente para los services/controllers.
2. **Cognito provisionado pero no integrado en el código.** Hoy se usa Basic Auth. JWT requiere o un API Gateway delante del ALB (Cognito Authorizer nativo) o un `JwtDecoder` en la SecurityConfig — ambos están out-of-scope del entregable.
3. **Sin DB.** Si la API necesitara persistencia (escribir incidentes, alertas), iría una Postgres en Aurora Serverless v2 o DynamoDB según patrón. Decidido fuera del scope. Discutible en presentación.
4. **Tracing.** No hay OTEL instrumentation. El `CorrelationIdFilter` está pero los traces como tales no se exportan. Está listo el espacio para enchufar `opentelemetry-spring-boot-starter` y un Collector → Tempo.

---
