# app/base

Kustomize base con los objetos K8s genéricos de `challenge-meli-api`. Los overlays en `app/environments/<env>` consumen este base y aplican patches por ambiente.

## Archivos

| Archivo | Qué define |
|---|---|
| `namespace.yaml` | Namespace `challenge-meli` (Pod Security `restricted`) + ServiceAccount `challenge-meli-api` |
| `rollout.yaml` | CRD `Rollout` (Argo Rollouts) con blue/green strategy + `PodDisruptionBudget` |
| `services.yaml` | Service `*-active` (tráfico real) + Service `*-preview` (nueva versión) + ServiceMonitor para Prometheus |
| `ingress.yaml` | `Ingress` con annotations del AWS Load Balancer Controller (target-type=ip, TLS, healthcheck) |
| `external-secret.yaml` | `ClusterSecretStore` apuntando a AWS Secrets Manager + `ExternalSecret` que sincroniza `/meli/<env>/challenge-meli-api` → `Secret` |
| `hpa.yaml` | `HorizontalPodAutoscaler` con `scaleTargetRef` al `Rollout` (no a un Deployment) |
| `analysis-smoke-test.yaml` | `AnalysisTemplate` que hace 3 HTTP GET a `/health/ready` del preview service |
| `analysis-error-rate.yaml` | `AnalysisTemplate` que consulta Prometheus por tasa de 5xx durante 5 min |
| `argocd-application.yaml` | 3 `Application` (dev/test/prod) — bootstrap manual, NO está en `kustomization.yaml` |
| `kustomization.yaml` | Lista de resources + commonLabels |

## Decisiones de diseño

### Rollout con blue/green (no canary, no Deployment)

- **Blue/green** elegido sobre canary porque la app es stateless y nos importa más "switch atómico + análisis automatizado" que "exposición gradual".
- **Rollout** sobre `Deployment` porque `Deployment` no soporta blue/green nativo (solo `RollingUpdate` y `Recreate`). Argo Rollouts añade el flujo de análisis automatizado pre y post promoción.

### Dos Services

- `challenge-meli-api-active`: el que recibe tráfico real. Selector lo gestiona Argo Rollouts (apunta al ReplicaSet activo via `rollouts-pod-template-hash`).
- `challenge-meli-api-preview`: apunta al ReplicaSet green durante un rollout en curso. Es lo que usa `prePromotionAnalysis` para hacer smoke test sin exponer al tráfico real.
- Ambos exponen el puerto 80 → 8080. El `*-active` también expone el puerto 8080 como `metrics` para que `ServiceMonitor` lo scrape.

### Sin `Deployment` separado

El `Rollout` reemplaza al `Deployment`. El `replicas` viene del `Rollout` (overrideado por overlay). HPA tiene `scaleTargetRef.kind: Rollout` — el HPA controller soporta esto cuando hay `scale` subresource (Argo Rollouts lo provee).

### `envFrom` (no `env` individual)

El pod template usa:

```yaml
envFrom:
  - configMapRef:
      name: challenge-meli-api-env       # generado por configMapGenerator del env
  - secretRef:
      name: challenge-meli-api-secrets   # generado por ExternalSecret
```

Razón: añadir una nueva env var es agregar una línea al `values.yaml` del env, sin tocar el Rollout. Y un cambio en el ConfigMap (hash nuevo) triggerea un rollout completo con blue/green + análisis.

`SERVER_PORT` y `JAVA_TOOL_OPTIONS` van inline porque son técnicas (no de negocio) y no varían por env.

### Probes diferenciados

- `livenessProbe` → `/health/live`. `failureThreshold: 3` × `periodSeconds: 15` = pod restart después de 45 s de fallar.
- `readinessProbe` → `/health/ready`. Quita el pod del Service si falla. La app incluye en `readinessState` checks a Prometheus, ArgoCD, Alertmanager (configurado en `application.yml`).
- `startupProbe` → `/health/live` con 30 retries × 5 s = 150 s para arrancar. Cubre cold start de Spring Boot + JVM warm-up. Mientras corre, liveness/readiness no actúan.

### Security context

- `runAsNonRoot: true`, `runAsUser: 1001` (matchea el `app` user del Dockerfile).
- `readOnlyRootFilesystem: true` + emptyDir `/tmp` writable (Spring necesita escribir tmp en arranque).
- `capabilities.drop: [ALL]`, `allowPrivilegeEscalation: false`, `seccompProfile: RuntimeDefault`.
- Cumple Pod Security Admission `restricted`.

### `automountServiceAccountToken: false`

