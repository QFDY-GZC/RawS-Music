from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont


ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path(r"C:\Users\QFDY\Downloads\mmexport1781021366459.jpg")
OUT_DIR = ROOT / "output"
OUT = OUT_DIR / "sample_certificate_ganzichen_invalid.jpg"


def font(size: int, bold: bool = False, italic: bool = False) -> ImageFont.FreeTypeFont:
    candidates = []
    windir = Path(r"C:\Windows\Fonts")
    if italic:
        candidates += [windir / "timesi.ttf", windir / "simkai.ttf"]
    if bold:
        candidates += [windir / "msyhbd.ttc", windir / "simhei.ttf"]
    candidates += [windir / "msyh.ttc", windir / "simhei.ttf", windir / "simsun.ttc"]

    for path in candidates:
        if path.exists():
            return ImageFont.truetype(str(path), size)
    return ImageFont.load_default()


def cover_name_area(img: Image.Image) -> Image.Image:
    # The original name sits on a lightly textured area. Use a blurred crop from
    # nearby whitespace so the replacement looks clean without disturbing borders.
    patch_box = (88, 276, 230, 326)
    sample_box = (235, 276, 377, 326)
    patch = img.crop(sample_box).filter(ImageFilter.GaussianBlur(8))
    img.paste(patch, patch_box)
    return img


def draw_rotated_watermark(img: Image.Image, text: str) -> None:
    w, h = img.size
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    wm_font = font(56, bold=True)
    color = (158, 42, 43, 70)

    for y in range(120, h, 220):
        d.text((70, y), text, fill=color, font=wm_font)
        d.text((285, y + 95), text, fill=color, font=wm_font)

    rotated = layer.rotate(-27, expand=False, resample=Image.Resampling.BICUBIC)
    img.alpha_composite(rotated)


def main() -> None:
    if not SOURCE.exists():
        raise FileNotFoundError(SOURCE)

    OUT_DIR.mkdir(exist_ok=True)

    img = Image.open(SOURCE).convert("RGBA")
    cover_name_area(img)

    d = ImageDraw.Draw(img)
    gold = (116, 91, 35, 255)
    red = (150, 37, 37, 255)

    d.text((99, 288), "甘子辰：", fill=gold, font=font(28, bold=True))
    draw_rotated_watermark(img, "样例 / 无效")

    notice = "非正式样例，仅供娱乐，不可作为证明使用"
    notice_font = font(24, bold=True)
    bbox = d.textbbox((0, 0), notice, font=notice_font)
    pad_x, pad_y = 20, 10
    x = (img.width - (bbox[2] - bbox[0])) // 2
    y = img.height - 82
    d.rounded_rectangle(
        (x - pad_x, y - pad_y, x + (bbox[2] - bbox[0]) + pad_x, y + (bbox[3] - bbox[1]) + pad_y),
        radius=8,
        fill=(255, 255, 255, 205),
        outline=(150, 37, 37, 210),
        width=2,
    )
    d.text((x, y), notice, fill=red, font=notice_font)

    img.convert("RGB").save(OUT, quality=95)
    print(OUT)


if __name__ == "__main__":
    main()
