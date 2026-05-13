# ADR-008 — Kustomize sobre Helm para los overlays de la app

## Contexto

La app `challenge-meli-api` se despliega en 3 ambientes (dev, test, prod) que comparten la mayoría del spec y difieren en valores acotados:

| Atributo | dev | test | prod |
|---|---|---|---|
| Replicas base | 1 | 2 | 3 |
| HPA min/max | 1/4 | 2/6 | 2/10 |
| `autoPromotionEnabled` | true | false | false |
| `prePromotionAnalysis` | — | — | smoke test |
| `postPromotionAnalysis` | — | — | error-rate |
| Log level | DEBUG | INFO | INFO |
| Ingress host | dev.… | test.… | api.… |
| ALB scheme | internal | internal | internet-facing |
| Secret path | `/meli/dev/…` | `/meli/test/…` | `/meli/prod/…` |

El pipeline tiene que bumpear el image tag por env de forma type-safe.

### Supuesto

Asumimos que la empresa ya usa Kustomize como herramienta estándar de overlays. Eso simplifica la decisión y evita meter una pieza nueva sólo para este servicio.

## La discusión

La discusión se acotó a **Helm vs Kustomize**. Ambos son lo bastante maduros, ArgoCD soporta los dos nativamente, y los dos cubren lo que necesitamos.

**Helm** brilla cuando hay 50+ valores variables o cuando se distribuye la chart como producto. Para 3 ambientes con diferencias acotadas, el peso del template engine (quote escaping, whitespace, debug de `gotpl`) no se paga.

**Kustomize** ganó por dos razones:

1. Simplifica el challenge — sin templating engine, el YAML se lee tal cual.
2. Asumimos que ya está adoptado en la organización, así que nos pegamos al estándar.

## Decisión

**Kustomize** con `base/` + `environments/<env>/`. El bump del tag opera sobre `kustomization.yaml`.

```
manifests/app/
├── base/
│   ├── namespace.yaml
│   ├── rollout.yaml
│   ├── services.yaml
│   ├── ingress.yaml
│   ├── external-secret.yaml
│   ├── hpa.yaml
│   ├── analysis-smoke-test.yaml
│   ├── analysis-error-rate.yaml
│   └── kustomization.yaml
└── environments/
    ├── dev/   { values.yaml, kustomization.yaml }
    ├── test/  { values.yaml, kustomization.yaml }
    └── prod/  { values.yaml, kustomization.yaml }
```

Por env:
- `values.yaml` — env-file (`KEY=VALUE`). Consumido por `configMapGenerator` → genera un `ConfigMap` con hash → trigger automático de rollout cuando cambia.
- `kustomization.yaml` — orquesta. `resources`, `images`, `patches`, `replicas`.

El pipeline bumpea con `yq`:

```bash
yq -i ".images[0].newTag = \"$IMAGE_TAG\"" environments/$TARGET_ENV/kustomization.yaml
yq -i ".images[0].newName = \"${ECR_REGISTRY}/${ECR_REPOSITORY}\"" environments/$TARGET_ENV/kustomization.yaml
```

## Por qué dos archivos por env (`values.yaml` + `kustomization.yaml`)

- **`values.yaml`** — sólo env vars que la app consume. Cualquiera lee este archivo y entiende "qué configs cambian por env".
- **`kustomization.yaml`** — la maquinaria Kustomize. Quien quiera ver "qué patches aplica este env" abre este.

Así evitamos que `values.yaml` se transforme en un Helm chart mal escrito.

## Por qué el bump por CI va a `kustomization.yaml` y no a `values.yaml`

Kustomize tiene un transformer nativo `images:` que matchea por `name` y reemplaza `newName` + `newTag`. `yq -i ".images[0].newTag = ..."` es:

- **Type-safe** (yq parsea YAML, no es sed/grep).
- **Idempotente**.
- **Centralizado** (un sólo punto de cambio).

## Consecuencias

### Ganamos
- **Diffs explícitos** entre base y cada overlay.
- **Trazabilidad** — cada env muestra qué patches aplica.
- **Nativo en `kubectl` y ArgoCD** — cero binarios extra.
- **`configMapGenerator` con hash** dispara rollout cuando cambian env vars.
- **Sin templating engine** — el YAML es leíble tal cual.

### Pagamos
- **`patches:` strategic merge** tiene matemática propia (anchors, `$patch: delete`) — el equipo lo aprende.
- **Si las diferencias entre envs explotan**, Kustomize se vuelve doloroso. Habría que mover a Helm. Hoy no es el caso.
- **No tenemos `helm rollback`** — el rollback es revert de commit en Git + ArgoCD sync. En la práctica es lo mismo y queda en auditoría.
