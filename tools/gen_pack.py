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
CONTENT_W = 216          # every line of a phone screen is padded to exactly this advance
PANEL_W = 232            # the screen panel behind the text; sticks out PANEL_MARGIN px on both sides
PANEL_MARGIN = (PANEL_W - CONTENT_W) // 2
PANEL_TOP = 4            # px the panel starts above the top of the first text line (= the dialog text padding)
PANEL_HEIGHTS = list(range(48, 241, 16)) + [255]   # one glyph per height; a glyph must fit the 256 px font atlas
HEADER_H = 13            # header bar behind the second line (contact name / screen title)
CAP_W = 3                # rounded end of a bubble
TAIL_W = 2               # the little tail on the last row of a bubble
TILE_WIDTHS = [1, 2, 4, 8, 16, 32, 64, 128]
SPACE_POWERS = 10        # spaces of +-1, 2, 4 ... 512 px
ZOOM_LEVELS = [1, 2, 3, 4, 6, 8, 12, 16]   # camera_<zoom>.json post effects that exist in the pack
APP_BUTTON_W = 56        # width of an app button on the home screen (the vanilla button under the app tile)
APP_ICON = 32            # app icon, drawn above its button
APP_ICON_GAP = 2         # px between the icon and the button
BIG_SCALE = 3            # lock-screen clock: the vanilla digits drawn this many times larger

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
        """The lock-screen clock: the vanilla digit glyphs, scaled by the provider's height (no texture of our own).

        ascii.png is a 16x16 grid of 8 px cells; row 3 holds 0-9 and the colon. Every other cell is skipped."""
        rows = []
        for r in range(16):
            row = ""
            for c in range(16):
                if r == 3 and c <= 10:
                    name = "big." + ("colon" if c == 10 else str(c))
                    width = 1 if c == 10 else 5         # pixel widths of the vanilla glyphs
                    row += self.alloc(name, int(0.5 + width * BIG_SCALE) + 1)
                else:
                    row += "\u0000"
            rows.append(row)
        self.providers.append({"type": "bitmap", "file": "minecraft:font/ascii.png", "height": 8 * BIG_SCALE,
                               "ascent": 7 * BIG_SCALE, "chars": rows})

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


