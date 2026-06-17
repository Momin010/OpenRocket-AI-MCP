import bpy, json, sys, math, mathutils, random

cfg = json.load(open(sys.argv[sys.argv.index("--") + 1:][0]))


# Per-environment look: sky (horizon, zenith), sun (elevation deg, energy, colour),
# ground colour, tree count, tree colour, snow flag, hill colour.
ENVP = {
    "day":    dict(sky=((0.38, 0.60, 0.92), (0.04, 0.22, 0.85)), sun=(48, 4.0, (1.0, 0.98, 0.95)),
                   ground=(0.06, 0.14, 0.05), trees=80, tree=(0.03, 0.13, 0.03), snow=False,
                   hill=(0.06, 0.11, 0.07)),
    "forest": dict(sky=((0.50, 0.66, 0.86), (0.10, 0.34, 0.72)), sun=(42, 3.2, (1.0, 0.97, 0.90)),
                   ground=(0.03, 0.09, 0.02), trees=180, tree=(0.02, 0.10, 0.02), snow=False,
                   hill=(0.03, 0.09, 0.04)),
    "winter": dict(sky=((0.78, 0.84, 0.93), (0.34, 0.50, 0.74)), sun=(16, 2.6, (0.82, 0.88, 1.0)),
                   ground=(0.86, 0.89, 0.97), trees=110, tree=(0.06, 0.13, 0.07), snow=True,
                   hill=(0.80, 0.84, 0.93)),
    "desert": dict(sky=((0.82, 0.80, 0.66), (0.18, 0.42, 0.78)), sun=(62, 5.2, (1.0, 0.94, 0.78)),
                   ground=(0.58, 0.47, 0.30), trees=0, tree=(0, 0, 0), snow=False,
                   hill=(0.50, 0.40, 0.26)),
    "sunset": dict(sky=((0.98, 0.48, 0.18), (0.05, 0.06, 0.28)), sun=(6, 3.0, (1.0, 0.58, 0.32)),
                   ground=(0.18, 0.10, 0.05), trees=60, tree=(0.06, 0.04, 0.02), snow=False,
                   hill=(0.14, 0.08, 0.05)),
}


def build_environment(p):
    """Scatter trees + distant hills for depth/parallax, coloured for the environment."""
    random.seed(7)
    leaf = bpy.data.materials.new("Leaf"); leaf.use_nodes = True
    lb = leaf.node_tree.nodes.get("Principled BSDF")
    lb.inputs["Base Color"].default_value = (*p["tree"], 1)
    lb.inputs["Roughness"].default_value = 1.0
    snowmat = None
    if p["snow"]:
        snowmat = bpy.data.materials.new("Snowcap"); snowmat.use_nodes = True
        snowmat.node_tree.nodes.get("Principled BSDF").inputs["Base Color"].default_value = (0.95, 0.96, 1.0, 1)
    trunk = bpy.data.materials.new("Trunk"); trunk.use_nodes = True
    trunk.node_tree.nodes.get("Principled BSDF").inputs["Base Color"].default_value = (0.08, 0.05, 0.02, 1)
    for _ in range(p["trees"]):
        ang = random.uniform(0, 2 * math.pi); rad = random.uniform(14, 240)
        x = math.cos(ang) * rad; y = math.sin(ang) * rad; hgt = random.uniform(3, 9)
        bpy.ops.mesh.primitive_cylinder_add(radius=0.22, depth=hgt * 0.4, location=(x, y, hgt * 0.2))
        bpy.context.active_object.data.materials.append(trunk)
        bpy.ops.mesh.primitive_cone_add(radius1=hgt * 0.38, radius2=0, depth=hgt, location=(x, y, hgt * 0.4 + hgt * 0.5))
        cone = bpy.context.active_object
        cone.data.materials.append(snowmat if (snowmat and random.random() < 0.7) else leaf)
    hill = bpy.data.materials.new("Hill"); hill.use_nodes = True
    hill.node_tree.nodes.get("Principled BSDF").inputs["Base Color"].default_value = (*p["hill"], 1)
    for _ in range(16):
        ang = random.uniform(0, 2 * math.pi); rad = random.uniform(900, 1900)
        x = math.cos(ang) * rad; y = math.sin(ang) * rad
        hh = random.uniform(140, 360); rr = random.uniform(450, 850)
        bpy.ops.mesh.primitive_cone_add(radius1=rr, radius2=0, depth=hh, location=(x, y, hh * 0.5 - 40))
        bpy.context.active_object.data.materials.append(hill)

