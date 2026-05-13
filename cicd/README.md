# cicd — Pipeline definition

Buildspecs de AWS CodeBuild que ejecutan las 4 fases del pipeline. La orquestación (CodePipeline con 9 stages + aprobaciones manuales) la crea Terraform en [`iac/modules/cicd`](../iac/modules/cicd/README.md). Este directorio es el entregable `/cicd` del [challenge](../Challenge%20DevOps.pdf).

> Decisión clave: el pipeline **nunca toca el cluster directamente**. Termina bumpeando el image tag en el manifest repo. ArgoCD detecta el commit y sincroniza. Es GitOps puro. Fundamentación en [ADR-010](../decisiones/ADR-010-codepipeline.md).

---

## Layout

```
cicd/
├── 01-test.yml               # Stage 2  — Unit tests + JaCoCo coverage gate (≥80%)
├── 02-code-quality.yml       # Stage 3  — SonarQube + Quality Gate (mocked)
├── 03-build-and-scan.yml     # Stage 4  — Docker build + Trivy scan (real) + push ECR
└── 04-update-manifests.yml   # Stages 5/7/9 — Bump tag en manifests por env (dev/test/prod)
```

`04-update-manifests.yml` se reutiliza en 3 stages distintos del pipeline. `TARGET_ENV` se inyecta como override por stage (`dev`, `test`, `prod`).

---

## Las 9 stages del pipeline

```
1. Source            CodeStar Connection → GitHub commit a main
2. UnitTests         01-test.yml          (gate: coverage ≥ 80%)
3. CodeQuality       02-code-quality.yml  (gate: Sonar Quality Gate = OK)
4. BuildAndScan      03-build-and-scan.yml (gate: Trivy HIGH/CRITICAL = 0)
5. DeployDev         04-update-manifests.yml  TARGET_ENV=dev   → ArgoCD sync nonprod-eks
6. ApproveTest       Manual approval vía SNS email
7. DeployTest        04-update-manifests.yml  TARGET_ENV=test  → ArgoCD sync nonprod-eks
8. ApproveProd       Manual approval vía SNS email
9. DeployProd        04-update-manifests.yml  TARGET_ENV=prod  → ArgoCD sync prod-eks
```

Las stages 5/7/9 son el mismo buildspec porque el bump es estructuralmente idéntico — sólo cambia el override `TARGET_ENV` y el rol IAM ya tiene granularidad por proyecto, no por env (decisión: la separación por env vive en el flujo del pipeline, no en los permisos).

---

## Por qué CodePipeline + CodeBuild (no GitHub Actions)

Detalle en [ADR-010](../decisiones/ADR-010-codepipeline.md). Resumen:

| Razón | Detalle |
|---|---|
| **Secrets nativos** | CodeBuild lee `Secrets Manager` y `Parameter Store` directo (`env.secrets-manager` y `env.parameter-store`). GH Actions necesita OIDC + step de fetch. |
| **IAM por proyecto** | 4 roles IAM separados (uno por buildspec) con permisos exactos. En GH Actions sería un único OIDC role o secretos compartidos. |
| **Aprobaciones manuales** | Stages `Approval` con SNS email out-of-the-box. En GH Actions se hace con environments + reviewers, válido pero más limitado. |
| **VPC** | El test project corre en VPC (acceso a recursos internos). GH Actions necesitaría runners self-hosted. |
| **Auditoría** | EventBridge → SNS publica cada transición del pipeline. Cero código custom. |
| **CMK propio** | Pipeline tiene CMK dedicada para artifacts; KMS policy separada del resto. |

Trade-off explícito: GH Actions sería más rápido para iterar el YAML (más comunidad, más actions disponibles). CodePipeline gana en seguridad y aislamiento, que es lo que importa para producción enterprise.

---

## Convenciones de configuración

Tres tipos de variables:

