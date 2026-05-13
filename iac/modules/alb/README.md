# alb

## Qué hace este módulo

Crea el Application Load Balancer público que sirve la API: ACM cert con validación DNS, ALB internet-facing en las subnets públicas, target group tipo IP apuntando al puerto 8080 de los pods, redirect 80→443, WAFv2 con 4 reglas (Common Rules, Known Bad Inputs, rate limit 10k/5min por IP, sólo GET), y bucket S3 para los access logs.

## Recursos que crea

- `aws_acm_certificate` con validación DNS (incluye `*.<dominio>` como SAN)
- `aws_security_group` del ALB + reglas mínimas (in: 80/443 ANY, out: 8080 → nodes SG)
- `aws_s3_bucket` para access logs con versioning, SSE, lifecycle 90d, public access block, policy mínima
- `aws_lb`, `aws_lb_target_group`, `aws_lb_listener` × 2 (80 redirect, 443 forward)
- `aws_wafv2_web_acl` con 4 reglas + `aws_wafv2_web_acl_association`

## Decisiones de diseño

- **Target group tipo IP, no instance.** Apunta directamente a los pods vía VPC CNI ENIs. Menor latencia (sin hop NodePort + kube-proxy) y permite a la ALB Controller registrar/desregistrar pods en rolling updates sin tocar nodos.
- **ACM con DNS validation, sin crear Hosted Zone.** Los CNAME de validación se exportan como output. El equipo de DNS los agrega manualmente. Evita acoplar este módulo a Route53.
- **SAN comodín `*.<dominio>`.** Permite hostear varios subdominios bajo el mismo cert (api., admin., etc.) sin reissue.
- **WAF Default Action `ALLOW`.** Las reglas listadas son `BLOCK` o managed (con scoring interno). El default-allow es estándar para un WAF que filtra outliers; default-block exigiría una allowlist exhaustiva imposible de mantener.
- **Rate limit por IP a 10k req/5min.** Bloquea ataques de origen único sin afectar tráfico legítimo. El umbral está calibrado para una API M2M.
- **Sólo GET permitido.** La API es de sólo lectura. POST/PUT/DELETE/PATCH no tienen caso de uso. Si en el futuro se agrega escritura, se actualiza la regla.
- **Listener 443 con TLS 1.3-2021-06.** La política más fuerte ampliamente soportada en clientes modernos.
- **Stickiness deshabilitada.** La API es stateless. Stickiness sólo crearía desbalance.
- **`drop_invalid_header_fields=true`.** Mitiga request smuggling.
- **`enable_deletion_protection=true`.** Previene `terraform destroy` accidental sobre el ALB de producción.
- **Access logs a S3 con encriptación + lifecycle 90 días.** Suficiente para correlación retrospectiva con WAF y para auditoría.

## Trade-offs

- **NAT único + ALB internet-facing = todo el tráfico saliente entra por una sola IP elástica.** En contextos enterprise, multiplicar NATs y/o usar PrivateLink puede valer; aquí se prioriza la simplicidad.
- **Rate limit por IP simple.** Un cliente enterprise con NAT corporativo único podría tropezarse a 10k/5min. Solución: agregar una rule de allow por IP corporativa con priority menor que el rate limit. No aplicado por defecto.
- **Sólo GET en WAF.** Si mañana hay POST, requiere apply de Terraform — pequeño price por defensa en profundidad.
- **Deletion protection on.** Hay que apagarla manualmente para destruir; aceptado a cambio de seguridad operativa.

## Alternativas descartadas

- **NLB:** descartado, no soporta WAFv2 ni TLS termination con ACM out-of-the-box; además se prefiere capa 7 para el header-based routing futuro.
- **CloudFront delante del ALB:** valioso a escala global, pero introduce TTL/caching complications para una API y duplica costos para tráfico regional.
- **WAF Default `BLOCK`:** descartado, requeriría enumerar todo el tráfico legítimo. WAF tradicionalmente es allowlist-amplio + denylist-específico.
- **Target type `instance`:** descartado, peor latencia y rolling updates fallidos en pods.

## Por qué los access logs en S3 son importantes

Cuando una regla del WAF dispara `BLOCK`, queda registrado en CloudWatch (sampled requests). Pero para entender qué pasó *antes* del block (cliente, headers, ruta) hay que cruzar con los access logs del ALB. Sin estos logs, post-mortem de un incidente WAF es prácticamente imposible.

## Variables de entrada

| Nombre | Tipo | Descripción | Requerido |
|---|---|---|---|
| `environment` | string | Sufijo en nombres | sí |
| `tags` | map(string) | Tags base | sí |
| `vpc_id` | string | VPC | sí |
| `public_subnet_ids` | list(string) | Subnets públicas | sí |
| `node_security_group_id` | string | SG de nodos (target del egress 8080) | sí |
| `base_domain` | string | Dominio del cert ACM | sí |

## Outputs

| Nombre | Descripción |
|---|---|
| `alb_arn` | ARN del ALB |
| `alb_dns_name` | DNS público del ALB |
| `alb_zone_id` | Hosted zone ID del ALB |
| `alb_security_group_id` | SG del ALB |
| `target_group_arn` | ARN del target group IP |
| `waf_acl_arn` | ARN del Web ACL |
| `acm_certificate_arn` | ARN del cert ACM |
| `acm_validation_records` | CNAMEs de validación para agregar manualmente |
| `alb_logs_bucket` | Bucket de access logs |

## Dependencias entre módulos

Entrante: `networking` (vpc_id, public_subnets), `eks` (node_security_group_id). Saliente: `eks` (alb_security_group_id ingresa al SG de nodos), observability (alb_arn para alarmas).
