# ADR-007 — Argo Rollouts blue/green con análisis automatizado

## Contexto

El challenge pide "deploy automático" y "estrategia de rollback" como entregables, y "blue/green o canary" en los bonus. La idea de fondo es no querer sorpresas a las 3 AM — el rollback de los casos esperables no debería necesitar intervención humana.

La API es stateless, no tiene migraciones de DB (no hay DB), expone health checks separados (live/ready) y emite métricas Prometheus que permiten medir error rate de forma confiable.

## Decisión

**Argo Rollouts con estrategia blue/green + dos AnalysisTemplates encadenados.**

```yaml
strategy:
  blueGreen:
    activeService:  challenge-meli-api-active
    previewService: challenge-meli-api-preview
    autoPromotionEnabled: false             # prod: gate manual; dev: true
    scaleDownDelaySeconds: 60
    abortScaleDownDelaySeconds: 30
    prePromotionAnalysis:
      templates: [{ templateName: smoke-test }]
    postPromotionAnalysis:
      templates: [{ templateName: error-rate }]
```

### `prePromotionAnalysis` — smoke test
- 3 × `GET /health/ready` al **preview** service (la versión nueva, aún sin tráfico).
- Interval 10 s, `failureLimit: 1`.
- FAIL → tráfico nunca llega al green. El blue sigue al 100%. Cero impacto.

### `postPromotionAnalysis` — error rate Prometheus
- Después de promover, query a Prometheus por rate de 5xx vs total durante 5 minutos.
- Count: 10 evaluaciones × 30 s, threshold < 1%.
- FAIL → rollback automático. Argo Rollouts re-apunta `*-active` al ReplicaSet anterior.
- PASS → blue se desescala a 0 después de `scaleDownDelaySeconds: 60`.

### Por env

| Env | `autoPromotionEnabled` | `prePromotionAnalysis` | `postPromotionAnalysis` |
|---|---|---|---|
| dev | true | (removed por patch) | (removed por patch) |
| test | false | (removed) — promoción manual | (removed) |
| prod | false | smoke-test | error-rate |

## Alternativas consideradas

### `Deployment` con `RollingUpdate`
Nativo y simple, pero no tiene preview (no podés correr smoke antes de exponer) y el rollback es manual con `kubectl rollout undo`. La métrica de error rate no dispara nada automático.

### Canary con Argo Rollouts
Tráfico gradual es lindo en papel, pero requiere **service mesh** o traffic-shaping ingress para steering exacto del %. Con sólo ALB target groups no hay split granular real. Para una API stateless, blue/green con switch atómico es suficiente.

### Flagger
Tiene capacidades similares, pero **requiere service mesh** (Istio, Linkerd, App Mesh). Meter un service mesh sólo para hacer canary no entraba en el tiempo del challenge — el costo operativo y la curva de mesh no se justifican para este alcance. Argo Rollouts hace blue/green con ALB sin mesh; Flagger no.

### Spinnaker
Otro control plane que mantener para una sola API. No.

## Por qué `prePromotionAnalysis` con un smoke test simple

El smoke test (3 GET a `/health/ready` del preview) detecta deploys rotos antes de exponer: imagen que arranca pero falla a la primera request por config rota, JWKS inalcanzable, env var faltante. Falla rápido y barato, sin tráfico real expuesto.

## Por qué `postPromotionAnalysis` con error rate

El smoke test pasa porque `/health/ready` responde 200 — eso no garantiza que las rutas reales funcionen bajo tráfico productivo. El post-analysis mira las primeras métricas del tráfico real durante 5 minutos:

```promql
sum(rate(http_server_requests_seconds_count{status=~"5..", app="challenge-meli-api"}[1m]))
  / sum(rate(http_server_requests_seconds_count{app="challenge-meli-api"}[1m]))
```

Si la versión nueva dispara 5xx, en 5 minutos lo notamos y revertimos automáticamente. El blue se mantiene vivo mientras corre este análisis, así que el rollback es instantáneo.

## Consecuencias

### Ganamos
- **Rollback automático** sin acción humana en los failure modes esperables.
- **Switch atómico** del tráfico (selector flip en ms).
- **Auditable**: cada Rollout queda en etcd con su revisión.
- **Compatible con HPA**.
- **Sin service mesh** — ahorro grande de complejidad.

### Pagamos
- **2 × replicas durante el rollout** (blue + green). Para `replicas: 3` en prod, son 6 pods un rato.
- **CRDs nuevas** (`Rollout`, `AnalysisTemplate`) — el equipo las aprende.
- **Dependencia de Prometheus** para el postPromotionAnalysis. Si Prometheus se cae, el análisis falla → rollback espurio. Compromiso aceptado: preferimos eso a deployar ciego sin métricas.
