# iac — Mercado Libre DevOps Challenge

Infraestructura AWS para la API Java + Spring Boot del challenge. Modularizada por dominio, con `least-privilege` IAM, secretos en Secrets Manager, encriptación con CMKs separadas por servicio, y observabilidad de infra en CloudWatch (los logs de aplicación viven en Loki).

## Estructura

```
iac/
├── README.md                      ← este archivo
├── versions.tf                    ← provider constraints (aws ~> 5.0)
├── backend.tf                     ← state en S3 + DynamoDB lock
├── main.tf                        ← composición de los 9 módulos
├── variables.tf                   ← inputs de root
├── outputs.tf                     ← outputs clave (ALB DNS, ECR URL, cluster, etc.)
├── terraform.tfvars.example       ← plantilla de tfvars
└── modules/
    ├── networking/                ← VPC, subnets, NAT, IGW, Flow Logs
    ├── eks/                       ← cluster privado + IRSA + node group + autoscaler
    ├── ecr/                       ← repo IMMUTABLE + scan + lifecycle
    ├── alb/                       ← ALB + WAF (4 reglas) + ACM + access logs
    ├── cognito/                   ← User Pool M2M (client_credentials)
    ├── iam/                       ← roles compartidos (no IRSA — ese vive en eks/)
    ├── secrets/                   ← 3 secretos con CMK dedicada + Deny non-TLS
    ├── cicd/                      ← S3 artifacts + 4 CodeBuild + Pipeline 9 stages
    └── observability/             ← CloudWatch alarmas + dashboard + log groups infra
```

## Cómo deployar

### Pre-requisitos manuales (una sola vez)

1. **Backend S3 + DynamoDB.** Crear bucket de tfstate (versioned + SSE-KMS), tabla DynamoDB con PK `LockID`, KMS CMK. Sustituir los placeholders en `backend.tf`.
2. **CodeStar Connection a GitHub.** Crear desde la consola AWS CodeStar Connections; copiar el ARN. Pasarlo como `codestar_connection_arn` en tfvars.
3. **Aprobar tags de ECR.** No requiere setup.

### Apply

```bash
cd iac
cp terraform.tfvars.example terraform.tfvars
# editar terraform.tfvars con valores reales

terraform init
terraform plan
terraform apply
```

### Post-apply (manual)

1. **Setear valores reales en Secrets Manager** (los crea placeholder):
   ```bash
   aws secretsmanager put-secret-value --secret-id /meli/pipeline/github-manifest-token --secret-string '<github-pat>'
   aws secretsmanager put-secret-value --secret-id /meli/pipeline/sonarqube-token --secret-string '<sonar-token>'
   ```
2. **Cognito client secret.** No queda en outputs (para no escribirlo al state). Extraerlo:
   ```bash
   SECRET=$(aws cognito-idp describe-user-pool-client \
     --user-pool-id $(terraform output -raw cognito_user_pool_endpoint | awk -F/ '{print $NF}') \
     --client-id $(terraform output -raw cognito_app_client_id) \
     --query 'UserPoolClient.ClientSecret' --output text)
   aws secretsmanager put-secret-value --secret-id /meli/api/cognito-client-secret --secret-string "$SECRET"
   ```
3. **DNS.** Tomar `terraform output acm_validation_records` y pedirle al equipo de DNS que agregue los CNAMEs en Route53.
4. **Cluster Autoscaler.** Se instala vía ArgoCD desde `manifests/platform/cluster-autoscaler/install.yaml`. Sustituir los placeholders (`PLACEHOLDER_EKS_CLUSTER_NAME`, `PLACEHOLDER_AWS_REGION`, `PLACEHOLDER_CLUSTER_AUTOSCALER_ROLE_ARN`) con los valores de `terraform output` antes del `argocd app sync` o del primer commit al repo de manifests.
5. **Parameter Store (`/meli/config/*`).** Los buildspecs leen valores de SSM Parameter Store (ver `cicd/*.yml`). Crearlos:
   ```bash
   aws ssm put-parameter --name /meli/config/ecr-registry --value "<account>.dkr.ecr.us-east-1.amazonaws.com" --type String
   aws ssm put-parameter --name /meli/config/ecr-repository --value "meli-devops-api" --type String
   aws ssm put-parameter --name /meli/config/aws-region --value "us-east-1" --type String
   aws ssm put-parameter --name /meli/config/min-coverage --value "80" --type String
   aws ssm put-parameter --name /meli/config/manifest-repo --value "<org>/<manifest-repo>" --type String
   aws ssm put-parameter --name /meli/config/sonar-host-url --value "https://sonarqube.example.com" --type String
   aws ssm put-parameter --name /meli/config/sonar-project-key --value "meli-devops-api" --type String
   aws ssm put-parameter --name /meli/config/trivy-severity --value "HIGH,CRITICAL" --type String
   aws ssm put-parameter --name /meli/config/maven-opts --value "-Xmx2g" --type String
   aws ssm put-parameter --name /meli/config/cicd-git-user-name --value "MeLi CI/CD Bot" --type String
   aws ssm put-parameter --name /meli/config/cicd-git-user-email --value "cicd-bot@meli.com" --type String
   ```

