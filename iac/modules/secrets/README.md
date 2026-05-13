# secrets

## Qué hace este módulo

Crea tres secretos en Secrets Manager con valores placeholder, todos cifrados con una CMK dedicada al servicio. Cada secret tiene un resource policy que niega `GetSecretValue` cuando la conexión no usa TLS.

## Recursos que crea

- `aws_kms_key` + `aws_kms_alias` para Secrets Manager
- 3 × `aws_secretsmanager_secret`:
  - `/meli/api/cognito-client-secret`
  - `/meli/pipeline/github-manifest-token`
  - `/meli/pipeline/sonarqube-token`
- 3 × `aws_secretsmanager_secret_version` con valor placeholder
- 3 × `aws_secretsmanager_secret_policy` denegando acceso fuera de TLS

## Decisiones de diseño

- **CMK dedicada para Secrets Manager.** Separa el blast radius de la CMK de ECR y la del pipeline. Si una de ellas se compromete, las otras quedan intactas.
- **Resource policy `Deny secretsmanager:GetSecretValue` con `aws:SecureTransport=false`.** Defense in depth: aunque AWS hoy fuerza TLS, el deny explícito asegura que ningún canal legacy o futura misconfiguration pueda leer secretos en claro.
- **Placeholders + `lifecycle.ignore_changes`.** Terraform crea el secret con un valor placeholder evidente. Después un operador rota el valor real con `aws secretsmanager put-secret-value`. El `ignore_changes` evita que el próximo `terraform apply` revierta el valor real al placeholder.
- **Rotación deshabilitada.** Para los tokens de pipeline (GitHub PAT, SonarQube token) la rotación es manual + documentada en runbook. Cognito client secret tampoco se rota automáticamente (Cognito no expone API de rotación segura).

## Trade-offs

- **`ignore_changes` sobre `secret_string` significa que Terraform nunca corrige drift en el valor.** Aceptado a cambio de no escribir el valor real en el state. Operadores deben asumir que el contenido del secret vive fuera de Terraform.
- **Sin rotación automática.** Tokens viejos viven más tiempo del óptimo. Mitigación: alertar via SNS si un secret no se ha actualizado en 90 días (no implementado en este módulo, dejado a la operación).
- **CMK separada = 1 USD/mes/CMK extra.** Costo aceptado por el aislamiento.

## Alternativas descartadas

- **Parameter Store SecureString:** más barato pero no soporta rotación nativa ni resource policies tan ricas. Secrets Manager es el lugar correcto para credenciales sensibles.
- **KMS por defecto (alias/aws/secretsmanager):** descartado porque la CMK gestionada por AWS no admite resource policies custom (ej. restringir descifrado a roles concretos).
- **Almacenar el placeholder real:** descartado, el placeholder debe ser obviamente inválido para forzar la rotación pre-deploy.

## Setup antes del primer deploy

Cada secret debe tener un valor real seteado manualmente antes de hacer apply de los módulos que lo consumen:

```bash
aws secretsmanager put-secret-value --secret-id /meli/api/cognito-client-secret --secret-string '<valor-real>'
aws secretsmanager put-secret-value --secret-id /meli/pipeline/github-manifest-token --secret-string '<github-pat>'
aws secretsmanager put-secret-value --secret-id /meli/pipeline/sonarqube-token --secret-string '<sonar-token>'
```

## Variables de entrada

| Nombre | Tipo | Descripción | Requerido |
|---|---|---|---|
| `environment` | string | Sufijo en alias KMS | sí |
| `tags` | map(string) | Tags base | sí |

## Outputs

| Nombre | Descripción |
|---|---|
| `kms_key_arn` | CMK que cifra los secretos |
| `secret_arns` | Mapa nombre→ARN |
| `cognito_client_secret_arn` | ARN del secret de Cognito |
| `github_manifest_token_arn` | ARN del PAT GitHub |
| `sonarqube_token_arn` | ARN del token Sonar |

## Dependencias entre módulos

Ninguna entrante. Salientes: `iam` (codebuild roles consumen los ARNs de los secrets), External Secrets en cluster (consume `/meli/api/*`).
