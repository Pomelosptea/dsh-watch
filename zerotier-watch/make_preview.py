from PIL import Image, ImageDraw, ImageFont
import math, os

W = H = 466
img = Image.new("RGB", (W, H), (5, 6, 10))
px = img.load()

# radial navy glow
for y in range(H):
    for x in range(W):
        dx, dy = x - W / 2, y - H / 2
        d = math.sqrt(dx * dx + dy * dy)
        t = max(0.0, 1.0 - d / 300.0)
        r = int(5 + 12 * t)
        g = int(6 + 22 * t)
        b = int(10 + 50 * t)
        px[x, y] = (r, g, b)

d = ImageDraw.Draw(img, "RGBA")
cx, cy = W / 2, H / 2

def f(name, size):
    return ImageFont.truetype(os.path.join(r"C:\Windows\Fonts", name), size)

# rings
for radius, color, width in [(86, (255, 255, 255, 45), 2), (78, (99, 230, 166, 170), 3)]:
    d.ellipse((cx - radius, 218 - radius, cx + radius, 218 + radius), outline=color, width=width)

def center(text, y, font, fill):
    bbox = d.textbbox((0, 0), text, font=font)
    d.text((cx - (bbox[2] - bbox[0]) / 2, y), text, font=font, fill=fill)

center("ZeroTier", 42, f("Dengb.ttf", 31), (255, 255, 255, 255))
center("OPPO WATCH X2  ·  VIRTUAL LAN", 85, f("Deng.ttf", 15), (139, 147, 170, 255))
center("已连接", 190, f("Dengb.ttf", 26), (99, 230, 166, 255))
center("10.42.0.7", 226, f("Deng.ttf", 17), (99, 230, 166, 255))
center("NETWORK", 306, f("Deng.ttf", 14), (91, 99, 119, 255))
center("8056c2e21c000001", 330, f("Deng.ttf", 20), (139, 147, 170, 255))

# primary button
d.rounded_rectangle((83, 360, 383, 414), radius=27, fill=(32, 37, 52, 255))
center("断开", 374, f("Dengb.ttf", 20), (255, 255, 255, 255))
center("更换网络 / 重新输入 ID", 432, f("Deng.ttf", 14), (91, 99, 119, 255))

img.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "dist", "preview-zerotier.png"))
print("saved")
