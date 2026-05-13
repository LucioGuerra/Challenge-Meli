# eks

## Qué hace este módulo

Crea el cluster EKS (control plane privado, encriptación de secrets con CMK dedicada), el OIDC provider para IRSA, los add-ons gestionados (vpc-cni, coredns, kube-proxy, ebs-csi-driver), el managed node group de m5.xlarge con autoscaling 3→8, y las IRSA roles para Cluster Autoscaler, EBS CSI, External Secrets Operator y AWS Load Balancer Controller. La instalación del Cluster Autoscaler vive en `manifests/platform/cluster-autoscaler/`.

## Recursos que crea

- `aws_eks_cluster` con logging completo de control plane
- `aws_kms_key` + `aws_kms_alias` para envelope encryption de Kubernetes secrets
- 2 security groups (control plane + nodes) con reglas mínimas entre ambos
- `aws_iam_openid_connect_provider` para IRSA
- `aws_eks_node_group` m5.xlarge managed
- `aws_eks_addon` × 4: vpc-cni (con `ENABLE_PREFIX_DELEGATION=true`), coredns, kube-proxy, aws-ebs-csi-driver
- IRSA roles: `cluster-autoscaler` (kube-system), `ebs-csi-controller-sa` (kube-system), `external-secrets` (external-secrets-system), `aws-load-balancer-controller` (kube-system)
- `aws-load-balancer-controller-policy.json`: política IAM oficial del LBC, adjuntada al rol via `file()`

## Decisiones de diseño

- **IRSA en lugar de Pod Identity.** Aunque Pod Identity es más nuevo y simple, este proyecto ya usa IRSA en la capa de plataforma de manifests (External Secrets, ALB Controller). Mantener consistencia.
- **Endpoint del API server: sólo privado.** Sin endpoint público. Acceso desde fuera del VPC requiere bastion o VPN.
- **Encriptación de secrets con CMK propio.** Sin esta config, los `Secret` de Kubernetes son base64 en texto plano dentro de etcd. La CMK aquí cifra envelope-style.
- **Logging del control plane completo: api/audit/authenticator/controllerManager/scheduler.** Sin esto, debugging de "por qué tal pod no se schedulea" o "quién borró este recurso" es imposible.
- **Managed Node Group sobre Self-managed o Fargate.** Fargate no soporta DaemonSets (Fluent Bit y node-exporter los requieren). Self-managed obliga a mantener AMIs y autoscaling propios. Managed delega ese trabajo a AWS sin perder control sobre la spec.
- **m5.xlarge con min=3, desired=3, max=8.** `min=3` garantiza un nodo por AZ (3 AZs). `m5.xlarge` (4 vCPU, 16 GB) **non-burstable** evita el throttling por CPU credits agotados bajo carga sostenida — crítico para sostener el SLO p99 < 500 ms. `max=8` cubre el HPA max de la api (80 pods, ~14 por nodo × 6 nodos = 84 pods api) más headroom para system pods. Detalle del sizing y trade-offs vs t3.medium en [ADR-016](../../../decisiones/ADR-016-cluster-autoscaler.md) y [ADR-021](../../../decisiones/ADR-021-load-test-capacity.md).
- **`maxUnavailable=1` durante updates.** Rolling sin downtime; con `min=3` un solo nodo fuera mantiene 2 AZs activas.
- **Tags de descubrimiento para Cluster Autoscaler en el ASG.** `k8s.io/cluster-autoscaler/<cluster>=owned` y `k8s.io/cluster-autoscaler/enabled=true`. Sin esos tags el Autoscaler ignora el ASG y no escala.
- **`ENABLE_PREFIX_DELEGATION=true` en vpc-cni.** Asigna prefijos /28 en vez de IPs sueltas; multiplica por 16 la densidad de pods por nodo.
- **`lifecycle.ignore_changes` sobre `scaling_config[0].desired_size`.** Una vez que el Cluster Autoscaler cambia el desired, Terraform no lo revierte en el próximo apply.
- **CMK separada para secrets de Kubernetes** (no se reutiliza la de ECR o la del pipeline) → blast radius aislado entre planos.

## Trade-offs

