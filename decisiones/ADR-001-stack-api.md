# ADR-001 — Stack de la API: Java 21 + Spring Boot 3.5

## Contexto

El challenge pide una API REST con producción en cloud, 10k RPS de pico y alta disponibilidad. El lenguaje y framework son libres pero hay que justificar la elección. La API es read-heavy, I/O-bound (consulta Prometheus, Alertmanager, ArgoCD) y tiene que escalar horizontalmente.

### Supuestos que tomamos

- Hay un Prometheus en algún lugar de la infra AWS al que la API le pega. No sabemos ni nos importa qué hay detrás.
- Prometheus tiene un Alertmanager configurado.
- Todo el stack vive en AWS.
- La organización ya tiene EKS corriendo.
- El tráfico de 10k RPS viene principalmente de sistemas automatizados internos (microservicios polling, dashboards de Grafana, pipelines de CI/CD). Tráfico humano directo es mínimo.

## La discusión

El debate real giró entre tres opciones: Go, Quarkus y Spring Boot.

**Go** seducía por su fluidez — binario chico, arranque rápido, concurrencia natural para una API con este perfil. Pero el equipo no lo conoce tan bien como Java.

**Java 21** quedó como elección natural por familiaridad del equipo. Ahí se abrió otra discusión: **Quarkus vs Spring**. Quarkus arranca más rápido, tiene mejor footprint y está pensado para cloud-native. Pero el tiempo apretaba y no había margen para sorpresas.

**Spring Boot 3.5** ganó por una razón pragmática: es lo más conocido por el equipo. Aunque sea el más lento de los tres en arranque y footprint, la curva de aprendizaje cero y la madurez de Actuator + Micrometer + Security + Validation hacen que el time-to-feature-complete sea el menor. En un challenge con deadline, predictibilidad gana sobre performance pura.

## Decisión

**Java 21 LTS + Spring Boot 3.5 + Maven**, con:

- `spring.threads.virtual.enabled: true` (Virtual Threads de Java 21).
- Spring Boot Actuator + Micrometer Prometheus para health y métricas.
- Spring Security + Bean Validation + Spring Web.
- Logback + Logstash encoder para JSON logs.
- JaCoCo para cobertura.
- Imagen Docker multi-stage con JRE 21 alpine, usuario no-root, ZGC.

## Consecuencias

### Ganamos
- **Virtual Threads** para concurrencia I/O-bound sin pool tuning.
- **Actuator + Micrometer** out-of-the-box: `/health/live`, `/health/ready`, `/metrics`.
- **Spring Security** + roles + BCrypt listo en pocas líneas.
- **Bean Validation** declarativa para query params y path variables.
- **Logback + Logstash encoder**: JSON logs nativos.
- **ZGC generacional** (default en Java 21): pausas <1 ms.

### Pagamos
- **Tiempo de arranque ~3-5 s en local, ~10 s en cluster.** Mitigado con `startupProbe`.
- **Footprint inicial ~200-300 MB RAM** (Spring + Tomcat).
- **Cold start** más alto que Go o Quarkus. Lo absorbe el `scaleDownDelaySeconds` del Rollout.
- **Tamaño de imagen ~280 MB** (vs ~30 MB en Go).
