# Decisiones — Architecture Decision Records

Cada archivo de esta carpeta documenta **una decisión técnica** del challenge. Formato ADR: contexto, decisión, alternativas relevantes y consecuencias.

> Por qué ADRs: el enunciado del challenge dice _"vamos directo a las decisiones: las que tomaste y las que no. No hay una única solución correcta. Hay decisiones bien y mal fundamentadas."_ — ([PDF, página 4](../Challenge%20DevOps.pdf)). Este formato deja cada decisión y su _por qué_ revisable de forma independiente.

---

## Índice

### Parte 1 — La API

| # | Decisión |
|---|---|
| [ADR-001](./ADR-001-stack-api.md) | Stack de la API: Java 21 + Spring Boot 3.5 |
| [ADR-003](./ADR-003-rest-vs-graphql.md) | REST sobre GraphQL |
| [ADR-004](./ADR-004-storage-in-memory.md) | Storage in-memory con clients pluggables (sin DB) |
| [ADR-005](./ADR-005-auth.md) | Basic Auth para correr la API, OAuth2 M2M como camino real |

### Parte 2 — Infra y deployment

| # | Decisión |
|---|---|
| [ADR-002](./ADR-002-compute-eks.md) | Compute: EKS sobre ECS / Lambda / App Runner |
| [ADR-006](./ADR-006-irsa.md) | IRSA sobre Pod Identity |
| [ADR-007](./ADR-007-blue-green.md) | Argo Rollouts blue/green con análisis automatizado |
| [ADR-008](./ADR-008-kustomize.md) | Kustomize sobre Helm para los overlays |

### CI/CD y GitOps

| # | Decisión |
|---|---|
| [ADR-009](./ADR-009-codepipeline.md) | CodePipeline + CodeBuild sobre GitHub Actions |

### Observabilidad

| # | Decisión |
|---|---|
| [ADR-010](./ADR-010-observability-split.md) | Split CloudWatch (AWS) vs Prometheus / Loki (cluster) |
| [ADR-013](./ADR-013-fluent-bit.md) | Fluent Bit como agente de logs |

### Seguridad

| # | Decisión |
|---|---|
| [ADR-011](./ADR-011-cmk-por-servicio.md) | CMK por servicio + Secrets Manager + ExternalSecret |

### Operación

| # | Decisión |
|---|---|
| [ADR-012](./ADR-012-multi-ambiente.md) | Multi-ambiente dev / test / prod con 2 clusters |
| [ADR-014](./ADR-014-costos.md) | Costos como variable de diseño y trade-offs |
| [ADR-015](./ADR-015-load-test-capacity.md) | Load test + capacity plan (sizing pod + HPA) |

---


---

## Convenciones

- **Una decisión por archivo.** Si una decisión arrastra otra, hay un ADR por cada una y se enlazan con `[[ADR-XXX]]`.
- **Trade-offs explícitos.** Cada ADR lista lo que se _paga_ por la decisión, no sólo lo que se gana.
- **Supuestos visibles.** Donde aplica, cada ADR declara los supuestos que tomamos sobre el contexto de la organización.