- **Endpoint privado = no debug local sin VPN/bastion.** Para acelerar el debug ad-hoc se podría habilitar `endpoint_public_access` con CIDR allowlist; en este challenge se prioriza la postura de seguridad.
- **`scale-down-unneeded-time=10m`.** Conservador → más costo pero menos flapping. Trade-off aceptado: estabilidad > minimización absoluta de costo.
- **CMK con `deletion_window=30`.** Si por error se destruye el módulo, hay 30 días para recuperarla. La contrapartida es que el costo del key sigue corriendo durante ese ventana.

## Alternativas descartadas

- **Pod Identity (eks-pod-identity-agent).** Más moderno y simple, pero rompe la consistencia con el resto del stack (External Secrets ya usa IRSA en `manifests/platform/external-secrets`). Cambio a IRSA explícito según pedido del usuario.
- **Karpenter en lugar de Cluster Autoscaler.** Karpenter da mejor bin-packing y latencia de provisión, pero introduce un control plane extra y exige reescribir provisioners. Para este alcance, CAS es suficiente.
- **`t3.medium` (config previa).** Más barato (~30 USD/nodo/mes vs ~140 de m5.xlarge) pero **burstable** — los CPU credits se agotan bajo carga sostenida y la CPU queda capped al baseline (40%). Para una API con SLO p99 < 500 ms y HPA midiendo CPU, eso era un riesgo directo al SLO (ver [ADR-021](../../../decisiones/ADR-021-load-test-capacity.md)). Descartado por el load test.
- **`m5.large` (2 vCPU, 8 GB).** Resuelve burst pero da solo ~5-6 api pods por nodo → necesitaríamos `max_size ≈ 14` para llegar a 80 pods. Más nodos chicos = peor bin-packing + más latencia de provisión agregada en spikes.
- **`AL2023` AMI:** estable, pero todavía hay add-ons EKS que se rinden mejor en AL2. Se evalúa para una migración futura.
- **Fargate profiles:** descartados — no soportan DaemonSets, y la observabilidad necesita Fluent Bit/node-exporter en cada nodo.

## Por qué IRSA en lugar de Pod Identity

Pod Identity es el modelo moderno desde EKS 1.24 y es ligeramente más simple: no requiere OIDC provider ni anotaciones en el ServiceAccount. Sin embargo el resto del stack de plataforma de este proyecto ya está configurado con IRSA (ver `manifests/platform/external-secrets`). Reemplazar consistente todo a IRSA es lo pedido para este challenge. En un greenfield real con EKS ≥1.28 se preferiría Pod Identity.

## Variables de entrada

| Nombre | Tipo | Descripción | Requerido |
|---|---|---|---|
| `environment` | string | Sufijo en nombres de recursos | sí |
| `tags` | map(string) | Tags base | sí |
| `cluster_name` | string | Nombre del cluster | sí |
| `kubernetes_version` | string | Versión Kubernetes (1.31) | sí |
| `vpc_id` | string | ID de la VPC | sí |
| `private_subnet_ids` | list(string) | Subnets privadas | sí |
| `cluster_role_arn` | string | Rol del control plane | sí |
| `node_role_arn` | string | Rol del node group | sí |
| `alb_security_group_id` | string | SG del ALB; abre 8080 en nodes desde ese SG | no |

## Outputs

| Nombre | Descripción |
|---|---|
| `cluster_name` | Nombre del cluster |
| `cluster_endpoint` | Endpoint privado del API server |
| `cluster_ca` | CA cert base64 |
| `cluster_security_group_id` | SG del control plane |
| `node_security_group_id` | SG de los workers |
| `oidc_provider_arn` | ARN del OIDC provider |
| `oidc_provider_url` | Issuer URL del OIDC provider |
| `cluster_autoscaler_role_arn` | Role IRSA del autoscaler |
| `external_secrets_role_arn` | Role IRSA del External Secrets Operator |
| `aws_load_balancer_controller_role_arn` | Role IRSA del AWS Load Balancer Controller |
| `ebs_csi_role_arn` | Role IRSA del EBS CSI controller |
| `secrets_kms_key_arn` | CMK para envelope encryption de secrets |

## Dependencias entre módulos

Entrante: `networking` (vpc_id, private_subnets), `iam` (cluster_role_arn, node_role_arn), `alb` (alb_security_group_id opcional). Saliente: `alb` (security group de nodos), `ecr` (repo policy referencia al node role name).
