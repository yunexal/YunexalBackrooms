#!/usr/bin/env python3
"""
Generates custom 32x32 Level 0 Backrooms textures (wallpaper, baseboard, damp spots,
moist carpet, drop ceiling tiles, working/flickering/dead fluorescent lights, HVAC vent,
limbo void block) + synthesized CC0 ambient OGG audio, and packages dist/YunexalBackrooms-RP.zip.
"""

import hashlib
import json
import os
import shutil
import subprocess
import wave
import zipfile

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.abspath(__file__))
PACK_DIR = os.path.join(ROOT, "pack")
TEX_DIR = os.path.join(PACK_DIR, "assets", "minecraft", "textures", "block")
SND_DIR = os.path.join(PACK_DIR, "assets", "minecraft", "sounds", "backrooms")
DIST_ZIP = os.path.join(ROOT, "dist", "YunexalBackrooms-RP.zip")


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)


def clamp_rgb(arr):
    return np.clip(arr, 0, 255).astype(np.uint8)


def save_png(arr, name):
    os.makedirs(TEX_DIR, exist_ok=True)
    img = Image.fromarray(clamp_rgb(arr), mode="RGBA" if arr.shape[2] == 4 else "RGB")
    img.save(os.path.join(TEX_DIR, name))


def make_wallpaper_base(seed=42, dark_shift=0):
    rng = np.random.default_rng(seed)
    # Mono-yellow sickly Backrooms wallpaper (#D8C572)
    base = np.zeros((32, 32, 3), dtype=np.float32)
    base[:, :] = [216 - dark_shift, 197 - dark_shift, 114 - int(dark_shift * 0.7)]

    # Fine paper fiber grain
    noise = rng.normal(0, 4.2, (32, 32, 1))
    base += noise

    # Iconic vertical pinstripe + subtle repeating Backrooms chevron/arrow wallpaper motif
    for x in range(32):
        if x % 8 == 0:
            base[:, x] -= [10, 10, 8]
        elif x % 8 == 4:
            base[:, x] -= [5, 5, 4]

    for y in range(32):
        for x in range(32):
            mx, my = x % 8, y % 8
            # Faint geometric arrow/diamond wallpaper print
            if (mx in (2, 6) and my in (1, 2, 3)) or (mx in (1, 3, 5, 7) and my == 2):
                base[y, x] -= [11, 11, 9]
            if mx == 4 and my in (5, 6):
                base[y, x] -= [8, 8, 6]

    # Subtle ceiling-shadow top border
    base[0, :] -= [14, 14, 12]
    base[1, :] -= [6, 6, 5]
    return base