## Decisiones de diseño globales

- **IRSA en lugar de Pod Identity.** El resto del stack de plataforma (External Secrets, ALB Controller en `manifests/platform`) ya usa IRSA — consistencia gana sobre modernidad.
- **Métricas y alertas de cluster → Prometheus + AlertManager. ALB y logs de infra AWS → CloudWatch.** Stack dual: `kube-prometheus-stack` (instalado vía manifest, ver `manifests/platform/observability`) cubre nodos, pods, kube-state, alertas operativas. CloudWatch sólo recibe lo que es AWS-emitido o que debe sobrevivir a la caída del cluster: control plane EKS, CodeBuild, VPC Flow Logs, ALB metrics y access logs.
- **Logs de aplicación → Loki vía Fluent Bit. Logs de infra AWS → CloudWatch.** Fluent Bit (escrito en C, ~10MB de RAM por instancia) recolecta stdout/stderr de cada pod y los envía a Loki. CloudWatch sólo recibe control-plane EKS, CodeBuild y VPC Flow Logs.
- **CMK por servicio.** Cuatro CMKs separadas: EKS-secrets, ECR, Secrets Manager y CodePipeline. Aísla blast radius entre servicios.
- **No comentarios en `.tf`.** El código se lee solo; las decisiones de diseño viven en el README de cada módulo.
- **Tags por defecto en el provider AWS.** Cualquier resource hereda automáticamente `Project`, `Environment`, `ManagedBy`, `Owner`, `CostCenter`. Cada módulo además agrega `Module = "<nombre>"`.

## Dependencias entre módulos

```
secrets ────┐
            ├──→ iam ──┐
ecr ────────┘          │
                       ├──→ eks ──→ alb ──→ observability ──→ cicd
networking ────────────┘
cognito (independiente)
```

`iam` se construye con `region`/`account_id` y patrones ARN para `cicd` (rompe el ciclo iam ↔ cicd).
El SG-to-SG entre ALB y nodos EKS se crea como recurso top-level en `main.tf` (rompe el ciclo eks ↔ alb).

## Lo que NO está aquí

- Route53 / Hosted Zones: la empresa gestiona DNS externamente; este IaC sólo exporta los CNAMEs de validación ACM.
- Manifests de Kubernetes (`Deployment`, `Service`, `Ingress`, `ArgoCD Application`, `ExternalSecret`, `HPA`): viven en `/manifests` y se aplican por ArgoCD/GitOps.
- Stack de observabilidad de aplicación (Prometheus, Grafana, Loki, Fluent Bit): vive en `/manifests/platform/observability`.
- Helm install de Cluster Autoscaler: post-apply step manual (ver arriba).

## Costo estimado

Aproximado, eu-west-1 / us-east-1 con tráfico moderado:

- EKS control plane: 73 USD/mes
- 3 × m5.xlarge (24/7): ~420 USD/mes (instance type derivado del load test — ver [ADR-021](../decisiones/ADR-021-load-test-capacity.md) y [ADR-016](../decisiones/ADR-016-cluster-autoscaler.md))
- NAT Gateway × 2 AZs: ~64 USD/mes + tráfico
- ALB: ~22 USD/mes + LCU
- ECR: <1 USD/mes (10 imágenes ≤ 500MB)
- CloudWatch: ~10 USD/mes (logs + metrics + alarmas)
- 4 CMKs: 4 USD/mes
- Total base: ~640 USD/mes para 3 nodos. +140 USD/mes por nodo extra durante autoscaling a max=8.

Detalle completo y trade-offs en [ADR-019](../decisiones/ADR-019-costos.md).

## Tests / validación

`terraform fmt -recursive && terraform validate` antes de cualquier PR.
`terraform plan` debería ser idempotente entre runs sin cambios en input.
