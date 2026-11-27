#!/usr/bin/env python3
"""Generates every binary/derived file of the Phone resource pack and the glyph table the plugin reads.

usage: python3 tools/gen_pack.py            (run from anywhere; paths are relative to the repo)

Writes
  resourcepack/assets/phone/textures/item/*.png        phone (2 states), photo paper, photo icon
  resourcepack/assets/phone/textures/font/*.png        bubble pieces, screen panels, header, icons
  resourcepack/assets/phone/font/ui.json               the phone:ui font (bitmap + space providers)
  resourcepack/assets/phone/post_effect/camera_*.json  one viewfinder/zoom effect per zoom level
  resourcepack/assets/phone/sounds/phone/*.ogg         synthesised notification / send / shutter sounds (needs ffmpeg)
  src/main/resources/font/glyphs.properties            name = codepoint,advance,...   (read by ui/Glyphs.java)

The geometry constants below are the single source of truth: the Java side reads glyphs.properties instead of
repeating them. Hand-written pack files (pack.mcmeta, items/, models/, shaders/, sounds.json, lang/) are not touched.
"""
import json
import math
import os
import random
import shutil
import struct
import subprocess
import sys
import wave

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pnglib import write_png  # noqa: E402

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
ASSETS = os.path.join(ROOT, "resourcepack", "assets", "phone")
TEX_FONT = os.path.join(ASSETS, "textures", "font")
TEX_ITEM = os.path.join(ASSETS, "textures", "item")

# ---------------------------------------------------------------------------------------------------------------------
# UI geometry (GUI pixels). Java reads these through glyphs.properties ("layout.*" keys).
# ---------------------------------------------------------------------------------------------------------------------
# The phone is portrait: a black screen in a rounded bezel, as wide as one 148 px row of dialog buttons.
#
# Horizontal alignment with the vanilla dialog (26.3 client): the body text widget is BODY_W wide (even), its lines
# are centred inside BODY_W - 8, and the button grid (CONTENT_W wide) is centred in the same column. With a line
# advance of PANEL_W + 1 = 166 the grid then starts exactly PANEL_MARGIN = 9 px right of the line start. The panel
# art leaves its column 0 transparent, so the bezel looks the same on both sides (8 px around the content).
CONTENT_W = 148          # = 1x148 = 2x73+2 = 3x48+4 = 5x28+8: every button grid fills the screen exactly
PANEL_W = 165            # the phone behind the text (column 0 transparent)
PANEL_MARGIN = 9         # content x 0 = panel x 9
BODY_W = 174             # width of the dialog text element (Java: ScreenRenderer.bodyWidth)
PANEL_TOP = 4            # px the panel starts above the top of the first text line (= the dialog text padding)
PANEL_HEIGHTS = list(range(48, 241, 16)) + [255]   # one glyph per height; a glyph must fit the 256 px font atlas
BUTTON_COLUMNS = {1: 148, 2: 73, 3: 48, 5: 28}     # columns -> button width of a grid that is CONTENT_W wide
CAP_W = 3                # rounded end of a bubble
TAIL_W = 2               # the little tail on the last row of a bubble
TILE_WIDTHS = [1, 2, 4, 8, 16, 32, 64, 128]
SPACE_POWERS = 10        # spaces of +-1, 2, 4 ... 512 px
ZOOM_LEVELS = [1, 2, 3, 4, 6, 8, 12, 16]   # camera_<zoom>.json post effects that exist in the pack
BIG_SCALE = 3            # lock-screen clock: the vanilla digits drawn this many times larger
PHOTO_SIZE = 128         # a photo in a dialog: one glyph per pixel, see photo_font()

# colours of the phone
BEZEL_EDGE = (74, 76, 86, 255)
BEZEL = (17, 17, 21, 255)
SCREEN = (4, 4, 6, 252)
ISLAND = (22, 22, 26, 255)

T = (0, 0, 0, 0)
WHITE = (255, 255, 255, 255)


def blank(w, h):
    return [[T] * w for _ in range(h)]


def rect(img, x0, y0, x1, y1, col):
    for y in range(max(0, y0), min(len(img), y1 + 1)):
        for x in range(max(0, x0), min(len(img[0]), x1 + 1)):
            img[y][x] = col


def rounded(img, x0, y0, x1, y1, radius, col):
    """Filled rectangle with pixel-art rounded corners (inclusive coordinates)."""
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            dx = max(x0 + radius - x, x - (x1 - radius), 0)
            dy = max(y0 + radius - y, y - (y1 - radius), 0)
            if dx * dx + dy * dy <= radius * radius + radius * 0.6:
                img[y][x] = col


