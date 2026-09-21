"""生成全套 launcher 图标 PNG（纯 Pillow，无外部素材依赖）。
运行：python tools/gen_icons.py
"""

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / "android" / "app" / "src" / "main" / "res"
BG = (47, 111, 237, 255)          # #2F6FED
FG = (255, 255, 255, 255)
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def draw_icon(size: int) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    r = int(size * 0.22)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=BG)

    # 云朵：三段圆弧 + 底部矩形
    s = size
    cx, cy = s * 0.50, s * 0.58
    d.ellipse([cx - s * 0.30, cy - s * 0.16, cx - s * 0.02, cy + s * 0.12], fill=FG)   # 左
    d.ellipse([cx - s * 0.02, cy - s * 0.26, cx + s * 0.26, cy + s * 0.02], fill=FG)   # 中
    d.ellipse([cx + s * 0.06, cy - s * 0.10, cx + s * 0.32, cy + s * 0.16], fill=FG)   # 右
    d.rectangle([cx - s * 0.30, cy + s * 0.02, cx + s * 0.32, cy + s * 0.16], fill=FG)

    # 上传箭头
    ax = cx
    d.polygon(
        [(ax, cy - s * 0.30), (ax - s * 0.10, cy - s * 0.16), (ax + s * 0.10, cy - s * 0.16)],
        fill=FG,
    )
    d.rectangle([ax - s * 0.035, cy - s * 0.30, ax + s * 0.035, cy - s * 0.20], fill=BG)
    return img


def main() -> None:
    for dpi, px in DENSITIES.items():
        d = ROOT / f"mipmap-{dpi}"
        d.mkdir(parents=True, exist_ok=True)
        draw_icon(px).save(d / "ic_launcher.png")
        draw_icon(px).save(d / "ic_launcher_round.png")
        print("write", d / "ic_launcher.png", px)


if __name__ == "__main__":
    main()
