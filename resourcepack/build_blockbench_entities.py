#!/usr/bin/env python3
"""
Generates high-detail Blockbench-grade skeletal 3D models (.bbmodel sources + Vanilla 26.3 JSON models)
and 64x64 shaded UV texture atlases for Backrooms entities: Bacteria, Smiler, and Hound.

All bones use pivot (8, 8, 8) as local joint origin and Blockbench North (-Z) as forward facing,
with "display.fixed" at scale [1, 1, 1] so ItemDisplay(FIXED) maps 16 units = 1.0 world block seamlessly.
"""

import json
import math
import os
import uuid
import numpy as np
from PIL import Image


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)


def make_uv_atlas_bacteria(out_png):
    """64x64 dark braided electrical cables, necrotic flesh, exposed pale wire tendrils & VHS static eyes."""
    rng = np.random.default_rng(7101)
    img = np.zeros((64, 64, 4), dtype=np.uint8)

    # Region [0..32, 0..32]: Main braided necrotic wire / black organic skin
    for y in range(32):
        for x in range(32):
            braid = math.sin(x * 0.9 + y * 0.45) * math.cos(y * 0.85 - x * 0.35)
            v = int(np.clip(17 + braid * 9 + rng.normal(0, 2.2), 6, 36))
            img[y, x] = [v + 1, v, v, 255]
            if (x + y * 2) % 11 == 0 and rng.random() < 0.45:
                # Subtle wet metallic sheen pixel
                img[y, x] = [44, 42, 39, 255]

    # Region [32..64, 0..32]: Exposed skeletal rib ridges & frayed dark copper/silver filaments
    for y in range(32):
        for x in range(32, 64):
            striation = ((x - 32) + y) % 4
            if striation == 0:
                img[y, x] = [38, 35, 31, 255]
            elif (x - 32) % 7 == 0 and y % 5 < 3:
                img[y, x] = [74, 54, 40, 255]  # frayed tarnished copper wire glint
            else:
                v = int(np.clip(22 + rng.normal(0, 3), 10, 38))
                img[y, x] = [v, v, v, 255]

    # Region [0..32, 32..64]: Cranial void & hollow sockets + twin piercing pale-white optic dots
    img[32:64, 0:32] = [11, 10, 11, 255]
    for y in range(32, 64):
        for x in range(32):
            v = int(np.clip(13 + rng.normal(0, 2.5), 5, 24))
            img[y, x] = [v, v, v + 1, 255]
    # Hollow sunken sockets at (x=4..12, y=38..46) & (x=18..26, y=38..46)
    img[38:46, 4:12] = [3, 3, 4, 255]
    img[38:46, 18:26] = [3, 3, 4, 255]
    # Pinprick eerie pale eyes
    img[41:43, 7:9] = [230, 226, 210, 255]
    img[41:43, 21:23] = [230, 226, 210, 255]

    # Region [32..64, 32..64]: Sharp splayed claw tips & tendon joints
    for y in range(32, 64):
        fade = (y - 32) / 31.0
        val = int(20 + fade * 34)
        for x in range(32, 64):
            img[y, x] = [val, val - 2, val - 4, 255]

    Image.fromarray(img, mode="RGBA").save(out_png)


def make_uv_atlas_smiler(out_png):
    """64x64 misty darkness shroud + gleaming luminous cyan-white eyes and ivory teeth."""
    rng = np.random.default_rng(7202)
    img = np.zeros((64, 64, 4), dtype=np.uint8)

    # Region [0..32, 0..32]: Deep abyssal misty charcoal shroud
    for y in range(32):
        for x in range(32):
            v = int(np.clip(10 + rng.normal(0, 2.0), 3, 18))
            img[y, x] = [v, v, v + 3, 255]

    # Region [32..64, 0..32]: Luminous glowing pure white eyes (subtle icy rim)
    img[0:32, 32:64] = [255, 255, 255, 255]
    for y in range(32):
        for x in range(32, 64):
            if y in (0, 31) or x in (32, 63):
                img[y, x] = [215, 232, 255, 255]

    # Region [0..32, 32..64]: Razor-sharp glowing enamel teeth gradient
    for y in range(32, 64):
        t = (y - 32) / 31.0
        g = int(255 - t * 18)
        b = int(255 - t * 28)
        img[y, 0:32] = [255, g, b, 255]

    # Region [32..64, 32..64]: Deep black orbital socket shadow
    img[32:64, 32:64] = [2, 2, 3, 255]

    Image.fromarray(img, mode="RGBA").save(out_png)


