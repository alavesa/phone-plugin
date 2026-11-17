"""Tiny dependency-free PNG reader/writer (RGBA, 8 bit) shared by the generator scripts. No PIL needed."""
import struct
import zlib


def write_png(path, width, height, pixels):
    """pixels: list of rows, each a list of (r, g, b, a) tuples."""
    raw = bytearray()
    for row in pixels:
        raw.append(0)
        for r, g, b, a in row:
            raw += bytes((r, g, b, a))

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(bytes(raw), 9)))
        f.write(chunk(b"IEND", b""))


def read_png(data):
    """Returns (width, height, alpha) where alpha[y][x] is 0..255. Handles the colour types vanilla font sheets use."""
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    pos = 8
    idat = bytearray()
    trns = None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        tag = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if tag == b"IHDR":
            width, height, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
            assert interlace == 0, "interlaced PNG not supported"
        elif tag == b"IDAT":
            idat += body
        elif tag == b"tRNS":
            trns = body
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    bits = channels * depth
    bpp = max(1, bits // 8)
    stride = (width * bits + 7) // 8
    raw = zlib.decompress(bytes(idat))
    rows = []
    prev = bytearray(stride)
    p = 0
    for _ in range(height):
        ftype = raw[p]
        line = bytearray(raw[p + 1:p + 1 + stride])
        p += 1 + stride
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if ftype == 1:
                line[i] = (line[i] + a) & 255
            elif ftype == 2:
                line[i] = (line[i] + b) & 255
            elif ftype == 3:
                line[i] = (line[i] + ((a + b) >> 1)) & 255
            elif ftype == 4:
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pred) & 255
        rows.append(line)
        prev = line

    def sample(line, x):
        if depth == 8:
            return line[x * channels:(x + 1) * channels]
        if depth == 16:
            return bytes(line[x * channels * 2 + k * 2] for k in range(channels))
        # packed 1/2/4 bit (grey or palette)
        bit = x * depth
        return bytes(((line[bit >> 3] >> (8 - depth - (bit & 7))) & ((1 << depth) - 1),))

    alpha = []
    for line in rows:
        out = []
        for x in range(width):
            s = sample(line, x)
            if ctype == 6:
                out.append(s[3])
            elif ctype == 4:
                out.append(s[1])
            elif ctype == 3:
                out.append(trns[s[0]] if trns is not None and s[0] < len(trns) else 255)
            elif ctype == 0:
                out.append(0 if trns is not None and len(trns) >= 2 and s[0] == trns[1] else 255)
            else:
                out.append(255)
        alpha.append(out)
    return width, height, alpha
