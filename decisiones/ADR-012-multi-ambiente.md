# ADR-012 — Multi-ambiente dev / test / prod con 2 clusters EKS

## Contexto

Necesitamos al menos un ambiente de producción y un ambiente previo para validar cambios. El "deseable" del PDF pregunta cómo garantizar paridad staging/prod sin procesar datos sensibles en staging.

### Supuestos

- La organización ya tiene EKS y opera múltiples clusters; aislar nonprod vs prod en clusters distintos no es exótico para ellos.
- Todo está en una sola cuenta AWS para el alcance del challenge.

## Decisión

**3 ambientes (dev, test, prod) en 2 clusters EKS:**

| Cluster | Ambientes que aloja | Por qué |
|---|---|---|
| `nonprod-eks` | dev + test | Paridad con prod, datos sintéticos, blast radius compartido entre dev/test es aceptable |
| `prod-eks` | prod | Aislado físicamente — un drift en nonprod no puede tocar prod |

**Misma definición Terraform** para ambos clusters. **Mismos manifests base** + overlays por env (Kustomize).

| Aspecto | dev | test | prod |
|---|---|---|---|
| Cluster | nonprod-eks | nonprod-eks | prod-eks |
| Namespace | challenge-meli | challenge-meli | challenge-meli |
| ALB scheme | internal | internal | internet-facing |
| Replicas base | 1 | 2 | 3 |
| HPA min/max | 1/4 | 2/6 | 2/10 |
| Promoción rollout | auto | manual | manual + analyses |
| Log level | DEBUG | INFO | INFO |
| Ingress host | dev.challenge-meli.\<base\> | test.… | api.… |
| Secret path | /meli/dev/challenge-meli-api | /meli/test/… | /meli/prod/… |
| Datos | sintéticos | sintéticos | reales el día que conectemos clients HTTP |

## Cómo garantizamos paridad sin datos sensibles

1. **Misma imagen Docker** en los 3 envs — sólo cambia el tag (commit SHA).
2. **Misma versión de Kubernetes**, mismos add-ons, misma versión de Argo Rollouts/ESO/AWS LBC.
3. **Mismos manifests base** — sólo cambian valores de overlay.
4. **Datos sintéticos** en dev/test (hoy in-memory; mañana apuntando a Prometheus/Alertmanager de nonprod).
5. **Secrets distintos por path** — `/meli/<env>/…`. Una credencial filtrada en dev no abre prod.
6. **IAM roles separados** entre los dos clusters.

## Alternativas consideradas

### 1 cluster, 3 namespaces
Menos infra (1 control plane EKS = -73 USD/mes), operación más simple. Pero el **blast radius es compartido** — un control plane comprometido lleva todos los envs, y no podés simular caídas (drain de nodos, upgrade de K8s) sin tocar prod.

### 3 clusters (uno por env)
Aislamiento total entre todos los envs, pero 3 × 73 USD/mes en control planes + NAT × AZs. Dev y test pueden compartir cluster sin penalizar; el aislamiento que importa es **nonprod vs prod**.

### 2 cuentas AWS (nonprod / prod)
Aislamiento perfecto a nivel IAM/billing/audit, y es el estándar de muchas empresas. Costo operativo mayor (federation de identidad, cross-account roles para CodePipeline) y el IaC actual asume una sola cuenta para simplicidad de entrega. **Marcable como evolución natural** para producción real — la arquitectura actual ya da el aislamiento de blast radius; migrar a 2 cuentas es un cambio de IAM/networking, no de la app.

## Por qué dev y test comparten cluster

| Aspecto | dev | test |
|---|---|---|
| Source | cualquier branch | release branch / main |
| Promoción | auto (CD continuo) | manual (gate humano) |
| Análisis Argo Rollouts | sin smoke ni error-rate | sin (idéntico a dev pero con replicas más altas) |
| Audiencia | devs | QA, product, oncall |
| Datos | sintéticos | sintéticos |

Las diferencias son **de proceso** (promoción manual vs auto) y **de magnitud** (replicas), no de aislamiento físico. Compartir cluster está bien.

## Paridad de configuración: del código al cluster

| Capa | Cómo se mantiene paridad |
|---|---|
| Código | Mismo binario (mismo commit SHA) en los 3 envs |
| Container image | Mismo manifest ECR; tag = commit SHA |
| Manifests | `kustomize build environments/<env>` genera diffs idénticos en estructura |
| Plataforma (CRDs, controllers) | Mismo IaC; lo único que difiere son los `tfvars` |
| Versiones | `kubernetes_version`, `chart_version` fijadas y bumpeadas por commit |

## Consecuencias

### Ganamos
- **Aislamiento prod ↔ nonprod** físico.
- **Mismo IaC, mismos manifests** → paridad estructural.
- **Datos sintéticos en nonprod** → cero compliance burden por datos sensibles.
- **Rollback en test** sin afectar dev.
- **Costo controlado**: 2 control planes en lugar de 3.

### Pagamos
- **Operación de 2 clusters** en lugar de 1.
- **2 NAT GW × 2 AZs × 2 clusters** = 4 NAT GW = ~128 USD/mes.
- **Bootstrap manual** de cada cluster (ArgoCD install + apps).
