"""Localización del diagrama de ajedrez en una foto.

Pipeline:
  1. find_board_quad: candidato a cuadrilátero del marco del tablero.
  2. warp_board: corrección de perspectiva a un cuadrado.
"""

import cv2
import numpy as np

WORK_SIZE = 1200  # lado largo con el que se trabaja para detectar
MIN_SCORE = 0.15  # por debajo, no hay tablero


def order_corners(pts: np.ndarray) -> np.ndarray:
    """Ordena 4 puntos como tl, tr, br, bl."""
    pts = pts.reshape(4, 2).astype(np.float32)
    s = pts.sum(axis=1)
    d = np.diff(pts, axis=1).ravel()
    return np.array(
        [pts[np.argmin(s)], pts[np.argmin(d)], pts[np.argmax(s)], pts[np.argmax(d)]],
        dtype=np.float32,
    )


def _squareness(quad: np.ndarray) -> float:
    """1.0 para un cuadrado perfecto; baja con lados desiguales."""
    sides = np.linalg.norm(quad - np.roll(quad, -1, axis=0), axis=1)
    return float(sides.min() / sides.max())


def _approx_quad(hull: np.ndarray) -> np.ndarray | None:
    """Simplifica una envolvente convexa a 4 vértices, si se puede."""
    peri = cv2.arcLength(hull, True)
    for eps in (0.01, 0.02, 0.03, 0.04, 0.05):
        approx = cv2.approxPolyDP(hull, eps * peri, True)
        if len(approx) == 4:
            return order_corners(approx)
        if len(approx) < 4:
            return None
    return None


def checker_score(gray: np.ndarray, quad: np.ndarray) -> float:
    """Cuánto se parece el interior de `quad` a un tablero 8x8 (~0..1).

    Endereza el cuadrilátero y mide el gris de cada casilla en sus cuatro
    esquinas, donde casi nunca hay tinta de las piezas. Luego mide el
    contraste alterno fila a fila y columna a columna: una fila que cae fuera
    del tablero (papel, coordenadas) no alterna y hunde la puntuación, cosa
    que una correlación global apenas notaría. No sabemos qué esquina es a1,
    así que el signo del patrón se toma del conjunto.
    """
    n = 256
    cell = n // 8
    warped = warp_board(gray, quad, n).astype(np.float32)
    inset, patch = cell // 8, cell // 5
    m = np.empty((8, 8), np.float32)
    for i in range(8):
        for j in range(8):
            y0, x0 = i * cell, j * cell
            ys = (y0 + inset, y0 + cell - inset - patch)
            xs = (x0 + inset, x0 + cell - inset - patch)
            m[i, j] = np.mean([warped[y:y + patch, x:x + patch].mean()
                               for y in ys for x in xs])
    spread = m.std()
    if spread == 0:
        return 0.0
    signed = (m - m.mean()) * (np.indices((8, 8)).sum(axis=0) % 2 * 2 - 1) / spread
    if signed.mean() < 0:
        signed = -signed
    lines = np.concatenate([signed.mean(axis=1), signed.mean(axis=0)])
    return float(0.5 * lines.mean() + 0.5 * lines.min())


def _binarize(gray: np.ndarray, block: int) -> np.ndarray:
    """Tinta = 255. `block` pequeño capta rayados; grande, casillas grises lisas."""
    blur = cv2.GaussianBlur(gray, (5, 5), 0)
    binary = cv2.adaptiveThreshold(
        blur, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, block, 10
    )
    return cv2.morphologyEx(binary, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))


def _quads_from_contours(binary: np.ndarray) -> list[np.ndarray]:
    """Candidatos a partir del marco: envolvente convexa de cada contorno grande.

    El marco casi nunca sale como contorno cerrado (se corta o se pega al
    rayado o al texto), por eso se usa la envolvente y no el contorno.
    """
    img_area = binary.shape[0] * binary.shape[1]
    contours, _ = cv2.findContours(binary, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    quads = []
    for c in contours:
        hull = cv2.convexHull(c)
        if cv2.contourArea(hull) < 0.04 * img_area:
            continue
        quad = _approx_quad(hull)
        if quad is not None and _squareness(quad) >= 0.6:
            quads.append(quad)
    return quads


def _dark_squares(binary: np.ndarray, open_k: int) -> list[tuple[np.ndarray, float]]:
    """Manchas con forma de casilla: (centro, lado aproximado)."""
    mask = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, np.ones((9, 9), np.uint8))
    # La apertura separa casillas oscuras que se tocan por las esquinas y
    # elimina líneas finas (texto, marco).
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((open_k, open_k), np.uint8))
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    max_side = max(binary.shape) / 6
    squares = []
    for c in contours:
        area = cv2.contourArea(c)
        (cx, cy), (rw, rh), _ = cv2.minAreaRect(c)
        if rw < 8 or rh < 8 or max(rw, rh) > max_side:
            continue
        if min(rw, rh) / max(rw, rh) < 0.6 or area / (rw * rh) < 0.7:
            continue
        squares.append((np.array([cx, cy], np.float32), float(np.sqrt(area))))
    return squares


