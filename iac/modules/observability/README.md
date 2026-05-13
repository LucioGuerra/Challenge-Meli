# observability

## Qué hace este módulo

Provisiona la capa de observabilidad **de infraestructura AWS** en CloudWatch — la parte que no podemos no tener en CloudWatch porque la genera el propio AWS o porque debe ser independiente del cluster: log group de CodeBuild, CMK dedicada para los logs, dos alarmas críticas sobre el ALB (5xx %, p99 latency) y un topic SNS para notificar. Todo el monitoreo de Kubernetes/nodos vive en Prometheus dentro del cluster (ver `manifests/platform/observability`).

## Recursos que crea

- `aws_kms_key` + `aws_kms_alias` para CloudWatch Logs
- `aws_cloudwatch_log_group` `/aws/codebuild/meli-devops-<env>` (retención 14d)
- `aws_sns_topic` `meli-devops-<env>-alerts` + suscripción email
- `aws_cloudwatch_metric_alarm` × 2: ALB 5xx % (>1%) y ALB p99 latency (>500ms)
- `aws_cloudwatch_dashboard` con 2 widgets ALB (volumen+errores, latency p50/p95/p99)

## Decisiones de diseño

- **CloudWatch sólo para lo que AWS emite nativamente.** ALB metrics, control plane EKS, CodeBuild, VPC Flow Logs y ALB access logs (en S3) viven en AWS y se quedan en CloudWatch. Todo lo demás (nodos, pods, app) va a Prometheus.
- **Sin alarmas de CPU/Memory de nodos.** El Cluster Autoscaler ya reacciona a la presión de recursos creando nodos antes de que se llegue a un umbral problemático. Una alarma a 80% llega tarde. Las alertas que importan (CA no pudo escalar, max_size alcanzado, nodos en NotReady, pods Pending por mucho tiempo) viven en Prometheus AlertManager con las `defaultRules` de `kube-prometheus-stack` ya activadas (`nodeExporterAlerting`, `kubernetesResources`, `kubernetesSystem`).
- **Alarma 5xx como expresión `5XX / RequestCount * 100`.** Una alarma sobre conteo absoluto se rompe a baja carga (1 error / 5 req = 20%, alarma justa pero ruidosa) y a alta carga (10 / 100k = 0.01%, falsa negativa). La tasa es lo correcto.
- **`treat_missing_data="notBreaching"`.** Período sin tráfico no es un fallo; evita ruido en ventanas de baja carga.
- **KMS policy del log key con condition `kms:EncryptionContext:aws:logs:arn`.** Restringe el uso de la CMK al servicio CloudWatch Logs sobre log groups de esta cuenta — blast radius acotado si la CMK se compartiera por accidente.
- **SNS con `alias/aws/sns`.** CMK gestionada por AWS basta para mensajes de alarma (no contienen secretos).

## Trade-offs

- **Dos planos de alertado (CloudWatch para ALB + Prometheus AlertManager para el resto).** Más complejidad operativa que un único pane, pero cada herramienta corre donde tiene los datos: CloudWatch ya tiene ALB metrics sin esfuerzo y no depende del cluster; Prometheus tiene visibilidad fina del cluster sin pagar ingest a CloudWatch.
- **Sólo 2 alarmas críticas en CW.** Less is more — más alarmas → fatigue → ignorancia. Estas 2 cubren los failure modes que el cluster no puede reportar por sí mismo (porque el ALB es AWS-managed).
- **Email único como receiver.** No hay PagerDuty/Slack en este módulo; cualquier extensión va por subscripción adicional al mismo topic.
- **Retención de log group de CodeBuild=14d.** Suficiente para post-mortem de builds recientes; los buildspecs ya guardan el output en S3 a más largo plazo.

## División de responsabilidades: CloudWatch vs Prometheus

| Métrica / log | Vive en | Por qué |
|---|---|---|
| ALB 5xx, latency, RequestCount | CloudWatch | El ALB es AWS-managed y emite a CW; no hay exporter trivial |
| VPC Flow Logs | CloudWatch | AWS-emitted, mejor querear con Logs Insights |
| Control plane EKS (api/audit/auth/cm/sch) | CloudWatch | AWS-managed, no se puede redirigir |
| CodeBuild logs | CloudWatch | AWS-managed |
| ALB access logs | S3 | Volumen alto, queryeable con Athena |
| Node CPU/Memory/Disk | Prometheus (node-exporter) | Ya scrapeado, sin costo ingest, alertas vía AlertManager |
| kube-state-metrics (pods, deployments, jobs) | Prometheus | Idem |
| Estado del Cluster Autoscaler | Prometheus | El CA expone métricas Prometheus nativas |
| App logs (stdout/stderr de pods) | Loki vía Fluent Bit | LogQL > Logs Insights, costo agresivamente bajo |
| Métricas custom de la API | Prometheus | ServiceMonitor sobre `/actuator/prometheus` |

Romper esta división — meter métricas de nodos o app en CloudWatch — sería pagar duplicado y acoplar el debugging del cluster al estado de la cuenta AWS.

## Alternativas descartadas

- **CloudWatch Container Insights:** se evaluó como fuente única para métricas de cluster, pero (1) requiere `amazon-cloudwatch-observability` addon o agente; (2) emite todo a CW namespace `ContainerInsights` con costo por métrica custom; (3) duplica lo que Prometheus ya tiene scrapeado vía `node-exporter` + `kube-state-metrics`. Descartado por costo y duplicación.
- **Prometheus federation hacia CloudWatch:** duplica métricas con costo lineal en CW ingest.
- **Loki en lugar de CloudWatch para logs de control plane:** Loki no puede recibir logs del control plane EKS (son AWS-managed).
- **Alarmas composite:** valioso para reducir flapping cuando varias condiciones deben coincidir; over-engineering para el alcance actual.

## Variables de entrada

| Nombre | Tipo | Descripción | Requerido |
|---|---|---|---|
| `environment` | string | Sufijo en nombres | sí |
| `tags` | map(string) | Tags base | sí |
| `region` | string | Región (KMS policy) | sí |
| `account_id` | string | Account ID (KMS policy) | sí |
| `alb_arn` | string | ARN del ALB (deriva dimensión de métricas) | sí |
| `alerts_email` | string | Email de la subscripción SNS | sí |

## Outputs

| Nombre | Descripción |
|---|---|
| `alerts_topic_arn` | Topic SNS de alertas |
| `codebuild_log_group_name` | Log group de CodeBuild |
| `log_group_arns` | ARNs de los log groups creados |
| `dashboard_arn` | ARN del dashboard |

## Dependencias entre módulos

Entrante: `alb` (alb_arn). Saliente: `cicd` consume `codebuild_log_group_name`. El log group de VPC Flow Logs lo crea el módulo `networking`, no este. El log group del control plane EKS lo crea el módulo `eks` directamente.
