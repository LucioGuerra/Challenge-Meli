# observability

Stack de observabilidad: Prometheus (métricas) + Grafana (UI) + Loki (logs) + Fluent Bit (agente de logs).

## División de responsabilidades con CloudWatch

Este stack es la fuente de verdad para **todo lo que vive dentro del cluster**: métricas de nodos (node-exporter), métricas de Kubernetes (kube-state-metrics), métricas de la app (ServiceMonitor → `/actuator/prometheus`), logs de aplicación (Fluent Bit → Loki), y alertas operativas (AlertManager).

CloudWatch (módulo `iac/modules/observability`) sólo se queda con lo que es AWS-emitido o que debe sobrevivir a la caída del cluster:

| Vive en CloudWatch | Vive en este stack |
|---|---|
| ALB metrics (5xx %, p99 latency) + alarms | Node CPU/Memory/Disk → Prometheus + AlertManager |
| Control plane EKS logs | kube-state, pods, deployments, jobs → Prometheus |
| CodeBuild logs | Cluster Autoscaler metrics → Prometheus |
| VPC Flow Logs | App logs (stdout/stderr) → Loki vía Fluent Bit |
| ALB access logs (S3) | App custom metrics → Prometheus vía ServiceMonitor |

**No duplicar.** Container Insights está desactivado a propósito: las métricas de nodos ya las scrape-a `node-exporter` sin costo de ingest a CloudWatch.

## Archivos

| Archivo | Helm chart | Repo |
|---|---|---|
| `prometheus-values.yaml` | `kube-prometheus-stack` | `https://prometheus-community.github.io/helm-charts` |
| `grafana-values.yaml` | `grafana` | `https://grafana.github.io/helm-charts` |
| `loki-values.yaml` | `loki` (deploymentMode: SimpleScalable) | `https://grafana.github.io/helm-charts` |
| `fluent-bit-values.yaml` | `fluent-bit` | `https://fluent.github.io/helm-charts` |

Solo values files. Las `Application` que los referencian se aplican desde el mgmt cluster. Estructura sugerida:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: prometheus
  namespace: argocd
spec:
  source:
    repoURL: https://prometheus-community.github.io/helm-charts
    chart: kube-prometheus-stack
    targetRevision: 62.7.0
    helm:
      valueFiles:
        - $values/manifests/platform/observability/prometheus-values.yaml
  sources:
    - repoURL: PLACEHOLDER_MANIFEST_REPO_URL
      targetRevision: main
      ref: values
  destination:
    server: https://kubernetes.default.svc
    namespace: monitoring
  syncPolicy:
    automated: { prune: true, selfHeal: true }
    syncOptions: [CreateNamespace=true, ServerSideApply=true]
```

Repetir el patrón para los 4 charts.

## Por qué kube-prometheus-stack

Bundle oficial que incluye Prometheus Operator + Prometheus + Alertmanager + node-exporter + kube-state-metrics + reglas de alertas curadas. Alternativa (vanilla Prometheus) requeriría rearmar todo a mano.

Grafana NO viene incluido en el bundle (`grafana.enabled: false`) → se instala separado para tener su propio ciclo de vida y values más limpios.

## Por qué Loki SimpleScalable y no Monolithic

| Modo | Cuándo usar |
|---|---|
| `SingleBinary` | < 100 GB/day, dev |
| `SimpleScalable` (write/read/backend separados) | 100 GB – 1 TB/day, producción típica |
| `Distributed` (ingester, querier, etc separados) | > 1 TB/day, multi-tenant grande |

`SimpleScalable` da escalado horizontal sin la complejidad del modo distributed.

## Por qué Fluent Bit y no Promtail / Grafana Alloy

- **Promtail**: deprecado por Grafana Labs (anuncio 2025). No invertir en algo que ya está siendo retirado.
- **Grafana Alloy**: agente unificado (logs + métricas + traces). Sentido si tu único agente debe colectar todo. Acá Prometheus ya scrape-ea métricas vía ServiceMonitor → no necesitamos colector unificado.
- **Fluent Bit**:
  - Stack agent recomendado por AWS para EKS (`aws-for-fluent-bit` es Fluent Bit + plugins AWS).
  - ~30 MB RAM por nodo. Alloy: ~150 MB.
  - Parsers nativos para multiline (stack traces Java), JSON estructurado (Logback JSON encoder de la app), CRI.
  - Output plugin nativo a Loki con labels dinámicos desde metadata K8s.

## Placeholders

| Placeholder | Dónde | Qué |
|---|---|---|
| `PLACEHOLDER_AWS_REGION` | loki, fluent-bit | `us-east-1` |
| `PLACEHOLDER_LOKI_S3_BUCKET` | loki | bucket S3 para chunks |
| `PLACEHOLDER_LOKI_IRSA_ROLE_ARN` | loki | IAM role con S3 access al bucket |
| `PLACEHOLDER_ACM_CERT_ARN` | grafana | cert para `grafana.<domain>` |
| `PLACEHOLDER_BASE_DOMAIN` | grafana | base domain (`meli.example.com`) |
| `PLACEHOLDER_EKS_CLUSTER_NAME` | fluent-bit | label `cluster_name` en logs |
| `PLACEHOLDER_ALERTS_EMAIL` | prometheus (alertmanager.config) | destino de las alertas (mismo email que el SNS de CW) |
| `PLACEHOLDER_SMTP_HOST` | prometheus | smarthost SMTP (ej `smtp.sendgrid.net`) |
| `PLACEHOLDER_SMTP_FROM` | prometheus | from address (ej `alerts@meli.example.com`) |
| `PLACEHOLDER_SMTP_USER` | prometheus | usuario SMTP |
| `PLACEHOLDER_SMTP_PASSWORD` | prometheus | password SMTP — mejor mover a un Secret y referenciar |

> Las credenciales SMTP idealmente se montan vía `ExternalSecret` desde Secrets Manager y se referencian con `auth_password_file:` en lugar de inline. Para esto, también hay que setear `alertmanagerSpec.secrets: [alertmanager-smtp]` y montarlo en el pod.

## AlertManager: routing y receivers

Las `defaultRules` de `kube-prometheus-stack` están todas activadas (`nodeExporterAlerting`, `kubernetesResources`, `kubernetesSystem`, `kubeApiserverSlos`, etc.), de modo que las alertas que reemplazan a las viejas `node_cpu` / `node_memory` de CloudWatch ya existen sin necesidad de definir reglas custom: `NodeMemoryHighUtilization`, `NodeCPUHighUsage`, `KubePodCrashLooping`, `KubePodNotReady`, `KubeNodeNotReady`, `KubeNodePressure`, etc.

La config de `alertmanager.config` define:
- Una ruta default y otra para `severity=critical`.
- Inhibición: si dispara una `critical` para un `(alertname, namespace)`, las `warning` del mismo par se silencian.
- Watchdog (alerta keep-alive) silenciada (es para chequear que AlertManager funciona, no para notificar).

## Grafana admin

El admin password se monta desde un `Secret` llamado `grafana-admin` con keys `admin-user` y `admin-password`. Crear vía `ExternalSecret`:

```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: grafana-admin
  namespace: monitoring
