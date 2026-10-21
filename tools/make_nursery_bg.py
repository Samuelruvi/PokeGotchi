"""Fondo de la GUARDERIA (pixel art, 135x300): casa de la guarderia al fondo, jardin vallado con
prado, flores, camino de tierra y un claro central. Arte original generado por codigo."""
import math, random, sys
from PIL import Image, ImageDraw

W, H = 135, 300
import os
_here = os.path.dirname(os.path.abspath(__file__))
EGG_PATH = os.environ.get("EGG_BASE", os.path.join(_here, "..", "app", "src", "main", "res", "drawable", "egg_base.png"))
EGG = Image.open(EGG_PATH).convert("RGBA").crop((9, 11, 23, 28))   # el icono real del huevo del juego (14x17)
random.seed(7)
img = Image.new("RGBA", (W, H), (0, 0, 0, 255))
px = img.load()

def rgb(r, g, b): return (r, g, b, 255)

# ---- paleta
G = [rgb(38, 94, 52), rgb(62, 132, 66), rgb(94, 166, 78), rgb(124, 194, 94), rgb(166, 220, 120)]
D = [rgb(138, 98, 62), rgb(178, 136, 88), rgb(212, 176, 120), rgb(232, 204, 150)]
WOOD = [rgb(66, 42, 26), rgb(116, 76, 44), rgb(158, 108, 62), rgb(196, 146, 88)]
ROOF = [rgb(112, 30, 36), rgb(160, 46, 48), rgb(200, 74, 64), rgb(232, 118, 96)]
WALL = [rgb(168, 148, 110), rgb(214, 194, 154), rgb(240, 226, 192)]
STONE = [rgb(88, 94, 106), rgb(134, 142, 154), rgb(180, 188, 198)]
WATER = [rgb(44, 104, 164), rgb(78, 156, 214), rgb(150, 208, 244)]
FLOWERS = [rgb(250, 250, 244), rgb(252, 214, 70), rgb(240, 128, 168), rgb(176, 124, 224), rgb(232, 72, 72)]

def put(x, y, c):
    if 0 <= x < W and 0 <= y < H: px[x, y] = c

def rect(x0, y0, x1, y1, c):
    for y in range(y0, y1):
        for x in range(x0, x1): put(x, y, c)