def _diagonal_clusters(squares):
    """Grupos de casillas unidas como vecinas diagonales, del más grande al más pequeño.

    En un tablero, las casillas oscuras vecinas están a ~√2 lados de distancia
    y tienen tamaños parecidos; letras y piezas sueltas no cumplen ambas cosas.
    Con varios diagramas en la página (#10), cada uno forma su propio grupo.
    """
    n = len(squares)
    adj = [[] for _ in range(n)]
    for i in range(n):
        ci, si = squares[i]
        for j in range(i + 1, n):
            cj, sj = squares[j]
            if max(si, sj) / min(si, sj) > 1.5:
                continue
            d = float(np.linalg.norm(ci - cj)) / ((si + sj) / 2)
            if 1.15 < d < 1.75:
                adj[i].append(j)
                adj[j].append(i)
    seen, comps = set(), []
    for start in range(n):
        if start in seen:
            continue
        comp, stack = [], [start]
        seen.add(start)
        while stack:
            k = stack.pop()
            comp.append(k)
            for nb in adj[k]:
                if nb not in seen:
                    seen.add(nb)
                    stack.append(nb)
        comps.append(comp)
    # Orden estable: a igual tamaño, el que se encontró antes (como cuando solo se usaba el mayor).
    comps.sort(key=lambda c: -len(c))
    return comps, adj


def _quads_from_lattice(squares, cluster, adj) -> list[np.ndarray]:
    """Asigna coordenadas de rejilla a las casillas y ajusta una homografía.

    Si las casillas detectadas no cubren las 8 filas o columnas (piezas encima,
    sombras), devuelve todas las formas de completar el tablero y deja que la
    puntuación de patrón elija.
    """
    if len(cluster) < 10:
        return []
    centers = {k: squares[k][0] for k in cluster}
    # Las dos direcciones diagonales p y q salen de los desplazamientos entre
    # vecinas (plegados al semiplano para que v y -v cuenten igual).
    offsets = []
    for k in cluster:
        for nb in adj[k]:
            o = centers[nb] - centers[k]
            if o[0] < 0 or (o[0] == 0 and o[1] < 0):
                o = -o
            offsets.append(o)
    offsets = np.array(offsets, np.float32)
    angles = np.arctan2(offsets[:, 1], offsets[:, 0])  # en (-pi/2, pi/2]
    p_mask = angles < np.median(angles)
    if p_mask.all() or not p_mask.any():
        return []
    p = np.median(offsets[p_mask], axis=0)
    q = np.median(offsets[~p_mask], axis=0)

    # BFS: cada vecina es ±p o ±q respecto a la actual.
    basis = [(p, (1, 0)), (-p, (-1, 0)), (q, (0, 1)), (-q, (0, -1))]
    coords = {cluster[0]: (0, 0)}
    queue = [cluster[0]]
    while queue:
        k = queue.pop(0)
        m, n = coords[k]
        for nb in adj[k]:
            if nb in coords:
                continue
            o = centers[nb] - centers[k]
            vec, (dm, dn) = min(basis, key=lambda b: np.linalg.norm(o - b[0]))
            if np.linalg.norm(o - vec) > 0.5 * np.linalg.norm(vec):
                continue
            coords[nb] = (m + dm, n + dn)
            queue.append(nb)

    # Rejilla de casillas: u = m + n, v = m - n (en lados de casilla).
    keys = list(coords)
    if len(keys) < 10:  # la BFS no ha podido colocar casi ninguna casilla
        return []
    uv = np.array([(m + n, m - n) for m, n in (coords[k] for k in keys)], np.float32)
    img_pts = np.array([centers[k] for k in keys], np.float32)
    H, inliers = cv2.findHomography(uv + 0.5, img_pts, cv2.RANSAC, 0.25 * np.mean(
        [squares[k][1] for k in keys]))
    if H is None:
        return []
    uv = uv[inliers.ravel() == 1]
    lo = uv.min(axis=0).astype(int)
    span = (uv.max(axis=0) + 1).astype(int) - lo
    if (span > 8).any() or (span < 5).any():
        return []
    # También ±1 alrededor: manchas fuera del tablero (coordenadas, texto)
    # pueden haberse colado en el grupo y desplazar el rango.
    quads = []
    for du in range(-1, 8 - span[0] + 2):
        for dv in range(-1, 8 - span[1] + 2):
            u0, v0 = lo[0] - du, lo[1] - dv
            corners = np.array([[u0, v0], [u0 + 8, v0], [u0 + 8, v0 + 8], [u0, v0 + 8]],
                               np.float32)
            quads.append(order_corners(cv2.perspectiveTransform(corners[None], H)[0]))
    return quads