def generate_textures():
    # 1. yellow_terracotta.png -> Classic Level 0 Wallpaper (Mid & Upper)
    wp = make_wallpaper_base(seed=101, dark_shift=0)
    save_png(wp, "yellow_terracotta.png")

    # 2. orange_terracotta.png -> Baseboard & Damp Lower Wallpaper Row
    bb = make_wallpaper_base(seed=202, dark_shift=4)
    rng = np.random.default_rng(203)
    # Creeping damp capillary moisture near bottom above baseboard
    for y in range(20, 27):
        factor = (y - 19) / 8.0
        stain = rng.uniform(0.6, 1.3, 32)
        for x in range(32):
            bb[y, x] -= np.array([26, 26, 20]) * factor * stain[x] * 0.55
    # Dark brown wooden/vinyl skirting molding (rows 27..31)
    bb[27, :] = [52, 39, 25]
    bb[28, :] = [88, 67, 43]
    bb[29:31, :] = [74, 56, 36]
    bb[31, :] = [48, 36, 23]
    for y in range(27, 32):
        bb[y] += rng.normal(0, 2.5, (32, 1))
    save_png(bb, "orange_terracotta.png")

    # 3. brown_terracotta.png -> Stained / Moldy Pillar & Water-Damaged Wallpaper
    mold = make_wallpaper_base(seed=303, dark_shift=22)
    rng = np.random.default_rng(304)
    # Vertical damp streaks and subtle mold patches
    for x in (5, 6, 17, 18, 26):
        streak_len = rng.integers(14, 30)
        for y in range(streak_len):
            mold[y, x] -= [22, 24, 18]
    for _ in range(18):
        cx, cy = rng.integers(2, 30, size=2)
        mold[cy - 1 : cy + 2, cx - 1 : cx + 2] -= [18, 17, 12]
    save_png(mold, "brown_terracotta.png")

    # 4. brown_wool.png -> Soggy Commercial Office Carpet (Floor)
    rng = np.random.default_rng(404)
    carpet = np.zeros((32, 32, 3), dtype=np.float32)
    carpet[:, :] = [148, 129, 76]  # Dingy moist tan/khaki
    # Tight loop-pile weave + random damp blotches
    grain = rng.normal(0, 7.5, (32, 32, 1))
    carpet += grain
    for y in range(32):
        for x in range(32):
            if (x + y) % 2 == 0:
                carpet[y, x] += [3, 3, 2]
            else:
                carpet[y, x] -= [3, 3, 2]
    # Subtle commercial carpet tile grid seam
    carpet[0, :] -= [14, 13, 10]
    carpet[:, 0] -= [14, 13, 10]
    # Damp wet spots
    for cx, cy in ((10, 14), (22, 24)):
        for dy in range(-4, 5):
            for dx in range(-4, 5):
                dist = (dx * dx + dy * dy) ** 0.5
                if dist < 4.5:
                    carpet[(cy + dy) % 32, (cx + dx) % 32] -= (4.5 - dist) * 3.8
    save_png(carpet, "brown_wool.png")

    # 5. calcite.png -> Acoustic Drop Ceiling Tiles (60x60cm grid)
    rng = np.random.default_rng(505)
    ceil = np.zeros((32, 32, 3), dtype=np.float32)
    ceil[:, :] = [201, 196, 178]
    ceil += rng.normal(0, 3.0, (32, 32, 1))
    # Acoustic fiber pinholes
    holes = rng.random((32, 32)) < 0.14
    ceil[holes] -= [26, 26, 24]
    # Metal T-bar grid frame along edges and center cross (16x16cm sub-tiles look crisp)
    for idx in (0, 15, 16, 31):
        ceil[idx, :] = [156, 153, 142]
        ceil[:, idx] = [156, 153, 142]
    # Faint yellow moisture stain in corner of one tile
    for y in range(3, 11):
        for x in range(19, 27):
            d = ((x - 23) ** 2 + (y - 7) ** 2) ** 0.5
            if d < 4.2:
                ceil[y, x] -= np.array([12, 15, 25]) * (1 - d / 4.5)
    save_png(ceil, "calcite.png")

    # 6. Working Fluorescent Light Panel (ochre_froglight_top & ochre_froglight_side)
    def make_light_panel(brightness=1.0, yellow_tint=0.0):
        rng_l = np.random.default_rng(606)
        panel = np.zeros((32, 32, 3), dtype=np.float32)
        # Drop-ceiling bezel frame
        panel[:, :] = [152, 150, 138]
        # Glowing acrylic diffuser interior
        lit_col = np.array([252 - yellow_tint * 18, 250 - yellow_tint * 20, 224 - yellow_tint * 55]) * brightness
        panel[3:29, 3:29] = lit_col + rng_l.normal(0, 2.0, (26, 26, 1))
        # Dual horizontal glowing fluorescent tube ridges inside diffuser
        for ty in (8, 9, 10, 21, 22, 23):
            panel[ty, 4:28] = clamp_rgb(lit_col + np.array([8, 8, 18]) * brightness)
        # Thin shadow border inside aluminum frame
        panel[3, 3:29] *= 0.85
        panel[28, 3:29] *= 0.85
        panel[3:29, 3] *= 0.85
        panel[3:29, 28] *= 0.85
        return panel

    working = make_light_panel(brightness=1.0, yellow_tint=0.0)
    save_png(working, "ochre_froglight_top.png")
    save_png(working, "ochre_froglight_side.png")

    # 7. Flickering Fluorescent Panel (sea_lantern.png vertical strip + .mcmeta)
    # 12 frames: mostly bright, sudden dips, dim hum, brief flash
    levels = [
        (1.00, 0.0), (0.98, 0.0), (1.00, 0.0), (0.45, 0.8),
        (0.95, 0.1), (0.30, 1.0), (0.55, 0.6), (1.00, 0.0),
        (1.00, 0.0), (0.97, 0.0), (0.65, 0.4), (0.99, 0.0),
    ]
    strip = np.vstack([make_light_panel(b, yt) for b, yt in levels])
    save_png(strip, "sea_lantern.png")
    write_json(os.path.join(TEX_DIR, "sea_lantern.png.mcmeta"), {
        "animation": {
            "frametime": 2,
            "interpolate": False
        }
    })

    # 8. Dead / Unlit Fluorescent Panel (polished_andesite.png)
    dead = make_light_panel(brightness=0.28, yellow_tint=0.9)
    save_png(dead, "polished_andesite.png")

    # 9. HVAC Vent / Utility Grate (iron_block.png)
    rng = np.random.default_rng(707)
    vent = np.zeros((32, 32, 3), dtype=np.float32)
    vent[:, :] = [135, 138, 134]
    vent += rng.normal(0, 3.0, (32, 32, 1))
    # Outer metallic rim + rivets
    vent[0:2, :] = [92, 95, 92]
    vent[30:32, :] = [92, 95, 92]
    vent[:, 0:2] = [92, 95, 92]
    vent[:, 30:32] = [92, 95, 92]
    for ry in (3, 28):
        for rx in (3, 28):
            vent[ry, rx] = [65, 68, 66]
    # Horizontal dark louvers
    for y in range(5, 27, 3):
        vent[y, 4:28] = [44, 46, 46]
        vent[y + 1, 4:28] = [108, 112, 108]
    save_png(vent, "iron_block.png")

    # 10. Limbo Pitch-Black Enclosure (black_concrete.png)
    rng = np.random.default_rng(808)
    limbo = np.zeros((32, 32, 3), dtype=np.float32) + 4.0
    limbo += rng.uniform(0, 2.5, (32, 32, 1))
    save_png(limbo, "black_concrete.png")

    # 11. Stripped Drywall / Plaster Under Torn Wallpaper (light_gray_terracotta.png)
    rng = np.random.default_rng(909)
    drywall = np.zeros((32, 32, 3), dtype=np.float32)
    drywall[:, :] = [166, 162, 150]
    drywall += rng.normal(0, 5.5, (32, 32, 1))
    # Joint seam & yellowed dried adhesive patches + ragged torn paper scraps at edges
    drywall[:, 15:17] -= [18, 18, 16]
    for _ in range(14):
        px, py = rng.integers(2, 28, size=2)
        drywall[py : py + 3, px : px + 3] = [196, 184, 126]
    drywall[28:32, :] = [74, 58, 40]
    save_png(drywall, "light_gray_terracotta.png")

    # 12. Manila Room Warm Beige Walls (white_terracotta.png)
    rng = np.random.default_rng(1010)
    manila = np.zeros((32, 32, 3), dtype=np.float32)
    manila[:, :] = [224, 210, 178]
    manila += rng.normal(0, 3.0, (32, 32, 1))
    manila[0, :] -= [10, 10, 8]
    manila[28:32, :] = [96, 74, 52]
    save_png(manila, "white_terracotta.png")

    # 13. Red Room Crimson Wallpaper (red_terracotta.png)
    red_wp = make_wallpaper_base(seed=1111, dark_shift=12)
    red_wp[:, :, 0] = np.clip(red_wp[:, :, 0] * 0.85, 0, 255)
    red_wp[:, :, 1] = np.clip(red_wp[:, :, 1] * 0.24, 0, 255)
    red_wp[:, :, 2] = np.clip(red_wp[:, :, 2] * 0.26, 0, 255)
    red_wp[27:32, :] = [34, 12, 14]
    save_png(red_wp, "red_terracotta.png")

    # 14. Red Room Crimson Carpet (red_wool.png)
    red_c = np.zeros((32, 32, 3), dtype=np.float32)
    red_c[:, :] = [104, 22, 24]
    red_c += np.random.default_rng(1212).normal(0, 6.0, (32, 32, 1))
    red_c[0, :] -= [12, 5, 5]
    red_c[:, 0] -= [12, 5, 5]
    save_png(red_c, "red_wool.png")

    # 15. Torn / Soft Anomalous Carpet & Concrete Subfloor (gray_wool.png)
    rng = np.random.default_rng(1313)
    soft_c = np.zeros((32, 32, 3), dtype=np.float32)
    soft_c[:, :] = [98, 92, 78]
    soft_c += rng.normal(0, 8.5, (32, 32, 1))
    # Darker sunken patch in center indicating unstable soft carpet
    for y in range(32):
        for x in range(32):
            d = ((x - 16) ** 2 + (y - 16) ** 2) ** 0.5
            if d < 12.0:
                soft_c[y, x] -= (12.0 - d) * 2.4
    save_png(soft_c, "gray_wool.png")

    # 16. Red Room Dim Emergency Fixture (shroomlight.png)
    red_lamp = make_light_panel(brightness=0.72, yellow_tint=0.0)
    red_lamp[3:29, 3:29, 0] = np.clip(red_lamp[3:29, 3:29, 0] * 0.96, 0, 255)
    red_lamp[3:29, 3:29, 1] *= 0.20
    red_lamp[3:29, 3:29, 2] *= 0.22
    save_png(red_lamp, "shroomlight.png")


