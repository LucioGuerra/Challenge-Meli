# ADR-009 — CodePipeline + CodeBuild sobre GitHub Actions

## Contexto

El challenge pide un pipeline con stages claras (build & test, calidad/seguridad, build de imagen, push a registry, deploy automático, rollback), multi-ambiente, aprobaciones manuales, secretos manejados sin filtrar y IAM granular por etapa.

### Supuesto

Todo el stack ya vive en AWS — la decisión real era si mantener el pipeline dentro de la cuenta o tirarlo a un servicio externo.

## La discusión

La opción que pesaba como alternativa real era **GitHub Actions**. Tiene a favor velocidad de iteración, sintaxis amigable, ecosistema enorme de actions reusables, y triggers ricos por evento.

Lo que terminó inclinando la balanza hacia **CodePipeline + CodeBuild**:

- **Todo se gestiona desde AWS**, en la misma cuenta y plano que el resto. La integración con Secrets Manager, Parameter Store, IAM granular y CMK es nativa.
- **Menos secretos para mantener** — no hay que rotar PATs de GitHub ni configurar OIDC trust hacia GitHub Actions; CodeStar Connection y los roles IAM de cada CodeBuild project resuelven la auth.
- **Seguridad más simple** — los buildspecs leen de Secrets Manager directo; IAM acota qué puede hacer cada stage sin OIDC chain.
- **Billing, IAM, audit y logs viven en el mismo lugar.**

## Decisión

**AWS CodePipeline + 4 CodeBuild projects + CodeStar Connection a GitHub.**

- **CodePipeline** orquesta (9 stages, aprobaciones manuales con SNS).
- **CodeBuild** × 4 projects (test, code-quality, build-and-scan, update-manifests).
- **CodeStar Connection** = OAuth con GitHub (no PAT por repo).
- **Secrets Manager** = `SONAR_TOKEN`, `GITHUB_TOKEN` (PAT del manifest repo).
- **Parameter Store `/meli/config/*`** = config no sensible.
- **EventBridge → SNS** publica eventos de cambio de estado.
- **CMK propio** para artifacts.

## Por qué CodeStar Connection y no GitHub PAT

CodeStar Connection es OAuth — el handshake se hace una vez desde la consola con permisos por repo. No depende de un PAT de un usuario humano, AWS gestiona el token refresh, y los permisos están acotados al repo (no al user account completo).

## Por qué 4 CodeBuild projects (no uno solo)

Cada project tiene su propio rol IAM con permisos exactos:

| Project | SSM `/meli/config/*` | Secrets Manager | ECR push | Manifest repo |
|---|---|---|---|---|
| test | sí | — | — | — |
| quality | sí | `/meli/pipeline/sonarqube-token` | — | — |
| build | sí | — | sí (sólo el repo de esta API) | — |
| manifests | sí | `/meli/pipeline/github-manifest-token` | — | sí |

Si el stage de **tests** se compromete (corre código de PRs externos, la superficie más expuesta), el atacante **no puede** pushear a ECR ni tocar el manifest repo.

## Estado de las stages de calidad / seguridad

- **Trivy (stage 03) corre real.** Se instala desde el release oficial, escanea la imagen contra la DB de vulns, publica reporte humano y JSON.
- **SonarQube (stage 02) sigue mocked.** Requiere un server SonarQube self-hosted o SonarCloud — infra adicional fuera de scope. La interfaz contra Parameter Store y Secrets Manager está lista; el switch a real es reemplazar el bloque `[MOCK]` por la llamada a `sonar-scanner`.

## Consecuencias

### Ganamos
- **Secretos nativos** desde Secrets Manager y Parameter Store en cada buildspec.
- **IAM granular por etapa** sin OIDC trust complicado.
- **VPC en el test project** sin self-hosted runners.
- **Aprobaciones manuales por email** con SNS — cero código custom.
- **CMK dedicada** para artifacts.
- **AWS-native** — billing, IAM, audit, logs en la misma cuenta.

### Pagamos
- **Velocidad de iteración menor** que GH Actions. Cambios al pipeline implican Terraform apply. Mitigado: los buildspecs viven en `/cicd` del repo de la app y se editan sin tocar Terraform — sólo el shape del pipeline está en TF.
- **UX de logs menos amigable** que GH Actions.
- **Costo CodeBuild**: <5 USD/mes para uso normal.
