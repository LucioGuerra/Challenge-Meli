# ADR-006 — IRSA sobre Pod Identity

## Contexto

Los pods del cluster necesitan llamar APIs de AWS (Cluster Autoscaler, External Secrets, AWS LBC, EBS CSI, Loki sobre S3). AWS ofrece dos mecanismos para eso:

1. **IRSA** (IAM Roles for Service Accounts) — federado vía OIDC provider del cluster.
2. **EKS Pod Identity** — más nuevo (GA en noviembre de 2023), sin OIDC, asociación directa SA ↔ Role.

### Supuesto clave

Asumimos que la organización ya usa **IRSA** como estándar interno, porque Pod Identity es relativamente nuevo (2023). En un greenfield 2026 Pod Identity sería más simple, pero entrar pisando un estándar establecido genera más fricción que valor.

## Decisión

**IRSA** para todos los workloads que necesiten IAM.

- El módulo `eks` crea el OIDC provider y los roles IRSA (cluster-autoscaler, ebs-csi, external-secrets, aws-load-balancer-controller).
- Cada ServiceAccount tiene anotación `eks.amazonaws.com/role-arn: <arn>`.
- Trust policy: `Federated: <oidc_provider_arn>` con condition `sub = system:serviceaccount:<ns>:<sa>`.

## Por qué no Pod Identity

Pod Identity es objetivamente más simple — sin OIDC, sin anotaciones, trust policy más corta. Pero adoptarlo en una organización que ya estandarizó IRSA implica:

- Romper el patrón que el resto del stack ya sigue.
- Forzar overrides en charts upstream que vienen con anotaciones IRSA por default.
- Generar inconsistencia silenciosa hasta el primer error.

**Consistencia gana sobre modernidad** cuando ambos mecanismos cumplen el mismo objetivo con la misma seguridad.

## Consecuencias

### Ganamos
- **Cero credenciales estáticas** en el cluster.
- **Roles auditables** vía CloudTrail con identidad clara (`<sa>:<ns>`).
- **Trust granular**: cada SA matchea exactamente un role.
- **Consistencia** con el estándar interno y con los charts upstream.

### Pagamos
- **Más boilerplate** que Pod Identity (anotación + condition `sub` por trust policy).
- **OIDC provider** es un recurso adicional a mantener (gratuito).
- **Una decisión "del pasado" en un proyecto greenfield** — explícitamente, en un greenfield aislado elegiríamos Pod Identity. Acá pesó más la consistencia interna.