def make_uv_atlas_hound(out_png):
    """64x64 pale bruised humanoid-canine hide, thick wet black hair strands, bloodstained maw & amber eyes."""
    rng = np.random.default_rng(7303)
    img = np.zeros((64, 64, 4), dtype=np.uint8)

    # Region [0..32, 0..32]: Pale ash-gray gaunt bruised flesh with rib & scar shading
    for y in range(32):
        for x in range(32):
            rib = 18 * max(0.0, math.sin(x * 0.85)) if (6 <= y <= 24) else 0
            r = int(np.clip(138 - rib + rng.normal(0, 5.0), 75, 168))
            g = int(np.clip(124 - rib + rng.normal(0, 4.5), 68, 152))
            b = int(np.clip(116 - rib + rng.normal(0, 4.5), 65, 142))
            img[y, x] = [r, g, b, 255]

    # Region [32..64, 0..32]: Coarse matted wet black hair locks hanging over Hound head
    for y in range(32):
        for x in range(32, 64):
            strand = 8 if (x % 3 == 0) else (-4 if (x % 3 == 1) else 0)
            v = int(np.clip(22 + strand + rng.normal(0, 3.0), 8, 42))
            img[y, x] = [v + 1, v, v, 255]

    # Region [0..32, 32..64]: Snarling crimson gums & sharp dirty-ivory teeth
    img[32:48, 0:32] = [92, 28, 30, 255]
    img[48:64, 0:32] = [228, 220, 198, 255]

    # Region [32..64, 32..64]: Piercing luminous amber-yellow feral eye irises
    img[32:64, 32:64] = [255, 214, 96, 255]
    img[40:56, 40:56] = [255, 245, 175, 255]

    Image.fromarray(img, mode="RGBA").save(out_png)


def uv_box(u_px, v_px, w_px=32, h_px=32):
    """Maps a pixel sub-region in a 64x64 atlas to Minecraft 0..16 UV box dict."""
    u1 = round(u_px * 16.0 / 64.0, 3)
    v1 = round(v_px * 16.0 / 64.0, 3)
    u2 = round((u_px + w_px) * 16.0 / 64.0, 3)
    v2 = round((v_px + h_px) * 16.0 / 64.0, 3)
    uv = [u1, v1, u2, v2]
    return {
        face: {"uv": uv, "texture": "#0"}
        for face in ("north", "east", "south", "west", "up", "down")
    }


def elem(x1, y1, z1, x2, y2, z2, uv_rect, rot=None):
    d = {
        "from": [round(x1, 3), round(y1, 3), round(z1, 3)],
        "to": [round(x2, 3), round(y2, 3), round(z2, 3)],
        "faces": uv_box(*uv_rect),
    }
    if rot is not None:
        angle, axis, origin = rot
        d["rotation"] = {"angle": angle, "axis": axis, "origin": list(origin)}
    return d


def build_bone_model(tex_key, elements):
    return {
        "format_version": "1.21.6",
        "credit": "Yunexal Backrooms (Blockbench Format)",
        "texture_size": [64, 64],
        "textures": {
            "0": tex_key,
            "particle": tex_key,
        },
        "elements": elements,
        "display": {
            "fixed": {
                "rotation": [0, 0, 0],
                "translation": [0, 0, 0],
                "scale": [1, 1, 1],
            },
            "ground": {
                "rotation": [0, 0, 0],
                "translation": [0, 2, 0],
                "scale": [0.5, 0.5, 0.5],
            },
        },
    }


