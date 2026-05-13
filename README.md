# MELI — Challenge DevOps & Platform Engineering

> "Necesito una API funcionando en producción, en cloud, con alta disponibilidad. El cliente espera 10.000 requests por segundo en pico. Y no quiero sorpresas a las 3am." — El CTO, según el [enunciado del challenge](./Challenge%20DevOps.pdf).

Este repo entrega la API y todo lo necesario para llevarla a producción: código, infraestructura, pipeline y manifests GitOps. La fundamentación de cada decisión vive en [`/decisiones`](./decisiones/README.md).

---

## Mapa del repositorio

| Carpeta | Qué hay | Entregable del PDF |
|---|---|---|
| [`challenge-meli/`](./challenge-meli/) | API REST en Java 21 + Spring Boot 3.5, Dockerfile multi-stage, docker-compose, tests | `/api` |
| [`iac/`](./iac/) | Terraform modularizado (9 módulos) para EKS, ALB+WAF, ECR, Cognito, IAM, Secrets Manager, observabilidad, CI/CD, networking | `/iac` |
| [`cicd/`](./cicd/) | 4 buildspecs de CodeBuild (test → quality → build & scan → update manifests) consumidos por CodePipeline | `/cicd` |
| [`manifests/`](./manifests/) | Repo GitOps para ArgoCD: 4 platform components + app con Kustomize y Argo Rollouts blue/green | (extra, deriva del pedido de "deploy automático + estrategia de rollback") |
| [`decisiones/`](./decisiones/) | ADRs (Architecture Decision Records). Una decisión por archivo, con contexto, alternativas y consecuencias | `README/decisiones` |

> **Nota sobre nombres.** El PDF pedía `/api`. La carpeta se llama `challenge-meli/` porque es el nombre del repo del proyecto Spring Boot generado por Spring Initializr. La correspondencia es 1:1.

---

## Arquitectura en una imagen

```
                    Internet
                       │
                       ▼
              ┌────────────────┐
              │    Route 53    │  (gestionado por equipo de DNS)
              └────────┬───────┘
                       ▼
              ┌────────────────┐
              │   WAFv2 +      │  4 reglas: Common, Bad Inputs,
              │     ALB        │  rate-limit 10k/5min, sólo GET
              └────────┬───────┘
                       ▼ target-type=ip
              ┌──────────────────────────────────────┐
              │  VPC (10.0.0.0/16) · 2 AZs           │
              │  ┌────────────────────────────────┐  │
              │  │     EKS 1.31 (private API)     │  │
              │  │   ┌──────────────────────────┐ │  │
              │  │   │  ns: challenge-meli      │ │  │
              │  │   │   Rollout (blue/green)   │ │  │
              │  │   │   2-10 replicas (HPA)    │ │  │
              │  │   │   API:8080  →  /metrics  │ │  │
              │  │   └──────────────────────────┘ │  │
              │  │   ┌──────────────────────────┐ │  │
              │  │   │  ns: monitoring          │ │  │
              │  │   │   Prometheus+Grafana     │ │  │
              │  │   │   Loki  +  Fluent Bit    │ │  │
              │  │   │   AlertManager           │ │  │
              │  │   └──────────────────────────┘ │  │
              │  │   ArgoCD + Argo Rollouts +     │ │
              │  │   AWS LBC + ESO                │ │
              │  └────────────────────────────────┘  │
              └──────────────────────────────────────┘
                       │                  │
        ┌──────────────┘                  └──────────────┐
        ▼                                                ▼
 ┌────────────┐   ┌──────────────┐   ┌──────────┐  ┌──────────────┐
 │ Cognito    │   │ Secrets Mgr  │   │   ECR    │  │  CloudWatch  │
 │ M2M JWT    │   │ (CMK propia) │   │ (CMK)    │  │ ALB metrics, │
 │ (futuro)   │   │ ESO sync     │   │ Inmutable│  │ control plane│
 └────────────┘   └──────────────┘   └──────────┘  └──────────────┘
                                                        │
                                                        ▼
                                                  Email / SNS
```

