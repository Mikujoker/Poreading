#!/usr/bin/env python3
"""生成阅读页纸纹背景（内置资源 app/src/main/assets/bg/）。

配方规则（2026-10 用户反馈后固化；改参数前先读这五条）
    1. 幅度按**底色的相对对比度**定，不按固定 LSB 数：
       白天底 ~240，±1.2 LSB 只有 ±0.5%（看不见）；夜间底 ~18，同样的 ±1.2 就是 ±7%（脏屏）。
       每层幅度都要问「占底色的百分之几」。
    2. 噪声必须**对称、有界**，禁止正向长尾：深浅底色上任何正向离群点都会变成「白点」。
    3. 高频颗粒用**相关噪声**（相邻像素一起起伏），不用逐像素白噪——白噪撒在纸上就是芝麻点。
    4. 不用随机分布的纤维/斑点：稀疏暗线不可控，且 JPEG 会在暗线边缘补出白点（振铃）。
       细结构交给**均匀帘纹**承担，间距抖动只用来防摩尔纹，不用来制造随机感。
    5. 存 JPEG q97 + 4:4:4，并在**落盘后回读**做自检：高频残差超标直接失败。

输出 1264x2780（用户手机真实分辨率，颗粒 1:1；放大就糊成一片灰）。

用法：python3 project/scripts/gen-paper-texture.py <输出目录>
"""
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

W, H = 1264, 2780

# 自检阈值：高频残差（相对 7px 低频底）的绝对幅度上限，单位 LSB。
# 白天底色亮，纹理结构允许 3.0；夜间底色暗，同样的绝对量对比度是白天的 13 倍，压到 1.2。
CHECK_BLUR_SIGMA = 7.0


def base(hex_color):
    rgb = np.array([int(hex_color[i:i + 2], 16) for i in (1, 3, 5)], np.float32)
    return np.tile(rgb, (H, W, 1))


def add_light_gradient(img, top, bottom):
    """竖向受光：上亮下暗。"""
    return img + np.linspace(top, bottom, H, dtype=np.float32)[:, None][..., None]


