# ADR-011 — CMK por servicio + Secrets Manager + ExternalSecret

## Contexto

El challenge pide "manejo de secretos" y "seguridad por defecto". Los secretos en este sistema son credenciales de la API, credenciales de monitoreo, tokens de pipeline (SonarQube, GitHub PAT) y el client_secret futuro de Cognito.

## Decisión

Una arquitectura **en 4 capas**:

### 1. CMK por servicio (KMS Customer Managed Keys)

Cuatro CMKs separadas:

| CMK | Alias | Para qué |
|---|---|---|
| EKS secrets | `alias/<env>-eks-secrets` | Envelope encryption de Kubernetes `Secret` en etcd |
| ECR | `alias/<env>-ecr` | Encrypt at rest de imágenes Docker |
| Secrets Manager | `alias/<env>-secrets-manager` | Encrypt at rest de los secrets |
| CodePipeline | `alias/<env>-pipeline-artifacts` | Encrypt de artifacts S3 entre stages |

Cada módulo Terraform crea su propia CMK. La razón: **aislamiento del blast radius**. Si una se compromete o se rota mal, las otras quedan intactas.

### 2. AWS Secrets Manager para los secretos sensibles

- `aws_secretsmanager_secret` con `kms_key_id = <cmk>`.
- `aws_secretsmanager_secret_version` con valor placeholder (Terraform).
- `aws_secretsmanager_secret_policy` con **`Deny secretsmanager:GetSecretValue` cuando `aws:SecureTransport=false`**.
- `lifecycle.ignore_changes = [secret_string]`.

### 3. ExternalSecret en el cluster

El operator External Secrets sincroniza `/meli/<env>/challenge-meli-api` de AWS Secrets Manager → `Secret` nativo de Kubernetes. El pod consume las credenciales via `envFrom.secretRef`. La app nunca llama AWS APIs — sólo lee env vars.

### 4. IRSA para los workloads de plataforma

External Secrets, Cluster Autoscaler, AWS LBC, EBS CSI: no usan secretos, usan roles IAM federados al OIDC del cluster (ver [[ADR-006]]).

## Alternativas consideradas

### Una sola CMK para toda la cuenta
Menos recursos, menos costo (1 USD/mes/CMK), gestión más simple. **Pero el blast radius es compartido** — si esa CMK se compromete, todos los datos cifrados de la cuenta están potencialmente expuestos. Ahorrar 3 USD/mes no compensa el riesgo.

### KMS gestionado por AWS (`alias/aws/secretsmanager`, etc.)
Sin costo de CMK, pero **no se le pueden poner resource policies custom** (ej. denegar descifrado si la principal no es un role específico) ni rotación atada al ciclo de AWS. La defensa en profundidad con resource policies es lo que diferencia un secreto bien protegido — y eso CMKs AWS-managed no lo permiten.

## Por qué `Deny non-TLS` en la resource policy

```json
{
  "Effect": "Deny",
  "Action": "secretsmanager:GetSecretValue",
  "Resource": "*",
  "Condition": {
    "Bool": { "aws:SecureTransport": "false" }
  }
}
```

AWS hoy ya fuerza TLS para Secrets Manager. Pero:

- **Defensa en profundidad**: si una mala config futura habilitara un endpoint sin TLS o si AWS cambiara comportamiento, este deny atrapa.
- Es **gratis** (cero costo, cero performance).
- Es **auditable** — un security scan ve el deny explícito y entiende la postura.

## Por qué `lifecycle.ignore_changes` sobre `secret_string`

Terraform crea el secret con un placeholder evidente (ej. `PLACEHOLDER-REPLACE-ME`). El operador setea el valor real post-apply:

```bash
aws secretsmanager put-secret-value \
  --secret-id /meli/api/cognito-client-secret \
  --secret-string '<valor-real>'
```

Sin `ignore_changes`, el próximo `terraform apply` revertiría el valor al placeholder — desastre. Con `ignore_changes`, Terraform crea pero no toca después.

Trade-off: Terraform pierde la capacidad de detectar drift en el valor del secret. Aceptado — el valor real no debe vivir en Terraform state.

## Consecuencias

### Ganamos
- **4 CMKs aisladas** → blast radius por servicio.
- **Resource policies custom** (deny non-TLS) en Secrets Manager.
- **App no llama AWS APIs** — todo viene vía `envFrom`, simple y portable.
- **Pipeline no escribe credenciales en logs** — CodeBuild enmascara env vars de Secrets Manager.
- **IRSA para workloads de plataforma** — cero credenciales estáticas en el cluster.

### Pagamos
- **4 × 1 USD/mes CMKs** = 4 USD/mes. Aceptado.
- **Operación post-apply**: el operador setea valores reales en Secrets Manager.
- **Rotación manual** de tokens externos (GitHub PAT, SonarQube).
- **`ignore_changes` pierde drift detection** en el valor del secret.
