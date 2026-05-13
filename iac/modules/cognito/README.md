# cognito

## Qué hace este módulo

Provisiona un User Pool de Cognito con un Resource Server (scope `api/read`) y un único App Client configurado para OAuth2 `client_credentials` (M2M). Crea un dominio gestionado por Cognito (`*.auth.<region>.amazoncognito.com`) que expone el token endpoint.

## Recursos que crea

- `aws_cognito_user_pool` con policy de password fuerte (no se crean usuarios humanos aquí, pero el pool exige una por API)
- `aws_cognito_resource_server` con scope `api/read`
- `aws_cognito_user_pool_client` M2M con `client_credentials`
- `aws_cognito_user_pool_domain` gestionado

## Decisiones de diseño

- **Client credentials únicamente.** Sin user/password, sin SRP, sin custom auth. Cognito sólo emite tokens vía `POST /oauth2/token` con client_id + client_secret.
- **Scope custom `api/read`.** Define explícitamente el alcance que la API verifica en el JWT. Cualquier petición sin ese scope recibe 403.
- **TTL del access token: 60 minutos.** Cada cliente M2M renueva su token cada hora. Es el patrón canónico para machine-to-machine.
- **`generate_secret=true`.** El App Client tiene secret. Terraform no exporta el secret en outputs porque escribirlo al state file lo deja visible para cualquiera que pueda leer el state.
- **`prevent_user_existence_errors`.** Endurece respuestas para no confirmar existencia/ausencia de usuarios. Aunque este pool no tiene usuarios humanos, dejarlo activado es la práctica correcta.
- **`admin_create_user_only=true`.** Cerramos la auto-registración.
- **Dominio gestionado por Cognito.** No usamos custom domain con ACM. Simplifica setup; en producción real un dominio propio queda mejor para branding y para evitar exponer `cognito.amazoncognito.com` al cliente.

## Trade-offs

- **El client_secret no se exporta.** Hay que extraerlo manualmente post-apply (consola o `aws cognito-idp describe-user-pool-client`) y guardarlo en Secrets Manager en `/meli/api/cognito-client-secret`. Si Terraform lo exportara, quedaría en texto plano en el state file. Riesgo de fricción operativa < riesgo de leak.
- **Dominio gestionado expone el host de Cognito.** En producción se debería usar `auth.<base_domain>` con un cert ACM. Para el challenge, se evita configuración extra de DNS.
- **TTL fijo de 1 hora.** Servicios con muchas instancias renuevan token con throughput constante; podría reducirse a 30 min si se quiere ventana más corta, costo de más llamadas al token endpoint.

## Alternativas descartadas

- **Custom Authorizers Lambda en API Gateway:** sin API Gateway en este stack; el ALB no soporta JWT validation nativa, así que la API valida el token en código.
- **Authorization Code Flow:** descartado porque no hay usuario humano que pueda hacer redirect interactivo. El cliente es M2M.
- **Refresh tokens:** descartados porque cada cliente puede renovar con `client_credentials` de la misma forma; los refresh tokens introducen estado a rotar sin beneficio para M2M.
- **Custom domain con ACM:** descartado para minimizar la configuración manual de DNS para el challenge.

## Cómo guardar el client secret post-apply

```bash
SECRET=$(aws cognito-idp describe-user-pool-client \
  --user-pool-id <pool-id> \
  --client-id <client-id> \
  --query 'UserPoolClient.ClientSecret' --output text)

aws secretsmanager put-secret-value \
  --secret-id /meli/api/cognito-client-secret \
  --secret-string "$SECRET"
```

## Variables de entrada

| Nombre | Tipo | Descripción | Requerido |
|---|---|---|---|
| `environment` | string | Sufijo en nombres | sí |
| `region` | string | Región (compone token URL) | sí |
| `tags` | map(string) | Tags base | sí |

## Outputs

| Nombre | Descripción |
|---|---|
| `user_pool_id` | ID del pool |
| `user_pool_arn` | ARN del pool |
| `user_pool_endpoint` | Issuer del JWKS |
| `app_client_id` | Client ID M2M |
| `token_url` | Endpoint OAuth2 (client_credentials) |
| `domain` | Prefijo del dominio Cognito |

## Dependencias entre módulos

Ninguna entrante. Saliente: la API consume `user_pool_endpoint`, `app_client_id` y `token_url` (vía env vars / config map).
