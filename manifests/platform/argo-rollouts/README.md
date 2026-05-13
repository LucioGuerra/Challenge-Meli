# argo-rollouts

Controller de progressive delivery. Reemplaza al `Deployment` nativo con el CRD `Rollout`, que soporta blue/green y canary con análisis automatizado.

## Archivos

- `install.yaml`: ArgoCD `Application` que instala el chart `argo-rollouts` v2.37.6 desde `https://argoproj.github.io/argo-helm`. Namespace: `argo-rollouts`. Incluye el dashboard web.

## Por qué Argo Rollouts y no Flagger

| Aspecto | Argo Rollouts | Flagger |
|---|---|---|
| Service mesh required | No | Sí (Istio, Linkerd, App Mesh) — overhead grande |
| ALB nativo | Sí (sin mesh) | Vía AWS App Mesh |
| Análisis con Prometheus | Sí (`AnalysisTemplate`) | Sí |
| Integración con ArgoCD | Native (ambos son argoproj) | External |
| Blue/Green | Sí | Sí pero requiere mesh |

Sin service mesh en el stack, Argo Rollouts es la elección natural.

## Por qué `Rollout` y no `Deployment`

- `Deployment` solo soporta `RollingUpdate` y `Recreate`. No hay blue/green nativo.
- `Rollout` permite: blue/green con dos Services, canary con weight progresivo, `AnalysisTemplate` que evalúa Prometheus/web/job y aborta automáticamente si falla.
- HPA funciona contra `Rollout` igual que contra `Deployment` (`scaleTargetRef.kind: Rollout`).

## Placeholders

Ninguno. La instalación no es env-specific.

## Obligatorio para producción

- `controller.replicas: 2` con leader election (default).
- `controller.podDisruptionBudget.maxUnavailable: 1`.
- `controller.metrics.serviceMonitor.enabled: true`.
- `keepCRDs: true` para que `helm uninstall` no borre las CRDs (que se llevarían los `Rollout` de todos los namespaces con cascade).

## Nice to have

- Dashboard expuesto via Ingress + auth. Por ahora `enabled: true` pero sin Ingress — accesible por `kubectl port-forward` o `kubectl argo rollouts dashboard`.

## Operar

```bash
# Estado de un rollout
kubectl argo rollouts get rollout challenge-meli-api -n challenge-meli --watch

# Promover manualmente (test env)
kubectl argo rollouts promote challenge-meli-api -n challenge-meli

# Abortar rollout activo
kubectl argo rollouts abort challenge-meli-api -n challenge-meli

# Rollback al ReplicaSet anterior
kubectl argo rollouts undo challenge-meli-api -n challenge-meli

# Dashboard local
kubectl argo rollouts dashboard
# Abre http://localhost:3100
```

## Dependencias

- ArgoCD instalado (para que la `Application` se aplique).
- No depende de otros platform components.

Es **requerido** antes de instalar `app/` porque la app usa el CRD `Rollout`.

## Qué pasa si falla

Sin el controller corriendo:
- Los CRs `Rollout` existentes siguen en el etcd, pero **no se reconcilian** → no se crean ReplicaSets nuevos en sync.
- ArgoCD reporta los `Rollout` como `Healthy` o `Suspended` según el último status escrito (stale).
- Los pods existentes siguen sirviendo tráfico (kube-controller-manager mantiene los ReplicaSets/Pods que ya existen).
- Nuevos deploys quedan congelados hasta que el controller vuelva.

Por eso `replicas: 2` y PDB son obligatorios.
