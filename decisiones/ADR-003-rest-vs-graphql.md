# ADR-003 — REST sobre GraphQL

## Contexto

El PDF permite REST o GraphQL si se justifica. El dominio modelado son 5 recursos read-only (services, metrics, SLOs, alerts, deploys) con paginación y filtros simples.

Honestamente, GraphQL nunca se discutió seriamente. Para un dominio tan acotado parecía agregar una capa más de complejidad sin ventaja: el cliente termina llamando los mismos 5 endpoints, sólo que pasando por un único `/graphql` con un schema que hay que mantener, un resolver layer, y toda la maquinaria de N+1 / DataLoader / query complexity para que no se desmadre.

## Decisión

**REST** con 5 endpoints `GET` y envelope JSON uniforme.

```
GET /api/v1/services?status&name&page&size
GET /api/v1/services/{id}
GET /api/v1/services/{id}/metrics
GET /api/v1/alerts?severity&status&serviceId&page&size
GET /api/v1/slos?status&serviceId&page&size
GET /api/v1/deploys?status&serviceId&page&size
```

Más operacionales:

```
GET /health/live      (público)
GET /health/ready     (público)
GET /metrics          (rol MONITORING)
```

## Consecuencias

### Ganamos
- **Cacheable HTTP** — los GET son idempotentes y CDN-friendly.
- **WAF + rate-limit por path** — las reglas de WAFv2 actúan sobre URI patterns.
- **Observabilidad por endpoint** — Prometheus tagea por path nativamente.
- **Errores HTTP estándar** que ALB metrics y Grafana entienden sin transformación.
- **Validación declarativa** con Bean Validation.
- **Onboarding cero** para cualquier dev: REST + JSON es lingua franca.

### Pagamos
- **Over-fetching moderado** — los DTOs son chicos y aplanados, así que en la práctica no duele.
- **Más endpoints, más superficie** — 5 endpoints vs un único `/graphql`. Aceptable para 5 recursos.
- **Versionado en `/api/v1/...`** explícito; agregar campos en el `meta` del envelope no rompe.
