"""生成 ShelfLife 的物品贴图（16x16）。

自带极简 PNG 编码器，不依赖 Pillow —— 这个脚本只在需要重新生成贴图时手工跑一次，
不在构建流程里，也不进 jar。

    python tools/generate_textures.py

两套调色板直接取自原版贴图，所以画出来的东西和原版"腐烂系"物品观感一致：

* 腐烂肉类  ← ``minecraft:item/rotten_flesh`` 的 11 色（红棕肉块 + 橄榄色霉斑）
* 腐烂剩菜  ← ``minecraft:item/poisonous_potato`` 的 12 色（黄绿斑点 + 棕）

参考贴图取自 ``build/moddev/artifacts/neoforge-*-client-extra-aka-minecraft-resources.jar``。
"""

import struct
import zlib
from pathlib import Path

WIDTH = HEIGHT = 16
OUT_DIR = Path(__file__).resolve().parent.parent / "src/main/resources/assets/shelflife/textures/item"


def write_png(path: Path, rows: list[str], palette: dict[str, tuple[int, int, int, int]]) -> None:
    """把字符画写成 8 位 RGBA 的 PNG。'.' 表示全透明。"""
    if len(rows) != HEIGHT:
        raise ValueError(f"{path.name}: 需要 {HEIGHT} 行，实际 {len(rows)} 行")
    for i, row in enumerate(rows):
        if len(row) != WIDTH:
            raise ValueError(f"{path.name}: 第 {i} 行宽度是 {len(row)}，应该是 {WIDTH}：{row!r}")

    raw = bytearray()
    for row in rows:
        raw.append(0)  # 每行的 filter type：0 = None
        for ch in row:
            if ch == ".":
                raw += b"\x00\x00\x00\x00"
            else:
                raw += bytes(palette[ch])

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", WIDTH, HEIGHT, 8, 6, 0, 0, 0)  # 8-bit, color type 6 = RGBA
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)
    print(f"wrote {path.name}")


# ---------------------------------------------------------------- 腐烂肉类
# 调色板取自原版腐肉（rotten_flesh.png）。形是重画的：一块肉，右下角一大片绿霉。
MEAT_PALETTE = {
    "B": (0x28, 0x14, 0x0A, 0xFF),  # 最深描边
    "A": (0x52, 0x2C, 0x10, 0xFF),  # 暗部
    "H": (0x62, 0x2C, 0x10, 0xFF),
    "C": (0x8B, 0x34, 0x18, 0xFF),  # 暗红
    "E": (0x83, 0x44, 0x18, 0xFF),
    "D": (0xB4, 0x44, 0x20, 0xFF),  # 主色
    "F": (0xC5, 0x65, 0x41, 0xFF),  # 亮红
    "I": (0xC5, 0x81, 0x5A, 0xFF),  # 高光
    "K": (0xC5, 0x95, 0x6A, 0xFF),  # 最亮
    "J": (0x6A, 0x5D, 0x18, 0xFF),  # 橄榄色霉斑
    "G": (0x6F, 0x4D, 0x1B, 0xFF),  # 霉斑暗部
}

MEAT_ROWS = [
    "................",
    "......BBBB......",
    "....BBCEEHBB....",
    "...BCEEFFEECB...",
    "..BCEFFIIFFECB..",
    ".BCEFFIKKIFFECB.",
    "BCEEFFIKKIFFEECB",
    "BCHEFFJJJJEECCB.",
    "BCHHEFJGGGJFCB..",
    "BCAHHEFJGGJFCB..",
    "BAAHHEEJGGJECB..",
    "BBAAHHEEJJECB...",
    ".BBAAHHEECCB....",
    "..BBBAAHCCB.....",
    "....BBBBBB......",
    "................",
]

# ---------------------------------------------------------------- 腐烂剩菜
# 调色板取自原版毒马铃薯（poisonous_potato.png）。形是重画的：一堆糊状剩饭，
# 上半是长了霉斑的绿黄，下半露出棕色的碗底/焦边。
LEFTOVERS_PALETTE = {
    "C": (0x31, 0x52, 0x37, 0xFF),  # 深绿描边
    "K": (0x6D, 0x37, 0x01, 0xFF),  # 深棕描边
    "A": (0x49, 0x67, 0x3F, 0xFF),  # 暗绿
    "F": (0x6B, 0x86, 0x3E, 0xFF),  # 中绿
    "E": (0xB0, 0xC8, 0x3A, 0xFF),  # 橄榄黄
    "J": (0xC4, 0xD9, 0x51, 0xFF),  # 黄绿
    "B": (0xD2, 0xE9, 0x62, 0xFF),  # 亮黄绿（霉斑高光）
    "D": (0x9A, 0x55, 0x00, 0xFF),  # 棕
    "G": (0xC8, 0x97, 0x3A, 0xFF),  # 浅棕
    "H": (0xAF, 0x84, 0x44, 0xFF),
    "I": (0xD9, 0xAA, 0x51, 0xFF),  # 亮棕
    "L": (0x86, 0x69, 0x3E, 0xFF),
}

LEFTOVERS_ROWS = [
    "................",
    ".....CCCCC......",
    "...CCBEEEBCC....",
    "..CBJEEBBJEEFC..",
    ".CBJEEJBBJEEAFC.",
    "CBEEJJEEBBJEEFAC",
    "CBEEJGEEJEEFFGAC",
    "CBFEEJEEGGEEFAC.",
    "CACFHEEJEEGFAC..",
    ".CCHHIEEGEEFC...",
    "..KKHHIGGECCK...",
    "..KKHHIIGCCKK...",
    "...KKHIGCCKK....",
    "....KKCCKK......",
    ".....KKKK.......",
    "................",
]


def main() -> None:
    write_png(OUT_DIR / "rotten_meat.png", MEAT_ROWS, MEAT_PALETTE)
    write_png(OUT_DIR / "rotten_leftovers.png", LEFTOVERS_ROWS, LEFTOVERS_PALETTE)


if __name__ == "__main__":
    main()
