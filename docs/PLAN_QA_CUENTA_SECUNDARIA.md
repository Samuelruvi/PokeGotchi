# Plan de ataque: pruebas a fondo en la cuenta secundaria (2026-10-17)

**Alcance acordado con el usuario:** la cuenta secundaria (Android usuario 999, "XSpace") es un conejillo de indias: se puede meter
Pokémon, subir niveles, forzar estados, borrar, etc. **La cuenta principal (usuario 0) NO se toca.** Se prueba todo lo que no se
haya probado directamente, se "hacen cosquillas" al juego para sacar fallos y se verifican mecánicas. **No se arregla nada salvo lo
muy grave** (pérdida de datos, crash en uso normal, progreso bloqueado); lo demás se informa con cómo reproducirlo.

## Cómo (método)
- **Arnés dentro de la app** (`QaHarness.kt`, solo pruebas, se lanza por broadcast con `--user 999`): ejecuta escenarios con la API real
  (`PetState`, `DungeonState`, `DungeonSimulator`) y comprueba **invariantes** tras cada paso. Se **niega a correr** si el uid es del
  usuario 0. Un fallo = excepción, invariante rota, pérdida de datos, resultado imposible o lentitud anormal.
- **Pruebas de interfaz** con volcados de la propia vista (no del móvil) y el log persistente; `logcat` para excepciones/ANR.
- **Copia de seguridad** de las preferencias de la secundaria antes de empezar, por si hace falta volver atrás.
- Cada hallazgo: severidad (GRAVE / MEDIA / BAJA), evidencia, cómo reproducirlo y propuesta de arreglo **sin aplicar** (salvo GRAVE).
- Lo que sea inferencia mía se etiqueta como tal; lo verificado en el móvil, como verificado.

## Invariantes que se vigilan siempre
1. El Pokémon activo existe y no está "evolucionado-away".
2. Un Pokémon está en UN solo sitio: activo / guardería / mazmorra (líder o compañero) son excluyentes.
3. Los miembros de guardería y mazmorra existen de verdad (sin "fantasmas" ni claves de XP sueltas).
4. Barras 0–100, XP ≥ 0, nivel 1–100, caramelos ≥ 0, plazas de guardería ≤ capacidad.
5. El nivel de entrenador máximo guardado nunca baja y los desbloqueos no se re-bloquean.
6. El estado de la mazmorra guardado es coherente (HP, casillas, delantero, compañeros) y se recarga igual que se guardó.
7. Un individuo nunca pisa a otro por un cambio de especie (no se pierde ningún Pokémon sin que el jugador lo decida).

## Bloques de prueba (de más a menos riesgo)
**A. Conflictos entre mecánicas (casos dirigidos).**
 1. Evolucionar cuando YA tengo la forma destino (¿se pisa el otro individuo?). Normal/shiny, con y sin ramas (Eevee).
 2. Evolucionar al líder o a un compañero de una mazmorra en marcha / en cooldown.
 3. Evolucionar un miembro de la guardería; evolucionar el activo.
 4. Borrar (Debug) un miembro de guardería, el líder, un compañero, el activo, el único Pokémon, el padre del huevo.
 5. Poner de activo a uno que está en guardería o mazmorra (API sin la protección de la UI).
 6. Meter en guardería al activo / a uno de mazmorra; mandar a mazmorra uno de guardería; compañero repetido o = líder.
 7. Bajar capacidad (nivel máx) con la guardería llena; caramelos a un evolucionado-away; caramelo a nivel 100.
 8. Soltar/quedarse el huevo con el padre ya borrado, evolucionado o en guardería.
 9. Regalo: elegir de una oferta vieja, elegir dos veces, oferta con especie ya desbloqueada.

**B. Fuzz de operaciones al azar** (cientos/miles de pasos con semilla reproducible): dar Pokémon (normal/shiny, niveles), evolucionar,
 borrar, cambiar activo, guardería (meter/sacar/liquidar), mazmorra (asignar/abandonar/tick), caramelos, huevos, saltos de tiempo,
 regalos, nivel de entrenador, fichas de fondo; invariantes tras cada operación.

**C. Progresión y números.** Curva de XP de los 6 ritmos (monótona, inversa de nivel, sin saltos) y niveles 1–100; XP por encima del 100;
 nivel de entrenador (pozo, persistencia del máximo, ¿se alcanza el 100? tiempo estimado), escalera de desbloqueos (mochila 4, guardería
 6/8/14/24/35/50, compañeros 10/15), fichas de fondo (`bgTokensAvailable` vs desbloqueados).

**D. Guardería.** XP por horas con saltos largos (días/meses), necesidades, huevos (puesta/eclosión, shiny nuevo vs repetido → caramelo,
 variantes regionales), miembros que desaparecen, 6 plazas llenas, avisos, rendimiento de `settleNursery` con muchos miembros.

**E. Mazmorra.** Simulación con equipos 1–3 a pisos hasta 100 (jefes, final del piso 100), nueva IA (perseguir al más cercano, golpe a cualquiera),
 persistencia y recarga (`persist`/`loadRunState`), ticks en segundo plano y puesta al día tras huecos grandes (`catchUpIfStalled`),
 cooldown, modo continuo con compañeros que dejan de ser válidos, reparto de XP, relevo por <25 %.

**F. Regalos y huevo del activo.** Ciclo 12 h / 24 h con saltos de tiempo, ofertas sin repetidos y sin especies ya tenidas, tasas de shiny,
 resolver/descartar, huevo (puesta, fases, eclosión, quedarse/soltar), caramelos.

**G. Formas y mecánicas.** Megas/Gigamax/fusiones (opciones, nivel, cooldown, donantes ocupados), formas decorativas y desbloqueables,
 `displaySpriteName` en todos los estados posibles para todas las especies con mecánica (que nunca devuelva un sprite inexistente).

**H. Datos y assets.** Cobertura de sprites (normal/shiny), sprites de mazmorra (4 direcciones), gritos, iconos de objetos, tiles;
 coherencia `pokedex.json` / `evolutions.json` / `base_stats.json` / `growth_rates.json` / `megas.json` (ids, nombres, ciclos en cadenas).

**I. Interfaz.** Ficha de cada especie (1025) sin crash y con tiempo razonable; Pokédex con cientos de Pokémon (rendimiento, orden, buscador,
 filtros que se guardan); selectores de guardería y mazmorra con muchos; diálogos del regalo/mochila/forma; consejos y tutorial (flujos,
 estado "visto", pulsaciones simuladas).

**J. Robustez.** Matar el proceso a mitad de operaciones, preferencias corruptas o con claves raras, 1025 individuos a la vez
 (memoria/tiempo de arranque/`trainerXpPool`), excepciones en `logcat`, ANR.

## Entregable
Informe final con: lista de problemas ordenada por gravedad (evidencia + repro), lo verificado sin problemas, lo que NO se pudo probar y por
qué, y recomendaciones. Los problemas graves arreglados (si los hubiera) se indican aparte.
