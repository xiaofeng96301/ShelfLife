"""生成 ShelfLife 的**方块**贴图（16x16）。

自带极简 PNG 编码器，不依赖 Pillow —— 这个脚本只在需要重新生成贴图时手工跑一次，
不在构建流程里，也不进 jar。

    python tools/generate_textures.py

**物品贴图里只有 moldy_cookie 由这个脚本生成**（而且是张占位图，等作者自己画）。
**rotten_meat / rotten_leftovers 是手绘的，不由这个脚本生成** ——
下面那两套字符画是另一版方案，已经不用了，写回资源目录会把成品覆盖掉。
手绘版的备份在 ``tools/texture_backup/``，要还原就手工拷回
``src/main/resources/assets/shelflife/textures/item/``。

冷箱的三张方块贴图由下面的字符画生成。

（字符画的调色板取自原版贴图，参考文件在
``build/moddev/artifacts/neoforge-*-client-extra-aka-minecraft-resources.jar`` 里。）
"""

import struct
import sys
import zlib
from pathlib import Path

WIDTH = HEIGHT = 16
OUT_DIR = Path(__file__).resolve().parent.parent / "src/main/resources/assets/shelflife/textures/item"


def write_png(path: Path, rows: list[str], palette: dict[str, tuple[int, int, int, int]],
              force: bool = False) -> None:
    """把字符画写成 8 位 RGBA 的 PNG。'.' 表示全透明。

    默认**不覆盖已存在的文件** —— 贴图经常是手工修过之后再跑脚本的，无脑覆盖会把人改的东西冲掉。
    确实要从字符画重生成就加 ``--force``。
    """
    if path.exists() and not force:
        print(f"skip {path.name} (exists; use --force to overwrite)")
        return
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


# ---------------------------------------------------------------- 发霉曲奇（占位图）
# 曲奇烤糊一半、边上长了霉斑。**这是占位图**，作者会自己画 —— 直接覆盖同名 PNG 即可，
# 模型那边引的就是这个路径（assets/shelflife/models/item/moldy_cookie.json）。
MOLDY_COOKIE_PALETTE = {
    "K": (0x3A, 0x24, 0x12, 0xFF),  # 描边
    "B": (0x8B, 0x5A, 0x2B, 0xFF),  # 饼干主色
    "L": (0xA9, 0x74, 0x3F, 0xFF),  # 亮部
    "X": (0x2E, 0x1B, 0x0D, 0xFF),  # 巧克力豆（烤糊的）
    "G": (0x46, 0x6B, 0x24, 0xFF),  # 霉斑暗部
    "g": (0x74, 0xA8, 0x3C, 0xFF),  # 霉斑
}

MOLDY_COOKIE_ROWS = [
    "................",
    "......KKKK......",
    "....KKBBBBKK....",
    "...KBBBLLLLBK...",
    "..KBBLLBBBBLLK..",
    ".KBBLBBXBBBLBBK.",
    ".KBXBBBBBBBGBBK.",
    "KBLLBBBXBBBGGBBK",
    "KBXBBBBBBBBGGBBK",
    "KBBBLBBXBBBBBGBK",
    ".KBBBBBBBBXBBK..",
    ".KBBXBBLBBBBK...",
    "..KBBBBBBXBBK...",
    "...KKBBBBKK.....",
    ".....KKKK.......",
    "................",
]

# ---------------------------------------------------------------- 冷箱（方块贴图）
# 木框 + 木板 + 一道冰蓝腰线。方块贴图必须全部不透明，所以这里没有 '.'。
BLOCK_OUT_DIR = Path(__file__).resolve().parent.parent / "src/main/resources/assets/shelflife/textures/block"

COLD_BOX_PALETTE = {
    "D": (0x3A, 0x2A, 0x1C, 0xFF),  # 深色木框
    "W": (0x6B, 0x4C, 0x30, 0xFF),  # 木色
    "L": (0x87, 0x63, 0x40, 0xFF),  # 亮木色
    "I": (0x5F, 0xA8, 0xC8, 0xFF),  # 冰蓝
    "i": (0x9A, 0xD4, 0xE8, 0xFF),  # 浅冰蓝
    "M": (0x4A, 0x4A, 0x52, 0xFF),  # 金属暗
    "m": (0x7C, 0x7C, 0x88, 0xFF),  # 金属亮
}

# 侧面：x0/x8/x15 是竖向木框（两块板），r8~r10 是横向冰蓝腰线。
COLD_BOX_SIDE_ROWS = (
    ["DDDDDDDDDDDDDDDD"]
    + ["D" + "W" * 7 + "D" + "W" * 6 + "D"]
    + ["D" + "WLLLLLW" + "D" + "WLLLLW" + "D"] * 3
    + ["D" + "W" * 7 + "D" + "W" * 6 + "D"]
    + ["DDDDDDDDDDDDDDDD"]
    + ["D" + "W" * 7 + "D" + "W" * 6 + "D"]
    + ["D" + "i" * 7 + "D" + "i" * 6 + "D"]
    + ["D" + "I" * 7 + "D" + "I" * 6 + "D"]
    + ["D" + "i" * 7 + "D" + "i" * 6 + "D"]
    + ["D" + "W" * 7 + "D" + "W" * 6 + "D"]
    + ["D" + "WLLLLLW" + "D" + "WLLLLW" + "D"] * 2
    + ["D" + "W" * 7 + "D" + "W" * 6 + "D"]
    + ["DDDDDDDDDDDDDDDD"]
)

# 顶面：金属搭扣居中，靠近下缘一圈霜环。
COLD_BOX_TOP_ROWS = (
    ["DDDDDDDDDDDDDDDD"]
    + ["D" + "W" * 14 + "D"] * 4
    + ["D" + "W" * 5 + "mmmm" + "W" * 5 + "D"]
    + ["D" + "W" * 5 + "MMMM" + "W" * 5 + "D"] * 3
    + ["D" + "W" * 5 + "mmmm" + "W" * 5 + "D"]
    + ["D" + "W" * 14 + "D"]
    + ["D" + "WW" + "I" * 10 + "WW" + "D"] * 2
    + ["D" + "W" * 14 + "D"] * 2
    + ["DDDDDDDDDDDDDDDD"]
)

# 底面：纯木板，交替明暗。
COLD_BOX_BOTTOM_ROWS = (
    ["DDDDDDDDDDDDDDDD"]
    + ["D" + ("W" * 14 if i % 2 == 0 else "L" * 14) + "D" for i in range(14)]
    + ["DDDDDDDDDDDDDDDD"]
)


def main() -> None:
    # 故意不写 rotten_meat.png / rotten_leftovers.png ——
    # 资源目录里那两张是手绘的，这个脚本生成的是另一版（见文件头的说明）。
    force = "--force" in sys.argv
    write_png(OUT_DIR / "moldy_cookie.png", MOLDY_COOKIE_ROWS, MOLDY_COOKIE_PALETTE, force)
    write_png(BLOCK_OUT_DIR / "cold_box_side.png", COLD_BOX_SIDE_ROWS, COLD_BOX_PALETTE, force)
    write_png(BLOCK_OUT_DIR / "cold_box_top.png", COLD_BOX_TOP_ROWS, COLD_BOX_PALETTE, force)
    write_png(BLOCK_OUT_DIR / "cold_box_bottom.png", COLD_BOX_BOTTOM_ROWS, COLD_BOX_PALETTE, force)


if __name__ == "__main__":
    main()
