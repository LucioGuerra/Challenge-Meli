# app

Manifests de la aplicación `challenge-meli-api`. Estructurados como Kustomize: un `base/` con manifests genéricos + 3 overlays en `environments/` (dev, test, prod).

## Layout

```
app/
├── base/                       # K8s objects genéricos, sin nada env-specific
│   ├── namespace.yaml
│   ├── rollout.yaml            # Argo Rollouts CRD (blue/green)
│   ├── services.yaml           # active + preview + ServiceMonitor
│   ├── ingress.yaml            # ALB Ingress
│   ├── external-secret.yaml    # ClusterSecretStore + ExternalSecret
│   ├── hpa.yaml                # HPA target=Rollout
│   ├── analysis-smoke-test.yaml
│   ├── analysis-error-rate.yaml
│   ├── argocd-application.yaml # 3 Apps (dev/test/prod) — bootstrap manual
│   └── kustomization.yaml
└── environments/
    ├── dev/   { values.yaml, kustomization.yaml }
    ├── test/  { values.yaml, kustomization.yaml }
    └── prod/  { values.yaml, kustomization.yaml }
```

## Helm vs Kustomize: por qué Kustomize

- Kustomize está integrado en `kubectl` y en ArgoCD nativamente. Cero binarios extra.
- `images:` transformer permite que el pipeline CodeBuild bumpee el image tag con `yq` de forma type-safe.
- Patches estratégicos son trazables (cada env muestra exactamente qué cambia respecto al base).
- Helm sería natural si hubiera 50 valores variables. Acá hay 3 ambientes con diferencias acotadas (replicas, autoPromotion, host, cert ARN, log level).

## Estructura `values.yaml` + `kustomization.yaml` por env

El spec original pedía `environments/<env>/values.yaml`. Para que esto trabaje con Kustomize:

- **`values.yaml`**: archivo en formato env-file (`KEY=VALUE`). Contiene **solo env vars** que la app consume (`ENVIRONMENT`, `LOG_LEVEL`, `PROMETHEUS_URL`, etc). Es leído por `configMapGenerator` en el `kustomization.yaml` y se materializa como `ConfigMap` `challenge-meli-api-env` (con hash → triggers de rollout cuando cambia).
- **`kustomization.yaml`**: orquesta. Hace `resources: [../../base]`, configMapGenerator de `values.yaml`, define replicas/images/patches específicos del env.

## Bump del image tag

Hecho por CodeBuild stage 04 (`cicd/04-update-manifests.yml`):

```bash
yq -i ".images[0].newTag = \"$IMAGE_TAG\"" environments/$TARGET_ENV/kustomization.yaml
yq -i ".images[0].newName = \"${ECR_REGISTRY}/${ECR_REPOSITORY}\"" environments/$TARGET_ENV/kustomization.yaml
```

El target del bump es `kustomization.yaml` (no `values.yaml`), porque Kustomize lee `images[].newTag` nativamente y propaga el cambio a todos los containers que matcheen `name: challenge-meli-api`.

## ArgoCD Applications

`app/base/argocd-application.yaml` contiene 3 `Application`:

| Name | Source path | Destination cluster |
|---|---|---|
| `challenge-meli-api-dev` | `manifests/app/environments/dev` | `nonprod-eks` |
| `challenge-meli-api-test` | `manifests/app/environments/test` | `nonprod-eks` |
| `challenge-meli-api-prod` | `manifests/app/environments/prod` | `prod-eks` |

Las 3 Apps se aplican una sola vez (bootstrap) en el cluster de mgmt:

```bash
kubectl apply -f manifests/app/base/argocd-application.yaml
```

Luego ArgoCD las gestiona sin intervención.

Nota: `argocd-application.yaml` **no** está en `base/kustomization.yaml` → no se aplica al sync de la app, solo se usa como bootstrap manifest. Esto evita el bucle "una Application que se aplica a sí misma".

## Diferencias por ambiente (resumen)

| Atributo | dev | test | prod |
|---|---|---|---|
| Replicas base | 1 | 2 | 3 |
| HPA min/max | 1/4 | 2/6 | 2/10 |
| `autoPromotionEnabled` | true | false | false |
| `prePromotionAnalysis` | — (removed) | — (removed) | smoke test |
| `postPromotionAnalysis` | — (removed) | — (removed) | error-rate |
| Log level | DEBUG | INFO | INFO |
| Ingress host | `dev.challenge-meli.<base>` | `test.challenge-meli.<base>` | `api.challenge-meli.<base>` |
| ALB scheme | `internal` | `internal` | `internet-facing` |
| Secret path | `/meli/dev/challenge-meli-api` | `/meli/test/...` | `/meli/prod/...` |

## Validar el render localmente

```bash
# Render dev
kubectl kustomize manifests/app/environments/dev

# Render + diff vs cluster
kubectl diff -k manifests/app/environments/dev

# Aplicar manualmente (sin ArgoCD, solo para debug)
kubectl apply -k manifests/app/environments/dev
```

## Dependencias

- `platform/argo-rollouts` → CRD `Rollout`, `AnalysisTemplate`.
- `platform/external-secrets` → CRD `ExternalSecret`, `ClusterSecretStore`.
- `platform/aws-load-balancer-controller` → para que `Ingress` se materialice como ALB.
- `platform/observability` (Prometheus Operator) → CRD `ServiceMonitor`.
- AWS Secrets Manager con secretos en `/meli/<env>/challenge-meli-api` con keys: `API_USERNAME`, `API_PASSWORD`, `MONITORING_USERNAME`, `MONITORING_PASSWORD`.
- ECR repo `challenge-meli-api` con imágenes pusheadas por el pipeline.
