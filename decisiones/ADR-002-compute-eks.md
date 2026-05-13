# ADR-002 — Compute: EKS sobre ECS / Lambda / App Runner

## Contexto

La API es stateless, long-running, I/O-bound, tiene que sostener 10k RPS de pico, vive en 3 ambientes y necesita rollback automatizado además de observabilidad de cluster.

### Supuestos

- La organización ya cuenta con EKS como plataforma estándar — esto inclina la balanza desde el arranque.
- Todo el stack está en AWS.

## Decisión

**Amazon EKS 1.31** con Managed Node Group:

- 2 nodos `t3.medium`, autoscaling 2 → 6 vía Cluster Autoscaler.
- Endpoint del API server **privado**.
- Add-ons gestionados: `vpc-cni` (con `ENABLE_PREFIX_DELEGATION=true`), `coredns`, `kube-proxy`, `aws-ebs-csi-driver`.
- IRSA habilitado (OIDC provider creado por el módulo `eks`).
- KMS CMK para envelope encryption de Kubernetes secrets.
- Logging del control plane completo.

## Alternativas consideradas

### ECS Fargate
Cero gestión de nodos, networking más simple, integración nativa con ALB/IAM/Secrets/CloudWatch. Pero sin DaemonSets (Fluent Bit y node-exporter no tienen lugar natural), sin Argo Rollouts con `AnalysisTemplate`, y el GitOps termina siendo menos directo que con Kubernetes.

### Lambda + API Gateway
Serverless real y escalado automático, pero 10k RPS sostenidos en Java tienen el peor cold start del menú. Además, sale más caro que un cluster pequeño y no permite GitOps end-to-end con ArgoCD.

### App Runner
Demasiado opaco para el nivel de control que pide el challenge (rollback strategy, observabilidad, blue/green).

### Fargate con EKS (Fargate Profiles)
Sin DaemonSets — la restricción dura que rompe la observabilidad que diseñamos.

## Consecuencias

### Ganamos
- **Argo Rollouts** + blue/green + `AnalysisTemplate`.
- **DaemonSets** funcionan: Fluent Bit y node-exporter en cada nodo.
- **HPA + Cluster Autoscaler** con behavior tuneado para absorber spikes.
- **Topology spread** por zona — una caída de AZ no tira la API.
- **GitOps puro**: el cluster vive en Git; el pipeline nunca toca el cluster.
- **Kubernetes API** es donde el equipo ya está formado.

### Pagamos
- **EKS control plane** = 73 USD/mes fijo por cluster.
- **Operación de cluster** (upgrades, add-ons, roles IRSA). Mitigado con managed node group + add-ons gestionados.
- **Mayor complejidad** que ECS para quien no conoce Kubernetes.
