# ADR-014 — Costos como variable de diseño y trade-offs

## Contexto

El PDF pide explícitamente "costo como variable de diseño" y aclara "presupuesto razonable sin carta blanca". El equipo de DevOps tiene que ser cómplice de finanzas, no su contrincante — un sistema sin transparencia de costos termina causando una crisis.

## Decisión

**Costo ~640 USD/mes base por cluster** (3 nodos `m5.xlarge` en idle), escalando ~140 USD/mes/nodo extra durante autoscaling al peak.

Para el set completo (nonprod-eks + prod-eks): estimación **1.000-1.400 USD/mes** en `us-east-1`.

> **Cambio 2026-05-13:** la baseline previa asumía `t3.medium` (~60 USD/mes en compute). El load test demostró que `t3.medium` no sostiene el SLO bajo carga (burst credits). Se cambió a `m5.xlarge` → **~3x el costo de compute** vs el setup previo, aceptado por el SLO de latencia.

## Breakdown estimado por cluster

| Componente | Costo mensual | Notas |
|---|---|---|
| EKS control plane | 73.00 USD | Fijo por cluster |
| 3 × `m5.xlarge` 24/7 | ~420.00 USD | 0.192 USD/h × 24 × 30 × 3 |
| NAT Gateway × 2 AZs | ~64.00 USD | Sin contar data transfer |
| NAT data transfer | ~10-30 USD | ECR pulls + Loki S3 |
| ALB base | 22.00 USD | 0.0225 USD/h × 24 × 30 |
| ALB LCUs | ~30-80 USD | A 10k RPS, ~50 USD/mes |
| ECR storage | <1 USD | |
| ECR data transfer | <1 USD | Inter-AZ pulls |
| S3 (Loki + ALB logs + artifacts) | ~5-15 USD | 50-150 GB |
| CloudWatch Logs | ~5-15 USD | Sólo lo AWS-emitido |
| CloudWatch Metrics + Alarmas | ~5-10 USD | |
| 4 × CMK | 4.00 USD | 1 USD/CMK/mes |
| SNS | <1 USD | |
| WAFv2 | ~10-15 USD | Web ACL + managed rules + requests |
| Secrets Manager | ~3 USD | 6 secrets × 0.40 USD + API calls |
| CodePipeline | ~1 USD | |
| CodeBuild | ~5-10 USD | |
| Cognito | 0.00 USD | Free tier hasta 50k MAU |
| **Subtotal** | **~640 USD/mes** | Sin tráfico spike |

Con autoscaling al peak (8 nodos × `m5.xlarge`): +700 USD/mes adicionales → ~1.340 USD/mes/cluster en peak sostenido. En la práctica el spike es acotado en el tiempo → ~700-900 USD/mes/cluster realista.

Para 2 clusters:
- `nonprod-eks` con `min=2 max=4` → ~440 USD/mes baseline.
- `prod-eks` con `min=3 max=8` → 640-900 USD/mes según tráfico.

**Total estimado:** 1.000-1.400 USD/mes en operación normal.

## Trade-offs explícitos por componente

### 2 NAT Gateways en lugar de 1 → +32 USD/mes
Sin redundancia de NAT, una caída de AZ saca a la mitad de los pods de internet. Para una API enterprise con SLA, no es aceptable.

### 4 CMKs en lugar de 1 → +3 USD/mes
Aislamiento del blast radius entre EKS, ECR, Secrets Manager y Pipeline. 3 USD es trivial al lado del valor de seguridad.

### EKS Managed vs Lambda
A 10k RPS sostenidos, Lambda es mucho más caro que un cluster pequeño. EKS gana en costo a partir de ~100 RPS sostenidos.

### CloudWatch sólo lo AWS-emitido → ahorra ~30-100 USD/mes
Container Insights ingest escala linealmente con métricas custom. Prometheus + Loki en cluster cubren el caso sin pagar ingest CW.

### Loki SimpleScalable en lugar de Distributed → ahorra ~50 USD/mes
SimpleScalable cubre 100 GB - 1 TB/day; Distributed gana cuando hay >1 TB/day. No es nuestro caso.

### S3 backend para Loki en lugar de EBS → ahorra ~60-80% del costo de storage
S3 Standard = 0.023 USD/GB/mes vs EBS gp3 = ~0.08 USD/GB/mes (3.5x).

### IRSA en lugar de credenciales estáticas → costo 0
Pero ahorra mucho en incident response si una credencial leakea.

### `m5.xlarge` en lugar de `t3.medium` → +~110 USD/mes/nodo
El load test demostró que la app degrada por CPU sostenida y que `t3.medium` quema CPU credits → CPU capped al 40% baseline → SLO p99 < 500 ms imposible. `m5` es non-burstable, la CPU está siempre disponible. Trade-off **costo vs SLO**: con SLA al cliente firmado, el SLO gana.

Mitigación parcial: 4 vCPU/16 GB de m5.xlarge da ~14 api pods/nodo (vs ~5 en t3.medium) → menos nodos para el mismo HPA max → menos costo agregado por nodo.

### Cognito User Pool free tier
0 USD/mes para 50k MAU. M2M clients no cuentan como MAU.

### ECR data transfer
Egress intra-AZ: gratis. Inter-AZ: 0.01 USD/GB. Inmutabilidad + `IfNotPresent` → cada pod pull-ea sólo en el primer arranque o cambio de tag.

## Consecuencias

### Ganamos
- **Estimación honesta** del costo mensual.
- **Trade-offs explícitos** — cada gasto justificado con un valor concreto.

### Pagamos
- El costo es **alto para un proyecto sin tráfico real todavía**. 1.000-1.400 USD/mes en operación normal es real (subió ~2x respecto del setup t3.medium previo, derivado del SLO de latencia).
- Mitigaciones disponibles:
  - **Apagar nonprod fuera de horario** con CronJob + autoscaler a 0 → ~60% de ahorro en compute no laboral.
  - **Spot Instances en nodos 4-8** podrían recortar 30-50% del costo de spike.
  - **VPC endpoints** (S3, ECR, Secrets Manager) reducen NAT data transfer.
  - **Reserved Instances / Savings Plans** una vez baseline estabilizado (mes 3-6).
