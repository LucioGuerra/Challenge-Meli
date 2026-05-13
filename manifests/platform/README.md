# platform

Componentes cluster-wide. Se instalan **una vez por cluster** (prod-eks y nonprod-eks). No varían por ambiente.

## Carpetas

| Carpeta | Qué provee | CRD que instala |
|---|---|---|
| `aws-load-balancer-controller/` | Controller que crea ALBs reales en AWS a partir de Ingress objects | `IngressClassParams`, `TargetGroupBinding` |
| `external-secrets/` | Operator que sincroniza AWS Secrets Manager → K8s Secrets | `ClusterSecretStore`, `SecretStore`, `ExternalSecret` |
| `argo-rollouts/` | Controller de blue/green y canary | `Rollout`, `AnalysisTemplate`, `Experiment` |
| `observability/` | kube-prometheus-stack + Grafana + Loki + Fluent Bit | `Prometheus`, `ServiceMonitor`, etc (Prometheus Operator) |

## Orden de instalación

1. `aws-load-balancer-controller` (los Ingress de Grafana, app, etc lo necesitan)
2. `external-secrets` (otros componentes lo consumen para credenciales)
3. `argo-rollouts` (la app referencia su CRD `Rollout`)
4. `observability` (la app referencia `ServiceMonitor` del Prometheus Operator)

## Patrón

Cada subdirectorio tiene un `install.yaml` que es una **ArgoCD `Application`** apuntando al Helm chart upstream con valores inline. Razón:

- Mantiene GitOps puro: lo que vive en el repo es lo que está en el cluster.
- ArgoCD versiona el chart version → reproducible.
- Helm hooks corren via `syncWave` si hace falta orden.
- Sin un `helm install` imperativo por fuera de ArgoCD.

`observability/` es la excepción: provee solo **values files** (`*-values.yaml`). Las Applications de Prometheus/Grafana/Loki/Fluent Bit se generan a partir de esos values — ver `observability/README.md` para el wiring.

## Pre-requisitos

Antes de aplicar el primer `install.yaml`:

- EKS cluster existente con node groups corriendo.
- ArgoCD instalado en el cluster (típicamente `argocd` namespace).
- IRSA roles creados en IAM para:
  - `aws-load-balancer-controller` (con la policy oficial del proyecto).
  - `external-secrets` (con `secretsmanager:GetSecretValue` sobre `/meli/*`).
  - `loki` (con S3 access sobre el bucket de Loki).
- Storage class `gp3` disponible (o ajustar values).
- VPC tags requeridos por el LBC (`kubernetes.io/role/elb` para internet-facing, `kubernetes.io/role/internal-elb` para internal).

Todos los `PLACEHOLDER_*` en los `install.yaml` y `*-values.yaml` deben ser reemplazados antes de aplicar (sed, kustomize replacements, o ArgoCD parameter overrides).

## Cómo actualizar

Cambiar el `targetRevision` (chart version) en el `install.yaml` correspondiente, commit y push. ArgoCD detecta el cambio y aplica el upgrade. Para cambios disruptivos (CRD breaking), leer el changelog del chart antes de bumpear.

## Cómo desinstalar

```bash
kubectl delete application <name> -n argocd
```

El finalizer `resources-finalizer.argocd.argoproj.io` asegura cleanup en cascada de los recursos manageados. **Cuidado** con `external-secrets` y `argo-rollouts`: borrarlos elimina las CRDs y todos los CRs dependientes en cualquier namespace.
