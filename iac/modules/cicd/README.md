# cicd

## Qué hace este módulo

Crea la cadena CI/CD completa: bucket S3 de artifacts (con CMK dedicada), 4 proyectos CodeBuild (uno por buildspec del directorio `cicd/`), 2 topics SNS para aprobaciones manuales, el pipeline CodePipeline con 9 stages (Source → UnitTests → CodeQuality → BuildAndScan → DeployDev → ApproveTest → DeployTest → ApproveProd → DeployProd), un EventBridge rule que publica cambios de estado a SNS y un security group para el test project (que corre en VPC).

## Recursos que crea

- `aws_kms_key` + alias para artifacts
- `aws_s3_bucket` + versioning + SSE-KMS + lifecycle 30d + public access block
- `aws_sns_topic` × 3 (test, prod, pipeline_events)
- `aws_codebuild_project` × 4 (test, quality, build, manifests)
- `aws_codepipeline` con 9 stages
- `aws_cloudwatch_event_rule` + target → SNS
- `aws_security_group` para CodeBuild test project en VPC

## Decisiones de diseño

- **CMK dedicada al pipeline.** Aislada de las CMKs de EKS, ECR y Secrets Manager. Pipeline compromise no propaga al cluster ni a la imagen.
- **Un solo `aws_codebuild_project` para los 3 deploys** (dev/test/prod). `TARGET_ENV` se inyecta vía `EnvironmentVariables` override en cada stage. DRY sin sacrificar granularidad de permisos (el role del project es el mismo para los 3 envs, las decisiones de a quién aprobar viven en los stages del pipeline).
- **`privileged_mode=true` sólo en el build project.** Docker daemon necesita capabilities elevadas. Es el único project donde se acepta el trade-off.
- **Test project con VPC config.** Permite que los tests accedan a recursos internos (DB de staging, servicios mock internos). Los demás projects no necesitan VPC y arrancan más rápido sin esa overhead.
- **CodeStar Source Connection.** Más seguro que GitHub PAT (OAuth con permisos por repo, no por usuario). Se crea una sola vez manualmente y se referencia por ARN.
- **EventBridge → SNS para cambios de estado.** Publica al topic `pipeline-events` cualquier transición del pipeline (FAILED, STARTED, SUCCEEDED). Permite construir notificaciones a Slack/PagerDuty sin tocar este módulo.
- **`enable_deletion_protection`-equivalente en buckets:** `force_destroy=false` en el bucket de artifacts. Si Terraform intentara borrar el bucket con objetos dentro, falla en lugar de purgar silencio.
- **SNS encriptado con `alias/aws/sns`.** CMK gestionada por AWS es suficiente para los mensajes de aprobación (que no contienen secretos).

## Trade-offs

- **`privileged_mode` en el build project** abre toda la superficie de Docker. Mitigación: ese project sólo tiene el rol `codebuild-build` que puede pushear a un único ECR repo, no a cualquier registry.
- **`OAuth` vía CodeStar requiere step manual una vez.** El handshake OAuth no es automatizable; alguien con permisos al repo de GitHub tiene que iniciar la connection desde la consola. El ARN resultante se pasa como variable.
- **Email-only subscriptions.** Notificaciones de aprobación vía email. En enterprise real se usaría Slack/PagerDuty vía Lambda intermediaria; out-of-scope.
- **Lifecycle 30 días en artifacts.** Pierde historial de artifacts antiguos. Aceptado — la idempotencia del pipeline + GitOps permite reconstruir cualquier deploy ejecutando el pipeline contra un commit pasado.

## Por qué el pipeline nunca toca el cluster directamente

El último stage no hace `kubectl apply`. Solo bumpea el tag de imagen en el manifest repo. ArgoCD (en el cluster) detecta el cambio y sincroniza. Esto es GitOps puro:

- El estado deseado del cluster vive en Git, no en el pipeline.
- El pipeline no necesita credenciales de Kubernetes (cero blast radius si se compromete).
- Rollback es un revert de Git, no una invocación al pipeline.
- Auditoría: cada cambio del cluster es un commit firmable.

## Alternativas descartadas

- **GitHub Actions:** ya existe la infraestructura AWS y CodePipeline integra mejor con CodeBuild/Secrets Manager/IAM. Cambiar de orquestador agregaría OIDC trust + tokens.
- **Jenkins:** descartado por costo operativo de mantener el master.
- **Single CodeBuild project con if-branching:** vimos que un proyecto por buildspec lo hace más legible y permite granularidad fina de IAM y compute.
- **Helm directo desde el pipeline contra el cluster:** descartado, viola el principio GitOps; ArgoCD ya hace ese trabajo.
- **Manual approvals via custom Lambda + Slack:** valioso a largo plazo; out-of-scope.

## Variables de entrada

| Nombre | Tipo | Descripción | Requerido |
|---|---|---|---|
| `environment` | string | Sufijo en nombres | sí |
| `tags` | map(string) | Tags base | sí |
| `vpc_id` | string | VPC del test project | sí |
| `private_subnet_ids` | list(string) | Subnets privadas para el test project | sí |
| `github_repo` | string | `owner/repo` del código | sí |
| `github_branch` | string | Branch trackeada | sí |
| `codestar_connection_arn` | string | ARN del CodeStar Connection | sí |
| `codebuild_*_role_arn` | string × 4 | Roles de los 4 projects | sí |
| `codepipeline_role_arn` | string | Role del pipeline | sí |
| `codebuild_log_group_name` | string | Log group de CodeBuild | sí |
| `approval_test_email` / `approval_prod_email` | string | Suscripciones SNS | sí |

## Outputs

| Nombre | Descripción |
|---|---|
| `pipeline_name` | Nombre del pipeline |
| `pipeline_arn` | ARN del pipeline |
| `artifact_bucket_name` | Bucket de artifacts |
| `artifact_bucket_arn` | ARN del bucket |
| `pipeline_kms_key_arn` | CMK del pipeline |
| `codebuild_project_arns` | ARNs de los 4 projects |
| `approval_topic_arns` | ARNs de los 2 topics de aprobación |

## Dependencias entre módulos

Entrante: `networking` (vpc, subnets), `iam` (todos los role arns). Saliente: `iam` (artifact bucket, kms key, project arns, codestar arn, approval topics).
