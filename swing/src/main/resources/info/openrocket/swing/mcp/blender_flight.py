import bpy, json, sys, math, mathutils, random

cfg = json.load(open(sys.argv[sys.argv.index("--") + 1:][0]))


def build_environment(sc_type):
    """Scatter trees + distant hills for depth/parallax."""
    random.seed(7)
    leaf = bpy.data.materials.new("Leaf"); leaf.use_nodes = True
    lb = leaf.node_tree.nodes.get("Principled BSDF")
    lb.inputs["Base Color"].default_value = (0.05, 0.04, 0.02, 1) if sc_type == "sunset" else (0.03, 0.13, 0.03, 1)
    lb.inputs["Roughness"].default_value = 1.0
    trunk = bpy.data.materials.new("Trunk"); trunk.use_nodes = True
    trunk.node_tree.nodes.get("Principled BSDF").inputs["Base Color"].default_value = (0.08, 0.05, 0.02, 1)
    for _ in range(70):
        ang = random.uniform(0, 2 * math.pi); rad = random.uniform(14, 240)
        x = math.cos(ang) * rad; y = math.sin(ang) * rad; hgt = random.uniform(3, 9)
        bpy.ops.mesh.primitive_cylinder_add(radius=0.22, depth=hgt * 0.4, location=(x, y, hgt * 0.2))
        bpy.context.active_object.data.materials.append(trunk)
        bpy.ops.mesh.primitive_cone_add(radius1=hgt * 0.38, radius2=0, depth=hgt, location=(x, y, hgt * 0.4 + hgt * 0.5))
        bpy.context.active_object.data.materials.append(leaf)
    hill = bpy.data.materials.new("Hill"); hill.use_nodes = True
    hill.node_tree.nodes.get("Principled BSDF").inputs["Base Color"].default_value = \
        (0.12, 0.08, 0.05, 1) if sc_type == "sunset" else (0.06, 0.11, 0.07, 1)
    for _ in range(16):
        ang = random.uniform(0, 2 * math.pi); rad = random.uniform(900, 1900)
        x = math.cos(ang) * rad; y = math.sin(ang) * rad
        hh = random.uniform(140, 360); rr = random.uniform(450, 850)
        bpy.ops.mesh.primitive_cone_add(radius1=rr, radius2=0, depth=hh, location=(x, y, hh * 0.5 - 40))
        bpy.context.active_object.data.materials.append(hill)

# ---- clean scene ----
bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
sc_type = cfg.get("scene", "day")

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
    bg.inputs[1].default_value = 1.0
else:
    # Custom vertical gradient sky (fully controllable, vivid, never blows out): map the view
    # ray's up-component to a horizon->zenith colour ramp.
    geo = nt.nodes.new("ShaderNodeNewGeometry")
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(geo.outputs["Incoming"], sep.inputs[0])
    mr = nt.nodes.new("ShaderNodeMapRange")
    mr.inputs["From Min"].default_value = -0.1
    mr.inputs["From Max"].default_value = 0.38
    nt.links.new(sep.outputs["Z"], mr.inputs["Value"])
    ramp = nt.nodes.new("ShaderNodeValToRGB")
    if sc_type == "sunset":
        ramp.color_ramp.elements[0].color = (0.98, 0.48, 0.18, 1)   # horizon
        ramp.color_ramp.elements[1].color = (0.05, 0.06, 0.28, 1)   # zenith
    else:
        ramp.color_ramp.elements[0].color = (0.38, 0.60, 0.92, 1)   # horizon
        ramp.color_ramp.elements[1].color = (0.04, 0.22, 0.85, 1)   # deep blue zenith
    nt.links.new(mr.outputs[0], ramp.inputs[0])
    nt.links.new(ramp.outputs[0], bg.inputs[0])
    bg.inputs[1].default_value = 1.0

# ---- sun ----
sd = bpy.data.lights.new("Sun", "SUN"); so = bpy.data.objects.new("Sun", sd)
scene.collection.objects.link(so)
sd.energy = 2.0 if sc_type == "space" else 4.0
sd.angle = math.radians(1)
elev = 5 if sc_type == "sunset" else 45
so.rotation_euler = (math.radians(90 - elev), 0, math.radians(40))

# ---- ground ----
if sc_type != "space":
    bpy.ops.mesh.primitive_plane_add(size=20000)
    gp = bpy.context.active_object
    gm = bpy.data.materials.new("Ground"); gm.use_nodes = True
    gnt = gm.node_tree
    gb = gnt.nodes.get("Principled BSDF")
    gb.inputs["Roughness"].default_value = 1.0
    tex = gnt.nodes.new("ShaderNodeTexNoise"); tex.inputs["Scale"].default_value = 1800.0
    ramp = gnt.nodes.new("ShaderNodeValToRGB")
    if sc_type == "sunset":
        ramp.color_ramp.elements[0].color = (0.14, 0.08, 0.03, 1)
        ramp.color_ramp.elements[1].color = (0.30, 0.18, 0.09, 1)
    else:
        ramp.color_ramp.elements[0].color = (0.03, 0.10, 0.03, 1)
        ramp.color_ramp.elements[1].color = (0.11, 0.24, 0.08, 1)
    gnt.links.new(tex.outputs["Fac"], ramp.inputs[0])
    gnt.links.new(ramp.outputs[0], gb.inputs["Base Color"])
    bump = gnt.nodes.new("ShaderNodeBump"); bump.inputs["Strength"].default_value = 0.2
    gnt.links.new(tex.outputs["Fac"], bump.inputs["Height"])
    gnt.links.new(bump.outputs[0], gb.inputs["Normal"])
    gp.data.materials.append(gm)
    build_environment(sc_type)

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
    rb = rm.node_tree.nodes.get("Principled BSDF")
    rb.inputs["Base Color"].default_value = (0.85, 0.86, 0.9, 1)
    rb.inputs["Roughness"].default_value = 0.22
    rb.inputs["Metallic"].default_value = 0.7
    rocket.data.materials.append(rm)

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

# exhaust flame: narrow emissive cone exactly at the nozzle (base z=0), tapering straight down
body_r = cfg.get("bodyRadius", 0.0125) * scl
rk_len = cfg.get("length", 0.5) * scl
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
for f in range(cfg["totalFrames"]):
    fr = f + 1
    x, y, z, tilt = R[f]
    empty.location = (x, y, z)
    empty.rotation_euler = (0, math.radians(-tilt), 0)
    empty.keyframe_insert("location", frame=fr)
    empty.keyframe_insert("rotation_euler", frame=fr)
    cx2, cy2, cz2 = C[f]
    cam.location = (cx2, cy2, cz2)
    cam.keyframe_insert("location", frame=fr)
    fs = cfg["flame"][f] if "flame" in cfg else 0.0
    flame.scale = (fs, fs, fs)
    flame.keyframe_insert("scale", frame=fr)
    cs = cfg["chute"][f] if "chute" in cfg else 0.0
    chute_root.scale = (cs, cs, cs)
    chute_root.keyframe_insert("scale", frame=fr)

bpy.ops.render.render(animation=True)
