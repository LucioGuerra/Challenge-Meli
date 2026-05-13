# external-secrets

External Secrets Operator (ESO). Sincroniza secretos desde AWS Secrets Manager a `Secret` nativos de K8s.

## Archivos

- `install.yaml`: ArgoCD `Application` que instala el chart `external-secrets` v0.10.4 desde `https://charts.external-secrets.io`. Namespace: `external-secrets-system`.

## Por qué ESO y no Secrets Store CSI Driver

| Aspecto | ESO | Secrets Store CSI |
|---|---|---|
| Output | `Secret` K8s nativo | Volumen montado (no Secret a menos que se habilite sync) |
| Refresh | Pull periódico (configurable) | Solo al montar (re-mount necesario) |
| Trabajar con `envFrom` | Sí, natural | Requiere `secretObjects` sync |
| Templating | Sí (`spec.target.template`) | Limitado |
| Rotación | Transparente: el `Secret` se actualiza, los pods que lo usan via `envFrom` necesitan restart (la app no lee K8s API) | Igual |

ESO es la elección estándar para apps que consumen secretos via env vars.

## Por qué `targetRevision: 0.10.4`

Versión estable, soporta `ClusterSecretStore` v1beta1. CRDs estables.

## Placeholders

| Placeholder | Qué es |
|---|---|
| `PLACEHOLDER_ESO_IRSA_ROLE_ARN` | IAM Role IRSA con `secretsmanager:GetSecretValue` sobre `arn:aws:secretsmanager:<region>:<account>:secret:/meli/*` |

## IAM requerida

Policy mínima sobre el role IRSA del SA `external-secrets`:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": [
      "secretsmanager:GetSecretValue",
      "secretsmanager:DescribeSecret",
      "secretsmanager:ListSecrets"
    ],
    "Resource": "arn:aws:secretsmanager:*:*:secret:/meli/*"
  }]
}
```

## ClusterSecretStore vs SecretStore

Usamos `ClusterSecretStore` (definido en `app/base/external-secret.yaml`) porque:
- Una sola config de auth para todos los namespaces.
- IRSA SA está en `external-secrets-system`, no en `challenge-meli` — usar `SecretStore` namespaced obligaría a duplicar la SA o usar `serviceAccount.audiences` con trust complicado.
- `ClusterSecretStore` permite que cada `ExternalSecret` en cualquier namespace lo referencie.

## Refresh interval

Definido por `ExternalSecret`. Default en `app/base/external-secret.yaml`: `1h`. Tradeoff: más frecuente = más calls a Secrets Manager ($), menos frecuente = más latencia entre rotación de secreto y propagación al pod.

## Obligatorio para producción

- `replicaCount: 2` con leader election.
- `webhook.replicaCount: 2` (admission webhook que valida CRs).
- `serviceMonitor.enabled: true`.
- Network policy o security group que permita egress a `secretsmanager.<region>.amazonaws.com` (443).

## Nice to have

- VPC endpoint para Secrets Manager (`com.amazonaws.<region>.secretsmanager`) → tráfico privado, menor latencia.
- Alertas Prometheus sobre `externalsecret_sync_calls_error`.

## Operar

```bash
# Listar secretos sincronizados
kubectl get externalsecret -A
kubectl get clustersecretstore

# Forzar refresh
kubectl annotate externalsecret challenge-meli-api-secrets -n challenge-meli \
  force-sync=$(date +%s) --overwrite

# Ver último sync status
kubectl describe externalsecret challenge-meli-api-secrets -n challenge-meli
```

## Dependencias

- EKS cluster con OIDC provider y IRSA role configurado.
- Secret existente en AWS Secrets Manager con el path indicado por env (ver `app/environments/<env>/kustomization.yaml`, key `/meli/<env>/challenge-meli-api`).

## Qué pasa si falla

Sin ESO: los `Secret` K8s no se crean. Los pods de la app fallan en startup porque `envFrom.secretRef` retorna `secret "challenge-meli-api-secrets" not found`. ArgoCD reportará el `ExternalSecret` como `SyncFailed`.
