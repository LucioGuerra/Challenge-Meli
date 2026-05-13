# aws-load-balancer-controller

Controller oficial de AWS que provisiona ALBs (y NLBs) reales a partir de `Ingress` y `Service type=LoadBalancer` objects.

## Archivos

- `install.yaml`: ArgoCD `Application` que instala el chart `aws-load-balancer-controller` v1.8.1 desde `https://aws.github.io/eks-charts`. Se despliega en `kube-system`.

## Por qué ALB y no nginx-ingress

- ALB es L7 nativo en AWS: WAF integrado, ACM para TLS, target groups en VPC, logs a S3.
- No requiere correr un Deployment de nginx dentro del cluster que cuide tráfico.
- Cost-effective: 1 ALB por Ingress group, no por Ingress.
- Mejor integración con autoscaling de pods (target type `ip` → pod IP direct, sin NodePort hop).

Descartado: nginx-ingress (extra hop), Traefik (menos AWS-native), NLB+kube-proxy (L4 only).

## Por qué `targetRevision: 1.8.1`

Versión estable del chart al momento de generar este manifest. Soporta K8s 1.27–1.31. Bump cuando AWS publique parches críticos — ver release notes en GitHub `kubernetes-sigs/aws-load-balancer-controller`.

## Placeholders a reemplazar

| Placeholder | Cómo obtenerlo | Ejemplo |
|---|---|---|
| `PLACEHOLDER_EKS_CLUSTER_NAME` | Nombre del cluster EKS | `prod-eks` / `nonprod-eks` |
| `PLACEHOLDER_AWS_REGION` | Región del cluster | `us-east-1` |
| `PLACEHOLDER_VPC_ID` | VPC del cluster | `vpc-0abc1234` |
| `PLACEHOLDER_LBC_IRSA_ROLE_ARN` | IAM Role con la policy del LBC, federado al cluster OIDC | `arn:aws:iam::123:role/eks-lbc` |

Cada cluster (prod / nonprod) tiene sus propios valores → mantener 2 ramas del repo o usar ArgoCD `parameters` por Application.

## IAM requerida

Adjuntar la [policy oficial](https://github.com/kubernetes-sigs/aws-load-balancer-controller/blob/main/docs/install/iam_policy.json) al role IRSA. Resumen: gestión de ELBv2, target groups, security groups, ACM read, EC2 describe, WAF si se usa.

## Obligatorio para producción

- `replicaCount: 2` y `podDisruptionBudget.maxUnavailable: 1` → no perder controller durante node drains.
- `serviceMonitor.enabled: true` → Prometheus scrape de métricas del controller.
- VPC tags correctos en subnets antes de aplicar (sino los Ingress quedan en pending sin error claro).

## Nice to have

- `enableWafv2: true` si se va a usar AWS WAF integrado.
- Logs del ALB a S3 vía annotation `alb.ingress.kubernetes.io/load-balancer-attributes`.

## Operar

```bash
# Status
kubectl -n kube-system get pods -l app.kubernetes.io/name=aws-load-balancer-controller

# Logs
kubectl -n kube-system logs -l app.kubernetes.io/name=aws-load-balancer-controller --tail=100

# Forzar resync desde ArgoCD
argocd app sync aws-load-balancer-controller
```

## Dependencias

- EKS cluster con OIDC provider configurado.
- IRSA role creado y mapeado al SA `aws-load-balancer-controller` en `kube-system`.
- No depende de otros componentes del repo.

## Qué pasa si falla

Sin LBC: Ingress objects quedan en `ADDRESS: <none>`. No hay tráfico externo. Grafana, app y cualquier servicio expuesto se vuelve inaccesible desde fuera del cluster. Los pods siguen funcionando, solo se pierde el ingreso.