# ---- 1) cesped base con parches
rect(0, 0, W, H, G[2])
patch = [[random.random() for _ in range(W // 3 + 2)] for _ in range(H // 3 + 2)]
for y in range(H):
    for x in range(W):
        v = (patch[y // 3][x // 3] * 0.6 + patch[(y // 3 + 1) % len(patch)][x // 3] * 0.2 + patch[y // 3][(x // 3 + 1) % len(patch[0])] * 0.2)
        if v > 0.72: put(x, y, G[3])
        elif v < 0.24: put(x, y, G[1])

def tuft(x, y):
    put(x, y, G[4]); put(x - 1, y + 1, G[3]); put(x + 1, y + 1, G[3]); put(x, y + 1, G[1])

for _ in range(520): tuft(random.randrange(1, W - 1), random.randrange(70, H - 4))

# ---- util: blob con sombreado y contorno (copas de arbol, arbustos)
def blob(cx, cy, r, cols, outline):
    pts = set()
    for y in range(int(cy - r) - 1, int(cy + r) + 2):
        for x in range(int(cx - r) - 1, int(cx + r) + 2):
            dx, dy = x - cx, y - cy
            if dx * dx + dy * dy <= r * r: pts.add((x, y))
    for (x, y) in pts:
        dx, dy = (x - cx) / r, (y - cy) / r
        light = dx * -0.55 + dy * -0.8
        c = cols[3] if light > 0.55 else cols[2] if light > 0.05 else cols[1] if light > -0.4 else cols[0]
        put(x, y, c)
    for (x, y) in pts:
        if any((x + a, y + b) not in pts for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            put(x, y, outline)

TREE = [rgb(34, 84, 50), rgb(52, 120, 62), rgb(80, 158, 74), rgb(124, 196, 92)]
TREE_OUT = rgb(24, 62, 38)
BUSH = [rgb(44, 104, 58), rgb(66, 140, 70), rgb(98, 174, 84), rgb(146, 210, 108)]

def tree(cx, base_y, scale=1.0):
    # tronco + copa de varios gajos
    tw = int(5 * scale)
    rect(cx - tw // 2, base_y - int(8 * scale), cx + tw // 2 + 1, base_y, WOOD[1])
    rect(cx - tw // 2, base_y - int(8 * scale), cx - tw // 2 + 1, base_y, WOOD[2])
    for dx, dy, r in [(-8, -14, 8), (8, -14, 8), (0, -22, 9), (-5, -9, 8), (6, -9, 8), (0, -15, 9)]:
        blob(cx + dx * scale, base_y + dy * scale, r * scale, TREE, TREE_OUT)

def bush(cx, cy, r=6, berries=False):
    blob(cx - r * 0.55, cy + 1, r * 0.8, BUSH, TREE_OUT)
    blob(cx + r * 0.55, cy + 1, r * 0.8, BUSH, TREE_OUT)
    blob(cx, cy - r * 0.35, r, BUSH, TREE_OUT)
    if berries:
        for _ in range(6):
            put(int(cx + random.uniform(-r, r)), int(cy + random.uniform(-r * 0.5, r * 0.6)), FLOWERS[4])

# ---- 2) linea de arboles al fondo (denso, oscuro)
for x in range(-6, W + 12, 13):
    tree(x, random.randint(20, 26), 1.15)
for x in range(2, W + 12, 15):
    tree(x, random.randint(30, 36), 1.0)

# ---- 3) la casa de la guarderia
CX = 67
# sombra en el cesped
for y in range(70, 80):
    for x in range(CX - 36, CX + 37):
        e = ((x - CX) / 36.0) ** 2 + ((y - 70) / 9.0) ** 2
        if e <= 1: put(x, y, G[1])
# chimenea (detras del tejado)
rect(88, 24, 97, 46, STONE[1]); rect(88, 24, 90, 46, STONE[2]); rect(95, 24, 97, 46, STONE[0])
rect(86, 22, 99, 26, STONE[0]); rect(87, 23, 98, 25, STONE[1])
# (el humo de la chimenea NO va en el fondo: lo anima la vista - ver NurseryView, CHIMNEY_X/Y)
# tejado (trapecio con tejas)
RY0, RY1 = 28, 54
for y in range(RY0, RY1):
    t = (y - RY0) / (RY1 - RY0)
    half = int(24 + t * 18)
    for x in range(CX - half, CX + half + 1):
        row = (y - RY0) // 4
        c = ROOF[2] if (y - RY0) % 4 != 3 else ROOF[1]
        if (x + row * 3) % 7 == 0 and (y - RY0) % 4 != 3: c = ROOF[1]
        if x - (CX - half) < 2: c = ROOF[3]
        if (CX + half) - x < 2: c = ROOF[0]
        put(x, y, c)
for x in range(CX - 42, CX + 43): put(x, RY1, ROOF[0]); put(x, RY1 + 1, ROOF[0])
for x in range(CX - 24, CX + 25): put(x, RY0, ROOF[3])
# placa ovalada de madera en el tejado con el icono REAL del huevo del juego
def ellipse(cx, cy, rx, ry, c):
    for y in range(int(cy - ry) - 1, int(cy + ry) + 2):
        for x in range(int(cx - rx) - 1, int(cx + rx) + 2):
            if ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1: put(x, y, c)
def paste_egg(x0, y0):
    for yy in range(EGG.height):
        for xx in range(EGG.width):
            r, g, b, a = EGG.getpixel((xx, yy))
            if a > 0: put(x0 + xx, y0 + yy, (r, g, b, 255))
ellipse(CX, 40, 12, 13, WOOD[0])
ellipse(CX, 40, 11, 12, WOOD[2])
ellipse(CX, 40, 9, 10.5, WALL[2])
ellipse(CX - 1, 38, 8, 9, WALL[1] if False else WALL[2])
paste_egg(CX - 7, 32)
# paredes
rect(CX - 38, 56, CX + 39, 78, WALL[1])
rect(CX - 38, 56, CX + 39, 59, WALL[0])
rect(CX - 38, 76, CX + 39, 78, WALL[0])
for x in range(CX - 38, CX + 39):
    if (x - (CX - 38)) % 6 == 0: rect(x, 59, x + 1, 76, WALL[2])
rect(CX - 38, 56, CX - 36, 78, WALL[2]); rect(CX + 37, 56, CX + 39, 78, WALL[0])
# puerta
rect(CX - 6, 60, CX + 7, 78, WOOD[0]); rect(CX - 5, 61, CX + 6, 78, WOOD[2])
rect(CX - 5, 61, CX + 6, 63, WOOD[3]); rect(CX - 4, 65, CX - 1, 71, WOOD[1]); rect(CX + 1, 65, CX + 5, 71, WOOD[1])
rect(CX + 3, 72, CX + 5, 74, rgb(250, 210, 80))
rect(CX - 8, 78, CX + 9, 80, STONE[1]); rect(CX - 8, 78, CX + 9, 79, STONE[2])
# ventanas con cajas de flores
for wx in (CX - 28, CX + 18):
    rect(wx, 62, wx + 12, 72, WOOD[0]); rect(wx + 1, 63, wx + 11, 71, WATER[1])
    rect(wx + 1, 63, wx + 11, 65, WATER[2]); rect(wx + 5, 63, wx + 7, 71, WOOD[2]); rect(wx + 1, 67, wx + 11, 68, WOOD[2])
    rect(wx - 1, 72, wx + 13, 76, WOOD[1]); rect(wx - 1, 72, wx + 13, 73, WOOD[3])
    for i in range(7):
        put(wx + i * 2, 71, FLOWERS[(i + wx) % 5]); put(wx + i * 2, 72, FLOWERS[(i + wx + 2) % 5])
# arbustos junto a la casa
bush(CX - 44, 78, 7, True); bush(CX + 45, 78, 7, True)

# ---- 4) camino de tierra: de la puerta a un claro central
def dirt_noise(x, y):
    v = (x * 7 + y * 13) % 11
    return D[1] if v < 2 else D[3] if v > 8 else D[2]

def in_path(x, y):
    # puerta -> bajada recta ancha 14, luego ensancha en el claro
    if 80 <= y < 120 and abs(x - CX) <= 7: return True
    if 120 <= y < 128 and abs(x - CX) <= 7 + (y - 120): return True
    ey = (y - 175) / 38.0
    ex = (x - CX) / 40.0
    return ex * ex + ey * ey <= 1
for y in range(80, 230):
    for x in range(W):
        if in_path(x, y): put(x, y, dirt_noise(x, y))
# borde: hierba que entra y piedrecitas
for y in range(80, 230):
    for x in range(1, W - 1):
        if in_path(x, y) and not (in_path(x - 1, y) and in_path(x + 1, y) and in_path(x, y - 1) and in_path(x, y + 1)):
            put(x, y, D[0] if random.random() < 0.55 else D[1])
            if random.random() < 0.10: put(x, y, STONE[1])
for _ in range(70):
    x, y = random.randrange(CX - 38, CX + 38), random.randrange(142, 212)
    if in_path(x, y): put(x, y, STONE[2]); put(x + 1, y, STONE[1])

# ---- 5a) arboles fuera del jardin (marco), ANTES de las vallas para que estas queden a la vista
for y in range(96, 296, 36):
    tree(-6, y + 14, 1.15)
    tree(141, y + 6, 1.15)

# ---- 5) vallas (frontal bajo la casa con puerta, laterales y trasera abajo)
def post(x, y, h=12):
    rect(x, y, x + 3, y + h, WOOD[1]); rect(x, y, x + 1, y + h, WOOD[3]); rect(x + 2, y, x + 3, y + h, WOOD[0])
    rect(x - 1, y - 1, x + 4, y + 1, WOOD[2])
def hrail(x0, x1, y):
    rect(x0, y, x1, y + 2, WOOD[2]); rect(x0, y, x1, y + 1, WOOD[3]); rect(x0, y + 2, x1, y + 3, WOOD[0])
def vrail(x, y0, y1):
    rect(x, y0, x + 2, y1, WOOD[2]); rect(x, y0, x + 1, y1, WOOD[3]); rect(x + 2, y0, x + 3, y1, WOOD[0])
FY = 88
hrail(5, CX - 11, FY + 3); hrail(5, CX - 11, FY + 8); hrail(CX + 12, 130, FY + 3); hrail(CX + 12, 130, FY + 8)
for x in list(range(5, CX - 11, 11)) + [CX - 12] + list(range(CX + 12, 130, 11)) + [127]:
    post(x, FY)
# puerta (dos postes mas altos y un arco)
for gx in (CX - 12, CX + 11): rect(gx - 1, FY - 6, gx + 4, FY + 12, WOOD[1]); rect(gx - 1, FY - 6, gx, FY + 12, WOOD[3]); rect(gx - 2, FY - 7, gx + 5, FY - 5, WOOD[2])
# laterales y trasera
for x in (4, 128):
    vrail(x + 1, FY + 4, 294)
    for y in range(FY + 4, 294, 14): post(x, y, 11)
hrail(4, 131, 289); hrail(4, 131, 294)
for x in range(4, 131, 11): post(x, 284, 14)


# ---- 7) flores y arbustos dentro del jardin
def flower_patch(cx, cy, n, spread):
    cols = random.sample(FLOWERS, 2)
    for _ in range(n):
        x = int(cx + random.gauss(0, spread)); y = int(cy + random.gauss(0, spread * 0.7))
        if not in_path(x, y):
            c = random.choice(cols)
            put(x, y, c); put(x - 1, y, G[3]); put(x + 1, y, G[3]); put(x, y + 1, G[1])
            if random.random() < 0.4: put(x, y - 1, FLOWERS[1] if c != FLOWERS[1] else c)
for (cx_, cy_, n_, s_) in [(18, 112, 22, 6), (112, 108, 24, 6), (16, 214, 20, 6), (118, 230, 22, 7), (30, 262, 18, 6), (100, 258, 18, 6), (66, 100, 10, 4), (20, 160, 14, 5), (116, 168, 14, 5)]:
    flower_patch(cx_, cy_, n_, s_)
bush(14, 150, 6, True); bush(121, 200, 6, True); bush(14, 268, 6); bush(121, 120, 6, True)

# ---- 8) detalles: setas (el cartel con el huevo y el fardo de heno se quitaron: pedido del usuario, la guarderia
# queda con el jardin despejado; el unico huevo es el del tejado)
# setas
for (mx, my) in [(40, 280), (96, 150), (24, 238)]:
    rect(mx, my, mx + 2, my + 3, rgb(240, 232, 214)); rect(mx - 2, my - 2, mx + 4, my + 1, FLOWERS[4]); put(mx, my - 1, FLOWERS[0]); put(mx + 2, my - 2, FLOWERS[0])

# ---- 9) viñeta suave (centra la mirada y oscurece los bordes) - ligera
for y in range(H):
    for x in range(W):
        dx = (x - W / 2) / (W / 2); dy = (y - 190) / 190
        v = min(1.0, max(0.0, (dx * dx * 0.5 + dy * dy * 0.35) - 0.35))
        if v > 0:
            r, g, b, a = px[x, y]
            f = 1 - 0.22 * min(1, v * 1.6)
            px[x, y] = (int(r * f), int(g * f), int(b * f), 255)

out = sys.argv[1] if len(sys.argv) > 1 else "bg_guarderia.png"
img.save(out)
img.resize((W * 4, H * 4), Image.NEAREST).save(out.replace(".png", "_x4.png"))
print("ok", out)