def _next_pow2(n):
    p = 1
    while p < n:
        p <<= 1
    return p


def ensure_stereo(sig):
    arr = np.asarray(sig, dtype=np.float64)
    if arr.ndim == 1:
        return np.column_stack((arr, arr))
    return arr.copy()


def fft_band(sig, sr, low_hz=None, high_hz=None, order=2.0):
    arr = np.asarray(sig, dtype=np.float64)
    n = arr.shape[0]
    freqs = np.fft.rfftfreq(n, 1.0 / sr)
    h = np.ones_like(freqs)
    if low_hz is not None and low_hz > 0.0:
        r = freqs / low_hz
        h *= np.sqrt((r ** (2.0 * order)) / (1.0 + r ** (2.0 * order)))
        h[0] = 0.0
    if high_hz is not None and high_hz > 0.0:
        r = freqs / high_hz
        h *= np.sqrt(1.0 / (1.0 + r ** (2.0 * order)))
    if arr.ndim == 1:
        return np.fft.irfft(np.fft.rfft(arr) * h, n=n)
    out = np.empty_like(arr)
    for ch in range(arr.shape[1]):
        out[:, ch] = np.fft.irfft(np.fft.rfft(arr[:, ch]) * h, n=n)
    return out


def corridor_reverb(sig, sr, rt60=0.75, wet=0.26, bright_hz=2300.0, seed=77):
    dry = ensure_stereo(sig)
    n = dry.shape[0]
    ir_len = max(int(sr * min(rt60 * 1.3, 2.2)), 512)
    rng = np.random.default_rng(seed)

    ir = np.zeros((ir_len, 2), dtype=np.float64)
    # Carpeted gypsum-wall hallway early reflections (asymmetric L/R spatial width)
    taps = [
        (0.0113, 0.36, 0.21),
        (0.0179, 0.19, 0.34),
        (0.0274, 0.25, 0.14),
        (0.0391, 0.12, 0.20),
        (0.0543, 0.15, 0.09),
        (0.0725, 0.07, 0.11),
    ]
    for delay_s, gl, gr in taps:
        idx = int(delay_s * sr)
        if idx < ir_len:
            ir[idx, 0] += gl
            ir[idx, 1] += gr

    t_ir = np.arange(ir_len, dtype=np.float64) / sr
    start_idx = int(0.028 * sr)
    tail_env = np.zeros(ir_len, dtype=np.float64)
    tail_env[start_idx:] = np.exp(-6.907755 * (t_ir[start_idx:] - 0.028) / max(rt60, 0.1))
    diffuse = rng.normal(0.0, 1.0, (ir_len, 2)) * tail_env[:, None]
    diffuse = fft_band(diffuse, sr, low_hz=95.0, high_hz=bright_hz, order=1.5)
    diffuse /= (np.sqrt(np.mean(diffuse ** 2)) + 1e-9)
    ir += diffuse * 0.038

    n_fft = _next_pow2(n + ir_len - 1)
    rev = np.empty_like(dry)
    for ch in range(2):
        conv = np.fft.irfft(np.fft.rfft(dry[:, ch], n=n_fft) * np.fft.rfft(ir[:, ch], n=n_fft), n=n_fft)
        rev[:, ch] = conv[:n]

    return dry * (1.0 - wet * 0.32) + rev * wet