def add_laid_lines(img, rng, amp=1.0, gap=(5, 7)):
    """帘纹：抄纸留下的细横纹。

    密集（5-7px）且只减不增：稀疏宽线本身就会读成「浅暗色斑点」，密集细线才是纸面质感。
    间距仍带抖动——纯周期纹在屏幕像素网格上会摩尔纹，抖动用 ±1px 就够。
    """
    gap_low, gap_high = (gap, gap + 1) if isinstance(gap, int) else gap
    layer = Image.new('L', (W, H), 128)
    d = ImageDraw.Draw(layer)
    y = 0
    while y < H:
        d.line([0, y, W, y], fill=int(128 - abs(rng.normal(0, amp))), width=1)
        y += int(rng.integers(gap_low, gap_high))
    layer = layer.filter(ImageFilter.GaussianBlur(0.55))
    arr = np.asarray(layer, np.float32) - 128.0
    # 沿横向的抄纸强弱：只做很缓的起伏，避免出现「一半有纹一半没有」的视觉断块
    xs = Image.fromarray(((rng.normal(0, 1, (1, W // 60)) + 3) * 20).astype(np.uint8))
    xs = np.asarray(xs.resize((W, 1), Image.BICUBIC), np.float32)
    xs = (xs - xs.mean()) / (xs.std() + 1e-6) * 0.15 + 1.0
    return img + (arr * xs)[..., None]


def add_mottle(img, rng, amp, cell):
    """云纹/受光不均：低频低幅，尺度 cell 像素。

    amp 是标准差，也按底色相对量给：白天 ≤3，夜间 ≤1。
    必须走 float（mode F）：早期版本把噪声塞进 uint8，负尾被裁到 0，分布变成正偏，
    结果就是一大片比底色亮的云斑——「浅暗色斑点」的一半来源。
    """
    arr = rng.normal(0, 1, (H // cell, W // cell)).astype(np.float32)
    layer = Image.fromarray(arr).resize((W, H), Image.BICUBIC)
    arr = np.asarray(layer, np.float32)
    arr = (arr - arr.mean()) / (arr.std() + 1e-6)
    return img + (arr * amp)[..., None]


def add_grain(img, rng, sigma, limit):
    """颗粒：相关噪声 + 严格对称有界。

    sigma/limit 都是 LSB。先出白噪再模糊成相关噪声（相邻像素一起起伏，看着是纸面不是噪点），
    然后硬夹到 ±limit —— 不留任何正向长尾，这才是「均匀一致」。
    """
    noise = Image.fromarray(((rng.normal(0, 1, (H, W)) * 40) + 128).clip(0, 255).astype(np.uint8))
    noise = noise.filter(ImageFilter.GaussianBlur(0.8))
    arr = np.asarray(noise, np.float32) - np.asarray(noise, np.float32).mean()
    arr = arr / (arr.std() + 1e-6) * sigma
    return img + np.clip(arr, -limit, limit)[..., None]


def save(img, path):
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(
        path, quality=97, subsampling=0, optimize=True
    )
    print('->', path)


def check_saved(path, peak_limit, speck_limit=100.0, speck_lsb=3.0):
    """回读落盘文件自检，两条判据分开：

    - **亮峰**（peak_limit）：纹理自身的结构预算。均匀帘纹必然带 ±1-2 LSB 残差，
      这不是缺陷，所以给它一个和纹理幅度相称的上限（旧配方是 11-15 LSB）。
    - **孤立亮斑**（speck_limit，每百万像素）：真正的缺陷。随机白点的特征是「周围没同类」，
      所以只数那些 7x7 邻域里同类像素不足 5% 的亮点（旧配方 >+3 LSB 有 8千-1.1万/百万）。
    """
    img = Image.open(path).convert('L')
    disp = img.resize((1080, int(img.height * 1080 / img.width)), Image.LANCZOS)
    low = disp.filter(ImageFilter.GaussianBlur(CHECK_BLUR_SIGMA))
    high = np.asarray(disp, np.float32) - np.asarray(low, np.float32)
    base_lum = float(np.median(np.asarray(disp, np.float32)))
    mask = high > speck_lsb
    density = np.asarray(
        Image.fromarray((mask * 255).astype(np.uint8)).filter(ImageFilter.BoxBlur(3)), np.float32
    ) / 255.0
    isolated = float((mask & (density < 0.05)).sum() / high.size * 1e6)
    bright, dark = float(high.max()), float(high.min())
    ok = bright <= peak_limit and isolated <= speck_limit
    print(f"   底色={base_lum:6.1f}  高频 sd={high.std():.2f}  亮峰={bright:5.2f}  暗峰={dark:6.2f}  "
          f"孤立亮斑(>+{speck_lsb:.0f}LSB)={isolated:7.1f}/百万  {'OK' if ok else '!! 超标'}")
    if not ok:
        raise SystemExit(f"自检失败：{path} 亮峰 {bright:.2f}/{peak_limit}，孤立亮斑 {isolated:.1f}/{speck_limit}")
    return bright, dark


def main(out='app/src/main/assets/bg'):
    rng = np.random.default_rng(11)
    checks = []

    # 白天：底色 ~240，颗粒 ±1.2 LSB 只有 ±0.5%；帘纹只减不增，密集细横纹。
    # 帘纹 amp 就是「纸感」旋钮：1.6-2.2 = 看得见纸的骨；调到 0.5-1.0 就只剩云纹（最干净）。
    paper = add_grain(add_laid_lines(add_mottle(
        add_light_gradient(base('#F4EDE0'), 6.0, -6.0), rng, 1.0, 30), rng, 1.8, (4, 7)),
        rng, 0.6, 1.2)
    save(paper, f'{out}/kazusa-paper-page.jpg')

    # 米纸·纤维（手抄粗纸）：靠更密的帘纹和大一点的云纹拉开区别，不再用随机纤维
    fiber = add_grain(add_laid_lines(add_mottle(
        add_light_gradient(base('#F4EEE4'), 0, 0), rng, 1.4, 30), rng, 2.2, (4, 6)),
        rng, 0.6, 1.2)
    save(fiber, f'{out}/kazusa-paper-fiber.jpg')

    # 米纸·棉絮（宣纸感）：云纹为主，帘纹最淡
    # 云纹 cell 必须比低频判据（7px）大一个量级且 amp 不超过 2，否则陡起来的云斑
    # 自己就会变成“浅暗/浅亮斑”（cell=18、amp=2.6 时亮峰能到 8 LSB）
    cotton = add_grain(add_laid_lines(add_mottle(
        add_light_gradient(base('#F5EFE6'), 0, 0), rng, 1.8, 30), rng, 1.4, (5, 8)),
        rng, 0.6, 1.2)
    save(cotton, f'{out}/kazusa-paper-cotton.jpg')

    # 米纸·宋（暖灰底，参考图取样 #E4DFDC）
    gray = add_grain(add_laid_lines(add_mottle(
        add_light_gradient(base('#E4DFDC'), 4.0, -4.0), rng, 1.2, 30), rng, 1.6, (4, 7)),
        rng, 0.6, 1.2)
    save(gray, f'{out}/kazusa-paper-gray.jpg')

    # 夜间：底色 18/20，所有幅度按 240→18 的比例缩（约 1/13），帘纹直接不给
    night_warm = add_grain(add_mottle(base('#16120E'), rng, 0.9, 26), rng, 0.28, 0.7)
    save(night_warm, f'{out}/kazusa-night-warm.jpg')
    night_blue = add_grain(add_mottle(base('#0B1420'), rng, 1.0, 26), rng, 0.30, 0.7)
    save(night_blue, f'{out}/kazusa-night-blue.jpg')

    print('自检（回读落盘文件；亮峰=纹理结构预算，孤立亮斑=真正的随机白点）：')
    for name, limit in (('kazusa-paper-page', 5.0), ('kazusa-paper-fiber', 5.0),
                        ('kazusa-paper-cotton', 5.0), ('kazusa-paper-gray', 5.0),
                        ('kazusa-night-warm', 3.0), ('kazusa-night-blue', 3.0)):
        checks.append(check_saved(f'{out}/{name}.jpg', limit))


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'app/src/main/assets/bg')
