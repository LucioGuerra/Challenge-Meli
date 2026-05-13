# ecr

## Qué hace este módulo

Crea un repositorio ECR endurecido para la imagen de la API: tags inmutables, scan on push, encriptación con CMK dedicada, lifecycle policy de retención corta para imágenes sin tag y top-10 imágenes tagueadas, y repository policy que sólo permite pull desde el rol de los nodos EKS.

## Recursos que crea

- `aws_kms_key` + `aws_kms_alias` dedicados al cifrado de ECR
- `aws_ecr_repository` con `IMMUTABLE` tag mutability y `scan_on_push=true`
- `aws_ecr_lifecycle_policy` con dos reglas (keep last 10 tagged + expire untagged @ 1d)
- `aws_ecr_repository_policy` con grant explícito de pull al rol de nodos

## Decisiones de diseño

- **`IMMUTABLE` tag mutability.** Una vez que un tag se asocia a un digest, no se puede sobreescribir. Bloquea ataques de supply chain donde un tag conocido (ej. `prod`) es repushado con bytes maliciosos.
- **Scan on push.** Defense in depth sobre Trivy del pipeline. Dos motores (ECR usa Inspector v2 / CLAIR, Trivy es Aqua) → dos oportunidades de detectar CVEs.
- **CMK dedicada, separada de la de EKS y la de Secrets Manager.** Si una CMK se compromete o se rota mal, el blast radius queda contenido a su servicio.
- **Repository policy con principal explícito.** Sólo el rol de nodos puede pullear. `GetAuthorizationToken` viene del rol del nodo IAM, no aquí.
- **Lifecycle policy estricta.** Imágenes untagged son intermediate o builds fallidos; expirar a 1 día es suficiente. Imágenes tagged se acotan a 10 para evitar la acumulación lineal que infla el costo.

## Trade-offs

- **`IMMUTABLE` complica desarrollo local.** No se puede hacer `docker push api:dev` repetidamente sin antes borrar el tag. Aceptable: en este flujo el tag es `<commit-sha-corto>`, único por commit por construcción.
- **CMK separada = costo extra (1 USD/mes/CMK).** Aceptado a cambio del aislamiento del blast radius.
- **`keep last 10` puede ser muy poco si el pipeline corre muchas veces por hora.** Para este proyecto (1-2 deploys por día) sobra. Si el ritmo crece, subir a 30-50.

## Alternativas descartadas

- **Repositorio público (ECR Public):** descartado, la imagen contiene código propietario.
- **Repositorio compartido con otros equipos:** descartado, viola el aislamiento del proyecto.
- **Sin lifecycle policy:** descartado, ECR sin cleanup crece a varios GB/mes inútilmente.
- **`MUTABLE` tag mutability + tag sólo por commit SHA:** parcial mitigación pero perdería la protección contra repushes accidentales del tag `latest` u otros nominales.

## Variables de entrada

| Nombre | Tipo | Descripción | Requerido |
|---|---|---|---|
| `environment` | string | Sufijo del alias KMS | sí |
| `tags` | map(string) | Tags base | sí |
| `repository_name` | string | Nombre del repo | no (default `meli-devops-api`) |
| `eks_node_role_arn` | string | ARN del rol de nodos autorizado a pullear | sí |

## Outputs

| Nombre | Descripción |
|---|---|
| `repository_url` | URL de pull/push |
| `repository_arn` | ARN (consumido por el rol CodeBuild build) |
| `repository_name` | Nombre del repo |
| `kms_key_arn` | ARN de la CMK |

## Dependencias entre módulos

Entrante: `iam` (eks_node_role_arn). Saliente: `iam` (ecr_repository_arn para el role del build stage).