El detalle por componente vive en los READMEs de cada carpeta y en `/decisiones`.

---

## Cómo correr local

### Sólo la API (lo mínimo para el `/api`)

```bash
cd challenge-meli
cp .env.example .env       # ajustar credenciales si querés
docker compose up --build
# API:        http://localhost:8080/api/v1/services
# Health:     http://localhost:8080/health/ready
# Metrics:    http://localhost:8080/metrics  (basic auth: prometheus:prom-secret)
```

Detalles, endpoints, troubleshooting → [`challenge-meli/README.md`](./challenge-meli/README.md).

### Tests

```bash
cd challenge-meli
./mvnw verify              # incluye JaCoCo + cobertura
```

---

## Cómo desplegar a producción

Pre-requisitos manuales (una sola vez por cuenta AWS): bucket S3 + DynamoDB para el state de Terraform, CodeStar Connection a GitHub, valores reales en Secrets Manager y SSM Parameter Store. Detalle paso a paso en [`iac/README.md`](./iac/README.md).

```bash
# 1. Infra base (VPC, EKS, ALB+WAF, ECR, Cognito, IAM, Secrets, observabilidad, CI/CD)
cd iac
cp terraform.tfvars.example terraform.tfvars && $EDITOR terraform.tfvars
terraform init && terraform apply

# 2. Platform components vía ArgoCD (bootstrap manual)
kubectl apply -n argocd -f manifests/platform/aws-load-balancer-controller/install.yaml
kubectl apply -n argocd -f manifests/platform/external-secrets/install.yaml
kubectl apply -n argocd -f manifests/platform/argo-rollouts/install.yaml
# observability: 4 Applications adicionales (ver manifests/platform/observability/README.md)

# 3. Apps de la app (dev / test / prod)
kubectl apply -n argocd -f manifests/app/base/argocd-application.yaml
```

A partir de ahí cada commit a `main` dispara el pipeline; CodeBuild bumpea el `kustomization.yaml` del env correspondiente; ArgoCD sincroniza; Argo Rollouts ejecuta blue/green con análisis automatizado. **El pipeline nunca toca el cluster directamente** — todo es GitOps.

---

## Decisiones clave (resumen)

| Decisión | Por qué | ADR |
|---|---|---|
| **Java 21 + Spring Boot 3.5** | Stack maduro, Virtual Threads para concurrencia, ecosistema actuator/micrometer listo | [ADR-001](./decisiones/ADR-001-stack-api.md) |
| **EKS sobre ECS / Lambda / App Runner** | Workload long-running + GitOps + multi-ambiente con paridad; Lambda no aguanta 10k RPS sostenidos sin tuning específico | [ADR-002](./decisiones/ADR-002-compute-eks.md) |
| **REST + 5 endpoints** | Dominio simple y read-heavy; GraphQL no agrega para 5 recursos | [ADR-003](./decisiones/ADR-003-rest-vs-graphql.md) |
| **In-memory + clients pluggables** | Foco del challenge es DevOps, no persistencia. Interfaces `MetricsClient`/`SloClient`/etc. permiten swap a Prometheus real sin tocar controllers | [ADR-004](./decisiones/ADR-004-storage-in-memory.md) |
| **Basic Auth ahora + Cognito M2M provisionado** | Auth simple para correr local + IaC ya deja el User Pool listo para JWT cuando se enchufe API Gateway o validación en código | [ADR-005](./decisiones/ADR-005-auth.md) |
| **IRSA sobre Pod Identity** | Consistencia con el resto del stack (ESO ya usaba IRSA). En greenfield 2026 elegiríamos Pod Identity | [ADR-006](./decisiones/ADR-006-irsa.md) |
| **Argo Rollouts blue/green** | Switch atómico + análisis pre/post promotion; sin service mesh requirement | [ADR-007](./decisiones/ADR-007-blue-green.md) |
| **Kustomize sobre Helm** | 3 envs con diferencias acotadas; nativo en kubectl/ArgoCD; bump de tag con `yq` type-safe | [ADR-008](./decisiones/ADR-008-kustomize.md) |
| **CodePipeline + CodeBuild sobre GitHub Actions** | Integración nativa con Secrets Manager, IAM, ECR; aprobaciones manuales con SNS | [ADR-009](./decisiones/ADR-009-codepipeline.md) |
| **Prometheus + Loki en cluster, CloudWatch sólo para AWS** | Cada herramienta donde tiene los datos; evita duplicar ingest | [ADR-010](./decisiones/ADR-010-observability-split.md) |
| **CMK por servicio** | Aísla blast radius entre EKS, ECR, Secrets Manager y CodePipeline | [ADR-011](./decisiones/ADR-011-cmk-por-servicio.md) |
| **Multi-ambiente dev/test/prod, 2 clusters** | Nonprod (dev+test) + prod separados → paridad sin compartir blast radius | [ADR-012](./decisiones/ADR-012-multi-ambiente.md) |
| **Fluent Bit sobre Promtail / Alloy** | Promtail deprecado; Alloy sobredimensionado cuando Prometheus ya scrapea métricas | [ADR-013](./decisiones/ADR-013-fluent-bit.md) |
| **Costos como variable de diseño** | ~200 USD/mes base; trade-offs explícitos por servicio | [ADR-014](./decisiones/ADR-014-costos.md) |
| **Load test + capacity plan** | Sizing de pods y HPA validado con carga real | [ADR-015](./decisiones/ADR-015-load-test-capacity.md) |