def export_bbmodel(out_path, project_name, tex_name, tex_png_path, bones):
    """Exports a valid Blockbench .bbmodel project file containing all sculpted bones and embedded texture."""
    import base64
    with open(tex_png_path, "rb") as tf:
        b64 = base64.b64encode(tf.read()).decode("ascii")

    tex_uuid = str(uuid.uuid5(uuid.NAMESPACE_DNS, f"{project_name}.tex"))
    bb_elements = []
    bb_outliner = []

    for bone_name, pivot_offset, els in bones:
        group_uuid = str(uuid.uuid5(uuid.NAMESPACE_DNS, f"{project_name}.grp.{bone_name}"))
        children = []
        ox, oy, oz = pivot_offset
        for idx, e in enumerate(els):
            el_uuid = str(uuid.uuid5(uuid.NAMESPACE_DNS, f"{project_name}.{bone_name}.{idx}"))
            f = e["from"]
            t = e["to"]
            # Convert bone-local coords (pivot 8,8,8) back into world model grid coords around origin (0,0,0)
            fx, fy, fz = f[0] - 8 + ox, f[1] - 8 + oy, f[2] - 8 + oz
            tx, ty, tz = t[0] - 8 + ox, t[1] - 8 + oy, t[2] - 8 + oz
            rot_arr = [0, 0, 0]
            el_orig = [ox, oy, oz]
            if "rotation" in e:
                ang = e["rotation"]["angle"]
                ax = e["rotation"]["axis"]
                ro = e["rotation"]["origin"]
                el_orig = [ro[0] - 8 + ox, ro[1] - 8 + oy, ro[2] - 8 + oz]
                rot_arr = [ang if ax == "x" else 0, ang if ax == "y" else 0, ang if ax == "z" else 0]
            faces = {}
            for fn, fv in e["faces"].items():
                faces[fn] = {"uv": [v * 4.0 for v in fv["uv"]], "texture": 0}
            bb_elements.append({
                "name": f"{bone_name}_{idx}",
                "box_uv": False,
                "rescale": False,
                "locked": False,
                "from": [fx, fy, fz],
                "to": [tx, ty, tz],
                "rotation": rot_arr,
                "origin": el_orig,
                "faces": faces,
                "uuid": el_uuid,
            })
            children.append(el_uuid)
        bb_outliner.append({
            "name": bone_name,
            "origin": [ox, oy, oz],
            "color": 0,
            "uuid": group_uuid,
            "export": True,
            "isOpen": True,
            "visibility": True,
            "children": children,
        })

    bb = {
        "meta": {"format_version": "4.10", "model_format": "java_block", "box_uv": False},
        "name": project_name,
        "resolution": {"width": 64, "height": 64},
        "elements": bb_elements,
        "outliner": bb_outliner,
        "textures": [{
            "name": tex_name,
            "id": "0",
            "uuid": tex_uuid,
            "source": f"data:image/png;base64,{b64}",
            "width": 64,
            "height": 64,
            "uv_width": 64,
            "uv_height": 64,
            "visible": True,
        }],
    }
    write_json(out_path, bb)


