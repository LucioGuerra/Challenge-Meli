# ADR-010 — Observability split: CloudWatch (AWS) vs Prometheus / Loki (cluster)

## Contexto

Hay que monitorear tres planos distintos:

1. **Infra AWS** — ALB metrics, CodeBuild logs, control plane EKS, VPC Flow Logs.
2. **Cluster Kubernetes** — nodos, pods, kube-state, autoscaler.
3. **App** — métricas custom Prometheus, logs estructurados JSON.

Dos enfoques posibles: meter todo a CloudWatch, o llevarlo todo a Prometheus + Loki + Grafana.

### Supuestos

- Existe un Prometheus en la infra AWS, con su Alertmanager configurado.
- El equipo ya consume Grafana / Prometheus para el resto de los servicios.

## Decisión

**Split por origen del dato.**

- **CloudWatch** se queda con lo **AWS-emitido o que debe sobrevivir a la caída del cluster**: ALB metrics, control plane EKS logs, CodeBuild logs, VPC Flow Logs, ALB access logs (en S3).
- **Prometheus + AlertManager + Grafana + Loki + Fluent Bit** corren en el cluster y son la fuente de verdad para todo lo que vive dentro: nodos, kube-state, app metrics, app logs.

```
┌─────────────────────────────────────────────────────────────────────┐
│                          CloudWatch (AWS)                           │
│  ALB metrics (5xx %, p99 latency) + dashboard                       │
│  Control plane EKS (api/audit/auth/cm/sch)                          │
│  CodeBuild logs                                                     │
│  VPC Flow Logs                                                      │
│  ALB access logs (S3, queryable con Athena)                         │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                     Prometheus stack (en cluster)                   │
│  kube-prometheus-stack: Prometheus Operator + Prometheus +          │
│                         node-exporter + kube-state-metrics          │
│  Grafana (dashboards + UI)                                          │
│  Loki (logs - SimpleScalable mode, backend S3)                      │
│  Fluent Bit (DaemonSet - colecta stdout pods → Loki)                │
└─────────────────────────────────────────────────────────────────────┘
```

## División por dimensión

| Dato | Vive en | Por qué |
|---|---|---|
| ALB 5xx %, p99 latency, RequestCount | CloudWatch | ALB es AWS-managed y emite a CW |
| Control plane EKS logs | CloudWatch | AWS-managed, no se puede redirigir |
| CodeBuild logs | CloudWatch | AWS-managed |
| VPC Flow Logs | CloudWatch | AWS-emitted; queryable con Logs Insights |
| ALB access logs | S3 | Volumen alto; queryable con Athena |
| Node CPU/Memory/Disk | Prometheus (node-exporter) | Ya scrapeado, cero costo ingest CW |
| kube-state-metrics | Prometheus | Idem |
| Estado del Cluster Autoscaler | Prometheus | El CA expone métricas Prometheus nativas |
| App metrics (custom) | Prometheus vía ServiceMonitor sobre `/metrics` | Path natural |
| App logs (stdout/stderr) | Loki vía Fluent Bit | LogQL > Logs Insights, costo bajo |

## Alternativas consideradas

### Todo a CloudWatch (Container Insights + CW Agent)
Un único pane of glass, independiente del cluster. Pero el costo de ingest crece linealmente con métricas custom y termina duplicando lo que `node-exporter` / `kube-state` ya tendrían disponible si Prometheus existiera. PromQL es además mejor que Metrics Insights para los queries del día a día.

### Todo a Prometheus + Loki (sin CloudWatch)
Un solo stack, todas las queries con LogQL/PromQL. Problema: las métricas del ALB necesitarían un exporter aparte, y los logs del control plane EKS / CodeBuild son AWS-emitidos a CW por diseño, no se pueden mover.

### CloudWatch + Datadog / New Relic / Splunk
Costo y vendor lock-in. La stack open-source cubre lo necesario para este alcance.

## Reglas implícitas del split

1. **Lo que AWS emite, queda en AWS.** Mover ALB metrics o control plane logs a Prometheus implica un exporter más sin beneficio claro.
2. **Lo que vive en el cluster, queda en el cluster.** Mover métricas de nodos/pods a CW implica costo de ingest sin beneficio.
3. **No duplicar.** Container Insights está desactivado a propósito — las métricas de nodos ya las scrapea `node-exporter`.

## Consecuencias

### Ganamos
- **Costo de ingest CW acotado** a lo AWS-emitido.
- **PromQL/LogQL** para el día-a-día de SRE.
- **kube-prometheus-stack** trae node-exporter + kube-state + Grafana datasources pre-configurados — cero código glue.

### Pagamos
- **Sin Container Insights** — no tenemos la UI nativa de AWS para Kubernetes. Aceptado: Grafana lo cubre con dashboards open-source.
- **Mantener Loki** (Fluent Bit + servicio Loki + S3 bucket) es operación nueva. Mitigado: stack estándar, bien documentado.