Lista completa: [`decisiones/README.md`](./decisiones/README.md).

---

## Cómo se cumple cada requisito del challenge

### Parte 1 — Construcción de la API

| Requisito | Dónde |
|---|---|
| Al menos 3 endpoints con propósito claro | 5 endpoints: `GET /api/v1/services`, `GET /api/v1/services/{id}`, `GET /api/v1/services/{id}/metrics`, `GET /api/v1/alerts`, `GET /api/v1/slos`, `GET /api/v1/deploys`. Ver [`challenge-meli/README.md`](./challenge-meli/README.md) |
| Manejo de errores consistente | `GlobalExceptionHandler` con formato uniforme (`code`, `message`, `status`, `timestamp`, `path`, `correlationId`, `errors[]`). Validaciones Bean Validation, type mismatch, route not found, generic 500 |
| Health check endpoint | Spring Boot Actuator: `/health/live` (liveness), `/health/ready` (readiness con probes a Prometheus / ArgoCD / Alertmanager) |
| Containerizada (docker compose up) | Dockerfile multi-stage (build/runtime), JRE 21 alpine, usuario no-root 1001, healthcheck, ZGC, layered jar |
| Sin credenciales hardcodeadas | 100% env vars / Spring `@Value`; local con `.env`, cluster con ExternalSecret → AWS Secrets Manager |

### Parte 2 — Deployment a producción

| Requisito | Dónde |
|---|---|
| **Exposición pública segura** | ALB internet-facing + ACM + WAFv2 + TLS 1.3 + `drop_invalid_header_fields` |
| **Alta disponibilidad** | 2 AZs, 2 NATs (1 por AZ), `topologySpreadConstraints` por zona, PDB minAvailable=1, control plane EKS multi-AZ |
| **Escalabilidad (10k RPS)** | HPA 2–10 réplicas (CPU 70% + mem 80%), Cluster Autoscaler 2–6 nodos, Virtual Threads en Java 21 |
| **Tolerancia a fallos** | Probes diferenciados, blue/green con rollback automático, NATs HA, ECR `IMMUTABLE`, Argo Rollouts abort |
| **Manejo de secretos** | AWS Secrets Manager + CMK propio + ExternalSecret + `Deny non-TLS`; no logueable; rotación documentada |
| **Monitoreo básico** | CloudWatch (ALB 5xx %, p99, dashboard) + Prometheus (cluster + app) + Loki (logs) + AlertManager + email |
| **Diagrama + decisiones** | Arriba + [`/decisiones`](./decisiones/) |
| **CI/CD: build, test, scan, deploy, rollback** | CodePipeline 9 stages (Source → UnitTests → CodeQuality → BuildAndScan → DeployDev → ApproveTest → DeployTest → ApproveProd → DeployProd) + Argo Rollouts auto-rollback. Ver [`cicd/README.md`](./cicd/README.md) |
| **IaC** | Terraform 9 módulos (networking, eks, ecr, alb, cognito, iam, secrets, observability, cicd). Ver [`iac/README.md`](./iac/README.md) |

