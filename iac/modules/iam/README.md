# iam

## Qué hace este módulo

Crea todos los roles IAM compartidos entre módulos: rol del control plane de EKS, rol de los nodos, cuatro roles independientes de CodeBuild (uno por proyecto), y el rol de orquestación de CodePipeline. Las IRSA roles para los pods (cluster-autoscaler, external-secrets, aws-load-balancer-controller, ebs-csi) viven en el módulo `eks` porque dependen del OIDC provider del cluster, evitando un ciclo entre módulos.

## Recursos que crea

- `aws_iam_role` × 7: `eks_cluster`, `eks_node`, `codebuild_test`, `codebuild_quality`, `codebuild_build`, `codebuild_manifests`, `codepipeline`
- `aws_iam_role_policy_attachment` × 5 (policies AWS-managed para EKS)
- `aws_iam_role_policy` × 5 (inline policies con permisos mínimos)

## Decisiones de diseño

- **Un role por CodeBuild project, no uno compartido.** Cada proyecto tiene exactamente los permisos que necesita y nada más. Permisos a nivel de recurso, no wildcards.
- **`logs:CreateLogGroup` con resource pattern `/aws/codebuild/meli-devops-api*`.** CodeBuild crea automáticamente su log group al primer build; este patrón cubre todos los proyectos del proyecto sin abrir el namespace de logs entero.
- **KMS en el role base.** Todos los CodeBuild roles necesitan poder usar la CMK del pipeline para leer/escribir artifacts cifrados. Sin esto el pipeline falla con `AccessDenied` opaco al primer artifact.
- **`SourcePolicyDocuments` para componer.** Los roles `quality`, `build` y `manifests` extienden el `codebuild_base` document. Mantenerlo modular evita repetir 30 líneas de logs+S3+SSM en cada policy.
- **CodePipeline sin StartBuild wildcard.** El rol del pipeline sólo puede `StartBuild` sobre los 4 ARNs específicos pasados como variable.

## Trade-offs

- **Más roles = más recursos a mantener.** Cuatro roles separados es más overhead que uno compartido pero el blast radius cae drásticamente: si el role de tests se compromete (worker reusado por mil PRs), el atacante no puede pushear a ECR ni tocar el manifest repo.
- **`ecr:GetAuthorizationToken` requiere `Resource: "*"`.** Es una limitación de AWS — esa API no soporta resource-level. Se acota mediante la confianza del role.
- **No se usa `aws_iam_policy` standalone reutilizable.** Las policies son inline en los roles. Trade-off: menos reuso entre cuentas, pero más simple de razonar (cada role y sus permisos en un solo lugar visible).

## Alternativas descartadas

- **Pod Identity para los pods de la app + autoscaler:** descartado a favor de IRSA por consistencia con el resto del manifest stack (External Secrets ya usa IRSA en `manifests/platform/external-secrets`).
- **Permission boundaries:** valiosas en cuenta multi-equipo, pero overkill para un challenge donde Terraform es la única superficie de creación de IAM.
- **AWS-managed `AWSCodeBuildAdminAccess`:** demasiado amplio. Sería una violación directa del least-privilege.

## Por qué un role por CodeBuild project

Si todos los stages compartieran un role, un compromiso en el stage de tests (que clona código de PRs externos) podría:
1. Pushear imágenes maliciosas a ECR.
2. Escribir en el manifest repo, deployando código no auditado.
3. Leer secrets de SonarQube y GitHub.

Con roles separados:
- `codebuild-test`: sólo lee `/meli/config/*` de SSM y escribe logs/artifacts.
- `codebuild-quality`: lo anterior + sólo el secret de Sonar.
- `codebuild-build`: lo anterior + push **únicamente** al ARN de este repo ECR.
- `codebuild-manifests`: lo anterior + sólo el secret del PAT de GitHub manifest.

Cada stage tiene la mínima superficie suficiente para hacer su trabajo.

## Variables de entrada

| Nombre | Tipo | Descripción | Requerido |
|---|---|---|---|
| `environment` | string | Sufijo en nombres de roles | sí |
| `tags` | map(string) | Tags base | sí |
| `region` | string | Región AWS (compone ARN patterns) | sí |
| `account_id` | string | Account ID (compone ARN patterns) | sí |
| `ecr_repository_arn` | string | ARN del repo ECR de la API | sí |
| `sonarqube_token_secret_arn` | string | ARN del secret de Sonar | sí |
| `github_manifest_token_secret_arn` | string | ARN del secret del PAT GitHub | sí |
| `codestar_connection_arn` | string | ARN de la connection de GitHub | sí |

## Sobre los ARN patterns

Para evitar dependencias circulares entre `iam` y `cicd` (donde iam necesita ARNs de
recursos creados por cicd, y cicd necesita roles creados por iam), este módulo construye
los ARNs de bucket de artifacts, CMK del pipeline, proyectos CodeBuild y topics de
aprobación a partir del `name_prefix` derivado del `environment`. Los patrones aceptan
cualquier sufijo (`-pipeline-*`, `-approvals-*`) y siguen siendo least-privilege porque
sólo acceptan recursos del proyecto en la misma cuenta/región.

## Outputs

| Nombre | Descripción |
|---|---|
| `eks_cluster_role_arn` | ARN del rol del control plane EKS |
| `eks_node_role_arn` | ARN del rol de los nodos EKS |
| `eks_node_role_name` | Nombre del rol de los nodos (para el repo policy de ECR) |
| `codebuild_test_role_arn` | ARN del role del stage test |
| `codebuild_quality_role_arn` | ARN del role del stage code-quality |
| `codebuild_build_role_arn` | ARN del role del stage build-and-scan |
| `codebuild_manifests_role_arn` | ARN del role del stage update-manifests |
| `codepipeline_role_arn` | ARN del role de CodePipeline |

## Dependencias entre módulos

Entrante: outputs de `secrets`, `ecr`, `cicd` (artifact bucket, kms key, codebuild project arns, approval topics). Saliente: roles consumidos por `eks`, `ecr` (repo policy), `cicd`.
