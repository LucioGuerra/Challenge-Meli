# ADR-013 — Fluent Bit como agente de logs

## Contexto

Necesitamos un agente de logs que:

- Corra como **DaemonSet** en cada nodo del cluster.
- Lea `stdout/stderr` de todos los pods (los logs de la app son JSON estructurado emitidos por Logback + Logstash encoder).
- Parse multiline (stack traces Java) y JSON.
- Envíe los logs a **Loki** (no a CloudWatch, ver [[ADR-010]]).
- Agregue **labels** con metadata de Kubernetes (namespace, pod, container, app).
- Sea liviano y esté mantenido activamente.

## Decisión

**Fluent Bit** como DaemonSet en cada nodo.

```yaml
# manifests/platform/observability/fluent-bit-values.yaml
# Chart: https://fluent.github.io/helm-charts (fluent-bit)
```

Labels emitidos a Loki por línea:
- `job=fluent-bit`
- `cluster=<EKS_CLUSTER_NAME>`
- `namespace=<pod namespace>`
- `pod=<pod name>` ⚠️ (alta cardinalidad)
- `container=<container name>`
- `app=<labels[app.kubernetes.io/name]>`

Tolerations universal + `priorityClassName: system-node-critical`.

## Alternativas consideradas

### Promtail
Era el agente "oficial" de Grafana para Loki, pero **Grafana Labs lo deprecó en 2025** a favor de Alloy. No es opción viable para algo nuevo.

### Grafana Alloy
Agente unificado (logs + métricas + traces) y recomendado por Grafana Labs como reemplazo de Promtail. Tiene un footprint mayor (~150 MB RAM vs ~30 MB de Fluent Bit) y mucha más superficie. El caso "unificado" no aplica acá: las métricas las hace Prometheus, los traces no existen aún, así que Alloy agrega complejidad sin beneficio.

### CloudWatch Container Insights / Fluent Bit a CloudWatch
Un único pane, pero costo de ingest CW lineal en volumen y LogQL >>> CloudWatch Logs Insights. Ver [[ADR-011]].

### Aplicación escribiendo directo a Loki (sin agente)
Acopla la app al backend de logs; si Loki se cae, la app bloquea o pierde logs. El patrón estándar es desacoplar producción (stdout) de transporte (agente).

## Por qué Fluent Bit específicamente

- **C nativo** — ~10-30 MB RAM por instancia.
- **Recomendado por AWS** — `aws-for-fluent-bit` es la imagen oficial usada en docs de EKS.
- **Parser nativo para multiline Java**: stack traces se agrupan en una sola entrada.
- **Parser nativo para JSON estructurado** (la app emite JSON vía Logstash encoder).
- **CRI parser** — entiende el formato de logs de containerd / cri-o.
- **Output plugin nativo a Loki** con labels dinámicos desde metadata K8s.
- **Persistencia de buffer en disco** — si Loki está down, los logs no se pierden inmediatamente.

## Etiqueta `pod` y cardinalidad

Loki indexa por labels. **Alto cardinality kills Loki performance**. El label `pod=<pod name>` es problemático: cada rollout crea ReplicaSets nuevos → pod names nuevos → series nuevas. En 1 mes con 1 deploy/día y 3 réplicas, son 90 valores distintos.

Mitigación: si la cardinalidad se vuelve problema, mover `pod` a **structured metadata** (Loki ≥ 3.0). Querable con `| json | pod="..."` sin penalizar cardinalidad. Hoy se mantiene como label porque facilita filtrar; monitor y mover si llega a 10k+ valores.

## Consecuencias

### Ganamos
- **Agente liviano** que cabe en cada nodo sin estrés.
- **Stack-recommended** por AWS y por la comunidad Loki/EKS.
- **Parseo nativo** de logs Java JSON estructurados.
- **Metadata K8s automática** sin código glue.
- **Buffer en disco** — outage corto de Loki no pierde logs.

### Pagamos
- **Otro DaemonSet** que mantener (versión, config). Mitigación: chart upstream estable, instalación vía ArgoCD.
- **Cardinalidad de `pod`** — manejable pero a vigilar.
- **Sin métricas + traces unificados** — cada uno tiene su agente (Prometheus para métricas, OTEL futuro para traces).