| Tipo | Para qué | Ejemplo |
|---|---|---|
| **Parameter Store (`/meli/config/*`)** | Config no sensible, puede cambiar sin tocar el YAML | `MIN_COVERAGE`, `ECR_REGISTRY`, `TRIVY_SEVERITY`, `MANIFEST_REPO` |
| **Secrets Manager (`/meli/pipeline/*`)** | Sensible, se rota | `SONAR_TOKEN`, `GITHUB_TOKEN` |
| **Plaintext** | Flag técnico estable + override dinámico del pipeline | `DOCKER_BUILDKIT=1`, `TARGET_ENV` (override por stage) |

Regla práctica: **si tenés que cambiar un threshold (coverage, severity, etc.), cambiás el Parameter Store, no el YAML**. El próximo run lo toma. Esto evita PRs cosméticos al repo de pipeline.

---

## Detalle por stage

### Stage 2 — `01-test.yml` · Unit Tests + Coverage Gate

```
Inputs:  source code
Outputs: jacoco_report (JaCoCo XML), surefire_report (JUnit XML)
Gate:    coverage ≥ ${MIN_COVERAGE}% (Parameter Store, hoy en 80)
```

- Runtime: Corretto 21 + Maven.
- Cache: `/root/.m2/**/*` (acelera build incremental).
- Reports: JaCoCo y Surefire publicados al panel de CodeBuild.
- Coverage calculado con `awk` sobre `jacoco.csv`. Si está debajo del threshold, **exit 1** y el pipeline corta.
- IAM: `ssm:GetParameters` sobre `/meli/config/*`. Sin acceso a ECR, Secrets, manifest repo. Si este stage se compromete (ej. dependencia transitiva maliciosa), el blast radius es nulo.

### Stage 3 — `02-code-quality.yml` · SonarQube + Quality Gate (mocked)

```
Inputs:  source code + JaCoCo XML del stage anterior
Outputs: (gate solamente)
Gate:    Sonar Quality Gate = OK
```

- **Modo mock por defecto.** Para usar Sonar real: descomentar las líneas `[PROD]` y comentar las `[MOCK]`. La interfaz con Parameter Store y Secrets Manager **no cambia** — la idea es que el switch sea sin riesgo.
- `SONAR_TOKEN` viene de Secrets Manager. CodeBuild enmascara automáticamente cualquier env var que provenga de Secrets Manager en los logs.
- `-Dsonar.qualitygate.wait=true` haría que el comando bloquee hasta tener resultado real; si Quality Gate = ERROR, exit code != 0 → pipeline falla.
- Variable de test `SIMULATE_QG_FAIL=true` para probar el fail-path en CI sin tocar Sonar.

### Stage 4 — `03-build-and-scan.yml` · Docker build + Trivy + push ECR

```
Inputs:  source code (Dockerfile en raíz)
Outputs: imageDetail.json (imageUri, imageTag, commitSha, builtAt)
Gate:    Trivy ${TRIVY_SEVERITY} count = 0 (default: HIGH,CRITICAL)
```

- `privileged_mode = true` (es el único stage donde se acepta — Docker daemon lo requiere).
- Tag de la imagen: `<commit-sha-short>` (7 chars). El repo ECR es `IMMUTABLE` → un mismo sha jamás se sobreescribe.
- **Trivy real.** Se instala desde el release oficial (`v0.58.1`), corre con `--ignore-unfixed` y publica dos reportes: `trivy-report.txt` (humano, queda en los logs de CodeBuild) y `trivy-report.json` (artifact para auditoría / ingest a Security Hub).
- **Gate desacoplado.** Por defecto `TRIVY_BLOCK=false` → el scan corre pero no bloquea el build mientras se calibra la baseline de CVEs. Para activar el gate: `TRIVY_BLOCK=true` en el environment del CodeBuild project (o desde Parameter Store). Threshold de severidades configurable en `TRIVY_SEVERITY` (Parameter Store, default `HIGH,CRITICAL`).
- **`imageDetail.json` es el contrato con el siguiente stage** — `04-update-manifests.yml` lo lee y no recalcula nada.
- IAM: `ssm:GetParameters /meli/config/*`, `ecr:GetAuthorizationToken (*)`, `ecr:PutImage` **únicamente** sobre el ARN del repo de esta API. Sin acceso al manifest repo.

