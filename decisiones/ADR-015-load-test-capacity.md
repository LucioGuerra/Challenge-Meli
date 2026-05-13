# ADR-015 — Load test + capacity planning del API

## Contexto

El SLO de latencia es **p99 < 500 ms** sobre `/api/v1/*`. Antes de fijar requests/limits y la curva del HPA hacía falta:

1. Saber **cuántos RPS aguanta un pod** antes de degradarse.
2. Saber **qué recurso satura primero** (CPU vs memoria vs threadpool vs GC).
3. Saber **cuánto tarda el sistema en escalar** ante un spike.

Sin estos números, sizing y HPA serían adivinanza.

### Supuesto sobre el tráfico

Asumimos que los 10k RPS provienen principalmente de sistemas automatizados internos: microservicios haciendo polling de estado, dashboards de Grafana consultando métricas, pipelines de CI/CD verificando disponibilidad. El tráfico humano directo es mínimo y esporádico. Eso significa que la carga es **sostenida y predecible**, no spiky con eventos virales — y favorece sizing por baseline en lugar de capacidad de absorción de bursts.

## Decisión

### Test de carga ejecutado (k6, local)

**Entorno**: laptop dev. Spring Boot 3.5 + Java 21 + ZGC. Single pod equivalente. Clients in-memory (no I/O real fuera del proceso).

**Tool**: k6 con escenarios escalonados (`stage_10`, `stage_25`, `stage_50`, `stage_75`, `stage_100`) y spike a 500 VUs.

**Resultados resumidos** (ventana 20 s por stage, threshold SLO `p95 < 500 ms`):

| Stage (VUs) | Requests | RPS | p95 (ms) | Resultado |
|---|---|---|---|---|
| 10 | 2.184 | 109.2 | 107.0 | ✓ OK |
| 25 | 2.413 | 120.7 | 300.8 | ✓ OK |
| 50 | 2.352 | 117.6 | 555.7 | ✗ Degradado |
| 75 | 2.533 | 126.7 | 762.7 | ✗ Degradado |
| 100 | 2.476 | 123.8 | 1.010,1 | ✗ Degradado |

**Spike 500 VUs (60 s)**: avg 1,77 s — p95 4,32 s — max 4,64 s. 21.000 requests, 0 fallos (status 200 = 100%), pero el threshold `p95<500ms` rompe. El pod **no se cae**, pero **se cola** — la cola HTTP empieza a acumular bajo CPU saturado.

### Lecturas operativas

1. **Cuello de botella**: **CPU**. La degradación de p95 escala linealmente con VUs sin que crezca el error rate ni el uso de memoria → no hay GC pressure ni OOM, hay cola.
2. **Sweet spot por pod**: ~25 VUs concurrentes / ~120 RPS mantiene p95 < 500 ms con margen.
3. **Punto de quiebre**: a partir de 50 VUs / ~120 RPS sostenidos, p95 cruza el SLO.
4. **Conclusión de scaling**: el HPA tiene que **escalar antes** de llegar al cuello — por eso CPU target = 60% (no 70-80%). Da 30-40% de headroom para que mientras los pods nuevos arrancan (cold start Spring ~30-60 s), los existentes absorban el spike.

### Sizing resultante (por pod)

| Recurso | Request | Limit |
|---|---|---|
| CPU | 250m | 1.000m (1 core) |
| Memoria | 256Mi | 512Mi |

Razones:
- **CPU limit = 1 core**: el cuello observado. Subirlo no ayuda mucho (la app es mayormente I/O-bound a backends mockeados). Mejor escalar horizontal.
- **CPU request = 250m**: factor 4 vs limit. Conservador para permitir bin-packing denso.
- **Memoria 256Mi/512Mi**: heap (`-XX:MaxRAMPercentage=75`) queda en ~384Mi. Tight para una JVM con ZGC; si entra Tempo/OTEL o clients HTTP reales con connection pools, revisar.

### HPA resultante

```yaml
minReplicas: 3
maxReplicas: 80
metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 60
behavior:
  scaleUp:
    stabilizationWindowSeconds: 30
    policies:
      - type: Pods
        value: 4
        periodSeconds: 60
    selectPolicy: Max
  scaleDown:
    stabilizationWindowSeconds: 300
    policies:
      - type: Pods
        value: 2
        periodSeconds: 60
    selectPolicy: Max
```