# ---- clean scene, or open a custom .blend environment (your own template) ----
custom_scene = cfg.get("sceneFile")
if custom_scene:
    bpy.ops.wm.open_mainfile(filepath=custom_scene)
else:
    bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
sc_type = cfg.get("scene", "day")
p = ENVP.get(sc_type, ENVP["day"])

scene.render.resolution_x = cfg["width"]
scene.render.resolution_y = cfg["height"]
scene.render.fps = cfg["fps"]
scene.render.engine = "BLENDER_EEVEE_NEXT"
scene.render.image_settings.file_format = "FFMPEG"
scene.render.ffmpeg.format = "MPEG4"
scene.render.ffmpeg.codec = "H264"
scene.render.ffmpeg.constant_rate_factor = "HIGH"
scene.render.filepath = cfg["out"]
scene.frame_start = 1
scene.frame_end = cfg["totalFrames"]
scene.view_settings.view_transform = "Standard"
try:
    scene.view_settings.look = "Medium High Contrast"
except Exception:
    pass
try:
    scene.eevee.use_raytracing = True
except Exception:
    pass

if not custom_scene:
    # ---- world / sky ----
    world = bpy.data.worlds.new("World"); scene.world = world
    world.use_nodes = True
    nt = world.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    out = nt.nodes.new("ShaderNodeOutputWorld")
    bg = nt.nodes.new("ShaderNodeBackground")
    nt.links.new(bg.outputs[0], out.inputs[0])
    if sc_type == "space":
        bg.inputs[0].default_value = (0.01, 0.01, 0.02, 1)
    else:
        # Controllable vertical gradient sky: view ray up-component -> horizon..zenith ramp.
        geo = nt.nodes.new("ShaderNodeNewGeometry")
        sep = nt.nodes.new("ShaderNodeSeparateXYZ")
        nt.links.new(geo.outputs["Incoming"], sep.inputs[0])
        mr = nt.nodes.new("ShaderNodeMapRange")
        mr.inputs["From Min"].default_value = -0.1
        mr.inputs["From Max"].default_value = 0.38
        nt.links.new(sep.outputs["Z"], mr.inputs["Value"])
        ramp = nt.nodes.new("ShaderNodeValToRGB")
        ramp.color_ramp.elements[0].color = (*p["sky"][0], 1)
        ramp.color_ramp.elements[1].color = (*p["sky"][1], 1)
        nt.links.new(mr.outputs[0], ramp.inputs[0])
        nt.links.new(ramp.outputs[0], bg.inputs[0])
    bg.inputs[1].default_value = 1.0

    # ---- sun ----
    sd = bpy.data.lights.new("Sun", "SUN"); so = bpy.data.objects.new("Sun", sd)
    scene.collection.objects.link(so)
    sd.angle = math.radians(1)
    if sc_type == "space":
        sd.energy = 2.0
        elev = 60
    else:
        sd.energy = p["sun"][1]
        sd.color = p["sun"][2]
        elev = p["sun"][0]
    so.rotation_euler = (math.radians(90 - elev), 0, math.radians(40))

    # ---- ground + environment ----
    if sc_type != "space":
        bpy.ops.mesh.primitive_plane_add(size=20000)
        gp = bpy.context.active_object
        gm = bpy.data.materials.new("Ground"); gm.use_nodes = True
        gnt = gm.node_tree
        gb = gnt.nodes.get("Principled BSDF")
        gb.inputs["Roughness"].default_value = 1.0
        tex = gnt.nodes.new("ShaderNodeTexNoise"); tex.inputs["Scale"].default_value = 1800.0
        ramp = gnt.nodes.new("ShaderNodeValToRGB")
        g = p["ground"]
        ramp.color_ramp.elements[0].color = (g[0] * 0.8, g[1] * 0.8, g[2] * 0.8, 1)
        ramp.color_ramp.elements[1].color = (min(1, g[0] * 1.2), min(1, g[1] * 1.2), min(1, g[2] * 1.2), 1)
        gnt.links.new(tex.outputs["Fac"], ramp.inputs[0])
        gnt.links.new(ramp.outputs[0], gb.inputs["Base Color"])
        bump = gnt.nodes.new("ShaderNodeBump"); bump.inputs["Strength"].default_value = 0.15
        gnt.links.new(tex.outputs["Fac"], bump.inputs["Height"])
        gnt.links.new(bump.outputs[0], gb.inputs["Normal"])
        gp.data.materials.append(gm)
        build_environment(p)