spec:
  refreshInterval: 1h
  secretStoreRef:
    name: aws-secrets-manager
    kind: ClusterSecretStore
  target:
    name: grafana-admin
  dataFrom:
    - extract:
        key: /meli/observability/grafana-admin
```

## Métricas de la app

La app `challenge-meli-api` expone `/metrics` en formato Prometheus en el puerto 8080 con basic auth (`MONITORING_USERNAME` / `MONITORING_PASSWORD`). El `ServiceMonitor` en `app/base/services.yaml` configura:

- `basicAuth` apuntando al secret `challenge-meli-api-secrets`.
- Interval 30 s, timeout 10 s.

Prometheus operator pickea el `ServiceMonitor` por el label `release: prometheus` (matcheado por el `serviceMonitorSelectorNilUsesHelmValues: false`).

## Loki labels y cardinalidad

Fluent Bit envía estos labels a Loki por línea:
- `job=fluent-bit` (estático)
- `cluster=<EKS_CLUSTER_NAME>` (un valor por cluster)
- `namespace=<pod namespace>`
- `pod=<pod name>` ⚠️
- `container=<container name>`
- `app=<labels[app.kubernetes.io/name]>`

⚠️ `pod` es alta cardinalidad (cambia con cada rollout). Si la cardinalidad se vuelve un problema, mover `pod` a structured metadata (Loki >= 3.0) en lugar de label.

## Obligatorio para producción

- Prometheus: 2 réplicas, retention 15d, storage 50Gi gp3.
- Alertmanager: 2 réplicas (gossip cluster), storage 10Gi.
- Loki: SimpleScalable (3 write, 3 read, 3 backend), S3 backend.
- Grafana: 2 réplicas, persistence 10Gi (para dashboards user-saved + plugin cache).
- Fluent Bit: DaemonSet con `priorityClassName: system-node-critical` y tolerations universal.
- ServiceMonitors habilitados en todos los componentes.

## Nice to have

- Long-term storage para Prometheus (Thanos sidecar) si necesitás > 15 días.
- Loki ruler para alertas basadas en logs.
- Tempo para traces (no incluido en este stack).

## Operar

```bash
# Prometheus targets
kubectl -n monitoring port-forward svc/prometheus-operated 9090
# http://localhost:9090/targets

# Grafana
kubectl -n monitoring port-forward svc/grafana 3000:80

# Loki query desde CLI
logcli query '{namespace="challenge-meli"}'

# Fluent Bit health
kubectl -n monitoring get ds fluent-bit
kubectl -n monitoring port-forward ds/fluent-bit 2020
# http://localhost:2020/api/v1/health
```

## Dependencias

- `aws-load-balancer-controller`: para el Ingress de Grafana.
- `external-secrets`: para el admin password de Grafana y para el secret de IRSA del controller (si aplica).
- StorageClass `gp3` o equivalente (ajustar values si difiere).
- S3 bucket pre-creado para Loki + IRSA role con permisos `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject`, `s3:ListBucket` sobre `arn:aws:s3:::<bucket>/*`.

## Qué pasa si falla

- Prometheus down: pierden métricas y alertas durante el outage. `prePromotionAnalysis` (smoke test) sigue funcionando (es HTTP directo, no consulta Prometheus). `postPromotionAnalysis` (error-rate) **falla** porque no puede consultar el endpoint → rollback automático del rollout. Considerar bypass manual durante un Prometheus outage.
- Loki down: se pierden logs nuevos. Fluent Bit los buffera en disco (`storage.metrics`) hasta cierto límite y luego empieza a droppear.
- Grafana down: solo afecta UI. Métricas siguen siendo recolectadas, alertas siguen disparando vía Alertmanager.
- Fluent Bit down (un nodo): se pierden logs de ese nodo durante el outage. Re-deploy del DaemonSet vuelve a leer desde el último offset persistido en `flb_kube.db`.