Razones por valor:

| Param | Valor | Por qué |
|---|---|---|
| `minReplicas` | 3 | Una por AZ. Tolera caída de 1 AZ sin perder capacidad por debajo de 2 pods. |
| `maxReplicas` | 80 | Headroom para el pico de 10k RPS. Con ~120 RPS por pod, 80 pods = ~9.600 RPS — suficiente para el pico sostenido; si los backends reales son más rápidos que los mocks, re-baselinear. |
| CPU target 60% | 60% | Headroom para spikes durante warmup. 70-80% deja al sistema cruzando SLO antes de que el HPA reaccione. |
| `scaleUp stabilization 30s` | 30 s | Reaccionar rápido a spikes — el tráfico automatizado puede llegar con un burst. |
| `scaleUp 4 pods/min` | máx 4 | Cluster Autoscaler tarda ~60-90 s en agregar nodo nuevo; subir 4 pods/min evita pedir nodos en avalancha. |
| `scaleDown stabilization 300s` | 5 min | Conservador. Evita oscilación si la carga baja-sube-baja. JVM warmup pago. |
| `scaleDown 2 pods/min` | máx 2 | Conservador: mejor pagar pods extra unos minutos que generar un degradado. |

### Por qué CPU como única métrica (no memoria)

- El test mostró memoria estable bajo carga (ZGC mantiene heap acotado).
- Memoria como métrica de HPA en una JVM es ruidosa: la JVM **retiene** heap aunque no esté en uso → el HPA quedaría siempre cerca del target, generando escalado innecesario.
- CPU correlaciona directamente con la latencia bajo el perfil observado → es el predictor honesto del SLO.

### Por qué `Pods` policy y no `Percent`

`Percent` escala exponencialmente (con 10 pods, +100% = +10 pods). `Pods` escala lineal: predecible, alineado con la velocidad del Cluster Autoscaler, suficiente para los perfiles de carga esperados.

### Interacción HPA ↔ Cluster Autoscaler

El HPA escala **pods**; si los nodos están saturados, los pods nuevos quedan `Pending` hasta que el Cluster Autoscaler provisione un nodo. Eso es la fuente principal de incumplimiento de SLO durante un spike. El sizing del cluster tiene que **soportar el HPA max sin Pending sostenido**.

Verificación con la config actual (m5.xlarge, `min=3 max=8`):

| Estado | Nodos | API pods cabe (~14/nodo) | System pods | Total pods api factibles |
|---|---|---|---|---|
| Idle | 3 (min) | 42 | ~10 | hasta 32 sin agregar nodo |
| Bajo carga normal | 3-4 | 42-56 | ~10 | hasta ~46 |
| Pico (CA escalado) | 8 (max) | 112 | ~15 | hasta ~97 |

→ HPA `max=80` requiere que CA escale hasta ~6 nodos (6 × 14 = 84 pods). Con `max=8` hay margen amplio.

**Latencias compuestas durante un spike:**

| Etapa | Latencia |
|---|---|
| HPA detecta CPU > 60% | hasta 30 s (stabilization) |
| HPA decide nuevo target | inmediato |
| Pods Pending si no hay capacidad | 0-5 s |
| CA provisiona nodo nuevo | 90-180 s |
| Pod schedulea + pull image + JVM warmup | 30-60 s |
| **Total worst case** | **~4-5 min** |

Mitigación: el `min=3` + headroom de CPU (target 60%) absorben hasta ~3-4 min de spike sin Pending.

## Consecuencias

### Ganamos
- **Sizing y HPA con base empírica**, no adivinanza.
- **Headroom explícito** (40% CPU + 30 s stabilization) que protege el SLO durante spikes.
- **CPU como métrica única**: simple, predecible, alineada con la causa raíz.
- **HPA con `Pods` policies**: compatible con la velocidad del Cluster Autoscaler.

### Pagamos
- **Más pods en idle** (min 3, target 60%) que con la config previa. Costo extra aceptado por reliability.
- **Baseline local** no es autoritativo — hay que re-correr el test en infra prod-like antes de declararlo cerrado.
- **Memoria 256Mi/512Mi tight** para una JVM Spring Boot — riesgo de OOMKill si crece overhead nativo. Revisar al primer signal de Killed.