def _quads_from_squares(binary: np.ndarray) -> list[np.ndarray]:
    """Candidatos a partir de las casillas oscuras (no necesita marco)."""
    quads = []
    for open_k in (5, 9, 15, 25):
        squares = _dark_squares(binary, open_k)
        clusters, adj = _diagonal_clusters(squares)
        # Los grupos de menos de 10 casillas no dan rejilla (ver _quads_from_lattice).
        for cluster in [c for c in clusters if len(c) >= 10][:MAX_BOARDS]:
            quads += _quads_from_lattice(squares, cluster, adj)
    return quads


MAX_BOARDS = 4  # diagramas por foto como mucho (#10)


MAX_OVERLAP = 0.2  # fracción del área del menor a partir de la que dos tableros son el mismo


def _overlaps(a: np.ndarray, b: np.ndarray) -> bool:
    """Dos cuadriláteros son el mismo tablero si el centro de uno cae dentro del otro o si
    se solapan más de MAX_OVERLAP (un candidato a caballo entre dos diagramas)."""
    a32, b32 = a.astype(np.float32), b.astype(np.float32)
    ca, cb = a32.mean(axis=0), b32.mean(axis=0)
    if (cv2.pointPolygonTest(b32, (float(ca[0]), float(ca[1])), False) >= 0
            or cv2.pointPolygonTest(a32, (float(cb[0]), float(cb[1])), False) >= 0):
        return True
    inter, _ = cv2.intersectConvexConvex(a32, b32)
    smaller = min(abs(cv2.contourArea(a32)), abs(cv2.contourArea(b32)))
    return smaller > 0 and inter / smaller > MAX_OVERLAP


def find_board_quads(img: np.ndarray, max_boards: int = MAX_BOARDS) -> list[tuple[np.ndarray, float]]:
    """Todos los tableros de la foto (#10): [(esquinas tl, tr, br, bl, puntuación)], el mejor primero.

    Los candidatos se recorren de mayor a menor puntuación; cada uno que no se solape
    con uno ya elegido se ajusta y se queda si supera MIN_SCORE. El primero es
    exactamente el que daba la búsqueda de un solo tablero.
    """
    h, w = img.shape[:2]
    scale = WORK_SIZE / max(h, w)
    small = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)

    # Mapas sobre los que medir el patrón: el gris sirve para casillas lisas;
    # la densidad de tinta, para rayados tan claros que apenas oscurecen.
    features = [gray]
    candidates = []
    for block in (31, 151):
        binary = _binarize(gray, block)
        features.append(cv2.blur(binary, (5, 5)))
        candidates += _quads_from_contours(binary)
        candidates += _quads_from_squares(binary)

    scored = [(quad, _score(features, quad)) for quad in candidates]
    # Orden estable: a igual puntuación, el primer candidato (como la búsqueda original).
    order = sorted(range(len(scored)), key=lambda i: -scored[i][1])
    found: list[tuple[np.ndarray, float]] = []
    tried: list[np.ndarray] = []
    for i in order:
        quad, score = scored[i]
        if score <= 0 or len(found) >= max_boards:
            break
        if any(_overlaps(quad, t) for t in tried):
            continue
        tried.append(quad)
        quad, score = refine_quad(features, quad, score)
        if score >= MIN_SCORE and not any(_overlaps(quad, q) for q, _ in found):
            found.append((quad, score))
        elif not found:
            return []  # el mejor candidato no llega: no hay tablero (como antes)
    return [(q / scale, sc) for q, sc in found]


def find_board_quad(img: np.ndarray) -> tuple[np.ndarray, float] | None:
    """Devuelve (esquinas tl, tr, br, bl en coordenadas de `img`, puntuación), o None."""
    found = find_board_quads(img, max_boards=1)
    return found[0] if found else None


def _score(features: list[np.ndarray], quad: np.ndarray) -> float:
    return max(checker_score(f, quad) for f in features)


def refine_quad(features: list[np.ndarray], quad: np.ndarray, score: float) -> tuple[np.ndarray, float]:
    """Ajuste fino de esquinas por descenso coordenado sobre la puntuación de patrón.

    Los candidatos suelen quedar desplazados una fracción de casilla (marco
    incluido, casillas del borde perdidas, perspectiva). Se mueve cada esquina
    en x e y con pasos decrecientes mientras la puntuación mejore.
    """
    side = float(np.mean(np.linalg.norm(quad - np.roll(quad, -1, axis=0), axis=1))) / 8
    quad = quad.copy()
    for frac in (0.5, 0.25, 0.12, 0.06):
        step = frac * side
        improved = True
        while improved:
            improved = False
            for k in range(4):
                for axis in (0, 1):
                    for sign in (1, -1):
                        trial = quad.copy()
                        trial[k, axis] += sign * step
                        s = _score(features, trial)
                        if s > score:
                            quad, score, improved = trial, s, True
    return quad, score


def warp_board(img: np.ndarray, quad: np.ndarray, size: int = 512) -> np.ndarray:
    dst = np.array([[0, 0], [size - 1, 0], [size - 1, size - 1], [0, size - 1]], np.float32)
    m = cv2.getPerspectiveTransform(quad.astype(np.float32), dst)
    return cv2.warpPerspective(img, m, (size, size), flags=cv2.INTER_AREA)