La app no llama a la K8s API → no necesita token de SA. Reduce superficie de ataque.

### ServiceAccount sin IRSA

La app no llama AWS APIs directamente (los secretos los baja ESO y los expone como Secret nativo). Por eso el SA no tiene anotación IRSA. Si en el futuro la app necesita llamar a AWS, agregar:

```yaml
metadata:
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::123:role/challenge-meli-api
```

### Ingress: target-type `ip` (no `instance`)

- `ip`: ALB target group apunta directo a pod IPs. Sin hop por NodePort. Lower latency.
- Requiere `aws-load-balancer-controller` (lo cumple).
- HC del ALB a `/health/live` (no `/health/ready`) para no quitar pods con dependencias externas momentáneamente caídas. K8s readiness sigue gestionando el Service membership.

### `scaleDownDelaySeconds: 60`

Si la nueva versión cae justo después de promotion, el blue (anterior) sigue corriendo 60 s → rollback es instantáneo (`kubectl argo rollouts undo`).

### `prePromotionAnalysis` vs `postPromotionAnalysis`

- **Pre**: smoke test al preview ANTES de mover tráfico. Si falla, el tráfico nunca llega al green → cero impacto al usuario.
- **Post**: error-rate query a Prometheus DESPUÉS de mover tráfico, durante 5 min. Si la tasa de 5xx > 1%, rollback automático al blue.
- Ambos son `failureLimit: 1` (cero tolerancia). `count` controla cuántas evaluaciones se hacen.

### HPA con CPU como única métrica

- **CPU target 60%**: el load test (ver [ADR-021](../../../decisiones/ADR-021-load-test-capacity.md)) mostró que la app se degrada por saturación de CPU antes que por memoria. 60% deja ~40% headroom para absorber spikes mientras los pods nuevos arrancan (cold start Spring 30-60 s). 70-80% rompe SLO antes de reaccionar.
- **Min 3 / max 80**: min = 1 pod por AZ (3 AZs). Max derivado del SLA del cliente (10k RPS) y el RPS-por-pod observado (~120). Re-baselinear cuando los clients sean HTTP reales.
- **Memoria fuera del HPA**: la JVM retiene heap aunque no esté en uso → métrica ruidosa. Causaría escalado innecesario.
- **`scaleUp`**: stabilization 30 s + máx **4 pods/min** (`Pods` policy, no `Percent`). Alineado con la velocidad del Cluster Autoscaler para no pedir nodos en avalancha.
- **`scaleDown`**: stabilization 300 s + máx **2 pods/min**. Conservador — JVM warmup pagado, mejor pagar pods extra unos minutos que oscilar.

## Por qué `argocd-application.yaml` no está en `kustomization.yaml`

Si lo incluyéramos, ArgoCD aplicaría sus propias Applications via sí mismo → loop conceptual + recursión que ensucia los diffs. La práctica estándar es:

- Las Applications son **bootstrap** (kubectl apply una sola vez).
- A partir de ahí ArgoCD se autogestiona via su sync.

Si querés gestionarlas via GitOps (App-of-Apps pattern), mover `argocd-application.yaml` a un repo/path separado y crear una `Application` root que apunte ahí.

## Placeholders

| Placeholder | Dónde | Reemplazar por |
|---|---|---|
| `PLACEHOLDER_AWS_REGION` | `external-secret.yaml` | región AWS del cluster |
| `PLACEHOLDER_AWS_SECRET_PATH` | `external-secret.yaml` | overrideado por overlay con `/meli/<env>/challenge-meli-api` |
| `PLACEHOLDER_ACM_CERT_ARN` | `ingress.yaml` | overrideado por overlay |
| `PLACEHOLDER_API_HOSTNAME` | `ingress.yaml` | overrideado por overlay |
| `PLACEHOLDER_MANIFEST_REPO_URL` | `argocd-application.yaml` | URL HTTPS del repo de manifests |

Los marcados como "overrideado por overlay" sirven solo si alguien aplica el base directamente (debug). En el flujo normal cada overlay los reemplaza.

## Dependencias

- Argo Rollouts controller (CRD `Rollout`, `AnalysisTemplate`).
- External Secrets Operator (CRD `ClusterSecretStore`, `ExternalSecret`).
- AWS Load Balancer Controller (Ingress class `alb`).
- Prometheus Operator (CRD `ServiceMonitor`).
- AWS Secrets Manager con secreto `/meli/<env>/challenge-meli-api` (keys: `API_USERNAME`, `API_PASSWORD`, `MONITORING_USERNAME`, `MONITORING_PASSWORD`).
