# Licencias de terceros incluidas en la app

| Componente | Autor | Licencia |
|---|---|---|
| Stockfish 16 y su red `nn-5af11540bbfe.nnue` (se compilan y descargan al compilar la app) | Los autores de Stockfish | GPLv3 — ver `engine/STOCKFISH-LICENSE.txt` y `engine/STOCKFISH-NOTICE.txt` |
| Piezas *chessnut* (`feature/board/src/main/res/drawable-nodpi/piece_w?.png`, `piece_b?.png`) | Alexis Luengas | Apache 2.0 — https://github.com/LexLuengas/chessnut-pieces |
| Piezas *cburnett* («Clásicas», `piece_cburnett_*.png`) | Colin M.L. Burnett | GPLv2+ — vía https://github.com/lichess-org/lila/blob/master/COPYING.md |
| Piezas *merida* (`piece_merida_*.png`) | Armando Hernandez Marroquin | GPLv2+ — vía https://github.com/lichess-org/lila/blob/master/COPYING.md |
| Piezas *fantasy* («Fantasía», `piece_fantasy_*.png`) | Maurizio Monge | MIT — https://github.com/maurimo/chess-art |
| Maia 1 (redes 1100–1900, convertidas a LiteRT: `ml/models/maia/*.tflite`) | CSSLab, Universidad de Toronto | GPLv3 — https://github.com/CSSLab/maia-chess |
| Tesseract4Android 4.9.0 (Tesseract 5) | Adaptech, Google y los autores de Tesseract | Apache 2.0 — https://github.com/adaptech-cz/Tesseract4Android |
| Leptonica, libjpeg-turbo y libpng (dentro de Tesseract4Android) | Dan Bloomberg; libjpeg-turbo; PNG Development Group | BSD 2 cláusulas; IJG/BSD; licencia de libpng |
| Modelo rápido de español de Tesseract (`ml/models/tessdata/spa.traineddata`, de tessdata_fast) | Google | Apache 2.0 — https://github.com/tesseract-ocr/tessdata_fast |
| OpenCV 5.0.0.1 | OpenCV | Apache 2.0 |
| LiteRT 2.3 | Google | Apache 2.0 |

Al incluir Stockfish, si la app se distribuye debe hacerse bajo GPLv3 (ofreciendo el
código fuente). El resto de componentes tienen licencias compatibles con ello.
