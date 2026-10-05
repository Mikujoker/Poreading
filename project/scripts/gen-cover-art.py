import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 640, 860
INK = (54, 50, 46)

def smooth_ridge(n, seed, periods=2.2, octaves=3):
    """低频平滑脊线：几段正弦叠加，不掺高频，才像水墨的山不是锯齿"""
    rng = np.random.default_rng(seed)
    x = np.linspace(0, 1, n)
    y = np.zeros(n, np.float32)
    f, a = periods, 1.0
    for _ in range(octaves):
        y += a * np.sin(2 * np.pi * f * x + rng.uniform(0, 6.28))
        f *= 2.1; a *= 0.45
    y = (y - y.min()) / (y.max() - y.min() + 1e-6)
    return y

def render(soft_water=True, out='/tmp/s.png'):
    acc = np.zeros((H, W), np.float32)      # 累积墨的覆盖度

    layers = [   # (基准线, 山高, 最浓 alpha, 脊线周期, seed)
        (0.50, 0.26, 0.075, 1.9, 5),
        (0.60, 0.22, 0.115, 2.4, 9),
        (0.70, 0.18, 0.170, 3.0, 13),
        (0.80, 0.13, 0.245, 3.6, 17),
    ]
    for i, (by, hh, alpha, periods, seed) in enumerate(layers):
        prof = smooth_ridge(W, seed, periods)
        top = H * by
        ys = (top - prof * H * hh).astype(np.float32)          # 每列的山脊 y
        yy = np.arange(H, dtype=np.float32)[:, None]
        # 山脊以下：自上而下渐隐的墨（水墨的渲染感），边缘再柔化
        depth = np.clip((yy - ys[None, :]) / (H * 0.30), 0, 1)
        layer = alpha * (1.0 - depth) ** 1.4
        layer[yy < ys[None, :]] = 0
        layer_img = Image.fromarray((layer * 255).astype(np.uint8)).filter(
            ImageFilter.GaussianBlur(2.2))
        acc = np.maximum(acc, np.asarray(layer_img, np.float32) / 255.0)

        # 柔雾：这一层脚下揉开一条宽雾带
        if i < len(layers) - 1:
            y0 = int(H * (by + 0.02))
            band = np.zeros((H, W), np.float32)
            band[max(0, y0 - 60):y0 + 60] = 1.0
            band = np.asarray(Image.fromarray((band * 255).astype(np.uint8)).filter(
                ImageFilter.GaussianBlur(26)), np.float32) / 255.0
            acc *= (1.0 - band * 0.62)

    art = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(art)
    # 淡日：模糊的浅圆
    sun = Image.new('L', (W, H), 0)
    ImageDraw.Draw(sun).ellipse(
        [int(W*0.64), int(H*0.13), int(W*0.64)+int(W*0.15), int(H*0.13)+int(W*0.15)], fill=68)
    art = Image.composite(Image.new('RGBA', (W, H), INK + (255,)), art,
                          sun.filter(ImageFilter.GaussianBlur(14)))

    ink = np.dstack([np.tile(np.array(INK, np.float32), (H, W, 1)),
                     (np.clip(acc, 0, 1) * 235)])
    ink_img = Image.fromarray(ink.astype(np.uint8))
    out_img = Image.alpha_composite(ink_img, art)

    if soft_water:
        # 水面：不是色块，而是把近山脚下压淡 + 几笔淡横纹
        water = Image.new('L', (W, H), 0)
        dw = ImageDraw.Draw(water)
        wy = int(H*0.865)
        dew = np.zeros((H, W), np.float32)
        dew[wy:] = 1.0
        dew = np.asarray(Image.fromarray((dew*255).astype(np.uint8)).filter(
            ImageFilter.GaussianBlur(18)), np.float32)/255.0
        a = np.asarray(out_img, np.float32)
        a[..., 3] *= (1 - dew*0.45)
        out_img = Image.fromarray(a.astype(np.uint8)).convert('RGBA')
        for k, yy in enumerate([0.895, 0.925, 0.952]):
            y = int(H*yy)
            ln = Image.new('L', (W, H), 0)
            ImageDraw.Draw(ln).line([int(W*0.20), y, int(W*0.60), y], fill=int(60-14*k), width=2)
            ln = ln.filter(ImageFilter.GaussianBlur(3))
            ink2 = np.dstack([np.tile(np.array(INK, np.float32), (H, W, 1)), (np.asarray(ln, np.float32))])
            out_img = Image.alpha_composite(out_img, Image.fromarray(ink2.astype(np.uint8)))

    out_img.save(out)
    print('->', out)

render(True, 'app/src/main/assets/coverArt/kazusa-shanshui.png')
# 预览：米粉底 + 山水
bg = Image.new('RGB', (640, 860), (247, 238, 230))
im = Image.open('app/src/main/assets/coverArt/kazusa-shanshui.png')
bg.paste(im, (0, 0), im)
bg.save('/tmp/shanshui-preview.png')
print('预览已生成')