### Stages 5/7/9 — `04-update-manifests.yml` · GitOps bump

```
Inputs:  imageDetail.json del stage anterior
Outputs: commit en el manifest repo
Override por stage: TARGET_ENV ∈ {dev, test, prod}
```

- **Defensa contra `TARGET_ENV` mal seteado.** Si llega `UNSET` o un valor inválido, **falla explícitamente** antes de tocar git.
- Lee `IMAGE_TAG` y `COMMIT_SHA` desde `imageDetail.json` (no los recalcula → un solo source of truth para el tag entre stages).
- Clona el manifest repo con `oauth2:${GITHUB_TOKEN}@github.com/...` — el PAT viene de Secrets Manager, no se loggea.
- Bump con `yq` sobre `manifests/app/environments/${TARGET_ENV}/kustomization.yaml`:
  - `.images[0].newTag = "<commit-sha>"`
  - `.images[0].newName = "${ECR_REGISTRY}/${ECR_REPOSITORY}"`
- **Validación post-edit:** lee el `newTag` con `yq` y confirma que coincide. Defensa contra bugs silenciosos de `yq`.
- **Idempotencia.** Si el tag ya estaba en el valor deseado, `git diff --staged --quiet` → exit 0 sin commit. El pipeline puede re-ejecutar el mismo commit múltiples veces sin generar ruido en el manifest repo.
- Commit message incluye `[skip ci]` para evitar dispar pipelines anidados si el manifest repo tuviera el suyo propio.
- Push directo a `main`. ArgoCD pickea el cambio (sync interval default 3 min, o instantáneo si está conectado un webhook).

---

## Estrategia de promoción dev → test → prod

| Env | Promoción | Análisis Argo Rollouts |
|---|---|---|
| **dev** | Automática tras BuildAndScan | `autoPromotionEnabled: true`, sin smoke ni error-rate (rápido, foco en feedback de dev) |
| **test** | **Aprobación manual** en CodePipeline (SNS email a equipo de QA) | Promoción manual del rollout con `kubectl argo rollouts promote` |
| **prod** | **Aprobación manual** en CodePipeline (SNS email a equipo de prod) | `prePromotionAnalysis` (smoke test 3 GET a `/health/ready` del preview) + `postPromotionAnalysis` (error rate Prometheus 5 min, threshold 1% 5xx) |