# ---- import rocket ----
before = set(bpy.data.objects)
bpy.ops.wm.obj_import(filepath=cfg["obj"], up_axis="Z", forward_axis="Y")
imported = [o for o in bpy.data.objects if o not in before]
# join into one object
for o in bpy.data.objects:
    o.select_set(False)
for o in imported:
    o.select_set(True)
bpy.context.view_layer.objects.active = imported[0]
bpy.ops.object.join()
rocket = bpy.context.view_layer.objects.active
rocket.name = "Rocket"

# material if none
if not rocket.data.materials:
    rm = bpy.data.materials.new("RocketBody"); rm.use_nodes = True
    rm.node_tree.nodes.get("Principled BSDF").inputs["Base Color"].default_value = (0.9, 0.9, 0.92, 1)
    rocket.data.materials.append(rm)
# realistic matte-painted finish on every rocket part (less plasticky than the raw export)
for ms in rocket.data.materials:
    if ms and ms.use_nodes:
        bsdf = ms.node_tree.nodes.get("Principled BSDF")
        if bsdf:
            bsdf.inputs["Roughness"].default_value = 0.5
            bsdf.inputs["Metallic"].default_value = 0.0

# center on axis, base at z=0, optional scale
scl = cfg.get("rocketScale", 1.0)
rocket.scale = (scl, scl, scl)
bpy.context.view_layer.update()
bb = [rocket.matrix_world @ mathutils.Vector(c) for c in rocket.bound_box]
minz = min(v.z for v in bb)
cx = sum(v.x for v in bb) / 8.0
cy = sum(v.y for v in bb) / 8.0
rocket.location = (-cx, -cy, -minz)
bpy.ops.object.transform_apply(location=True, rotation=False, scale=True)

# parent to an empty that we animate
empty = bpy.data.objects.new("RocketRoot", None); scene.collection.objects.link(empty)
rocket.parent = empty

body_r = cfg.get("bodyRadius", 0.0125) * scl
rk_len = cfg.get("length", 0.5) * scl

# launch pad + rod at the origin (where the rocket lifts off) for realism/scale
if not custom_scene and sc_type != "space":
    bpy.ops.mesh.primitive_cylinder_add(radius=1.4, depth=0.18, location=(0, 0, 0.09))
    pad = bpy.context.active_object
    pm = bpy.data.materials.new("Pad"); pm.use_nodes = True
    pbs = pm.node_tree.nodes.get("Principled BSDF")
    pbs.inputs["Base Color"].default_value = (0.22, 0.22, 0.24, 1)
    pbs.inputs["Roughness"].default_value = 0.95
    pad.data.materials.append(pm)
    bpy.ops.mesh.primitive_cylinder_add(radius=0.012, depth=rk_len * 1.6, location=(0.18, 0, rk_len * 0.8))
    rod = bpy.context.active_object
    rdm = bpy.data.materials.new("Rod"); rdm.use_nodes = True
    rbs = rdm.node_tree.nodes.get("Principled BSDF")
    rbs.inputs["Base Color"].default_value = (0.04, 0.04, 0.04, 1)
    rbs.inputs["Metallic"].default_value = 0.8
    rbs.inputs["Roughness"].default_value = 0.3
    rod.data.materials.append(rdm)

# exhaust flame: narrow emissive cone exactly at the nozzle (base z=0), tapering straight down
fl_depth = rk_len * 0.5
bpy.ops.mesh.primitive_cone_add(radius1=body_r * 0.8, radius2=0.0, depth=fl_depth, location=(0, 0, 0))
flame = bpy.context.active_object; flame.name = "Flame"
flame.rotation_euler = (math.radians(180), 0, 0)   # wide end at nozzle, taper points down (-Z)
flame.location = (0, 0, -fl_depth / 2.0)            # top of cone sits at the base (z=0)
fm = bpy.data.materials.new("Flame"); fm.use_nodes = True
fnt = fm.node_tree
for nn in list(fnt.nodes):
    fnt.nodes.remove(nn)
