#!/usr/bin/env python3
"""Writes a PGS (.sup) subtitle file: a white bar, shown 4 of every 5 seconds.

pgs.py <out.sup> <seconds>

ffmpeg cannot make an image subtitle out of text, and the test server needs a file with image
subtitle tracks (setup.sh: Image Subs Movie). So the stream is written by hand; ffmpeg then copies
it into the file as PGS and converts it to VobSub.
"""
import struct
import sys

WIDTH, HEIGHT = 640, 360          # the video's size
BAR_W, BAR_H = 320, 24
BAR_X, BAR_Y = (WIDTH - BAR_W) // 2, HEIGHT - BAR_H - 24


def segment(kind, pts, payload=b""):
    return b"PG" + struct.pack(">IIBH", pts, 0, kind, len(payload)) + payload


def composition(number, objects):
    head = struct.pack(">HHBHBBBB", WIDTH, HEIGHT, 0x10, number, 0x80 if objects else 0x00, 0, 0, objects)
    return head + (struct.pack(">HBBHH", 0, 0, 0, BAR_X, BAR_Y) if objects else b"")


def show(number, pts):
    window = struct.pack(">BBHHHH", 1, 0, BAR_X, BAR_Y, BAR_W, BAR_H)
    palette = struct.pack(">BB", 0, 0) + struct.pack(">BBBBB", 1, 235, 128, 128, 255)  # 1: white
    # every line: BAR_W pixels of colour 1, end of line
    line = struct.pack(">BBBB", 0, 0xC0 | (BAR_W >> 8), BAR_W & 0xFF, 1) + b"\x00\x00"
    rle = line * BAR_H
    size = struct.pack(">I", len(rle) + 4)[1:]
    bitmap = struct.pack(">HBB", 0, 0, 0xC0) + size + struct.pack(">HH", BAR_W, BAR_H) + rle
    return (segment(0x16, pts, composition(number, 1)) + segment(0x17, pts, window)
            + segment(0x14, pts, palette) + segment(0x15, pts, bitmap) + segment(0x80, pts))


def hide(number, pts):
    window = struct.pack(">BBHHHH", 1, 0, BAR_X, BAR_Y, BAR_W, BAR_H)
    return segment(0x16, pts, composition(number, 0)) + segment(0x17, pts, window) + segment(0x80, pts)


def main():
    out, seconds = sys.argv[1], int(sys.argv[2])
    data, number = b"", 0
    for start in range(0, seconds - 4, 5):
        data += show(number, start * 90000) + hide(number + 1, (start + 4) * 90000)
        number += 2
    with open(out, "wb") as f:
        f.write(data)


if __name__ == "__main__":
    main()
