import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 480, 645          # 直接按封面资源尺寸出图

def smooth_ridge(n, seed, periods, octaves=3):
    rng = np.random.default_rng(seed)
    x = np.linspace(0, 1, n); y = np.zeros(n, np.float32); f, a = periods, 1.0
    for _ in range(octaves):
        y += a * np.sin(2 * np.pi * f * x + rng.uniform(0, 6.28)); f *= 2.1; a *= 0.45
    return (y - y.min()) / (y.max() - y.min() + 1e-6)

def render(out):
    # 青绿山水：远淡近深，色相在青绿之间（和米粉底和谐）
    layers = [   # (基准线, 山高, 颜色, 最浓覆盖, 脊线周期, seed)
        (0.52, 0.24, (150, 178, 172), 0.34, 1.9, 5),
        (0.62, 0.21, (124, 156, 152), 0.45, 2.4, 9),
        (0.72, 0.17, ( 96, 132, 134), 0.56, 3.0, 13),
        (0.82, 0.13, ( 72, 110, 116), 0.66, 3.6, 17),
    ]
    acc = np.zeros((H, W), np.float32)
    rgb = np.zeros((H, W, 3), np.float32)
    for i, (by, hh, color, alpha, periods, seed) in enumerate(layers):
        prof = smooth_ridge(W, seed, periods)
        ys = (H * by - prof * H * hh).astype(np.float32)
        yy = np.arange(H, dtype=np.float32)[:, None]
        depth = np.clip((yy - ys[None, :]) / (H * 0.32), 0, 1)
        layer = alpha * (1.0 - depth) ** 1.4
        layer[yy < ys[None, :]] = 0
        layer = np.asarray(Image.fromarray((layer * 255).astype(np.uint8)).filter(
            ImageFilter.GaussianBlur(2.0)), np.float32) / 255.0
        # 近处盖远处
        m = (layer > acc)
        rgb[m] = np.array(color, np.float32)
        acc = np.maximum(acc, layer)
        if i < len(layers) - 1:                       # 柔雾
            y0 = int(H * (by + 0.02))
            band = np.zeros((H, W), np.float32); band[max(0, y0 - 46):y0 + 46] = 1.0
            band = np.asarray(Image.fromarray((band * 255).astype(np.uint8)).filter(
                ImageFilter.GaussianBlur(20)), np.float32) / 255.0
            acc *= (1.0 - band * 0.50)
    art = Image.fromarray(np.dstack([rgb, acc * 255]).astype(np.uint8))

    # 红日：盘面 + 金红霞光（多层光晕 + 放射光芒 + 顶空暖霞）
    cx, cy, r = int(W * 0.76), int(H * 0.245), int(W * 0.075)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    dist = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)

    def tint(color, alpha_map):
        return Image.merge('RGBA', [
            Image.new('L', (W, H), color[0]),
            Image.new('L', (W, H), color[1]),
            Image.new('L', (W, H), color[2]),
            Image.fromarray(np.clip(alpha_map, 0, 255).astype(np.uint8)),
        ])

    # (a) 顶空暖霞：大面积极淡的金色，只往太阳周围铺
    wash_r = W * 1.05
    wash = np.clip(1 - dist / wash_r, 0, 1) ** 1.5 * 40
    art = Image.alpha_composite(art, tint((246, 205, 142), wash))
    # (b) 光晕：近金远朱，平方衰减
    halo_r = r * 5.4
    t = np.clip(dist / halo_r, 0, 1)
    halo = (1 - t) ** 2.0 * 124
    near = np.clip(1 - dist / (halo_r * 0.45), 0, 1)
    for i, (a, b) in enumerate([(198, 248), (72, 200), (48, 108)]):   # 朱 → 金
        pass
    halo_img = Image.merge('RGBA', [
        Image.fromarray(np.clip(198 + 50 * near, 0, 255).astype(np.uint8)),
        Image.fromarray(np.clip(72 + 128 * near, 0, 255).astype(np.uint8)),
        Image.fromarray(np.clip(48 + 60 * near, 0, 255).astype(np.uint8)),
        Image.fromarray(np.clip(halo, 0, 255).astype(np.uint8)),
    ])
    art = Image.alpha_composite(art, halo_img)
    # (c) 放射光芒：细长楔形（外圈长而淡、内芯短而亮），少糊才看得出放射感
    rays = Image.new('L', (W, H), 0)
    dr = ImageDraw.Draw(rays)
    rng_rays = np.random.default_rng(7)
    for k in range(22):
        ang = 2 * np.pi * k / 22 + rng_rays.uniform(-0.05, 0.05)
        ln = r * rng_rays.uniform(3.4, 6.2)
        wdt = np.deg2rad(rng_rays.uniform(0.8, 1.5))
        dr.polygon(
            [(cx, cy),
             (cx + ln * np.cos(ang - wdt), cy + ln * np.sin(ang - wdt)),
             (cx + ln * np.cos(ang + wdt), cy + ln * np.sin(ang + wdt))],
            fill=int(rng_rays.uniform(170, 245)))
    art = Image.alpha_composite(art, tint((250, 210, 138), np.asarray(
        rays.filter(ImageFilter.GaussianBlur(4.5)), np.float32)))
    # 内芯：更短更亮的一圈，压出「日头在发光」的感觉
    core = Image.new('L', (W, H), 0)
    dc = ImageDraw.Draw(core)
    for k in range(14):
        ang = 2 * np.pi * k / 14 + 0.22
        ln = r * rng_rays.uniform(1.5, 2.2)
        wdt = np.deg2rad(2.4)
        dc.polygon(
            [(cx, cy),
             (cx + ln * np.cos(ang - wdt), cy + ln * np.sin(ang - wdt)),
             (cx + ln * np.cos(ang + wdt), cy + ln * np.sin(ang + wdt))],
            fill=150)
    art = Image.alpha_composite(art, tint((255, 232, 178), np.asarray(
        core.filter(ImageFilter.GaussianBlur(7)), np.float32)))
    # (d) 盘面：朱砂实心 + 一线柔边
    disc = Image.new('L', (W, H), 0)
    ImageDraw.Draw(disc).ellipse([cx - r, cy - r, cx + r, cy + r], fill=252)
    art = Image.alpha_composite(art, tint((178, 58, 44), np.asarray(
        disc.filter(ImageFilter.GaussianBlur(1.4)), np.float32)))

    # 水波：近处几笔淡横纹
    for k, yy in enumerate([0.90, 0.93, 0.96]):
        y = int(H * yy); ln = Image.new('L', (W, H), 0)
        ImageDraw.Draw(ln).line([int(W*0.18), y, int(W*0.62), y], fill=int(70-16*k), width=2)
        art = Image.alpha_composite(art, Image.merge('RGBA', [
            Image.new('L', (W, H), 46), Image.new('L', (W, H), 80),
            Image.new('L', (W, H), 88), ln.filter(ImageFilter.GaussianBlur(2.5))]))
    art.save(out)
    print('->', out)

render('/mnt/c/Users/mikujoker/legado-work/shanshui-qing.png')
bg = Image.new('RGB', (W, H), (247, 238, 230))
im = Image.open('/mnt/c/Users/mikujoker/legado-work/shanshui-qing.png')
bg.paste(im, (0, 0), im)
bg.save('/mnt/c/Users/mikujoker/legado-work/shanshui-qing-preview.png')
print('预览已生成')