def disc(img, cx, cy, r, col):
    for y in range(len(img)):
        for x in range(len(img[0])):
            if (x - cx) ** 2 + (y - cy) ** 2 <= r * r + r * 0.5:
                img[y][x] = col


def save(path, img):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    write_png(path, len(img[0]), len(img), img)


# ---------------------------------------------------------------------------------------------------------------------
# Item textures (32x32)
# ---------------------------------------------------------------------------------------------------------------------
def phone_texture(unread):
    img = blank(32, 32)
    edge, body, dark = (70, 76, 92, 255), (27, 30, 38, 255), (14, 16, 21, 255)
    rounded(img, 8, 2, 23, 29, 3, edge)
    rounded(img, 9, 3, 22, 28, 2, body)
    # screen with a vertical gradient
    for y in range(6, 25):
        t = (y - 6) / 18.0
        col = (int(72 - 30 * t), int(176 - 70 * t), int(250 - 40 * t), 255)
        rect(img, 11, y, 20, y, col)
    # glare
    for i in range(5):
        img[7 + i][19 - i] = (150, 215, 255, 255)
    # app icons: a 3x3 grid of 2x2 tiles
    apps = [(120, 220, 120), (255, 205, 90), (255, 120, 120), (190, 150, 255), (255, 255, 255), (110, 235, 235),
            (255, 170, 80), (130, 190, 255), (240, 130, 200)]
    for i, c in enumerate(apps):
        x, y = 12 + (i % 3) * 3, 11 + (i // 3) * 4
        rect(img, x, y, x + 1, y + 1, c + (255,))
    rect(img, 14, 4, 17, 4, dark)            # speaker
    rect(img, 15, 26, 16, 27, edge)          # home button
    if unread:
        # red pixelated notification dot, top-right corner
        disc(img, 23, 6, 4, (120, 12, 18, 255))
        disc(img, 23, 6, 3, (232, 40, 46, 255))
        img[4][22] = (255, 150, 150, 255)
        img[5][21] = (255, 110, 110, 255)
    return img


def photo_paper_texture():
    img = blank(32, 32)
    rect(img, 7, 4, 24, 27, (150, 156, 168, 255))
    rect(img, 8, 5, 23, 26, (244, 246, 250, 255))
    # glossy stripes
    for i in range(14):
        img[8 + i][21 - i] = (214, 226, 244, 255)
        if i < 11:
            img[11 + i][22 - i] = (226, 234, 247, 255)
    # folded corner
    for i in range(5):
        rect(img, 24 - i, 4 + i, 24, 4 + i, T)
        img[4 + i][24 - i - 1 if 24 - i - 1 >= 7 else 7] = (150, 156, 168, 255)
    for i in range(4):
        rect(img, 20 + i, 5 + i + 1, 20 + i, 8, (200, 206, 218, 255))
    return img


def photo_texture():
    """Inventory icon of a printed photo: an instant-camera print with a little landscape."""
    img = blank(32, 32)
    rect(img, 5, 3, 26, 28, (140, 140, 140, 255))
    rect(img, 6, 4, 25, 27, (246, 244, 236, 255))
    for y in range(6, 21):
        t = (y - 6) / 14.0
        rect(img, 8, y, 23, y, (int(110 + 70 * t), int(180 + 40 * t), 245, 255))
    disc_img = [(19, 9)]
    for cx, cy in disc_img:
        for y in range(6, 21):
            for x in range(8, 24):
                if (x - cx) ** 2 + (y - cy) ** 2 <= 5:
                    img[y][x] = (255, 232, 120, 255)
    for x in range(8, 24):
        hill = int(16 + 2.5 * math.sin((x - 8) / 3.2) + (x - 8) * 0.08)
        rect(img, x, hill, x, 20, (86, 160, 78, 255))
        img[hill][x] = (120, 190, 100, 255)
    rect(img, 9, 23, 18, 23, (190, 186, 176, 255))   # caption line
    return img


# ---------------------------------------------------------------------------------------------------------------------
# Font pieces
# ---------------------------------------------------------------------------------------------------------------------
class FontBuilder:
    """Collects bitmap/space providers and the name -> glyph table."""

    def __init__(self):
        self.providers = []
        self.glyphs = {}        # name -> (codepoint, advance)
        self.next_cp = 0xE000

    def alloc(self, name, advance):
        cp = self.next_cp
        self.next_cp += 1
        self.glyphs[name] = (cp, advance)
        return chr(cp)

    def sheet(self, file_name, cells, cell_w, cell_h, height, ascent):
        """cells: list of (name, image). One provider, one row; every image is at most cell_w x cell_h."""
        img = blank(cell_w * len(cells), cell_h)
        chars = ""
        for i, (name, cell) in enumerate(cells):
            width = 0
            for y, row in enumerate(cell):
                for x, px in enumerate(row):
                    img[y][i * cell_w + x] = px
                    if px[3] != 0:
                        width = max(width, x + 1)
            # the client's formula: (int)(0.5 + width * height / cell_height) + 1
            chars += self.alloc(name, int(0.5 + width * height / cell_h) + 1)
        save(os.path.join(TEX_FONT, file_name), img)
        assert ascent <= height, "%s: the client rejects a font whose ascent (%d) is larger than its height (%d)" % (
            file_name, ascent, height)
        self.providers.append({"type": "bitmap", "file": "phone:font/" + file_name, "height": height,
                               "ascent": ascent, "chars": [chars]})

    def big_digits(self):
        """The lock-screen clock: 5x7 pixel digits of our own, drawn three times larger by the provider's height.

        (An earlier version pointed a provider at the vanilla ascii.png; the 26.3 client drew its "2" blank.)"""
        font = {
            "0": [".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."],
            "1": ["..#..", ".##..", "..#..", "..#..", "..#..", "..#..", "#####"],
            "2": [".###.", "#...#", "....#", "..##.", ".#...", "#....", "#####"],
            "3": [".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###."],
            "4": ["...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."],
            "5": ["#####", "#....", "####.", "....#", "....#", "#...#", ".###."],
            "6": ["..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."],
            "7": ["#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."],
            "8": [".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."],
            "9": [".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."],
            "colon": [".", "#", ".", ".", ".", "#", "."],
        }
        cells = []
        for name, rows in font.items():
            img = blank(8, 8)
            for y, row in enumerate(rows):
                for x, c in enumerate(row):
                    if c == "#":
                        img[y][x] = WHITE
            cells.append(("big." + name, img))
        self.sheet("big_digits.png", cells, 8, 8, 8 * BIG_SCALE, 7 * BIG_SCALE)

    def spaces(self):
        advances = {}
        for k in range(SPACE_POWERS):
            advances[self.alloc("space.p%d" % (1 << k), 1 << k)] = 1 << k
        for k in range(SPACE_POWERS):
            advances[self.alloc("space.n%d" % (1 << k), -(1 << k))] = -(1 << k)
        self.providers.insert(0, {"type": "space", "advances": advances})


def bubble_row(kind):
    """Pieces of one text row of a speech bubble. Rows are 9 px apart (the line height of dialog text).

    kind  rows relative to the top of the text line   corners
    top   -2 .. 7  (10 px)                             rounded above
    mid   -1 .. 7  ( 9 px)                             none
    bot   -1 .. 8  (10 px)                             rounded below (+ optional tail)
    one   -2 .. 8  (11 px)                             rounded above and below (+ optional tail)

    All pieces are white; the plugin tints them through the text colour.
    """
    top_round = kind in ("top", "one")
    bot_round = kind in ("bot", "one")
    h = 9 + (1 if top_round else 0) + (1 if bot_round else 0)
    ascent = 8 + (1 if top_round else 0)
    shade = (232, 236, 242, 255)         # last row slightly darker: a hint of depth after tinting

    def inset(y):
        d = 0
        if top_round:
            d = max(d, 2 - y)
        if bot_round:
            d = max(d, y - (h - 3))
        return max(0, d)

    def colour(y):
        return shade if (bot_round and y == h - 1) else WHITE

    def cap(left, tail):
        w = CAP_W + (TAIL_W if tail else 0)
        img = blank(128, h)
        for y in range(h):
            for i in range(CAP_W):
                # i = distance from the outer edge of the bubble
                if i >= inset(y):
                    x = (i + (TAIL_W if tail else 0)) if left else (CAP_W - 1 - i)
                    img[y][x] = colour(y)
        if tail:
            # two pixels poking out at the bottom, pointing away from the bubble
            for (dx, y) in ((0, h - 1), (1, h - 1), (1, h - 2)):
                x = dx if left else (w - 1 - dx)
                img[y][x] = colour(y)
            # fill the corner the tail grows out of
            for y in (h - 2, h - 1):
                for i in range(CAP_W):
                    x = (i + TAIL_W) if left else (CAP_W - 1 - i)
                    img[y][x] = colour(y)
        return img

    def tile(w):
        img = blank(128, h)
        for y in range(h):
            for x in range(w):
                img[y][x] = colour(y)
        return img

    cells = [("bubble.%s.capl" % kind, cap(True, False)), ("bubble.%s.capr" % kind, cap(False, False))]
    if bot_round:
        cells += [("bubble.%s.capl_tail" % kind, cap(True, True)), ("bubble.%s.capr_tail" % kind, cap(False, True))]
    cells += [("bubble.%s.tile%d" % (kind, w), tile(w)) for w in TILE_WIDTHS]
    return cells, h, ascent


SCREEN_X0, SCREEN_X1 = 5, PANEL_W - 5      # the black screen inside the bezel (inclusive)


def sides(img, y0, y1):
    """Bezel on both sides and black screen between them, rows y0..y1 (inclusive)."""
    for y in range(y0, y1 + 1):
        img[y][1] = BEZEL_EDGE
        img[y][PANEL_W - 1] = BEZEL_EDGE
        for x in range(2, SCREEN_X0):
            img[y][x] = BEZEL
        for x in range(SCREEN_X1 + 1, PANEL_W - 1):
            img[y][x] = BEZEL
        for x in range(SCREEN_X0, SCREEN_X1 + 1):
            img[y][x] = SCREEN


def panel(height, top):
    """The phone from its top edge down: rounded bezel with the dynamic island, signal and battery, black screen.

    top=False: a continuation piece (sides and screen only) for a second panel further down a long screen.
    The bottom of the phone is not part of it: the home-indicator button row draws that (band_home)."""
    img = blank(PANEL_W, height)
    sides(img, 0, height - 1)
    if not top:
        return img
    # rounded top: clear the corners, then outline
    for y in range(0, 16):
        for x in range(0, PANEL_W):
            img[y][x] = T
    big = blank(PANEL_W, 40)
    rounded(big, 1, 0, PANEL_W - 1, 39, 13, BEZEL_EDGE)
    rounded(big, 2, 1, PANEL_W - 2, 39, 12, BEZEL)
    rounded(big, SCREEN_X0, 4, SCREEN_X1, 39, 9, SCREEN)
    for y in range(0, min(16, height)):
        img[y] = big[y][:]
    cx = (1 + PANEL_W - 1) // 2
    # dynamic island with a camera dot
    rounded(img, cx - 19, 6, cx + 19, 12, 3, ISLAND)
    disc(img, cx + 12, 9, 1.6, (40, 44, 70, 255))
    img[8][cx + 12] = (90, 100, 150, 255)
    # signal bars and battery on the right of the island
    white = (240, 240, 244, 255)
    sx = SCREEN_X1 - 30
    for i in range(4):
        rect(img, sx + i * 3, 11 - i * 2 - 1, sx + i * 3 + 1, 11, white)
    bx = SCREEN_X1 - 15
    rect(img, bx, 6, bx + 10, 11, white)
    rect(img, bx + 1, 7, bx + 9, 10, SCREEN)
    rect(img, bx + 2, 8, bx + 8, 9, white)
    rect(img, bx + 11, 8, bx + 11, 9, white)
    return img


def band(columns):
    """The background of one row of buttons (row + the 2 px gap under it), drawn by the label of the row's first
    button. It covers everything under the row with the black screen, except a 1 px ring where the first button's
    own border is, so that border still lights up on hover. Later buttons of the row are drawn over it."""
    img = blank(PANEL_W, 22)
    sides(img, 0, 21)
    w = BUTTON_COLUMNS[columns]
    x0, x1 = PANEL_MARGIN, PANEL_MARGIN + w - 1
    for y in range(0, 20):
        for x in range(x0, x1 + 1):
            if y in (0, 19) or x in (x0, x1):
                img[y][x] = T
    return img


def band_home():
    """The last button row: the bottom of the phone with the home indicator. The client cuts everything off at the
    bottom of the last button row, so the phone's rounded bottom has to fit inside these 20 px."""
    img = blank(PANEL_W, 20)
    big = blank(PANEL_W, 60)
    rounded(big, 1, 0, PANEL_W - 1, 59, 13, BEZEL_EDGE)
    rounded(big, 2, 0, PANEL_W - 2, 58, 12, BEZEL)
    rounded(big, SCREEN_X0, 0, SCREEN_X1, 54, 9, SCREEN)
    for y in range(20):
        img[y] = big[40 + y][:]
    cx = (1 + PANEL_W - 1) // 2
    rounded(img, cx - 24, 8, cx + 24, 9, 1, (236, 236, 240, 255))
    return img


def cover(w):
    """Paints over the face of a w px button (1 px inside its border) with the screen colour."""
    img = blank(w - 2, 18)
    rect(img, 0, 0, w - 3, 17, SCREEN[:3] + (255,))
    return img


def app_icon(colour, logo):
    """16x16 app icon for the app list on the home screen: a rounded square with a white logo."""
    img = blank(16, 16)
    rounded(img, 0, 0, 15, 15, 4, colour + (255,))
    for y, row in enumerate(logo):
        for x, c in enumerate(row):
            if c == "#":
                img[2 + y][2 + x] = WHITE
    return img


APP_LOGOS = {
    "messages": ((52, 199, 89), ["............", "..########..", ".##########.", ".##########.",
                                 ".##########.", ".##########.", "..########..", "..##........", ".##.........",
                                 "............", "............", "............"]),
    "camera": ((90, 96, 112), ["............", "....####....", ".##########.", ".###....###.",
                               ".##......##.", ".##......##.", ".###....###.", ".##########.", "............",
                               "............", "............", "............"]),
    "photos": ((250, 250, 252), None),
    "gram": ((214, 41, 118), ["............", ".##########.", ".#........#.", ".#...##.#.#.",
                              ".#..#..#..#.", ".#..#..#..#.", ".#...##...#.", ".#........#.", ".##########.",
                              "............", "............", "............"]),
    "settings": ((142, 142, 147), ["............", ".....##.....", "..#.####.#..", "...######...",
                                   ".####..####.", ".####..####.", "...######...", "..#.####.#..", ".....##.....",
                                   "............", "............", "............"]),
}


def photos_icon():
    """The Photos app: a white tile with a colourful flower, like the real one."""
    img = blank(16, 16)
    rounded(img, 0, 0, 15, 15, 4, (250, 250, 252, 255))
    petals = [(255, 59, 48), (255, 149, 0), (255, 204, 0), (52, 199, 89), (0, 199, 190), (0, 122, 255),
              (175, 82, 222), (255, 45, 85)]
    for i, col in enumerate(petals):
        a = i * math.pi / 4
        disc(img, 7.5 + math.cos(a) * 3.6, 7.5 + math.sin(a) * 3.6, 1.9, col + (255,))
    return img


def ring():
    """Pink ring around an 8x8 player face (the avatar of a Minestagram account)."""
    img = blank(12, 12)
    for y in range(12):
        for x in range(12):
            d = math.hypot(x - 5.5, y - 5.5)
            if 4.9 <= d <= 6.0:
                img[y][x] = WHITE
    return img


def photo_frame():
    """1 px outline around a 128x128 photo (tinted by the text colour)."""
    s = PHOTO_SIZE + 2
    img = blank(s, s)
    for i in range(s):
        img[0][i] = img[s - 1][i] = img[i][0] = img[i][s - 1] = WHITE
    return img


def small_glyphs():
    """8x8 monochrome marks (white; tinted by the text colour)."""
    def grid(rows):
        return [[WHITE if c == "#" else T for c in row.ljust(8, ".")] for row in rows]

    dot = grid(["........", "..###...", ".#####..", ".#####..", ".#####..", "..###...", "........", "........"])
    tick = grid(["........", "........", "......#.", ".....#..", ".#..#...", "..##....", "........", "........"])
    ticks = grid(["........", "........", "...#..#.", "..#..#..", "##.##...", ".#..#...", "........", "........"])
    back = grid(["........", "...#....", "..#.....", ".#####..", "..#.....", "...#....", "........", "........"])
    cam = grid(["........", "..##....", ".######.", ".#.##.#.", ".#.##.#.", ".######.", "........", "........"])
    clock = grid(["........", "..###...", ".#.#.#..", ".#.##.#.".replace("#.#.", "#.#.")[:8], ".#...#..", "..###...", "........", "........"])
    up = grid(["........", "...#....", "..###...", ".#####..", "...#....", "...#....", "........", "........"])
    down = grid(["........", "...#....", "...#....", ".#####..", "..###...", "...#....", "........", "........"])
    lock = grid(["........", "..###...", ".#...#..", ".#####..", ".##.##..", ".#####..", "........", "........"])
    pencil = grid(["........", ".....##.", "....###.", "...###..", "..###...", ".###....", ".##.....", "........"])
    rec = grid(["........", "..###...", ".#####..", ".#####..", ".#####..", "..###...", "........", "........"])
    # Minestagram and the tab bar
    heart = grid([".##.##..", "#..#..#.", "#.....#.", "#.....#.", ".#...#..", "..#.#...", "...#....", "........"])
    heart_full = grid([".##.##..", "#######.", "#######.", "#######.", ".#####..", "..###...", "...#....", "........"])
    comment = grid(["........", ".#####..", "#.....#.", "#.....#.", "#.....#.", ".#####..", ".#......", "#......."])
    repost = grid(["..#.....", ".######.", "..#...#.", "......#.", ".#......", ".#...#..", ".######.", ".....#.."])
    home = grid(["...#....", "..###...", ".#####..", "#######.", ".#####..", ".##.##..", ".##.##..", "........"])
    search = grid([".###....", "#...#...", "#...#...", "#...#...", ".###....", ".....#..", "......#.", "........"])
    plus = grid(["#######.", "#.....#.", "#..#..#.", "#.###.#.", "#..#..#.", "#.....#.", "#######.", "........"])
    profile = grid(["..###...", ".#...#..", "#.....#.", "#.....#.", "#.....#.", ".#...#..", "..###...", "........"])
    left = grid(["........", "....#...", "...#....", "..#.....", "...#....", "....#...", "........", "........"])
    right = grid(["........", "..#.....", "...#....", "....#...", "...#....", "..#.....", "........", "........"])
    play = grid(["........", ".#......", ".###....", ".#####..", ".###....", ".#......", "........", "........"])
    pause = grid(["........", ".##.##..", ".##.##..", ".##.##..", ".##.##..", ".##.##..", "........", "........"])
    filt = grid(["#.#.#...", "#.#.#...", "#.#.#...", "#####...", ".###....", "..#.....", "..#.....", "........"])
    image = grid(["#######.", "#.....#.", "#.##..#.", "#.....#.", "#..#..#.", "#.###.#.", "#######.", "........"])
    share = grid(["...#....", "..###...", ".#.#.#..", "...#....", "#..#..#.", "#.....#.", "#######.", "........"])
    printer = grid(["..###...", "..#.#...", "#######.", "#.....#.", "#.###.#.", "..#.#...", "..###...", "........"])
    trash = grid(["..###...", "#######.", ".#####..", ".#.#.#..", ".#.#.#..", ".#.#.#..", ".#####..", "........"])
    return [("mark.dot", dot), ("mark.tick", tick), ("mark.ticks", ticks), ("mark.back", back), ("mark.camera", cam),
            ("mark.clock", clock), ("mark.up", up), ("mark.down", down), ("mark.lock", lock), ("mark.pencil", pencil),
            ("mark.rec", rec), ("mark.heart", heart), ("mark.heart_full", heart_full), ("mark.comment", comment),
            ("mark.repost", repost), ("mark.home", home), ("mark.search", search), ("mark.plus", plus),
            ("mark.profile", profile), ("mark.left", left), ("mark.right", right), ("mark.play", play), ("mark.pause", pause), ("mark.filter", filt),
            ("mark.image", image), ("mark.share", share), ("mark.print", printer), ("mark.trash", trash)]


def build_font():
    fb = FontBuilder()
    for kind in ("top", "mid", "bot", "one"):
        cells, h, ascent = bubble_row(kind)
        fb.sheet("bubble_%s.png" % kind, cells, 128, h, h, ascent)
    for h in PANEL_HEIGHTS:
        # the phone's top hangs PANEL_TOP px above the first line; continuation pieces start at a line's top
        fb.sheet("panel_%d.png" % h, [("panel.%d" % h, panel(h, True))], PANEL_W, h, h, 7 + PANEL_TOP)
        fb.sheet("panelx_%d.png" % h, [("panelx.%d" % h, panel(h, False))], PANEL_W, h, h, 7)
    # button rows. A label is drawn 2 px inside its button with the text top 6 px below the button's top: the row
    # band starts at the button's top (ascent 7 + 6), a cover 1 px inside the border (ascent 7 + 5)
    fb.sheet("bands.png", [("band.%d" % n, band(n)) for n in sorted(BUTTON_COLUMNS)], PANEL_W, 22, 22, 7 + 6)
    fb.sheet("band_home.png", [("band.home", band_home())], PANEL_W, 20, 20, 7 + 6)
    fb.sheet("covers.png", [("cover.%d" % w, cover(w)) for w in sorted(set(BUTTON_COLUMNS.values()))],
             max(BUTTON_COLUMNS.values()) - 2, 18, 18, 7 + 5)
    # app icons: 16 px, centred on a text line (text rows 0..7 -> icon rows -4..11)
    icons = [("app.photos", photos_icon())]
    icons += [("app.%s" % name, app_icon(col, logo)) for name, (col, logo) in APP_LOGOS.items() if logo]
    fb.sheet("icons.png", icons, 16, 16, 16, 7 + 4)
    fb.sheet("marks.png", small_glyphs(), 8, 8, 8, 7)
    fb.sheet("ring.png", [("ring", ring())], 12, 12, 12, 7 + 2)
    fb.sheet("photo_frame.png", [("photo.frame", photo_frame())], PHOTO_SIZE + 2, PHOTO_SIZE + 2, PHOTO_SIZE + 2, 7 + 1)
    fb.big_digits()
    fb.spaces()
    path = os.path.join(ASSETS, "font", "ui.json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump({"providers": fb.providers}, f, indent=1, ensure_ascii=True)
        f.write("\n")
    return fb


def photo_font():
    """The phone:photo font: one white 1x1 pixel per photo row. Row r is drawn r px below the top of its text line
    (ascent 7 - r; the client only rejects an ascent above the height). A glyph advances 2 px (bitmap glyphs advance
    by their width + 1), so a row is drawn in two passes, even then odd columns. The plugin colours every pixel
    through the text colour; see ui/PhotoText.java."""
    providers = []
    for r in range(PHOTO_SIZE):
        # the client rejects an ascent above the glyph's height: the top rows get a taller cell, pixel on top
        h = max(1, 7 - r)
        name = "px.png" if h == 1 else "px_%d.png" % h
        save(os.path.join(TEX_FONT, name), [[WHITE]] + [[T]] * (h - 1))
        providers.append({"type": "bitmap", "file": "phone:font/" + name, "height": h, "ascent": 7 - r,
                          "chars": [chr(PHOTO_BASE + r)]})
    path = os.path.join(ASSETS, "font", "photo.json")
    with open(path, "w") as f:
        json.dump({"providers": providers}, f, indent=1, ensure_ascii=True)
        f.write("\n")


PHOTO_BASE = 0xE000


def write_glyph_table(fb):
    path = os.path.join(ROOT, "src", "main", "resources", "font", "glyphs.properties")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write("# Generated by tools/gen_pack.py - do not edit. Glyphs of the phone:ui font.\n")
        f.write("# glyph.<name> = <hex codepoint>,<advance in GUI px>\n")
        for name, (cp, adv) in fb.glyphs.items():
            f.write("glyph.%s=%04X,%d\n" % (name, cp, adv))
        f.write("layout.content_width=%d\n" % CONTENT_W)
        f.write("layout.panel_width=%d\n" % PANEL_W)
        f.write("layout.panel_margin=%d\n" % PANEL_MARGIN)
        f.write("layout.body_width=%d\n" % BODY_W)
        f.write("layout.panel_top=%d\n" % PANEL_TOP)
        f.write("layout.panel_heights=%s\n" % ",".join(str(h) for h in PANEL_HEIGHTS))
        f.write("layout.button_columns=%s\n" % ",".join("%d:%d" % (n, w) for n, w in sorted(BUTTON_COLUMNS.items())))
        f.write("layout.cap_width=%d\n" % CAP_W)
        f.write("layout.tail_width=%d\n" % TAIL_W)
        f.write("layout.tile_widths=%s\n" % ",".join(str(w) for w in TILE_WIDTHS))
        f.write("layout.space_powers=%d\n" % SPACE_POWERS)
        f.write("layout.big_scale=%d\n" % BIG_SCALE)
        f.write("photo.size=%d\n" % PHOTO_SIZE)
        f.write("photo.base=%04X\n" % PHOTO_BASE)
        f.write("camera.zoom_levels=%s\n" % ",".join(str(z) for z in ZOOM_LEVELS))


# ---------------------------------------------------------------------------------------------------------------------
# Post effects: one JSON per zoom level, all running shaders/post/camera.fsh
# ---------------------------------------------------------------------------------------------------------------------
def blit_pass():
    return {"vertex_shader": "minecraft:core/screenquad", "fragment_shader": "minecraft:post/blit",
            "inputs": [{"sampler_name": "In", "target": "swap"}],
            "uniforms": {"BlitConfig": [{"name": "ColorModulate", "type": "vec4", "value": [1.0, 1.0, 1.0, 1.0]}]},
            "output": "minecraft:main"}


def write_post_effects():
    out = os.path.join(ASSETS, "post_effect")
    os.makedirs(out, exist_ok=True)
    for i, zoom in enumerate(ZOOM_LEVELS):
        effect = {
            "targets": {"swap": {}},
            "passes": [
                {"vertex_shader": "minecraft:core/screenquad", "fragment_shader": "phone:post/camera",
                 "inputs": [{"sampler_name": "In", "target": "minecraft:main", "bilinear": True}],
                 "output": "swap",
                 "uniforms": {"CameraConfig": [
                     {"name": "Zoom", "type": "float", "value": float(zoom)},
                     {"name": "Level", "type": "float", "value": float(i)},
                     {"name": "Levels", "type": "float", "value": float(len(ZOOM_LEVELS))},
                     {"name": "Frame", "type": "float", "value": 1.0}]}},
                blit_pass()]}
        with open(os.path.join(out, "camera_%d.json" % zoom), "w") as f:
            json.dump(effect, f, indent=1)
            f.write("\n")


# ---------------------------------------------------------------------------------------------------------------------
# Sounds (synthesised; replace the .ogg files with real recordings if you like - same names)
# ---------------------------------------------------------------------------------------------------------------------
RATE = 44100


def synth_notify():
    out = []
    for i in range(int(RATE * 0.55)):
        t = i / RATE
        s = 0.0
        for start, freq in ((0.0, 1318.5), (0.11, 1760.0)):
            if t >= start:
                u = t - start
                env = math.exp(-u * 9.0) * min(1.0, u * 400.0)
                s += env * (math.sin(2 * math.pi * freq * u) + 0.3 * math.sin(2 * math.pi * freq * 2 * u))
        out.append(s * 0.38)
    return out


def synth_send():
    out = []
    phase = 0.0
    n = int(RATE * 0.16)
    for i in range(n):
        t = i / n
        freq = 520.0 + 900.0 * t * t
        phase += 2 * math.pi * freq / RATE
        env = math.sin(math.pi * t) ** 0.7
        out.append(math.sin(phase) * env * 0.35)
    return out


def synth_shutter():
    rnd = random.Random(7)
    out = []
    n = int(RATE * 0.22)
    low = 0.0
    for i in range(n):
        t = i / RATE
        env = math.exp(-t * 90.0) + 0.8 * (math.exp(-(t - 0.085) * 70.0) if t >= 0.085 else 0.0)
        noise = rnd.uniform(-1, 1)
        low += (noise - low) * 0.35
        out.append((0.6 * low + 0.4 * noise) * env * 0.5 + 0.15 * env * math.sin(2 * math.pi * 180 * t))
    return out


def write_sounds():
    out_dir = os.path.join(ASSETS, "sounds", "phone")
    os.makedirs(out_dir, exist_ok=True)
    ffmpeg = shutil.which("ffmpeg")
    for name, samples in (("notify", synth_notify()), ("send", synth_send()), ("shutter", synth_shutter())):
        wav = os.path.join(out_dir, name + ".wav")
        with wave.open(wav, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, s)) * 32767)) for s in samples))
        ogg = os.path.join(out_dir, name + ".ogg")
        if ffmpeg is None:
            print("ffmpeg not found: kept the existing %s.ogg (if any)" % name)
        else:
            # the encoder built into ffmpeg only does stereo; -bitexact keeps the file identical between runs
            subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-i", wav, "-c:a", "vorbis", "-strict", "-2",
                            "-ac", "2", "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact", ogg],
                           check=True)
        os.remove(wav)


def main():
    save(os.path.join(TEX_ITEM, "phone.png"), phone_texture(False))
    save(os.path.join(TEX_ITEM, "phone_unread.png"), phone_texture(True))
    save(os.path.join(TEX_ITEM, "photo_paper.png"), photo_paper_texture())
    save(os.path.join(TEX_ITEM, "photo.png"), photo_texture())
    # every font texture is generated: start from an empty folder so renamed pieces leave nothing behind
    shutil.rmtree(TEX_FONT, ignore_errors=True)
    fb = build_font()
    photo_font()
    write_glyph_table(fb)
    write_post_effects()
    write_sounds()
    print("pack generated: %d glyphs" % len(fb.glyphs))


if __name__ == "__main__":
    main()
