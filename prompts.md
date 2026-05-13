# Uso de IA en este proyecto

## Resumen

Se utilizó Claude (Anthropic) como herramienta de apoyo en tres áreas clave:

1. **Escritura de código**: generación de boilerplate (DTOs, exception handlers, YAML, Terraform) que fue revisado y refinado línea por línea.
2. **Debate de decisiones**: la IA ayudó a explorar alternativas arquitectónicas, comparar opciones y plantear escenarios que luego fueron evaluados y decididos manualmente.
3. **Consulta de soluciones**: cuando surgían problemas específicos (configuración de flags JVM, estructura de manifests, patrones de CI/CD), se consultó la IA como acelerador de búsqueda y síntesis.

**Criterio fundamental**: todo el código, la configuración y las decisiones fueron revisados, entendidos y validados manualmente antes de ser incluidos en el proyecto. La IA no tomó decisiones arquitectónicas por sí sola — cada decisión técnica en este entregable es mía y está fundamentada en código y decisiones explícitas.

---

## Patrones útiles que funcionaron bien

- **Comparación estructurada (A vs B con criterio X)**: generó evaluaciones objetivas y verificables.
- **Draft de ADR con estructura explícita**: aceleró la generación de documentación técnica con formato consistente.
- **Review crítico sin reescritura ("decime qué falta")**: surfaced gaps que después validé a mano.

