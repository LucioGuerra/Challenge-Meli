# ADR-004 — Storage in-memory con clients pluggables (sin DB)

## Contexto

La gracia del challenge es que no podés implementar algo completo porque la infra real no existe — no hay Prometheus, ni Alertmanager, ni ArgoCD para enchufarse. Lo importante es dejar la puerta abierta para hacerlo el día que esos sistemas existan.

### Supuestos

- Existe un Prometheus en algún lugar de la infra AWS al que la API le va a pegar. No nos importa qué hay detrás.
- El catálogo de services, los SLOs, las alertas y los deploys viven en sistemas externos (Alertmanager, ArgoCD, un service catalog, etc.).

## Decisión

**Sin base de datos. Clients in-memory detrás de interfaces.**

La pieza importante: en la API se trabaja contra **interfaces**, no contra implementaciones concretas. El día que aparezcan los backends reales, swappear un cliente mockeado por uno real es simplemente **cambiar de bean** — los controllers y los services no se enteran.

```java
public interface MetricsClient       { Optional<ServiceMetrics> findByServiceId(String id); ... }
public interface SloClient           { List<Slo> findAll(); ... }
public interface AlertingClient      { List<Alert> findAll(); ... }
public interface DeploymentClient    { List<Deploy> findAll(); ... }
public interface ServiceCatalogClient { List<Service> findAll(); ... }
```

Implementaciones actuales: `InMemoryPrometheusClient`, `InMemoryAlertingClient`, `InMemoryDeploymentClient`, `InMemoryServiceCatalogClient`.

Las URLs de los backends reales (`PROMETHEUS_URL`, `ARGOCD_URL`, `ALERTMANAGER_URL`) ya están inyectadas como configuración. La siguiente iteración reemplaza el cuerpo de cada método por un `RestClient` real apuntando a esas URLs.

## Consecuencias

### Ganamos
- **Swap directo a HTTP real** sin tocar controllers ni services. Cambiar el `@Bean` de la implementación alcanza.
- **Tests rápidos** sin Testcontainers ni mocks complejos.
- **Cero infra extra** — sin DB que provisionar, sin migrations, sin backups.
- **Costo cero** en idle.

### Pagamos
- **No es una API "real"** — devuelve datos hardcoded. Declarado explícitamente en el README.
- **No hay persistencia de estado propio** (ej. saved queries, audit log) — fuera de alcance.