fout = fnt.nodes.new("ShaderNodeOutputMaterial")
fem = fnt.nodes.new("ShaderNodeEmission")
fem.inputs[0].default_value = (1.0, 0.6, 0.15, 1)
fem.inputs[1].default_value = 40.0
fnt.links.new(fem.outputs[0], fout.inputs[0])
flame.data.materials.append(fm)
flame.parent = empty

# parachute (flattened dome + shroud lines), grouped under a root we scale per-frame
chute_root = bpy.data.objects.new("ChuteRoot", None); scene.collection.objects.link(chute_root)
chute_root.parent = empty
canopy_r = rk_len * 1.3
top_z = rk_len + canopy_r * 0.7
bpy.ops.mesh.primitive_uv_sphere_add(radius=canopy_r, location=(0, 0, top_z))
canopy = bpy.context.active_object; canopy.name = "Canopy"; canopy.scale = (1, 1, 0.5)
cm = bpy.data.materials.new("Canopy"); cm.use_nodes = True
cb = cm.node_tree.nodes.get("Principled BSDF")
cb.inputs["Base Color"].default_value = (0.95, 0.25, 0.12, 1)
cb.inputs["Roughness"].default_value = 0.9
canopy.data.materials.append(cm)
canopy.parent = chute_root
for k in range(8):
    a = k / 8.0 * 2 * math.pi
    top = mathutils.Vector((math.cos(a) * canopy_r * 0.9, math.sin(a) * canopy_r * 0.9, top_z - canopy_r * 0.25))
    bot = mathutils.Vector((0, 0, rk_len))
    mid = (top + bot) / 2; d = (top - bot).length
    bpy.ops.mesh.primitive_cylinder_add(radius=canopy_r * 0.02, depth=d, location=mid)
    ln = bpy.context.active_object
    ln.rotation_euler = mathutils.Vector((0, 0, 1)).rotation_difference((top - bot).normalized()).to_euler()
    ln.data.materials.append(cm); ln.parent = chute_root
chute_root.scale = (0, 0, 0)

# ---- camera (auto-tracks the rocket) ----
cd = bpy.data.cameras.new("Cam"); cam = bpy.data.objects.new("Cam", cd)
scene.collection.objects.link(cam); scene.camera = cam
cd.lens = cfg.get("lens", 40)
tt = cam.constraints.new("TRACK_TO"); tt.target = empty
tt.track_axis = "TRACK_NEGATIVE_Z"; tt.up_axis = "UP_Y"

# ---- animate per frame ----
R = cfg["rocket"]; C = cfg["cameras"]
empty.rotation_mode = "QUATERNION"
for f in range(cfg["totalFrames"]):
    fr = f + 1
    x, y, z, dx, dy, dz = R[f]
    empty.location = (x, y, z)
    # Point the rocket's long axis (+Z) along the real nose-direction vector from the 6-DOF sim.
    empty.rotation_quaternion = mathutils.Vector((dx, dy, dz)).to_track_quat("Z", "Y")
    empty.keyframe_insert("location", frame=fr)
    empty.keyframe_insert("rotation_quaternion", frame=fr)
    cx2, cy2, cz2 = C[f]
    cam.location = (cx2, cy2, cz2)
    cam.keyframe_insert("location", frame=fr)
    if "lensSeq" in cfg:
        cam.data.lens = cfg["lensSeq"][f]
        cam.data.keyframe_insert("lens", frame=fr)
    fs = cfg["flame"][f] if "flame" in cfg else 0.0
    flame.scale = (fs, fs, fs)
    flame.keyframe_insert("scale", frame=fr)
    cs = cfg["chute"][f] if "chute" in cfg else 0.0
    chute_root.scale = (cs, cs, cs)
    chute_root.keyframe_insert("scale", frame=fr)

if cfg.get("interactive"):
    # Leave Blender open for live exploration (orbit/zoom/scrub/switch cameras).
    if cfg.get("blendPath"):
        bpy.ops.wm.save_as_mainfile(filepath=cfg["blendPath"])
    scene.frame_set(1)
    # try to view through the camera in any 3D viewport
    for area in bpy.context.screen.areas if bpy.context.screen else []:
        if area.type == "VIEW_3D":
            area.spaces[0].region_3d.view_perspective = "CAMERA"
            for sp in area.spaces:
                if sp.type == "VIEW_3D":
                    sp.shading.type = "RENDERED"
else:
    bpy.ops.render.render(animation=True)
