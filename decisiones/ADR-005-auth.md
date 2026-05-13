# ADR-005 — Auth: Basic Auth para correr la API, OAuth2 M2M como camino real

## Contexto

La API expone info de infraestructura para consumo M2M. Hay dos cosas distintas en juego: **correr la API y probarla** (local, smoke tests, scrape de Prometheus) versus **exponerla a internet de forma segura**.

## Decisión

### Hoy: Basic Auth

Se eligió Basic Auth básicamente para poder **levantar la API y probarla** sin montar infra de auth externa. Es lo más simple que cumple con "auth obligatoria, sin credenciales hardcodeadas".

```java
.requestMatchers("/health/**").permitAll()
.requestMatchers("/metrics").hasRole("MONITORING")     // Prometheus scrape
.requestMatchers("/api/v1/**").hasRole("API_USER")     // Cliente real
.anyRequest().authenticated()
```

- Dos cuentas en `InMemoryUserDetailsManager` (`API_USER` y `MONITORING`).
- **BCrypt** para hash, **stateless**, **CSRF off**.
- En cluster, las credenciales vienen de AWS Secrets Manager vía ExternalSecret + `envFrom`.

### Para producción: OAuth2 M2M (sólo si la API se expone a internet)

Lo ideal para salir a producción **si la API necesita estar expuesta a internet** es usar **OAuth2 con `client_credentials`** (Cognito M2M o equivalente). Eso es lo que hace el camino seguro:

- Tokens con TTL corto en vez de credenciales largas.
- Revocación granular por cliente.
- Scopes para diferenciar lectura/escritura.

Si la API se queda detrás del perímetro corporativo y sólo la consumen sistemas internos, Basic Auth con rotación de credenciales puede ser suficiente.

## Consecuencias

### Ganamos
- **Funciona local desde el primer `docker compose up`** sin infra externa.
- **Funciona en cluster** vía ExternalSecret + Secrets Manager.
- **Separación de credenciales** entre cliente real y scrape de Prometheus.
- **Stateless + BCrypt + CSRF off** → posture correcta para una API.

### Pagamos
- **Basic Auth no escala a N clientes con scopes distintos.** Aceptado como solución para correr y probar; el camino a OAuth2 está claro.
- **`InMemoryUserDetailsManager` no es un store de usuarios real.** Aceptable: son 2 cuentas técnicas.
