import bpy, json, sys, math, mathutils

cfg = json.load(open(sys.argv[sys.argv.index("--") + 1:][0]))

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
scene.view_settings.view_transform = "AgX"
scene.view_settings.look = "AgX - Punchy"
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
    sky = nt.nodes.new("ShaderNodeTexSky")
    sky.sky_type = "NISHITA"
    sky.sun_elevation = math.radians(5 if sc_type == "sunset" else 45)
    sky.sun_rotation = math.radians(40)
    nt.links.new(sky.outputs[0], bg.inputs[0])

# ---- sun ----
sd = bpy.data.lights.new("Sun", "SUN"); so = bpy.data.objects.new("Sun", sd)
scene.collection.objects.link(so)
sd.energy = 2.0 if sc_type == "space" else 3.0
sd.angle = math.radians(1)
elev = 5 if sc_type == "sunset" else 45
so.rotation_euler = (math.radians(90 - elev), 0, math.radians(40))

# ---- ground ----
if sc_type != "space":
    bpy.ops.mesh.primitive_plane_add(size=20000)
    gp = bpy.context.active_object
    gm = bpy.data.materials.new("Ground"); gm.use_nodes = True
    gb = gm.node_tree.nodes.get("Principled BSDF")
    gb.inputs["Base Color"].default_value = (0.20, 0.12, 0.06, 1) if sc_type == "sunset" else (0.07, 0.15, 0.05, 1)
    gb.inputs["Roughness"].default_value = 1.0
    gp.data.materials.append(gm)

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

# exhaust flame (emissive cone at the tail, scaled per-frame)
bpy.ops.mesh.primitive_cone_add(radius1=0.02 * scl, radius2=0.0, depth=0.35 * scl, location=(0, 0, 0))
flame = bpy.context.active_object; flame.name = "Flame"
flame.rotation_euler = (math.radians(180), 0, 0)   # taper points down (-Z)
flame.location = (0, 0, -0.18 * scl)
fm = bpy.data.materials.new("Flame"); fm.use_nodes = True
fnt = fm.node_tree
for nn in list(fnt.nodes):
    fnt.nodes.remove(nn)
fout = fnt.nodes.new("ShaderNodeOutputMaterial")
fem = fnt.nodes.new("ShaderNodeEmission")
fem.inputs[0].default_value = (1.0, 0.55, 0.12, 1)
fem.inputs[1].default_value = 30.0
fnt.links.new(fem.outputs[0], fout.inputs[0])
flame.data.materials.append(fm)
flame.parent = empty

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

bpy.ops.render.render(animation=True)