Detalle del flujo blue/green end-to-end en [`manifests/README.md`](../manifests/README.md#flujo-bluegreen-end-to-end-prod).

---

## Estrategia de rollback

### Automático (durante un rollout)

Lo dispara Argo Rollouts cuando los analyses fallan:

- `prePromotionAnalysis` FAIL → tráfico nunca pasa al green → cero impacto al usuario.
- `postPromotionAnalysis` FAIL → re-apunta `*-active` al blue → tráfico vuelve al blue en segundos.

Sin acción humana.

### Manual durante rollout activo

```bash
kubectl argo rollouts abort challenge-meli-api -n challenge-meli
```

Aborta la promoción. El blue sigue activo.

### Manual después de promoción completada

```bash
# Recomendado: revert del commit en el manifest repo → ArgoCD aplica → Argo Rollouts hace blue/green con la versión anterior (que pasa por sus propios analyses).
git revert <bump-commit>
git push origin main

# Emergencia (skip GitOps):
kubectl argo rollouts undo challenge-meli-api -n challenge-meli
# OJO: genera drift que ArgoCD revertirá por selfHeal. Sólo usar si se va a revertir el commit inmediatamente.
```

Por qué el path de Git es el preferido: queda en auditoría, es reproducible, y respeta el principio "el estado del cluster vive en Git". El `undo` directo es para apagar un fuego cuando no hay tiempo de PR.

---

## IAM por stage (least privilege)

Detalle en [`iac/modules/iam/README.md`](../iac/modules/iam/README.md). Resumen:

| Project | Lee SSM `/meli/config/*` | Lee Secrets Manager | Push ECR | Push manifest repo |
|---|---|---|---|---|
| codebuild-test | sí | — | — | — |
| codebuild-quality | sí | `/meli/pipeline/sonarqube-token` | — | — |
| codebuild-build | sí | — | sí (sólo el repo de esta API) | — |
| codebuild-manifests | sí | `/meli/pipeline/github-manifest-token` | — | sí (via PAT) |

Si el stage de tests se compromete (corre código de PRs externos, alto riesgo), el atacante **no puede** pushear a ECR, leer secrets de Sonar, ni escribir en el manifest repo. Cada role tiene la mínima superficie posible.

---

## Parameter Store / Secrets Manager — keys que el pipeline lee

### Parameter Store (`/meli/config/*`)
- `min-coverage` — threshold del coverage gate
- `maven-opts` — JVM opts para Maven
- `sonar-host-url` / `sonar-project-key` — Sonar config
- `ecr-registry` / `ecr-repository` / `aws-region` — ECR target
- `trivy-severity` — umbral de fallo Trivy
- `manifest-repo` — `<org>/<repo>` del repo de manifests
- `cicd-git-user-name` / `cicd-git-user-email` — autor de los commits del pipeline

### Secrets Manager (`/meli/pipeline/*`)
- `sonarqube-token`
- `github-manifest-token` (PAT con scope `repo` sobre el manifest repo)

Los valores iniciales se crean como placeholder por Terraform en [`iac/modules/secrets`](../iac/modules/secrets/README.md). El operador setea los valores reales con `aws secretsmanager put-secret-value` post-apply (paso documentado en `iac/README.md`).

---

## Gates de seguridad (resumen del PDF "análisis de calidad / seguridad")

| Gate | Donde |
|---|---|
| Tests pasan | Stage 2 — Maven falla si los tests fallan |
| Coverage ≥ 80% | Stage 2 — gate explícito post-tests |
| Code quality (Sonar) | Stage 3 — `qualitygate.wait=true` |
| Image vuln scan | Stage 4 — Trivy (+ ECR scan-on-push como defensa en profundidad) |
| Image inmutable | ECR `IMMUTABLE` impide repushes silenciosos |
| Pre-promotion smoke | Stage prod — Argo Rollouts smoke test al preview |
| Post-promotion error rate | Stage prod — Argo Rollouts query Prometheus durante 5 min |
| Manual approval test | Stage 6 — SNS email |
| Manual approval prod | Stage 8 — SNS email |
| GitOps drift detection | ArgoCD `selfHeal` revierte cambios manuales fuera de Git |
| WAF | Pre-cluster en runtime — independiente del pipeline pero parte del defense-in-depth |

---

## Cómo correr cambios del pipeline localmente (smoke)

Cada buildspec se puede ejecutar local con AWS CodeBuild Local (Docker):

```bash
# https://github.com/aws/aws-codebuild-docker-images
./codebuild_build.sh -i aws/codebuild/standard:7.0 \
    -a /tmp/out -s . -b cicd/01-test.yml
```

Los env vars de Parameter Store y Secrets Manager hay que pasarlos via `-e VAR=value` en el wrapper cuando se corre la stage localmente.

---

## Limitaciones declaradas

1. **SonarQube en stage 02 sigue mocked.** Falta un Sonar real (o SonarCloud) y permitir egress desde CodeBuild. Stage 03 (Trivy) ya es real desde 2026-05-12 (instala el binario oficial, publica reportes JSON + tabla). El gate de Trivy está desacoplado (`TRIVY_BLOCK=false` por default) hasta calibrar la baseline de CVEs.
2. **PAT de GitHub manifest repo es manual.** Se rota cada 90 días por el operador. Mejora futura: GitHub App con installation token (1h de vida), descrita en el comentario del YAML.
3. **Aprobaciones por email-only.** Para enterprise real se conectaría Slack/PagerDuty via Lambda intermediaria suscrita al topic SNS. Out-of-scope.
4. **El manifest repo y el de código son separados.** Esto es deliberado (GitOps puro), pero implica que un cambio coordinado entre código y manifests requiere 2 PRs (poco frecuente — los manifests no suelen cambiar por cada deploy, sólo cuando cambia la spec).

---
- ArgoCD GitOps principles: https://opengitops.dev/
