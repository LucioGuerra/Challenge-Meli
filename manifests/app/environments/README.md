# app/environments

Overlays Kustomize por ambiente. Cada uno consume `../../base` y aplica patches específicos.

## Estructura por env

```
<env>/
├── values.yaml          # KEY=VALUE env vars consumidas por la app
└── kustomization.yaml   # Kustomize overlay (resources + patches + images + replicas)
```

## Por qué dos archivos por env

El spec pedía `values.yaml`. Con Kustomize hace falta un `kustomization.yaml`. Solución:

- **`values.yaml`** = env-file (`KEY=VALUE`) consumido por `configMapGenerator` → genera `ConfigMap` `challenge-meli-api-env`. Solo lleva valores que la app lee como env vars: `ENVIRONMENT`, `LOG_LEVEL`, `PROMETHEUS_URL`, `ARGOCD_URL`, `ALERTMANAGER_URL`, `API_PAGE_DEFAULT_SIZE`.
- **`kustomization.yaml`** = orquesta. Define `replicas`, `images`, `patches` (Rollout strategy, HPA limits, Ingress host/scheme/cert, ExternalSecret AWS path).

El pipeline CodeBuild stage 04 bumpea **`kustomization.yaml`** (`images[0].newTag` y `images[0].newName`), no `values.yaml`. Razón: Kustomize lee `images:` nativamente.

## Matriz de diferencias

### dev

```yaml
replicas: 1
images[].newTag: <commit-sha del último build de dev>
patches:
  Rollout:
    autoPromotionEnabled: true       # rápido, sin gate manual
    prePromotionAnalysis: <removed>
    postPromotionAnalysis: <removed>
  HPA:
    minReplicas: 1
    maxReplicas: 4
  Ingress:
    host: dev.challenge-meli.<base>
    scheme: internal                 # solo VPC
  ExternalSecret:
    awsKey: /meli/dev/challenge-meli-api
```

### test

```yaml
replicas: 2
images[].newTag: <commit-sha del último build promovido a test>
patches:
  Rollout:
    autoPromotionEnabled: false      # promoción manual con kubectl argo rollouts promote
    prePromotionAnalysis: <removed>
    postPromotionAnalysis: <removed>
  HPA:
    maxReplicas: 6
  Ingress:
    host: test.challenge-meli.<base>
    scheme: internal
  ExternalSecret:
    awsKey: /meli/test/challenge-meli-api
```

### prod

```yaml
replicas: 3
images[].newTag: <commit-sha del último build promovido a prod>
# Sin remove de analyses — base ya las tiene
patches:
  Ingress:
    host: api.challenge-meli.<base>
    scheme: internet-facing          # público
  ExternalSecret:
    awsKey: /meli/prod/challenge-meli-api
```

## Bumpeo del tag por CI

Pipeline `cicd/04-update-manifests.yml`:

```bash
export VALUES_FILE="environments/${TARGET_ENV}/kustomization.yaml"
yq -i ".images[0].newTag = \"$IMAGE_TAG\"" "$VALUES_FILE"
yq -i ".images[0].newName = \"${ECR_REGISTRY}/${ECR_REPOSITORY}\"" "$VALUES_FILE"
git commit -m "chore(${TARGET_ENV}): bump image to ${IMAGE_TAG} [skip ci]"
git push origin HEAD:main
```

ArgoCD detecta el commit, sincroniza el `Rollout`, Argo Rollouts ejecuta blue/green (con/sin análisis según env).

## Crear un nuevo ambiente

1. `cp -r environments/test environments/staging`
2. Editar `staging/values.yaml` (env vars específicas).
3. Editar `staging/kustomization.yaml` (replicas, host, cert, secret path).
4. Agregar una nueva `Application` en `app/base/argocd-application.yaml`.
5. Agregar un stage `DeployStaging` en CodePipeline con `TARGET_ENV=staging`.

## Renderizar localmente

```bash
kubectl kustomize environments/dev | less
kubectl kustomize environments/prod | less
```

## Aplicar manualmente (sin ArgoCD)

```bash
# Solo para debugging — el path correcto es via ArgoCD.
kubectl apply -k environments/dev --context nonprod-eks
```

## Dependencias

Heredadas del `base/`. Ver `../base/README.md`.
