# networking

## Qué hace este módulo

Crea la VPC base del proyecto: 1 VPC `/16` con 2 AZs, 1 subnet pública y 1 privada por AZ, IGW, un NAT Gateway por AZ (HA), route tables privadas por AZ, y VPC Flow Logs hacia CloudWatch. Las subnets quedan etiquetadas para descubrimiento por EKS, ALB Controller y Cluster Autoscaler.

## Recursos que crea

- `aws_vpc` (10.0.0.0/16)
- 2 subnets públicas (10.0.1.0/24, 10.0.3.0/24)
- 2 subnets privadas (10.0.2.0/24, 10.0.4.0/24)
- 1 `aws_internet_gateway`
- 2 `aws_eip` + 2 `aws_nat_gateway` (uno por AZ, cada uno en su subnet pública)
- 3 route tables: 1 pública → IGW, 2 privadas → NAT de su AZ + asociaciones
- 1 `aws_cloudwatch_log_group` `/vpc/flow-logs` con retención 7 días
- 1 IAM role + policy + 1 `aws_flow_log` que publica todo el tráfico

## Decisiones de diseño

- **Una NAT por AZ (no compartida).** Cada subnet privada rutea por la NAT de su propia AZ. El costo adicional (~32 USD/mes) es marginal comparado con eliminar el SPOF de egress: si una AZ cae, los pods de la otra AZ siguen llegando a ECR/S3/internet sin interrupción.
- **Subnets `/24` (251 IPs útiles).** Suficiente para nodos m5.xlarge + ENIs por pod con `ENABLE_PREFIX_DELEGATION`. Si el cluster escalara mucho, conviene mover a `/22`.
- **Flow Logs sólo a CloudWatch.** La capa de observabilidad de aplicación (Loki) no recibe flow logs; estos son tráfico de infraestructura puro y pertenecen al stack de CloudWatch que mira AWS, no a Loki que mira la app.
- **Tags de descubrimiento en todas las subnets.** Tres etiquetas críticas: `kubernetes.io/role/elb=1` (públicas), `kubernetes.io/role/internal-elb=1` (privadas), `kubernetes.io/cluster/<cluster>=owned` (todas).

## Trade-offs

- **2 NATs = ~64 USD/mes en lugar de ~32.** Trade-off aceptado a favor de HA: en una infra real de empresa, perder egress completo por caída de una AZ es inaceptable.
- **`map_public_ip_on_launch=true` en públicas.** Cualquier instancia que se lance ahí tendrá IP pública automáticamente. Aceptable porque las únicas cargas en subnets públicas son ALB (managed) y NAT GW (managed); no se permite que nodos EKS aterricen ahí.
- **Retención de flow logs = 7 días.** Suficiente para diagnosticar incidentes recientes; troubleshooting de incidentes viejos va a Athena sobre S3, no a CloudWatch.

## Alternativas descartadas

- **VPC con IPv6 dual-stack:** descartado por complejidad innecesaria para el alcance del challenge.
- **Subnets de tránsito (3-tier classic):** no aporta nada con EKS — los nodos van directo a privadas, ALB a públicas.
- **`aws-vpc-ipam`:** overkill; un solo entorno, un solo CIDR.
- **Flow Logs a S3:** más barato a largo plazo, pero CloudWatch permite alarmas inmediatas sobre patrones de tráfico (reject loops, exfiltración). Para retención corta + reactividad, CloudWatch gana.

## Por qué las tags de descubrimiento son críticas

- Sin `kubernetes.io/role/elb=1` en las públicas, el **AWS Load Balancer Controller** no encuentra dónde poner el ALB y el `Ingress` queda en `Pending` para siempre.
- Sin `kubernetes.io/role/internal-elb=1` en las privadas, los ALBs internos (si más adelante se usan) tampoco se aprovisionan.
- Sin `kubernetes.io/cluster/<name>=owned`, ni el ALB Controller ni el Cluster Autoscaler reconocen los recursos como pertenecientes a este cluster.

## Variables de entrada

| Nombre | Tipo | Descripción | Requerido |
|---|---|---|---|
| `region` | string | Región AWS (deriva los AZ names) | sí |
| `environment` | string | Sufijo en los Name tags | sí |
| `vpc_cidr` | string | CIDR de la VPC | no (default `10.0.0.0/16`) |
| `cluster_name` | string | Nombre del cluster EKS para los tags de descubrimiento | sí |
| `tags` | map(string) | Tags base | sí |
| `flow_logs_kms_key_arn` | string | CMK para cifrar el log group de flow logs | no |

## Outputs

| Nombre | Descripción |
|---|---|
| `vpc_id` | ID de la VPC |
| `vpc_cidr` | CIDR de la VPC |
| `public_subnet_ids` | Lista de IDs de subnets públicas |
| `private_subnet_ids` | Lista de IDs de subnets privadas |
| `vpc_flow_log_group_arn` | ARN del log group de flow logs |

## Dependencias entre módulos

Ninguna entrante. Salientes: `eks`, `alb`, `cicd` (codebuild en subnets privadas).
