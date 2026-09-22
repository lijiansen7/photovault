"""生成飞牛 fnOS 应用包需要的图标。

fnOS 要求：
  应用根目录：  ICON.PNG (64x64)、ICON_256.PNG (256x256)
  桌面入口：    app/ui/images/icon_{64,256}.png （由 app/ui/config 的 icon 字段引用）

图形沿用 App 的自适应图标风格：品牌蓝底 + 白色「照片」图形（相框 + 山 + 太阳）。
用 PIL 直接画，不依赖 Android 构建。

用法：
    python tools/gen_fnos_icons.py
"""
from PIL import Image, ImageDraw
import os

BG = (29, 78, 216)      # #1D4ED8，与 ic_launcher_background.xml 一致
FG = (255, 255, 255)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "fnos", "photovault")


def draw(size: int) -> Image.Image:
    img = Image.new("RGB", (size, size), BG)
    d = ImageDraw.Draw(img)
    s = size / 256.0

    # 相框
    d.rounded_rectangle(
        [48 * s, 64 * s, 208 * s, 192 * s],
        radius=14 * s, outline=FG, width=max(1, int(9 * s)),
    )
    # 太阳
    d.ellipse([86 * s, 90 * s, 114 * s, 118 * s], fill=FG)
    # 山（一大一小两个峰）
    d.polygon([(62 * s, 178 * s), (108 * s, 126 * s), (150 * s, 178 * s)], fill=FG)
    d.polygon([(130 * s, 178 * s), (166 * s, 142 * s), (196 * s, 178 * s)], fill=FG)
    return img


def main() -> None:
    os.makedirs(os.path.join(APP, "app", "ui", "images"), exist_ok=True)

    base = draw(256)
    small = base.resize((64, 64), Image.LANCZOS)

    targets = [
        (base, os.path.join(APP, "ICON_256.PNG")),
        (small, os.path.join(APP, "ICON.PNG")),
        (base, os.path.join(APP, "app", "ui", "images", "icon_256.png")),
        (small, os.path.join(APP, "app", "ui", "images", "icon_64.png")),
    ]
    for img, path in targets:
        img.save(path, "PNG")
        print(f"  {os.path.relpath(path, ROOT)}  {img.size[0]}x{img.size[1]}")


if __name__ == "__main__":
    main()
