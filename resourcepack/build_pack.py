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


def synth_ogg(samples_lr, out_ogg, sr=48000):
    os.makedirs(os.path.dirname(out_ogg), exist_ok=True)
    tmp_wav = out_ogg + ".tmp.wav"
    if samples_lr.ndim == 1:
        samples_lr = np.column_stack((samples_lr, samples_lr))
    pcm = np.clip(samples_lr * 32767.0, -32767, 32767).astype(np.int16)
    with wave.open(tmp_wav, "wb") as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(sr)
        wf.writeframes(pcm.tobytes())
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", tmp_wav, "-c:a", "libvorbis", "-q:a", "6", out_ogg],
        check=True,
    )
    os.remove(tmp_wav)


def generate_audio():
    sr = 48000

    # 1. fluorescent_hum.ogg (16.0s studio 48kHz seamless warm loop optimized for headphones)
    dur = 16.0
    n = int(sr * dur)
    t = np.linspace(0, dur, n, endpoint=False)
    rng = np.random.default_rng(901)

    # Warm deep transformer hum (100Hz + subtle 200Hz harmonic; zero harsh 60Hz buzz/grit)
    hum = (
        0.24 * np.sin(2 * np.pi * 100.0 * t)
        + 0.09 * np.sin(2 * np.pi * 200.0 * t + 0.35)
        + 0.03 * np.sin(2 * np.pi * 300.0 * t + 1.1)
    )

    # Smooth pink/brown air-ballast ambience via FFT spectral shaping (periodic by construction -> zero loop click!)
    white_l = rng.normal(0, 1.0, n)
    white_r = rng.normal(0, 1.0, n)
    freqs = np.fft.rfftfreq(n, 1.0 / sr)
    # 1/sqrt(f) pink slope + gentle rolloff above 3.8kHz for silky headphone comfort
    shape = np.where(freqs > 18.0, 1.0 / np.sqrt(np.maximum(freqs, 20.0)), 0.0) * np.exp(-freqs / 4200.0)
    air_l = np.fft.irfft(np.fft.rfft(white_l) * shape, n=n)
    air_r = np.fft.irfft(np.fft.rfft(white_r) * shape, n=n)
    air_l = air_l / (np.std(air_l) + 1e-6) * 0.065
    air_r = air_r / (np.std(air_r) + 1e-6) * 0.065

    lfo = 0.94 + 0.06 * np.sin(2 * np.pi * (2.0 / dur) * t)
    sig_l = (hum + air_l) * lfo * 0.42
    sig_r = (hum * 0.98 + air_r) * lfo * 0.42
    synth_ogg(np.column_stack((sig_l, sig_r)), os.path.join(SND_DIR, "fluorescent_hum.ogg"), sr)

    # 2. light_flicker.ogg (1.2s 48kHz crisp ballast buzz/crackle)
    dur_f = 1.2
    nf = int(sr * dur_f)
    tf = np.linspace(0, dur_f, nf, endpoint=False)
    buzz = 0.25 * np.sin(2 * np.pi * 100.0 * tf) + 0.16 * np.sin(2 * np.pi * 200.0 * tf)
    clicks = np.zeros_like(tf)
    for ts in (0.09, 0.16, 0.41, 0.48, 0.84):
        idx = int(ts * sr)
        clicks[idx : idx + 220] = rng.normal(0, 0.45, 220) * np.exp(-np.linspace(0, 6, 220))
    env = np.sin(np.pi * tf / dur_f) ** 0.5
    f_sig = (buzz * 0.35 + clicks) * env * 0.55
    synth_ogg(f_sig, os.path.join(SND_DIR, "light_flicker.ogg"), sr)

    # 3. noclip.ogg (1.4s 48kHz stereo sub-bass reality tear)
    dur_n = 1.4
    tn = np.linspace(0, dur_n, int(sr * dur_n), endpoint=False)
    freq_sweep = 115.0 * np.exp(-2.8 * tn) + 32.0
    phase = 2 * np.pi * np.cumsum(freq_sweep) / sr
    sub = np.sin(phase) * np.exp(-1.9 * tn)
    tear = np.convolve(rng.normal(0, 0.28, len(tn)) * np.exp(-5.5 * tn), np.ones(12) / 12.0, mode="same")
    n_sig = np.tanh((sub * 0.72 + tear * 0.5) * 1.35) * 0.72
    synth_ogg(n_sig, os.path.join(SND_DIR, "noclip.ogg"), sr)

    # 4. distant_thump.ogg (1.6s 48kHz muffled hallway echo)
    dur_d = 1.6
    td = np.linspace(0, dur_d, int(sr * dur_d), endpoint=False)
    thud = np.sin(2 * np.pi * 48.0 * td) * np.exp(-3.6 * td)
    tail = np.convolve(rng.normal(0, 0.12, len(td)) * np.exp(-2.8 * td), np.ones(90) / 90.0, mode="same")
    d_sig = (thud * 0.65 + tail * 0.5) * 0.7
    synth_ogg(d_sig, os.path.join(SND_DIR, "distant_thump.ogg"), sr)

    # 5. wallpaper_tear.ogg (0.45s crisp paper ripping sound)
    dur_w = 0.45
    tw = np.linspace(0, dur_w, int(sr * dur_w), endpoint=False)
    w_noise = rng.normal(0, 1.0, len(tw))
    band = w_noise - np.convolve(w_noise, np.ones(18) / 18.0, mode="same")
    w_env = (np.sin(np.pi * tw / dur_w) ** 0.7) * (0.65 + 0.35 * np.sin(2 * np.pi * 24.0 * tw))
    synth_ogg(band * w_env * 0.32, os.path.join(SND_DIR, "wallpaper_tear.ogg"), sr)

    # 6. carpet_rip.ogg (0.42s muffled fibrous tear)
    dur_c = 0.42
    tc = np.linspace(0, dur_c, int(sr * dur_c), endpoint=False)
    c_noise = np.convolve(rng.normal(0, 1.0, len(tc)), np.ones(14) / 14.0, mode="same")
    c_env = np.sin(np.pi * tc / dur_c) ** 0.8
    synth_ogg(c_noise * c_env * 0.48, os.path.join(SND_DIR, "carpet_rip.ogg"), sr)

    # 7. metal_pry.ogg (0.5s metallic vent clank)
    dur_m = 0.5
    tm = np.linspace(0, dur_m, int(sr * dur_m), endpoint=False)
    clang = (
        0.4 * np.sin(2 * np.pi * 420.0 * tm) * np.exp(-10.0 * tm)
        + 0.3 * np.sin(2 * np.pi * 890.0 * tm) * np.exp(-14.0 * tm)
        + 0.2 * np.sin(2 * np.pi * 1410.0 * tm) * np.exp(-18.0 * tm)
    )
    synth_ogg(clang * 0.65, os.path.join(SND_DIR, "metal_pry.ogg"), sr)

    # 8. red_room_pulse.ogg (2.5s eerie deep dread drone)
    dur_r = 2.5
    tr = np.linspace(0, dur_r, int(sr * dur_r), endpoint=False)
    pulse = (0.45 * np.sin(2 * np.pi * 55.0 * tr) + 0.28 * np.sin(2 * np.pi * 57.5 * tr)) * np.sin(np.pi * tr / dur_r)
    synth_ogg(pulse * 0.55, os.path.join(SND_DIR, "red_room_pulse.ogg"), sr)

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