def panel(height):
    """The phone screen: dark rounded panel with a status bar (signal, battery) and a home indicator."""
    img = blank(PANEL_W, height)
    border, bg, bar = (58, 64, 80, 255), (18, 21, 28, 244), (26, 30, 40, 250)
    rounded(img, 0, 0, PANEL_W - 1, height - 1, 6, border)
    rounded(img, 1, 1, PANEL_W - 2, height - 2, 5, bg)
    # status bar strip
    for y in range(2, 14):
        for x in range(2, PANEL_W - 2):
            if img[y][x] == bg:
                img[y][x] = bar
    # signal bars, left
    for i in range(4):
        rect(img, 8 + i * 3, 10 - i * 2, 9 + i * 3, 10, (210, 216, 228, 255) if i < 3 else (96, 102, 118, 255))
    # battery, right
    bx = PANEL_W - 24
    rect(img, bx, 5, bx + 13, 10, (210, 216, 228, 255))
    rect(img, bx + 1, 6, bx + 12, 9, bar)
    rect(img, bx + 2, 7, bx + 9, 8, (120, 220, 120, 255))
    rect(img, bx + 14, 7, bx + 14, 8, (210, 216, 228, 255))
    # home indicator
    if height >= 40:
        rect(img, PANEL_W // 2 - 24, height - 6, PANEL_W // 2 + 24, height - 5, (96, 102, 118, 255))
    return img


def header_bar():
    img = blank(PANEL_W, HEADER_H)
    rect(img, 1, 0, PANEL_W - 2, HEADER_H - 2, (34, 39, 52, 250))
    rect(img, 1, HEADER_H - 1, PANEL_W - 2, HEADER_H - 1, (58, 64, 80, 255))
    return img


def icon_messages():
    img = blank(16, 16)
    rounded(img, 0, 0, 15, 15, 3, (52, 199, 89, 255))
    rounded(img, 3, 3, 12, 10, 2, WHITE)
    for (x, y) in ((4, 11), (5, 11), (4, 12)):
        img[y][x] = WHITE
    for x in (5, 8, 11):
        img[7][x - 1] = (52, 199, 89, 255)
    return img


def icon_camera():
    img = blank(16, 16)
    rounded(img, 0, 0, 15, 15, 3, (88, 96, 116, 255))
    rect(img, 2, 5, 13, 12, (236, 240, 246, 255))
    rect(img, 5, 3, 9, 4, (236, 240, 246, 255))
    disc(img, 7.5, 8.5, 2.6, (40, 46, 60, 255))
    disc(img, 7.5, 8.5, 1.2, (110, 190, 255, 255))
    img[6][12] = (255, 190, 70, 255)
    return img


def icon_contacts():
    img = blank(16, 16)
    rounded(img, 0, 0, 15, 15, 3, (72, 132, 240, 255))
    disc(img, 7.5, 5.5, 2.3, WHITE)
    rounded(img, 3, 9, 12, 13, 2, WHITE)
    return img


def app_tile(draw_logo, colour):
    """An app on the home screen: a round icon with the label area of the button underneath.

    The glyph is drawn as part of the button's label, 1 px inside the vanilla button: the lower 18 rows cover the
    grey button face with a dark pill for the label (the button's 1 px border stays visible and still lights up on
    hover), the icon sits above the button, over the bottom of the screen text.
    """
    w, icon_rows = APP_BUTTON_W - 2, APP_ICON + APP_ICON_GAP
    img = blank(w, icon_rows + 19)
    x0 = (w - APP_ICON) // 2
    dark = tuple(int(c * 0.72) for c in colour) + (255,)
    disc(img, x0 + 15.5, 15.5, 15.6, dark)
    disc(img, x0 + 15.5, 15.5, 14.6, colour + (255,))
    # soft highlight on the upper left of the disc
    light = tuple(min(255, int(c * 1.18 + 14)) for c in colour) + (255,)
    for y in range(3, 14):
        for x in range(x0 + 4, x0 + 16):
            if img[y][x][:3] == colour and (x - x0 - 15.5) ** 2 + (y - 15.5) ** 2 > 10.5 ** 2 and x + y < x0 + 21:
                img[y][x] = light
    draw_logo(img, x0)
    # label area: rows icon_rows+1 .. icon_rows+18 = the inside of the 20 px button
    rect(img, 0, icon_rows + 1, w - 1, icon_rows + 18, (18, 21, 28, 255))
    rounded(img, 1, icon_rows + 2, w - 2, icon_rows + 17, 3, (38, 44, 58, 255))
    return img


def logo_messages(img, x0):
    rounded(img, x0 + 7, 8, x0 + 24, 20, 3, WHITE)
    for (x, y) in ((9, 21), (10, 21), (11, 21), (9, 22), (10, 22), (9, 23)):
        img[y][x0 + x] = WHITE
    for x in (11, 15, 19):
        rect(img, x0 + x, 13, x0 + x + 1, 14, (52, 199, 89, 255))


def logo_camera(img, x0):
    body = (240, 243, 248, 255)
    rounded(img, x0 + 6, 10, x0 + 25, 23, 2, body)
    rect(img, x0 + 12, 7, x0 + 19, 9, body)
    disc(img, x0 + 15.5, 16.5, 4.6, (52, 58, 74, 255))
    disc(img, x0 + 15.5, 16.5, 2.6, (110, 190, 255, 255))
    img[15][x0 + 14] = WHITE
    rect(img, x0 + 21, 12, x0 + 22, 13, (255, 190, 70, 255))


def logo_settings(img, x0):
    cog = (240, 243, 248, 255)
    cx, cy = x0 + 15.5, 15.5
    for y in range(32):
        for x in range(x0, x0 + 32):
            dx, dy = x - cx, y - cy
            r = math.hypot(dx, dy)
            ang = math.atan2(dy, dx)
            tooth = math.cos(ang * 8) > 0.25
            if 3.4 < r <= (10.6 if tooth else 8.2):
                img[y][x] = cog


def badge(text):
    """Unread badge on an app icon: a red disc with a 3x5 pixel number."""
    digits = {"1": ["010", "110", "010", "010", "111"], "2": ["110", "001", "010", "100", "111"],
              "3": ["110", "001", "010", "001", "110"], "4": ["101", "101", "111", "001", "001"],
              "5": ["111", "100", "110", "001", "110"], "6": ["011", "100", "110", "101", "010"],
              "7": ["111", "001", "010", "010", "010"], "8": ["010", "101", "010", "101", "010"],
              "9": ["010", "101", "011", "001", "110"], "+": ["000", "010", "111", "010", "000"]}
    img = blank(16, 11)
    w = 11 if len(text) == 1 else 15
    rounded(img, 0, 0, w - 1, 10, 5, (120, 12, 18, 255))
    rounded(img, 1, 1, w - 2, 9, 4, (232, 40, 46, 255))
    x = (w - (len(text) * 4 - 1)) // 2
    for ch in text:
        for ry, row in enumerate(digits[ch]):
            for rx, c in enumerate(row):
                if c == "1":
                    img[3 + ry][x + rx] = WHITE
        x += 4
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
    return [("mark.dot", dot), ("mark.tick", tick), ("mark.ticks", ticks), ("mark.back", back), ("mark.camera", cam),
            ("mark.clock", clock), ("mark.up", up), ("mark.down", down), ("mark.lock", lock), ("mark.pencil", pencil),
            ("mark.rec", rec)]


def build_font():
    fb = FontBuilder()
    for kind in ("top", "mid", "bot", "one"):
        cells, h, ascent = bubble_row(kind)
        fb.sheet("bubble_%s.png" % kind, cells, 128, h, h, ascent)
    for h in PANEL_HEIGHTS:
        fb.sheet("panel_%d.png" % h, [("panel.%d" % h, panel(h))], PANEL_W, h, h, 7 + PANEL_TOP)
    # header bar: behind the title line, 2 px above the text top (so it stays clear of the clock on the line above)
    fb.sheet("header.png", [("header", header_bar())], PANEL_W, HEADER_H, HEADER_H, 7 + 2)
    # app icons: 16 px, centred on a text line (text rows 0..7 -> icon rows -4..11)
    fb.sheet("icons.png", [("icon.messages", icon_messages()), ("icon.camera", icon_camera()),
                           ("icon.contacts", icon_contacts())], 16, 16, 16, 7 + 4)
    fb.sheet("marks.png", small_glyphs(), 8, 8, 8, 7)
    # home-screen apps: the tile hangs from 40 px above the label text down to the bottom of the button
    tile_h = APP_ICON + APP_ICON_GAP + 19
    tile_ascent = 7 + 6 + APP_ICON + APP_ICON_GAP
    fb.sheet("apps.png", [("app.messages", app_tile(logo_messages, (52, 199, 89))),
                          ("app.camera", app_tile(logo_camera, (96, 104, 124))),
                          ("app.settings", app_tile(logo_settings, (72, 132, 240)))],
             APP_BUTTON_W - 2, tile_h, tile_h, tile_ascent)
    # the badge sits 2 px above the icon's top. A glyph may not have an ascent larger than its height, so the
    # badge cell is as tall as its ascent, with the art in the top rows
    badge_h = tile_ascent + 2
    fb.sheet("badges.png", [("badge.%s" % t, badge(t) + blank(16, badge_h - 11))
                            for t in ("1", "2", "3", "4", "5", "6", "7", "8", "9", "9+")],
             16, badge_h, badge_h, badge_h)
    fb.big_digits()
    fb.spaces()
    path = os.path.join(ASSETS, "font", "ui.json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump({"providers": fb.providers}, f, indent=1, ensure_ascii=True)
        f.write("\n")
    return fb


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
        f.write("layout.panel_top=%d\n" % PANEL_TOP)
        f.write("layout.panel_heights=%s\n" % ",".join(str(h) for h in PANEL_HEIGHTS))
        f.write("layout.cap_width=%d\n" % CAP_W)
        f.write("layout.tail_width=%d\n" % TAIL_W)
        f.write("layout.tile_widths=%s\n" % ",".join(str(w) for w in TILE_WIDTHS))
        f.write("layout.space_powers=%d\n" % SPACE_POWERS)
        f.write("layout.app_button_width=%d\n" % APP_BUTTON_W)
        f.write("layout.app_icon_rows=%d\n" % (APP_ICON + APP_ICON_GAP))
        f.write("layout.big_scale=%d\n" % BIG_SCALE)
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
    fb = build_font()
    write_glyph_table(fb)
    write_post_effects()
    write_sounds()
    print("pack generated: %d glyphs" % len(fb.glyphs))


if __name__ == "__main__":
    main()
