"""
生成 ZONOZ 应用图标：白底 + 黑色 "ZONOZ" 文字。

产出（写进 Res 目录）：
  mipmap-{mdpi..xxxhdpi}/ic_launcher.png            传统方形（白色圆角底）
  mipmap-{mdpi..xxxhdpi}/ic_launcher_round.png      圆形
  mipmap-{mdpi..xxxhdpi}/ic_launcher_foreground.png adaptive 前景（透明底，黑字）

用法：
    python tools/make_icons.py

依赖：Pillow（pip install pillow）+ 系统里的 Arial Bold。
非 Windows 机器请把 FONT 改成自己系统里任意一个粗体字体路径。
"""

import os

from PIL import Image, ImageDraw, ImageFont

# 仓库根目录（本文件在 tools/ 下）
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "SonosLocalCast", "app", "src", "main", "res")
PREVIEW = os.path.join(ROOT, "tools", "icon-preview.png")

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\arialbd.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]
FONT = next((p for p in FONT_CANDIDATES if os.path.exists(p)), FONT_CANDIDATES[0])

MASTER = 1024
INK = (17, 17, 17, 255)
WHITE = (255, 255, 255, 255)
CLEAR = (255, 255, 255, 0)

BRAND = "ZONOZ"

# 字号固定为画布的 18.8%（不要靠"按宽度自适应"变相放大字号，
# 那会让"调小字距"变成"字变大"，总宽反而不变）。
FONT_EM = 0.188

# 字距 = 字号 × 该比例。与 App 内通知角标 NotifArt.SPACING_EM 保持一致。
SPACING_EM = 0.114

DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}


def draw_spaced(draw, center_x, center_y, text, font, fill, spacing):
    """带字距的居中绘制（PIL 没有 letter-spacing，只能逐字排）"""
    widths = [draw.textlength(ch, font=font) for ch in text]
    total = sum(widths) + spacing * (len(text) - 1)
    x = center_x - total / 2.0
    for ch, w in zip(text, widths):
        draw.text((x, center_y), ch, font=font, fill=fill, anchor="lm")
        x += w + spacing


def text_block(width_ratio: float, color=INK):
    """返回在 MASTER 画布中央画 BRAND 的函数；width_ratio 只是宽度上限。"""

    def paint(img):
        draw = ImageDraw.Draw(img)
        target_w = MASTER * width_ratio

        size = int(MASTER * FONT_EM)
        font = ImageFont.truetype(FONT, size)
        # 只有超宽（圆形图标 / adaptive 前景）时才等比缩小
        while size > 8:
            spacing = int(size * SPACING_EM)
            widths = [draw.textlength(ch, font=font) for ch in BRAND]
            if sum(widths) + spacing * (len(BRAND) - 1) <= target_w:
                break
            size -= 2
            font = ImageFont.truetype(FONT, size)

        draw_spaced(draw, MASTER / 2, MASTER / 2, BRAND, font, color, int(size * SPACING_EM))

    return paint


def legacy_master():
    img = Image.new("RGBA", (MASTER, MASTER), CLEAR)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, MASTER - 1, MASTER - 1], radius=int(MASTER * 0.225), fill=WHITE)
    text_block(0.78)(img)
    return img


def round_master():
    img = Image.new("RGBA", (MASTER, MASTER), CLEAR)
    d = ImageDraw.Draw(img)
    d.ellipse([0, 0, MASTER - 1, MASTER - 1], fill=WHITE)
    text_block(0.62)(img)
    return img


def foreground_master():
    img = Image.new("RGBA", (MASTER, MASTER), CLEAR)
    text_block(0.60)(img)
    return img


def save(img, path, size):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path, "PNG", optimize=True)


def main():
    legacy, rnd, fg = legacy_master(), round_master(), foreground_master()

    prev = Image.new("RGBA", (MASTER * 3 + 80, MASTER), (40, 40, 46, 255))
    prev.paste(legacy, (0, 0), legacy)
    prev.paste(rnd, (MASTER + 40, 0), rnd)
    prev.paste(fg, (MASTER * 2 + 80, 0), fg)
    os.makedirs(os.path.dirname(PREVIEW), exist_ok=True)
    prev.convert("RGB").save(PREVIEW)

    count = 0
    for name, scale in DENSITIES.items():
        d = os.path.join(RES, f"mipmap-{name}")
        save(legacy, os.path.join(d, "ic_launcher.png"), int(48 * scale))
        save(rnd, os.path.join(d, "ic_launcher_round.png"), int(48 * scale))
        save(fg, os.path.join(d, "ic_launcher_foreground.png"), int(108 * scale))
        count += 3

    print(f"generated {count} png files into {RES}")
    print(f"preview: {PREVIEW}")


if __name__ == "__main__":
    main()