def generate_blockbench_entities(pack_dir, blockbench_src_dir):
    item_tex_dir = os.path.join(pack_dir, "assets", "minecraft", "textures", "item")
    item_model_dir = os.path.join(pack_dir, "assets", "minecraft", "models", "item", "entity")
    item_def_dir = os.path.join(pack_dir, "assets", "minecraft", "items")
    os.makedirs(item_tex_dir, exist_ok=True)
    os.makedirs(item_model_dir, exist_ok=True)
    os.makedirs(item_def_dir, exist_ok=True)
    os.makedirs(blockbench_src_dir, exist_ok=True)

    # 1. Generate 64x64 shaded UV texture sheets
    bac_png = os.path.join(item_tex_dir, "entity_bacteria.png")
    smiler_png = os.path.join(item_tex_dir, "entity_smiler.png")
    hound_png = os.path.join(item_tex_dir, "entity_hound.png")
    make_uv_atlas_bacteria(bac_png)
    make_uv_atlas_smiler(smiler_png)
    make_uv_atlas_hound(hound_png)

    WIRE = (0, 0, 32, 32)
    RIB = (32, 0, 32, 32)
    SKULL = (0, 32, 32, 32)
    EYE_DOT = (7, 41, 2, 2)
    CLAW = (32, 32, 32, 32)

    # =========================================================================
    # BACTERIA (Kane Pixels tall ~2.45m tangled wire biomutant, 6 skeletal bones)
    # Pivot (8, 8, 8) = joint center in each bone!
    # =========================================================================
    bacteria_bones = [
        ("bacteria_torso", (0, 18, 0), [
            # Crooked gaunt central spine & split wire bundles
            elem(6.8, 7.5, 6.9, 9.2, 16.5, 9.1, WIRE),
            elem(7.2, 16.0, 6.4, 8.8, 22.0, 8.6, WIRE, (22.5, "x", (8, 16, 7.5))),
            # Protruding asymmetric ribs & wire coils
            elem(5.4, 15.8, 6.2, 10.6, 17.3, 9.4, RIB, (-22.5, "z", (8, 16.5, 8))),
            elem(5.8, 13.6, 6.5, 10.2, 14.9, 9.2, RIB),
            elem(6.1, 11.4, 6.7, 9.9, 12.6, 9.0, RIB, (22.5, "z", (8, 12, 8))),
            # Twisted upper shoulder tendrils curving upward
            elem(4.2, 18.8, 6.8, 7.2, 20.6, 8.8, WIRE, (-22.5, "z", (6, 19.5, 8))),
            elem(8.8, 19.0, 6.8, 11.8, 20.9, 8.8, WIRE, (22.5, "z", (10, 19.5, 8))),
            # Pelvic joint bar
            elem(5.5, 6.5, 6.8, 10.5, 8.4, 9.2, RIB),
            # Coiled dorsal wires sticking out the back (+Z)
            elem(7.4, 14.0, 8.8, 8.6, 21.0, 10.3, CLAW, (-22.5, "x", (8, 14, 9))),
            elem(6.0, 17.5, 8.4, 7.2, 23.0, 9.8, CLAW, (-45, "x", (6.5, 17.5, 9))),
        ]),
        ("bacteria_head", (0, 32, -1), [
            # Slender twisted wire-knotted head core facing North (-Z)
            elem(6.1, 8.2, 5.7, 9.9, 14.0, 9.8, SKULL),
            # Drooping slack open mandible frame
            elem(6.5, 5.8, 5.2, 9.5, 8.2, 8.6, RIB, (22.5, "x", (8, 8, 8))),
            # Tiny sunken eerie pale pinprick glints inside dark sockets (subtle, non-cartoon)
            elem(6.7, 11.1, 5.45, 7.25, 11.65, 5.8, EYE_DOT),
            elem(8.75, 11.1, 5.45, 9.3, 11.65, 5.8, EYE_DOT),
            # Twisted wire loops framing cheeks & crown
            elem(5.5, 9.2, 6.2, 6.3, 13.6, 7.2, WIRE, (-22.5, "z", (5.9, 11, 6.7))),
            elem(9.7, 9.4, 6.3, 10.5, 13.8, 7.3, WIRE, (22.5, "z", (10.1, 11, 6.8))),
            # Asymmetric gnarled dorsal cable horns & halo tendrils
            elem(5.5, 13.6, 6.8, 6.7, 19.6, 8.1, WIRE, (-22.5, "z", (6.1, 13.6, 7.4))),
            elem(9.2, 13.6, 7.0, 10.3, 21.4, 8.2, CLAW, (22.5, "z", (9.7, 13.6, 7.5))),
            elem(7.4, 13.8, 8.1, 8.4, 18.8, 9.2, WIRE, (-22.5, "x", (7.9, 13.8, 8.6))),
            elem(6.6, 17.5, 6.0, 7.4, 21.8, 7.0, CLAW, (22.5, "z", (7.0, 17.5, 6.5))),
        ]),
        ("bacteria_arm_left", (4.5, 30, 0), [
            # Pivot (8, 8, 8) = shoulder; extends downward into double-jointed clawed tendril
            elem(7.0, 0.5, 7.0, 9.0, 8.5, 9.0, WIRE),
            elem(7.2, -7.8, 6.2, 8.7, 1.2, 7.8, WIRE, (22.5, "x", (8, 1.0, 7.5))),
            # 3 long splayed wire fingers
            elem(7.0, -11.5, 5.8, 7.6, -7.2, 6.5, CLAW, (22.5, "z", (7.3, -7.5, 6.2))),
            elem(7.8, -12.2, 5.7, 8.4, -7.4, 6.4, CLAW),
            elem(8.5, -11.3, 6.0, 9.1, -7.2, 6.7, CLAW, (-22.5, "z", (8.8, -7.5, 6.3))),
        ]),
        ("bacteria_arm_right", (-4.5, 30, 0), [
            elem(7.0, 0.5, 7.0, 9.0, 8.5, 9.0, WIRE),
            elem(7.3, -8.2, 6.1, 8.8, 1.2, 7.8, WIRE, (22.5, "x", (8, 1.0, 7.5))),
            elem(6.9, -11.6, 5.9, 7.5, -7.4, 6.6, CLAW, (22.5, "z", (7.2, -7.5, 6.2))),
            elem(7.7, -12.4, 5.6, 8.3, -7.6, 6.3, CLAW),
            elem(8.4, -11.5, 5.8, 9.0, -7.4, 6.5, CLAW, (-22.5, "z", (8.7, -7.5, 6.2))),
        ]),
        ("bacteria_leg_left", (2.2, 16, 0), [
            # Digitigrade thigh + backward angled shin + splayed claw toes
            elem(6.9, 0.2, 6.4, 9.1, 8.5, 8.6, WIRE, (-22.5, "x", (8, 8, 7.5))),
            elem(7.1, -8.5, 7.6, 8.9, 0.8, 9.6, WIRE, (22.5, "x", (8, 0.5, 8.5))),
            elem(6.9, -9.2, 5.4, 9.1, -8.0, 9.0, CLAW),
            elem(6.7, -9.2, 3.8, 7.4, -8.3, 5.8, CLAW),
            elem(8.5, -9.2, 3.8, 9.2, -8.3, 5.8, CLAW),
        ]),
        ("bacteria_leg_right", (-2.2, 16, 0), [
            elem(6.9, 0.2, 6.4, 9.1, 8.5, 8.6, WIRE, (-22.5, "x", (8, 8, 7.5))),
            elem(7.1, -8.5, 7.6, 8.9, 0.8, 9.6, WIRE, (22.5, "x", (8, 0.5, 8.5))),
            elem(6.9, -9.2, 5.4, 9.1, -8.0, 9.0, CLAW),
            elem(6.7, -9.2, 3.8, 7.4, -8.3, 5.8, CLAW),
            elem(8.5, -9.2, 3.8, 9.2, -8.3, 5.8, CLAW),
        ]),
    ]

    # =========================================================================
    # SMILER (Layered abyssal shadow shroud + fullbright 3D luminous eyes/fangs)
    # =========================================================================
    SM_VOID = (0, 0, 32, 32)
    SM_EYE = (32, 0, 32, 32)
    SM_TOOTH = (0, 32, 32, 32)
    SM_DEEP = (32, 32, 32, 32)

    smiler_bones = [
        ("smiler_shroud", (0, 22, 0), [
            # Dark obsidian skull shroud visible only under close flashlight beam
            elem(2.5, 2.0, 4.5, 13.5, 14.5, 13.0, SM_VOID),
            elem(3.2, 14.0, 5.5, 12.8, 16.5, 12.2, SM_VOID, (-22.5, "x", (8, 14.5, 8))),
            # Deep black orbital pits framing glowing eyes
            elem(3.8, 8.8, 3.9, 7.0, 12.0, 4.8, SM_DEEP),
            elem(9.0, 8.8, 3.9, 12.2, 12.0, 4.8, SM_DEEP),
            # Hanging wispy darkness tendrils descending below jaw
            elem(3.2, -6.5, 6.5, 5.2, 2.5, 8.5, SM_VOID, (22.5, "z", (4.2, 2.0, 7.5))),
            elem(6.8, -8.5, 7.2, 9.2, 2.5, 9.2, SM_VOID),
            elem(10.8, -6.0, 6.5, 12.8, 2.5, 8.5, SM_VOID, (-22.5, "z", (11.8, 2.0, 7.5))),
            elem(5.0, -4.5, 9.5, 7.0, 3.0, 11.5, SM_VOID, (22.5, "x", (6, 2.5, 10.5))),
            elem(9.2, -5.0, 9.5, 11.2, 3.0, 11.5, SM_VOID, (22.5, "x", (10, 2.5, 10.5))),
        ]),
        ("smiler_face", (0, 22, 0), [
            # Piercing eerie glowing eyes (rounded stepped silhouette rather than square blocks)
            elem(4.7, 9.6, 3.3, 6.2, 11.3, 3.8, SM_EYE),
            elem(4.4, 10.0, 3.4, 6.5, 10.9, 3.8, SM_EYE),
            elem(9.8, 9.6, 3.3, 11.3, 11.3, 3.8, SM_EYE),
            elem(9.5, 10.0, 3.4, 11.6, 10.9, 3.8, SM_EYE),
            # Delicate thin glowing smile contour + wide separated sharp fangs in dark mouth gap!
            elem(2.1, 6.1, 4.1, 2.7, 7.2, 4.5, SM_TOOTH, (45, "z", (2.4, 6.6, 4.3))),
            elem(2.9, 5.1, 3.8, 3.8, 5.7, 4.2, SM_TOOTH, (22.5, "z", (3.3, 5.4, 4.0))),
            elem(12.2, 5.1, 3.8, 13.1, 5.7, 4.2, SM_TOOTH, (-22.5, "z", (12.7, 5.4, 4.0))),
            elem(13.3, 6.1, 4.1, 13.9, 7.2, 4.5, SM_TOOTH, (-45, "z", (13.6, 6.6, 4.3))),
            # Thin outer grin hairline connecting bottom curve subtly
            elem(4.2, 2.55, 3.6, 11.8, 2.85, 4.0, SM_TOOTH),
            elem(3.0, 3.35, 3.8, 4.4, 3.70, 4.2, SM_TOOTH, (22.5, "z", (3.7, 3.5, 4.0))),
            elem(11.6, 3.35, 3.8, 12.9, 3.70, 4.2, SM_TOOTH, (-22.5, "z", (12.3, 3.5, 4.0))),
            # 7 upper sharp separated fangs descending along curved upper arc
            elem(3.2, 4.9, 3.65, 3.75, 6.0, 4.05, SM_TOOTH),
            elem(4.5, 4.1, 3.45, 5.10, 5.4, 3.85, SM_TOOTH),
            elem(6.0, 3.7, 3.30, 6.65, 5.1, 3.70, SM_TOOTH),
            elem(7.65, 3.55, 3.25, 8.35, 5.0, 3.65, SM_TOOTH),
            elem(9.35, 3.7, 3.30, 10.0, 5.1, 3.70, SM_TOOTH),
            elem(10.9, 4.1, 3.45, 11.5, 5.4, 3.85, SM_TOOTH),
            elem(12.25, 4.9, 3.65, 12.8, 6.0, 4.05, SM_TOOTH),
            # 6 lower offset sharp teeth rising from the bottom jaw line
            elem(3.85, 3.5, 3.60, 4.35, 4.5, 4.00, SM_TOOTH),
            elem(5.30, 2.9, 3.40, 5.85, 4.1, 3.80, SM_TOOTH),
            elem(6.85, 2.7, 3.30, 7.45, 4.0, 3.70, SM_TOOTH),
            elem(8.55, 2.7, 3.30, 9.15, 4.0, 3.70, SM_TOOTH),
            elem(10.15, 2.9, 3.40, 10.7, 4.1, 3.80, SM_TOOTH),
            elem(11.65, 3.5, 3.60, 12.15, 4.5, 4.00, SM_TOOTH),
        ]),
    ]

    # =========================================================================
    # HOUND (Canonical gaunt hairless quadruped with matted black hair & feral maw)
    # =========================================================================
    H_SKIN = (0, 0, 32, 32)
    H_HAIR = (32, 0, 32, 32)
    H_GUM = (0, 32, 32, 16)
    H_FANG = (0, 48, 32, 16)
    H_EYE = (32, 32, 32, 32)

    hound_bones = [
        ("hound_torso", (0, 12, 0), [
            # Hunched skeletal canine ribcage (North -Z front shoulders -> South +Z narrow pelvis)
            elem(4.6, 6.5, 2.0, 11.4, 12.2, 9.0, H_SKIN, (-22.5, "x", (8, 9, 5.5))),
            elem(5.2, 5.4, 8.2, 10.8, 10.4, 15.6, H_SKIN, (22.5, "x", (8, 8, 11.5))),
            # Jagged protruding spine vertebrae & shoulder blades
            elem(7.3, 11.8, 3.0, 8.7, 13.4, 8.5, H_SKIN, (-22.5, "x", (8, 12.5, 5.5))),
            elem(7.4, 10.2, 8.5, 8.6, 11.6, 13.8, H_SKIN),
            # Scruffy tufts of coarse black hair along spine/neck
            elem(5.8, 11.5, 1.2, 10.2, 13.6, 5.0, H_HAIR, (-22.5, "x", (8, 12, 3))),
            elem(6.8, 7.2, 14.8, 9.2, 9.0, 19.2, H_HAIR, (22.5, "x", (8, 8, 17))),
        ]),
        ("hound_head", (0, 13, -7), [
            # Gaunt skull & snout pointing North (-Z)
            elem(4.8, 6.8, 3.0, 11.2, 12.6, 9.8, H_SKIN),
            elem(5.6, 7.4, -0.8, 10.4, 10.6, 3.6, H_SKIN),
            # Hinged open lower jaw with crimson gums & white fangs
            elem(5.8, 5.2, -0.4, 10.2, 6.8, 4.2, H_GUM, (22.5, "x", (8, 6.5, 4.0))),
            elem(6.0, 6.7, -0.6, 6.6, 7.8, 0.2, H_FANG),
            elem(9.4, 6.7, -0.6, 10.0, 7.8, 0.2, H_FANG),
            elem(7.1, 6.9, -0.6, 7.6, 7.6, 0.1, H_FANG),
            elem(8.4, 6.9, -0.6, 8.9, 7.6, 0.1, H_FANG),
            elem(6.1, 6.1, -0.2, 6.6, 7.1, 0.5, H_FANG, (22.5, "x", (8, 6.5, 4.0))),
            elem(9.4, 6.1, -0.2, 9.9, 7.1, 0.5, H_FANG, (22.5, "x", (8, 6.5, 4.0))),
            # Iconic Hound ragged staggered black hair locks (instead of a flat helmet)
            elem(4.7, 12.1, 2.5, 11.3, 13.4, 9.9, H_HAIR),
            elem(4.2, 7.2, 3.0, 5.1, 12.4, 5.4, H_HAIR, (-22.5, "z", (4.7, 12.4, 4.2))),
            elem(4.0, 6.4, 5.6, 5.0, 12.5, 8.2, H_HAIR, (-22.5, "z", (4.5, 12.5, 6.9))),
            elem(4.4, 8.0, 8.4, 5.2, 12.3, 10.2, H_HAIR, (-22.5, "z", (4.8, 12.3, 9.3))),
            elem(10.9, 7.2, 3.0, 11.8, 12.4, 5.4, H_HAIR, (22.5, "z", (11.3, 12.4, 4.2))),
            elem(11.0, 6.4, 5.6, 12.0, 12.5, 8.2, H_HAIR, (22.5, "z", (11.5, 12.5, 6.9))),
            elem(10.8, 8.0, 8.4, 11.6, 12.3, 10.2, H_HAIR, (22.5, "z", (11.2, 12.3, 9.3))),
            # Ragged wispy bangs parting slightly above glowing eyes
            elem(4.9, 9.5, 2.1, 6.1, 12.6, 3.1, H_HAIR, (-22.5, "x", (5.5, 12.6, 2.6))),
            elem(6.7, 9.7, 2.0, 9.3, 12.7, 3.1, H_HAIR, (-22.5, "x", (8.0, 12.7, 2.6))),
            elem(9.9, 9.5, 2.1, 11.1, 12.6, 3.1, H_HAIR, (-22.5, "x", (10.5, 12.6, 2.6))),
        ]),
        ("hound_eyes", (0, 13, -7), [
            # Fullbright feral amber eyes peeking strictly beneath the bangs (slightly proud of front face -Z)
            elem(5.5, 8.9, 2.4, 6.8, 9.9, 2.8, H_EYE),
            elem(9.2, 8.9, 2.4, 10.5, 9.9, 2.8, H_EYE),
        ]),
        ("hound_leg_fl", (3.2, 9, -5.5), [
            elem(6.9, 2.5, 6.9, 9.1, 8.8, 9.1, H_SKIN, (-22.5, "x", (8, 8.5, 8))),
            elem(7.1, -3.5, 6.0, 8.9, 3.2, 7.8, H_SKIN, (22.5, "x", (8, 3, 7))),
            elem(6.8, -4.4, 4.2, 9.2, -3.2, 7.5, H_SKIN),
            elem(6.8, -4.4, 3.1, 7.3, -3.7, 4.3, H_FANG),
            elem(7.8, -4.4, 3.0, 8.3, -3.7, 4.3, H_FANG),
            elem(8.8, -4.4, 3.1, 9.3, -3.7, 4.3, H_FANG),
        ]),
        ("hound_leg_fr", (-3.2, 9, -5.5), [
            elem(6.9, 2.5, 6.9, 9.1, 8.8, 9.1, H_SKIN, (-22.5, "x", (8, 8.5, 8))),
            elem(7.1, -3.5, 6.0, 8.9, 3.2, 7.8, H_SKIN, (22.5, "x", (8, 3, 7))),
            elem(6.8, -4.4, 4.2, 9.2, -3.2, 7.5, H_SKIN),
            elem(6.8, -4.4, 3.1, 7.3, -3.7, 4.3, H_FANG),
            elem(7.8, -4.4, 3.0, 8.3, -3.7, 4.3, H_FANG),
            elem(8.8, -4.4, 3.1, 9.3, -3.7, 4.3, H_FANG),
        ]),
        ("hound_leg_bl", (3.0, 8.5, 5.8), [
            elem(6.8, 2.2, 6.8, 9.2, 8.6, 9.2, H_SKIN, (22.5, "x", (8, 8, 8))),
            elem(7.1, -3.6, 7.6, 8.9, 3.0, 9.4, H_SKIN, (-22.5, "x", (8, 2.8, 8.5))),
            elem(6.8, -4.3, 5.2, 9.2, -3.2, 8.4, H_SKIN),
            elem(6.8, -4.3, 4.1, 7.4, -3.6, 5.3, H_FANG),
            elem(8.6, -4.3, 4.1, 9.2, -3.6, 5.3, H_FANG),
        ]),
        ("hound_leg_br", (-3.0, 8.5, 5.8), [
            elem(6.8, 2.2, 6.8, 9.2, 8.6, 9.2, H_SKIN, (22.5, "x", (8, 8, 8))),
            elem(7.1, -3.6, 7.6, 8.9, 3.0, 9.4, H_SKIN, (-22.5, "x", (8, 2.8, 8.5))),
            elem(6.8, -4.3, 5.2, 9.2, -3.2, 8.4, H_SKIN),
            elem(6.8, -4.3, 4.1, 7.4, -3.6, 5.3, H_FANG),
            elem(8.6, -4.3, 4.1, 9.2, -3.6, 5.3, H_FANG),
        ]),
    ]

    for tex_key, bones in (
        ("minecraft:item/entity_bacteria", bacteria_bones),
        ("minecraft:item/entity_smiler", smiler_bones),
        ("minecraft:item/entity_hound", hound_bones),
    ):
        for bone_name, _, els in bones:
            write_json(os.path.join(item_model_dir, f"{bone_name}.json"), build_bone_model(tex_key, els))
            write_json(os.path.join(item_def_dir, f"{bone_name}.json"), {
                "model": {
                    "type": "minecraft:model",
                    "model": f"minecraft:item/entity/{bone_name}"
                }
            })

    export_bbmodel(os.path.join(blockbench_src_dir, "bacteria.bbmodel"), "bacteria", "entity_bacteria.png", bac_png, bacteria_bones)
    export_bbmodel(os.path.join(blockbench_src_dir, "smiler.bbmodel"), "smiler", "entity_smiler.png", smiler_png, smiler_bones)
    export_bbmodel(os.path.join(blockbench_src_dir, "hound.bbmodel"), "hound", "entity_hound.png", hound_png, hound_bones)
    print(f"Generated 3 Blockbench projects + {len(bacteria_bones)+len(smiler_bones)+len(hound_bones)} JSON bone models.")