def fade_edges(sig, sr, fade_in_s=0.01, fade_out_s=0.02, equal_power=False):
    arr = ensure_stereo(sig)
    n = arr.shape[0]
    fi = min(int(round(fade_in_s * sr)), n // 2)
    fo = min(int(round(fade_out_s * sr)), n // 2)
    if fi > 0:
        u = np.linspace(0.0, 1.0, fi, endpoint=True)
        w = np.sin(0.5 * np.pi * u) if equal_power else 0.5 * (1.0 - np.cos(np.pi * u))
        arr[:fi] *= w[:, None]
    if fo > 0:
        u = np.linspace(0.0, 1.0, fo, endpoint=True)
        w = np.cos(0.5 * np.pi * u) if equal_power else 0.5 * (1.0 + np.cos(np.pi * u))
        arr[n - fo:] *= w[:, None]
    return arr


def synth_ogg(samples_lr, out_ogg, sr=48000, drive=1.0, target_peak=None):
    os.makedirs(os.path.dirname(out_ogg), exist_ok=True)
    tmp_wav = out_ogg + ".tmp.wav"
    arr = ensure_stereo(samples_lr)
    if drive != 1.0:
        arr = np.tanh(arr * drive) / np.tanh(drive)
    if target_peak is not None:
        mx = np.max(np.abs(arr))
        if mx > 1e-6:
            arr *= target_peak / mx
    pcm = np.clip(arr * 32767.0, -32767, 32767).astype(np.int16)
    with wave.open(tmp_wav, "wb") as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(sr)
        wf.writeframes(pcm.tobytes())
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", tmp_wav, "-c:a", "libvorbis", "-q:a", "7", out_ogg],
        check=True,
    )
    os.remove(tmp_wav)


def generate_audio():
    sr = 48000

    # 1. fluorescent_hum.ogg (16.0s 48kHz seamless constant-power 1s crossfade @ 15.0s, warm & gentle)
    # 60Hz/120Hz/180Hz completes exact integer cycles at 15.0s -> zero phase cancellation when looped
    dur = 16.0
    n = int(sr * dur)
    t = np.linspace(0.0, dur, n, endpoint=False)
    rng = np.random.default_rng(901)

    transformer = (
        0.18 * np.sin(2.0 * np.pi * 60.0 * t)
        + 0.11 * np.sin(2.0 * np.pi * 120.0 * t)
        + 0.032 * np.sin(2.0 * np.pi * 180.0 * t)
        + 0.012 * np.sin(2.0 * np.pi * 240.0 * t)
    )
    lfo = 0.94 + 0.04 * np.sin(2.0 * np.pi * (4.0 / 15.0) * t) + 0.02 * np.sin(2.0 * np.pi * (11.0 / 15.0) * t)

    # Warm HVAC & damp hallway room tone (strictly damped above 1.9kHz so zero harsh hiss)
    air = rng.normal(0.0, 1.0, (n, 2))
    freqs = np.fft.rfftfreq(n, 1.0 / sr)
    room_shape = np.where(freqs > 28.0, 1.0 / (np.maximum(freqs, 28.0) ** 0.72), 0.0) * np.exp(-freqs / 1600.0)
    air_l = np.fft.irfft(np.fft.rfft(air[:, 0]) * room_shape, n=n)
    air_r = np.fft.irfft(np.fft.rfft(air[:, 1]) * room_shape, n=n)
    air_l = air_l / (np.std(air_l) + 1e-9) * 0.024
    air_r = air_r / (np.std(air_r) + 1e-9) * 0.024

    hum_lr = np.column_stack((transformer * lfo + air_l, transformer * 0.97 * lfo + air_r))
    hum_lr = corridor_reverb(hum_lr, sr, rt60=0.45, wet=0.18, bright_hz=1200.0, seed=11)
    hum_lr = fade_edges(hum_lr, sr, fade_in_s=1.0, fade_out_s=1.0, equal_power=True)
    synth_ogg(hum_lr, os.path.join(SND_DIR, "fluorescent_hum.ogg"), sr, drive=1.08, target_peak=0.18)

    # 2. light_flicker.ogg (1.4s 48kHz faulty magnetic ballast & glass-tube corona arc in corridor)
    dur_f = 1.4
    nf = int(sr * dur_f)
    tf = np.linspace(0.0, dur_f, nf, endpoint=False)
    # Stuttering magnetic shutter modulation
    shutter = 0.25 + 0.75 * np.clip(
        0.5 + 0.5 * np.sin(2.0 * np.pi * 17.0 * tf) * np.cos(2.0 * np.pi * 6.5 * tf), 0.0, 1.0
    )
    coil = (0.18 * np.sin(2.0 * np.pi * 120.0 * tf) + 0.12 * np.sin(2.0 * np.pi * 240.0 * tf)
            + 0.06 * np.sin(2.0 * np.pi * 680.0 * tf)) * shutter

    arc_l = np.zeros(nf, dtype=np.float64)
    arc_r = np.zeros(nf, dtype=np.float64)
    for ts, amp in ((0.08, 0.9), (0.14, 0.55), (0.37, 1.0), (0.43, 0.7), (0.68, 0.45), (0.89, 0.85), (0.94, 0.5)):
        idx = int(ts * sr)
        burst_len = min(int(0.032 * sr), nf - idx)
        if burst_len <= 0:
            continue
        tb = np.arange(burst_len, dtype=np.float64) / sr
        b_env = np.exp(-tb / 0.0055) * (1.0 - np.exp(-tb / 0.0005))
        plasma = rng.normal(0.0, 1.0, burst_len) * b_env * amp
        res = (0.45 * np.sin(2.0 * np.pi * 1850.0 * tb) + 0.30 * np.sin(2.0 * np.pi * 3120.0 * tb)) * b_env * amp
        arc_l[idx:idx + burst_len] += plasma * 0.45 + res * 0.35
        arc_r[idx:idx + burst_len] += rng.normal(0.0, 1.0, burst_len) * b_env * amp * 0.45 + res * 0.32

    arc_lr = fft_band(np.column_stack((arc_l, arc_r)), sr, low_hz=450.0, high_hz=4800.0, order=2.0)
    flicker_lr = np.column_stack((coil * 0.32, coil * 0.30)) + arc_lr * 0.72
    flicker_lr = corridor_reverb(flicker_lr, sr, rt60=0.55, wet=0.32, bright_hz=2800.0, seed=22)
    flicker_lr = fade_edges(flicker_lr, sr, fade_in_s=0.04, fade_out_s=0.22)
    synth_ogg(flicker_lr, os.path.join(SND_DIR, "light_flicker.ogg"), sr, drive=1.15, target_peak=0.38)

    # 3. noclip.ogg (1.6s 48kHz supernatural vacuum intake + sub-drop + hollow hallway shimmer)
    dur_n = 1.6
    nn = int(sr * dur_n)
    tn = np.linspace(0.0, dur_n, nn, endpoint=False)
    split = int(0.24 * sr)

    # Brief reverse air-suction swell into reality drop
    t_pre = tn[:split]
    swell_env = (t_pre / 0.24) ** 2.4
    whoosh_pre = fft_band(rng.normal(0.0, 1.0, (split, 2)), sr, low_hz=180.0, high_hz=1600.0, order=1.5) * swell_env[:, None]

    t_post = tn[split:] - 0.24
    freq_drop = 88.0 * np.exp(-3.2 * t_post) + 28.0
    ph_drop = 2.0 * np.pi * np.cumsum(freq_drop) / sr
    sub_drop = (np.sin(ph_drop) + 0.35 * np.sin(2.0 * ph_drop)) * np.exp(-2.1 * t_post) * (1.0 - np.exp(-t_post / 0.008))
    shimmer = (0.10 * np.sin(2.0 * np.pi * 528.0 * t_post) + 0.07 * np.sin(2.0 * np.pi * 792.0 * t_post)) * np.exp(-2.8 * t_post)
    air_post = fft_band(rng.normal(0.0, 1.0, (len(t_post), 2)), sr, low_hz=90.0, high_hz=720.0, order=1.8) * np.exp(-4.0 * t_post)[:, None]

    post_lr = np.column_stack((sub_drop * 0.8 + shimmer, sub_drop * 0.8 - shimmer * 0.4)) + air_post * 0.28
    noclip_lr = np.vstack((whoosh_pre * 0.45, post_lr))
    noclip_lr = corridor_reverb(noclip_lr, sr, rt60=0.95, wet=0.38, bright_hz=2100.0, seed=33)
    noclip_lr = fade_edges(noclip_lr, sr, fade_in_s=0.03, fade_out_s=0.35)
    synth_ogg(noclip_lr, os.path.join(SND_DIR, "noclip.ogg"), sr, drive=1.45, target_peak=0.58)

    # 4. distant_thump.ogg (1.8s 48kHz heavy drywall/door thud echoing down empty corridor)
    dur_d = 1.8
    nd = int(sr * dur_d)
    td = np.linspace(0.0, dur_d, nd, endpoint=False)
    thump_f = 78.0 * np.exp(-28.0 * td) + 41.0
    thump_ph = 2.0 * np.pi * np.cumsum(thump_f) / sr
    body = (0.72 * np.sin(thump_ph) * np.exp(-6.5 * td)
            + 0.30 * np.sin(2.0 * np.pi * 112.0 * td) * np.exp(-16.0 * td)
            + 0.14 * np.sin(2.0 * np.pi * 235.0 * td) * np.exp(-28.0 * td))
    wood_hit = fft_band(rng.normal(0.0, 1.0, (nd, 2)), sr, low_hz=70.0, high_hz=580.0, order=2.0) * np.exp(-22.0 * td)[:, None]
    thump_lr = ensure_stereo(body * (1.0 - np.exp(-td / 0.004))) + wood_hit * 0.42
    # Muffle highs strongly as if coming around two hallway corners
    thump_lr = fft_band(thump_lr, sr, low_hz=28.0, high_hz=520.0, order=2.0)
    thump_lr = corridor_reverb(thump_lr, sr, rt60=1.05, wet=0.52, bright_hz=680.0, seed=44)
    thump_lr = fade_edges(thump_lr, sr, fade_in_s=0.005, fade_out_s=0.45)
    synth_ogg(thump_lr, os.path.join(SND_DIR, "distant_thump.ogg"), sr, drive=1.15, target_peak=0.44)

    # 5. wallpaper_tear.ogg (0.55s 48kHz crisp dry cellulose paper tearing off plaster wall)
    dur_w = 0.55
    nw = int(sr * dur_w)
    tw = np.linspace(0.0, dur_w, nw, endpoint=False)
    snaps_l = np.zeros(nw, dtype=np.float64)
    snaps_r = np.zeros(nw, dtype=np.float64)
    for _ in range(64):
        pos = int(rng.uniform(0.02, dur_w - 0.06) * sr)
        elen = min(int(0.009 * sr), nw - pos)
        te = np.arange(elen, dtype=np.float64) / sr
        f_snap = rng.uniform(1350.0, 4200.0)
        grain = np.sin(2.0 * np.pi * f_snap * te) * np.exp(-te / 0.0018) * rng.uniform(0.3, 1.0)
        pan = rng.uniform(0.35, 0.65)
        snaps_l[pos:pos + elen] += grain * (1.0 - pan)
        snaps_r[pos:pos + elen] += grain * pan
    pull_curve = (np.sin(np.pi * tw / dur_w) ** 0.65) * (0.75 + 0.25 * np.sin(2.0 * np.pi * 11.0 * tw + 0.6))
    paper_body = fft_band(rng.normal(0.0, 1.0, (nw, 2)), sr, low_hz=680.0, high_hz=4800.0, order=2.0) * pull_curve[:, None]
    wall_tap = ensure_stereo(0.22 * np.sin(2.0 * np.pi * 115.0 * tw) * np.exp(-32.0 * tw))
    wp_lr = np.column_stack((snaps_l, snaps_r)) * 0.55 + paper_body * 0.42 + wall_tap
    wp_lr = corridor_reverb(wp_lr, sr, rt60=0.35, wet=0.20, bright_hz=3200.0, seed=55)
    wp_lr = fade_edges(wp_lr, sr, fade_in_s=0.008, fade_out_s=0.08)
    synth_ogg(wp_lr, os.path.join(SND_DIR, "wallpaper_tear.ogg"), sr, drive=1.1, target_peak=0.45)

    # 6. carpet_rip.ogg (0.48s 48kHz coarse damp wool & adhesive subfloor peel)
    dur_c = 0.48
    nc = int(sr * dur_c)
    tc = np.linspace(0.0, dur_c, nc, endpoint=False)
    peel_env = (np.sin(np.pi * tc / dur_c) ** 0.75) * (0.72 + 0.28 * np.cos(2.0 * np.pi * 9.0 * tc))
    fibers = fft_band(rng.normal(0.0, 1.0, (nc, 2)), sr, low_hz=260.0, high_hz=2100.0, order=2.0) * peel_env[:, None]
    glue_l = np.zeros(nc, dtype=np.float64)
    glue_r = np.zeros(nc, dtype=np.float64)
    for _ in range(36):
        pos = int(rng.uniform(0.03, dur_c - 0.05) * sr)
        elen = min(int(0.012 * sr), nc - pos)
        te = np.arange(elen, dtype=np.float64) / sr
        pop = np.sin(2.0 * np.pi * rng.uniform(420.0, 950.0) * te) * np.exp(-te / 0.0028) * rng.uniform(0.3, 0.9)
        glue_l[pos:pos + elen] += pop * rng.uniform(0.4, 0.8)
        glue_r[pos:pos + elen] += pop * rng.uniform(0.4, 0.8)
    thud_c = ensure_stereo(0.28 * np.sin(2.0 * np.pi * 78.0 * tc) * np.exp(-18.0 * tc))
    c_lr = fibers * 0.48 + np.column_stack((glue_l, glue_r)) * 0.45 + thud_c
    c_lr = corridor_reverb(c_lr, sr, rt60=0.30, wet=0.18, bright_hz=1800.0, seed=66)
    c_lr = fade_edges(c_lr, sr, fade_in_s=0.008, fade_out_s=0.07)
    synth_ogg(c_lr, os.path.join(SND_DIR, "carpet_rip.ogg"), sr, drive=1.15, target_peak=0.45)

    # 7. metal_pry.ogg (0.65s 48kHz genuine inharmonic thin sheet-steel HVAC vent pry & clatter)
    dur_m = 0.65
    nm = int(sr * dur_m)
    tm = np.linspace(0.0, dur_m, nm, endpoint=False)
    # Realistic inharmonic modes of a thin galvanized duct grate
    modes = [
        (312.0, 0.34, 7.5), (487.0, 0.42, 9.0), (691.0, 0.31, 11.0), (938.0, 0.25, 13.5),
        (1214.0, 0.20, 15.0), (1582.0, 0.15, 17.5), (2045.0, 0.11, 20.0), (2610.0, 0.07, 23.0),
    ]
    metal_l = np.zeros(nm, dtype=np.float64)
    metal_r = np.zeros(nm, dtype=np.float64)
    for idx_m, (mf, ma, md) in enumerate(modes):
        pan_l = 0.88 if (idx_m % 2 == 0) else 1.0
        pan_r = 1.0 if (idx_m % 2 == 0) else 0.88
        mode_sig = ma * np.sin(2.0 * np.pi * mf * tm + idx_m * 0.4) * np.exp(-md * tm)
        metal_l += mode_sig * pan_l
        metal_r += mode_sig * pan_r
    scrape = fft_band(rng.normal(0.0, 1.0, (nm, 2)), sr, low_hz=1400.0, high_hz=5200.0, order=2.0) * np.exp(-26.0 * tm)[:, None]
    impact = ensure_stereo(0.42 * np.sin(2.0 * np.pi * 135.0 * tm) * np.exp(-32.0 * tm))
    m_lr = np.column_stack((metal_l, metal_r)) * 0.65 + scrape * 0.36 + impact
    m_lr = corridor_reverb(m_lr, sr, rt60=0.45, wet=0.25, bright_hz=3000.0, seed=88)
    m_lr = fade_edges(m_lr, sr, fade_in_s=0.004, fade_out_s=0.10)
    synth_ogg(m_lr, os.path.join(SND_DIR, "metal_pry.ogg"), sr, drive=1.2, target_peak=0.48)

    # 8. red_room_pulse.ogg (4.4s 48kHz warm dark subharmonic dread drone, crossfade-ready @ 3.8s)
    dur_r = 4.4
    nr = int(sr * dur_r)
    tr = np.linspace(0.0, dur_r, nr, endpoint=False)
    drone = (
        0.34 * np.sin(2.0 * np.pi * 41.2 * tr)
        + 0.22 * np.sin(2.0 * np.pi * 61.8 * tr)
        + 0.14 * np.sin(2.0 * np.pi * 82.4 * tr)
        + 0.08 * np.sin(2.0 * np.pi * 123.6 * tr)
    )
    breath = 0.72 + 0.28 * np.sin(2.0 * np.pi * 0.45 * tr - 0.5 * np.pi)
    dark_air = fft_band(rng.normal(0.0, 1.0, (nr, 2)), sr, low_hz=45.0, high_hz=320.0, order=2.0)
    dark_air = dark_air / (np.std(dark_air) + 1e-9) * 0.08
    pulse_lr = np.column_stack((drone * breath, drone * 0.96 * breath)) + dark_air
    pulse_lr = corridor_reverb(pulse_lr, sr, rt60=1.15, wet=0.40, bright_hz=580.0, seed=99)
    pulse_lr = fade_edges(pulse_lr, sr, fade_in_s=0.65, fade_out_s=0.80, equal_power=True)
    synth_ogg(pulse_lr, os.path.join(SND_DIR, "red_room_pulse.ogg"), sr, drive=1.12, target_peak=0.28)

    write_json(os.path.join(PACK_DIR, "assets", "minecraft", "sounds.json"), {
        "backrooms.ambient.hum": {
            "sounds": [{"name": "backrooms/fluorescent_hum", "stream": False}]
        },
        "backrooms.ambient.flicker": {
            "sounds": [{"name": "backrooms/light_flicker", "stream": False}]
        },
        "backrooms.ui.noclip": {
            "sounds": [{"name": "backrooms/noclip", "stream": False}]
        },
        "backrooms.ambient.thump": {
            "sounds": [{"name": "backrooms/distant_thump", "stream": False}]
        },
        "backrooms.fx.wallpaper_tear": {
            "sounds": [{"name": "backrooms/wallpaper_tear", "stream": False}]
        },
        "backrooms.fx.carpet_rip": {
            "sounds": [{"name": "backrooms/carpet_rip", "stream": False}]
        },
        "backrooms.fx.metal_pry": {
            "sounds": [{"name": "backrooms/metal_pry", "stream": False}]
        },
        "backrooms.ambient.red_pulse": {
            "sounds": [{"name": "backrooms/red_room_pulse", "stream": False}]
        },
    })


def main():
    if os.path.isdir(PACK_DIR):
        shutil.rmtree(PACK_DIR)
    os.makedirs(PACK_DIR, exist_ok=True)
    os.makedirs(os.path.dirname(DIST_ZIP), exist_ok=True)

    write_json(os.path.join(PACK_DIR, "pack.mcmeta"), {
        "pack": {
            "min_format": 97,
            "max_format": 120,
            "description": "YUNEXAL BACKROOMS // Level 0 Textures & Audio (26.3)",
        }
    })

    generate_textures()
    generate_audio()

    with zipfile.ZipFile(DIST_ZIP, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for base, _, files in os.walk(PACK_DIR):
            for fn in sorted(files):
                full = os.path.join(base, fn)
                rel = os.path.relpath(full, PACK_DIR).replace(os.sep, "/")
                zf.write(full, rel)

    with open(DIST_ZIP, "rb") as f:
        sha1 = hashlib.sha1(f.read()).hexdigest()
    size = os.path.getsize(DIST_ZIP)
    print(f"Built {DIST_ZIP} ({size} bytes, sha1={sha1})")


if __name__ == "__main__":
    main()
