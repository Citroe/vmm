#!/usr/bin/env python3
"""Pack the committed model artefacts into the memory images the MLP bring-up
harness loads with $readmemh. Re-run after any model/export.py re-export:

    python3 fpga/gen_mlp_mem.py

Outputs (fpga/mem/, one hex word per line, address 0 first):

  tcdm_bank{0..3}.mem  2048 x 32b each: the initial contents of the four TCDM
                       banks (bank b holds bytes b*0x2000 .. +0x1fff), laid
                       out per vmm.cim.CimMap --
                         0x0000  image      800 INT4, golden image 0
                         0x0200  hidden     (zero, written by the run)
                         0x0240  logits     (zero, written by the run)
                         0x0300  bias 1     64 x signed 32b
                         0x0400  bias 2     10 x signed 32b, rest 0
                         0x0800  weights    1664 rows x 4 words, layer 1's
                                            50 tiles then layer 2's 2
                       INT4 vectors are 8 lanes per word, lane 0 in the low
                       nibble.
  mlp_images.mem       2048 x 32b  the 16 golden images in the same packing,
                                   image i at words i*128 .. +99
  mlp_expected.mem       16 x 148b per image: {label[3:0], pred[3:0],
                                   logit9 .. logit0 (14b each)}
"""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
ART = HERE.parent / "model" / "artifacts"
OUT = HERE / "mem"

DIM = 32
N_IMAGES = 16
IMG_STRIDE_WORDS = 128
PSUM1_W, PSUM2_W = 18, 14

# TCDM layout -- keep in step with src/main/scala/vmm/cim/CimMap.scala
BANKS, BANK_WORDS = 4, 2048
IMG_BASE, BIAS1_BASE, BIAS2_BASE, WEIGHT_BASE = 0x0000, 0x0300, 0x0400, 0x0800
IMG_LANES = 800


def hex_lines(name):
    return [int(t, 16) for t in (ART / name).read_text().split()]


def write(name, words, digits):
    (OUT / name).write_text("".join(f"{w:0{digits}x}\n" for w in words))
    print(f"{name}: {len(words)} words")


def pack(nibbles):
    """INT4 lanes -> 32-bit words, 8 lanes per word, lane 0 in the low nibble."""
    assert len(nibbles) % 8 == 0
    return [sum((n & 0xF) << (4 * i) for i, n in enumerate(nibbles[w:w + 8]))
            for w in range(0, len(nibbles), 8)]


def sext32(value, width):
    """`width`-bit two's-complement pattern -> 32-bit pattern."""
    if value >> (width - 1):
        value -= 1 << width
    return value & 0xFFFFFFFF


def main():
    OUT.mkdir(exist_ok=True)

    golden = json.loads((ART / "golden.json").read_text())
    assert len(golden) == N_IMAGES
    images = []
    for g in golden:
        assert len(g["image_int4"]) == 784
        images.append(pack(g["image_int4"] + [0] * (IMG_LANES - 784)))

    weights = pack(hex_lines("layer1_w.hex") + hex_lines("layer2_w.hex"))
    assert len(weights) == 52 * DIM * DIM // 8
    b1 = hex_lines("layer1_b.hex")
    b2 = hex_lines("layer2_b.hex")
    assert len(b1) == 64 and len(b2) == 10

    tcdm = [0] * (BANKS * BANK_WORDS)

    def place(base, words):
        assert base % 4 == 0 and base // 4 + len(words) <= len(tcdm)
        tcdm[base // 4:base // 4 + len(words)] = words

    place(IMG_BASE, images[0])
    place(BIAS1_BASE, [sext32(b, PSUM1_W) for b in b1])
    place(BIAS2_BASE, [sext32(b, PSUM2_W) for b in b2])
    place(WEIGHT_BASE, weights)
    for b in range(BANKS):
        write(f"tcdm_bank{b}.mem", tcdm[b * BANK_WORDS:(b + 1) * BANK_WORDS], 8)

    rom = []
    for img in images:
        rom += img + [0] * (IMG_STRIDE_WORDS - len(img))
    write("mlp_images.mem", rom, 8)

    expected = []
    for g in golden:
        word = (g["label"] << 144) | (g["pred"] << 140)
        for i, logit in enumerate(g["logits_int"]):
            word |= (logit & ((1 << PSUM2_W) - 1)) << (PSUM2_W * i)
        expected.append(word)
    write("mlp_expected.mem", expected, 37)


if __name__ == "__main__":
    main()
