import os
from PIL import Image, ImageDraw, ImageFont

ROOT = r"M:\Docment\GitHub\X-bigtv\app\src\main\res"

def draw_x(d, size, color=(29, 155, 240), pad_ratio=0.22, w_ratio=0.155):
    pad = size * pad_ratio
    w = int(size * w_ratio)
    x0, y0, x1, y1 = pad, pad, size - pad, size - pad
    d.line([(x0, y0), (x1, y1)], fill=color, width=w)
    d.line([(x1, y0), (x0, y1)], fill=color, width=w)

def launcher(size):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 255))
    d = ImageDraw.Draw(img)
    draw_x(d, size, pad_ratio=0.20, w_ratio=0.14)
    return img

for name, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    folder = os.path.join(ROOT, "mipmap-" + name)
    os.makedirs(folder, exist_ok=True)
    launcher(size).save(os.path.join(folder, "ic_launcher.png"))

font_path = r"C:\Windows\Fonts\msyh.ttc"

def banner():
    W, H = 320, 180
    img = Image.new("RGB", (W, H), (0, 0, 0))
    d = ImageDraw.Draw(img)
    # subtle panel
    d.rectangle([0, 0, W - 1, H - 1], outline=(47, 51, 54), width=2)
    mark = 64
    icon = Image.new("RGBA", (mark, mark), (0, 0, 0, 0))
    di = ImageDraw.Draw(icon)
    draw_x(di, mark, pad_ratio=0.18, w_ratio=0.13)
    img.paste(icon, (26, (H - mark) // 2 - 14), icon)
    try:
        f1 = ImageFont.truetype(font_path, 34)
        f2 = ImageFont.truetype(font_path, 18)
    except Exception:
        f1 = ImageFont.load_default()
        f2 = ImageFont.load_default()
    d.text((104, 52), "X 大屏", font=f1, fill=(231, 233, 234))
    d.text((104, 96), "遥控器刷推 · 短视频流", font=f2, fill=(113, 118, 123))
    return img

os.makedirs(os.path.join(ROOT, "drawable-nodpi"), exist_ok=True)
banner().save(os.path.join(ROOT, "drawable-nodpi", "tv_banner.png"))
print("icons ok")
