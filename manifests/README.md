# manifests

Repo GitOps observado por ArgoCD para `challenge-meli-api`. Cubre 2 clusters EKS (prod y nonprod) y 3 ambientes (dev, test, prod).

## Layout

```
manifests/
├── platform/           # componentes cluster-wide (instalar una vez por cluster)
│   ├── aws-load-balancer-controller/
│   ├── external-secrets/
│   ├── argo-rollouts/
│   └── observability/  # kube-prometheus-stack + grafana + loki + fluent-bit
└── app/                # la app challenge-meli-api
    ├── base/           # Kustomize base
    └── environments/   # overlays por env (dev, test, prod)
```

## Orden de instalación de platform components

Estricto: cada paso depende del anterior por CRDs o por integraciones runtime.

1. **aws-load-balancer-controller** → necesario para que Ingresses (ALB) funcionen. Sin esto los Ingress quedan en pending.
2. **external-secrets** → instala las CRDs `ClusterSecretStore` / `ExternalSecret` que usan la app y otras pieces (Grafana admin password).
3. **argo-rollouts** → instala las CRDs `Rollout` y `AnalysisTemplate` que usa la app.
4. **observability** → kube-prometheus-stack, Grafana, Loki, Fluent Bit. La app referencia ServiceMonitor (Prometheus Operator CRD) y emite logs que Fluent Bit envía a Loki.
5. **app** → finalmente la app vía las 3 ArgoCD Applications de `app/base/argocd-application.yaml`.

Cada Application incluye `syncOptions: CreateNamespace=true` para que el namespace target se cree solo. Las CRDs se instalan con `ServerSideApply=true` para evitar problemas de tamaño en metadata.

## Cómo ArgoCD observa este repo

Bootstrap manual una sola vez por cluster:

```bash
# En cluster de management (o nonprod-eks si ArgoCD vive ahí):
kubectl apply -n argocd -f manifests/platform/aws-load-balancer-controller/install.yaml
kubectl apply -n argocd -f manifests/platform/external-secrets/install.yaml
kubectl apply -n argocd -f manifests/platform/argo-rollouts/install.yaml
# Observability se instala con 4 Applications adicionales — ver platform/observability/README.md

# Apps de la app:
kubectl apply -n argocd -f manifests/app/base/argocd-application.yaml
```

A partir de ahí ArgoCD detecta cualquier commit a `main` y sincroniza automáticamente. `selfHeal: true` corrige drift manual.

## Flujo blue/green end-to-end (prod)

1. CodeBuild bumpea `manifests/app/environments/prod/kustomization.yaml` → `images[0].newTag = <commit-sha>`.
2. ArgoCD detecta el commit, sincroniza el `Rollout` de la Application `challenge-meli-api-prod`.
3. Argo Rollouts levanta el ReplicaSet **green** (preview), apuntado por `challenge-meli-api-preview` Service.
4. `prePromotionAnalysis` ejecuta `analysis-smoke-test`: 3 GET a `/health/ready` del preview cada 10 s. `failureLimit: 1`.
   - FAIL → Rollout aborta, blue sigue recibiendo el 100% del tráfico, green se desescala.
   - PASS → Argo Rollouts cambia el selector del Service `*-active` al ReplicaSet green. Tráfico se mueve instantáneamente.
5. `postPromotionAnalysis` ejecuta `analysis-error-rate`: query Prometheus por error rate 5xx durante 5 min (count: 10, interval: 30 s). Threshold: < 1%.
   - FAIL → rollback automático al blue. Argo Rollouts re-apunta `*-active` al ReplicaSet anterior. Green se desescala.
   - PASS → blue se escala a 0 después de 60 s (`scaleDownDelaySeconds`).
6. Deploy completado sin intervención humana.

Para **dev** se salta el análisis (`autoPromotionEnabled: true`). Para **test** el operador promueve manualmente con `kubectl argo rollouts promote challenge-meli-api -n challenge-meli`.

## Rollback

### Automático
Lo manejan los `prePromotionAnalysis` y `postPromotionAnalysis` del Rollout. No requiere acción humana.

### Manual (durante un rollout activo)
```bash
kubectl argo rollouts abort challenge-meli-api -n challenge-meli
```
Aborta la promoción al green. El blue sigue activo.

### Manual (después de promoción completada)
Revertir el commit del bump y push a `main`. ArgoCD sincroniza y Argo Rollouts inicia un nuevo blue/green con la imagen anterior (que pasa por su propio análisis).

Alternativa de emergencia (skip ArgoCD):
```bash
kubectl argo rollouts undo challenge-meli-api -n challenge-meli
```
Esto crea un drift que ArgoCD intentará revertir por `selfHeal: true` — usar solo si vas a revertir el commit inmediatamente.

## Por qué Fluent Bit (y no Promtail ni Grafana Alloy)

- **Promtail**: deprecado por Grafana Labs en 2025. No es opción viable para algo nuevo.
- **Grafana Alloy**: agente unificado (logs + métricas + traces). Buena opción si necesitás colectar todo desde un solo binario. En este stack **Prometheus ya scrape-ea métricas vía ServiceMonitor**, así que el caso de uso unificado no aplica. Alloy agrega complejidad de config sin beneficio acá.
- **Fluent Bit**:
  - Hace una sola cosa (log forwarding) y la hace bien.
  - Agente oficialmente recomendado por AWS para EKS (`aws-for-fluent-bit` es Fluent Bit).
  - DaemonSet liviano: ~30 MB RAM por nodo vs ~150 MB de Alloy.
  - Parser nativo para multiline (Java stack traces) y JSON estructurado.
  - Output plugin para Loki en `tree`, sin necesidad de gateway de Loki Push API custom.

## Convenciones

- Sin comentarios inline en YAML. Razonamiento en estos READMEs.
- Sin secretos plaintext. Todo viene de AWS Secrets Manager via External Secrets Operator.
- Namespace `challenge-meli` con Pod Security Admission en modo `restricted`.
- Labels consistentes: `app.kubernetes.io/name`, `app.kubernetes.io/part-of: challenge-meli`, `app.kubernetes.io/managed-by: argocd`.
