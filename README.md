<p align="center">
  <img src="docs/icon.png" alt="Pawneyes" width="128">
</p>

<h1 align="center">Pawneyes</h1>

<p align="center">
  Escanea con el móvil un diagrama de ajedrez de un libro, conviértelo en una posición editable y analízala con Stockfish.
</p>

<p align="center">
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/ilismal/pawneyes">
    <img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Consíguelo en Obtainium" height="60">
  </a>
</p>

> [!NOTE]
> **Esto es un proyecto hobby para aprender**: visión por ordenador, entrenamiento de un
> clasificador con datos sintéticos, Android con Compose, CameraX y un motor de ajedrez.
> Funciona razonablemente con los libros con los que lo he probado, pero no está pensado
> para competir con nada. Si lo que quieres es escanear diagramas de verdad, mejor usa
> [chessvision.ai](https://chessvision.ai) o aplicaciones similares, que son mucho más
> completas y fiables.

<p align="center">
  <img src="docs/screenshots/01-escaneo-y-analisis.png" alt="Diagrama escaneado y analizado" width="260">
  &nbsp;
  <img src="docs/screenshots/02-explorar-linea.png" alt="Recorriendo una línea de Stockfish" width="260">
  &nbsp;
  <img src="docs/screenshots/03-editor.png" alt="Editor de posiciones" width="260">
</p>

## Qué hace

- **Escanea un diagrama** desde la cámara (con autocaptura cuando el tablero se queda quieto),
  desde la galería o compartiendo una foto con la app. Detecta el tablero aunque la foto esté
  girada o en perspectiva, y marca en rojo las casillas de las que no está seguro.
- **Editor de posiciones**: colocar y quitar piezas, turno, enroques, FEN, deshacer, avisos de
  posiciones imposibles (reyes que faltan, peones en la 1ª fila, jaque al bando que no mueve…).
- **Análisis con Stockfish 19** dentro de la app: evaluación, las tres mejores líneas en notación
  algebraica española (R, D, T, A, C) y una flecha con la mejor jugada.
- **Recorrer las líneas** jugada a jugada; el motor sigue analizando la posición que estás viendo.
- Copiar el FEN o abrir la posición en Lichess.

Requiere Android 8.0 o superior en un móvil ARM64 (casi todos los actuales). La APK ocupa unos
100 MB, la mayor parte por Stockfish y sus redes neuronales.

## Instalación

- Con **[Obtainium](https://github.com/ImranR98/Obtainium)**: pulsa el botón de arriba desde el
  móvil. Obtainium avisa de las nuevas versiones.
- A mano: descarga la APK de la última [release](https://github.com/ilismal/pawneyes/releases).

## Cómo funciona

```
foto ─► detector del tablero (OpenCV) ─► tablero enderezado 512×512
     ─► 64 casillas ─► CNN pequeña (LiteRT) en las 4 rotaciones
     ─► elegir rotación + reglas de legalidad ─► posición ─► editor / Stockfish
```

1. **Detector** (`ml/detect`, portado a `android/vision`): binarización adaptativa, casillas
   oscuras agrupadas por vecindad diagonal, homografía y un ajuste fino que maximiza el
   contraste de tablero de ajedrez. No necesita que el diagrama tenga marco.
2. **Clasificador de casillas** (`ml/train`): una CNN de ~300 000 parámetros, 13 clases (vacía +
   6 piezas × 2 colores). Se entrena **sobre todo con diagramas sintéticos**: el generador
   (`ml/synth`) dibuja tableros con 25 juegos de piezas (y reserva otros 3 para validar) y distintos estilos de casilla
   (rayado, punteado, gris liso…) y simula la foto (perspectiva, curvatura de la página,
   sombras, desenfoque, ruido, JPEG). Después se ajusta con unas pocas fotos reales.
3. **Rotación y legalidad**: se prueban las 4 orientaciones y se elige la de mayor confianza en
   las casillas ocupadas; luego se corrigen errores imposibles (un rey de cada color, máximo de
   peones…) usando la segunda opción del modelo.

La implementación Android reproduce exactamente la de Python: hay tests que comparan cada paso
(esquinas del detector, recorte, salidas del modelo, rotación y reglas) contra fixtures
generados en Python.

**Precisión**: en mis 10 fotos de prueba (que no se publican, ver abajo) acierta el 99,4 % de las
casillas y lee 8 de los 10 tableros sin ningún error. Funciona mejor con los libros que ha
visto en el ajuste fino; con estilos de pieza muy distintos se equivoca más.

## Estructura

```
android/            App (Kotlin, Jetpack Compose)
  core/chess/       Posición, FEN, generador de jugadas (verificado con perft), notación
  vision/           Detector, recorte y clasificador (OpenCV 5 + LiteRT)
  engine/           Stockfish por UCI (el binario se descarga al compilar)
  feature/board/    Tablero y editor
  feature/camera/   Cámara con detección en vivo y autocaptura
  app/              Pantallas, análisis y explorador de líneas
ml/                 Python: generador sintético, detector, entrenamiento y exportación
```

## Compilar

### App

Necesitas JDK 21 y el SDK de Android (lo más cómodo: Android Studio).

```sh
cd android
./gradlew :app:assembleDebug          # APK de depuración en app/build/outputs/apk/debug/
./gradlew :core:chess:test            # tests JVM
```

Stockfish se descarga automáticamente de su release oficial la primera vez (y se comprueba su
SHA-256). El modelo ya entrenado está en `ml/models/square_net.tflite`.

Para una APK release firmada, crea `android/release.jks` y `android/keystore.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). El workflow
`.github/workflows/release.yml` hace lo mismo en GitHub Actions al publicar una etiqueta `vX.Y.Z`.

### Modelo (opcional)

Con [uv](https://docs.astral.sh/uv/):

```sh
cd ml
uv run python -m synth.download_pieces       # juegos de piezas de Lichess
uv run python synth/pieces.py                # rasterizarlos
uv run python -m train.train --epochs 12     # entrenar (solo con datos sintéticos)
uv run python -m export.export_tflite        # exportar a TFLite y comprobar que coincide
```

Las fotos de evaluación (`ejemplos/`), las del ajuste fino (`entrenamiento/`) y los fixtures que
salen de ellas (`fixtures/`) **no se publican**: son páginas de libros con derechos de autor.
Para usar las tuyas, pon fotos con su `.fen` (solo la parte de piezas) en `ejemplos/` y genera
los fixtures con `uv run python -m export.fixtures`. Sin fixtures, los tests de paridad
Python/Android simplemente se omiten.

## Licencia

[GPLv3](LICENSE). La app incluye [Stockfish](https://stockfishchess.org) (GPLv3), las piezas
[chessnut](https://github.com/LexLuengas/chessnut-pieces) de Alexis Luengas (Apache 2.0),
OpenCV y LiteRT (Apache 2.0): ver [android/LICENSES.md](android/LICENSES.md).
