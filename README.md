# PokeGotchi

Widget de pantalla de inicio para Android al estilo Tamagotchi, protagonizado por Pokémon.
El widget vive siempre en tu home screen: cuida a tu Pokémon (dale de comer, acarícialo,
báñalo), sube su nivel, hazlo evolucionar, y desbloquea Mega Evolución, Gigantamax y
Fusiones, todo sin necesidad de abrir la app.

Proyecto personal de fan, sin ánimo de lucro. Los sprites y sonidos de los Pokémon
pertenecen a Nintendo / Game Freak / The Pokémon Company; este repositorio no tiene
afiliación con ellos y no se distribuye con fines comerciales.

## Características

- Cuidado en tiempo real: hambre, higiene y felicidad decaen con el tiempo; se recuperan
  interactuando desde el propio widget.
- Sistema de niveles y experiencia, con curvas de crecimiento fieles a los juegos originales.
- Evoluciones (incluyendo las que dependen de felicidad, objeto u otras condiciones), Mega
  Evolución, Gigantamax y Fusiones (Necrozma, Kyurem, Calyrex).
- Formas especiales y de comportamiento (Wormadam/Gourgeist según objeto, Darmanitan modo
  Zen, Eiscue, Morpeko, Cramorant, Dudunsparce/Maushold, Terapagos...).
- Pokédex completa con formas regionales (Alola/Galar/Hisui/Paldea) y sprites propios,
  animados, en estilo pixel art.
- Sistema de huevos, fondos desbloqueables y ciclo día/noche.

## Requisitos

- Android 8.0 (API 26) o superior.
- ~120 MB libres (sprites y sonidos van empaquetados en la app).

## Instalar la última versión (sin compilar nada)

Descarga el APK desde la sección [Releases](../../releases) de este repositorio e
instálalo en tu dispositivo (activa "Instalar apps de origen desconocido" si tu Android
lo pide). Después, mantén pulsado un espacio libre en tu pantalla de inicio → **Widgets**
→ busca **PokeGotchi** → arrástralo a tu home screen.

## Compilar desde el código fuente

Requiere [Android Studio](https://developer.android.com/studio) (recomendado, sincroniza
el SDK automáticamente) o el JDK 11+ y el Android SDK ya instalados.

```bash
git clone https://github.com/Samuelruvi/PokeGotchi.git
cd PokeGotchi
./gradlew assembleDebug
```

El APK resultante queda en `app/build/outputs/apk/debug/app-debug.apk`. Instálalo con
`adb install app/build/outputs/apk/debug/app-debug.apk` o cópialo al dispositivo.

## Estructura del proyecto

- `app/src/main/java/com/example/pokegotchi/` — lógica del juego (`PetState.kt`), UI
  (`MainActivity.kt`), el widget (`PokeWidgetProvider.kt`), y la generación de sprites/
  efectos (`SpriteRepository.kt`, `EffectGenerator.kt`).
- `app/src/main/assets/` — datos de cada especie (stats, tipos, evoluciones, Pokédex,
  curvas de crecimiento, Megas/Gigantamax) y los sprites/cries empaquetados.
