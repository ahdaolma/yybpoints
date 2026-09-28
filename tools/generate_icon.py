"""Build a small-size readable launcher mark for the Android module."""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter
import math


ROOT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"
SIZE = 1024


def backdrop():
    image = Image.new("RGBA", (SIZE, SIZE))
    pixels = image.load()
    for y in range(SIZE):
        for x in range(SIZE):
            d = min(1.0, math.hypot(x - 460, y - 395) / 750)
            pixels[x, y] = (
                int(20 * (1 - d) + 8 * d),
                int(49 * (1 - d) + 23 * d),
                int(65 * (1 - d) + 35 * d),
                255,
            )
    mask = Image.new("L", (SIZE, SIZE))
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, SIZE - 1, SIZE - 1), radius=228, fill=255)
    image.putalpha(mask)
    return image


def symbol():
    transparent = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    glow = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse((236, 236, 788, 788), outline=(68, 234, 194, 180), width=56)
    transparent.alpha_composite(glow.filter(ImageFilter.GaussianBlur(43)))
    draw = ImageDraw.Draw(transparent)
    draw.ellipse((247, 247, 777, 777), outline=(76, 220, 185, 255), width=48)
    draw.arc((260, 260, 764, 764), 202, 302, fill=(158, 251, 225, 255), width=31)
    draw.polygon([(441, 359), (441, 666), (689, 512)], fill=(246, 253, 250, 255))
    draw.ellipse((703, 699, 811, 807), fill=(255, 199, 104, 255))
    draw.ellipse((730, 720, 749, 739), fill=(255, 239, 202, 210))
    return transparent


def save():
    mark = symbol()
    foreground = ROOT / "drawable-nodpi" / "ic_launcher_foreground.png"
    foreground.parent.mkdir(parents=True, exist_ok=True)
    mark.resize((432, 432), Image.Resampling.LANCZOS).save(foreground)
    icon = backdrop()
    icon.alpha_composite(mark)
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for density, pixels in sizes.items():
        path = ROOT / f"mipmap-{density}" / "ic_launcher.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        icon.resize((pixels, pixels), Image.Resampling.LANCZOS).save(path)
    preview = ROOT.parent.parent.parent.parent / "tools" / "icon-preview.png"
    icon.resize((384, 384), Image.Resampling.LANCZOS).save(preview)
    print(preview)


if __name__ == "__main__":
    save()