### Bonus

| Bonus | Cómo |
|---|---|
| Autoscaling | HPA (pods) + Cluster Autoscaler (nodos) |
| WAF / rate limiting | WAFv2 con 4 reglas + rate limit 10k/5min/IP |
| Blue/green o canary | Argo Rollouts blue/green con `prePromotionAnalysis` (smoke test) + `postPromotionAnalysis` (error rate Prometheus) |
| Observabilidad sólida | Stack dual: CloudWatch (AWS) + Prometheus/Grafana/Loki/Fluent Bit/AlertManager (cluster) |
| Tests automatizados | JUnit + Spring Security Test + JaCoCo, gate de coverage ≥80% en el pipeline |
| Uso práctico de IA | Sí — `/prompts.md` documenta cómo se usó |

---

## Preguntas abiertas (deseables)

| Pregunta | Respuesta corta | ADR / archivo |
|---|---|---|
| Estrategia multi-ambiente (paridad sin datos sensibles en staging) | 2 clusters EKS (nonprod / prod), mismo IaC, secrets distintos por path `/meli/<env>/*`, datos sintéticos en nonprod (la app es read-only sobre mocks ahora) | [ADR-012](./decisiones/ADR-012-multi-ambiente.md) |
| Observabilidad con criterio (SLOs / SLIs / tracing) | SLOs definidos en `/api/v1/slos` (disponibilidad 99.95%, error rate < 1%, p99 < 500ms); alertas en AlertManager; tracing opt-in vía OTEL (no incluido en este alcance) | — |
| Costo como variable de diseño | ~200 USD/mes base (2 nodos) + ~30 USD/mes/nodo extra durante autoscaling; trade-offs explícitos por servicio | [ADR-014](./decisiones/ADR-014-costos.md) |
| Governance y cambios críticos en prod fuera de horario | Aprobaciones manuales en CodePipeline + SNS, runbook de break-glass, ArgoCD `selfHeal` para revertir drift | — |

---

## Uso de IA en el challenge

Documentado en [`prompts.md`](./prompts.md).

---

## Limitaciones conocidas (declaradas, no escondidas)

1. **Clientes externos in-memory.** `InMemoryPrometheusClient`, `InMemoryAlertingClient`, `InMemoryDeploymentClient`, `InMemoryServiceCatalogClient` devuelven datos hardcodeados. Foco del challenge era DevOps; el reemplazo por HTTP real es swap directo gracias a las interfaces. Ver [ADR-004](./decisiones/ADR-004-storage-in-memory.md).
2. **Cognito provisioneado pero no integrado en el código.** Basic Auth corre hoy; Cognito está en IaC y listo para activarse cuando se enchufe API Gateway o un resource server. Ver [ADR-005](./decisiones/ADR-005-auth.md).
3. **SonarQube mocked en stage 02; Trivy ya es real en stage 03.** Stage 02 simula el resultado de SonarQube porque no hay Sonar/SonarCloud disponible. Stage 03 instala el binario oficial de Trivy, escanea la imagen y publica reportes (`trivy-report.txt` + `trivy-report.json`) como artifacts. Por default el gate no es bloqueante (`TRIVY_BLOCK=false`) hasta calibrar la baseline de CVEs; activar con la variable en el environment del CodeBuild project. Ver [`cicd/README.md`](./cicd/README.md).
4. **Tracing distribuido no incluido.** El stack soporta agregar OpenTelemetry Collector + Tempo sin tocar el código (el `CorrelationIdFilter` ya propaga el correlation id). Out-of-scope del entregable.
5. **Hosted Zone Route 53 fuera del IaC.** La empresa gestiona DNS externamente; IaC exporta los CNAMEs de validación ACM y se delegan manualmente.

---
