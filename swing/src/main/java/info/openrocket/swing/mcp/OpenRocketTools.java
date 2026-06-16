package info.openrocket.swing.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import info.openrocket.core.database.motor.ThrustCurveMotorSet;
import info.openrocket.core.database.motor.ThrustCurveMotorSetDatabase;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.OpenRocketDocumentFactory;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.file.GeneralRocketLoader;
import info.openrocket.core.file.GeneralRocketSaver;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.startup.Application;
import info.openrocket.swing.gui.main.BasicFrame;

import javax.swing.SwingUtilities;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The full-CRUD tool layer that backs the MCP bridge. Every method here maps to an MCP tool
 * an AI agent can call. Reads traverse the live OpenRocket document model; mutations are applied
 * on the Swing EDT (with an undo position) so the open GUI updates in real time.
 *
 * The component get/set tools are deliberately generic: they introspect the bean-style
 * getters/setters of each {@link RocketComponent} so that <em>every</em> parameter a human can
 * edit is reachable, without hand-coding hundreds of component dialogs.
 */
public class OpenRocketTools {

	/** Thrown for expected, user-facing tool errors (returned to the agent as an error result). */
	static class ToolException extends Exception {
		ToolException(String message) {
			super(message);
		}
	}

	private static final String PKG = "info.openrocket.core.rocketcomponent.";

	/** Dispatch a tool call by name. Returns the structured result object. */
	public JsonObject call(String name, JsonObject args) throws Exception {
		switch (name) {
			case "list_open_designs":   return listOpenDesigns();
			case "new_design":          return newDesign(args);
			case "open_file":           return openFile(args);
			case "save_file":           return saveFile(args);
			case "get_component_tree":  return getComponentTree(args);
			case "get_component":       return getComponent(args);
			case "list_component_types":return listComponentTypes();
			case "add_component":       return addComponent(args);
			case "set_component":       return setComponent(args);
			case "delete_component":    return deleteComponent(args);
			case "list_simulations":    return listSimulations(args);
			case "add_simulation":      return addSimulation(args);
			case "run_simulation":      return runSimulation(args);
			case "get_simulation_results": return getSimulationResults(args);
			case "delete_simulation":   return deleteSimulation(args);
			case "search_motors":       return searchMotors(args);
			case "set_motor":           return setMotor(args);
			default:
				throw new ToolException("Unknown tool: " + name);
		}
	}

	// ------------------------------------------------------------------
	// Designs / documents
	// ------------------------------------------------------------------

	private JsonObject listOpenDesigns() {
		JsonArray arr = new JsonArray();
		List<BasicFrame> frames = BasicFrame.getAllFrames();
		for (int i = 0; i < frames.size(); i++) {
			BasicFrame f = frames.get(i);
			OpenRocketDocument doc = f.getDocument();
			JsonObject o = new JsonObject();
			o.addProperty("designIndex", i);
			o.addProperty("name", doc.getRocket().getName());
			o.addProperty("file", doc.getFile() == null ? null : doc.getFile().getAbsolutePath());
			o.addProperty("simulationCount", doc.getSimulationCount());
			o.addProperty("active", f.isActive());
			arr.add(o);
		}
		JsonObject result = new JsonObject();
		result.add("designs", arr);
		return result;
	}

	private JsonObject newDesign(JsonObject args) throws Exception {
		String name = optString(args, "name", null);
		AtomicReference<UUID> idRef = new AtomicReference<>();
		onEdt(() -> {
			OpenRocketDocument doc = OpenRocketDocumentFactory.createNewRocket();
			if (name != null) {
				doc.getRocket().setName(name);
			}
			BasicFrame frame = new BasicFrame(doc);
			frame.setVisible(true);
			idRef.set(doc.getRocket().getID());
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("designIndex", BasicFrame.getAllFrames().size() - 1);
		result.addProperty("rocketId", idRef.get().toString());
		return result;
	}

	private JsonObject openFile(JsonObject args) throws Exception {
		String path = requireString(args, "path");
		File file = new File(path);
		if (!file.exists()) {
			throw new ToolException("File does not exist: " + file.getAbsolutePath());
		}
		// Load headlessly (no modal warning/progress dialogs that would block an agent), then
		// attach the document to a new window on the EDT.
		final OpenRocketDocument doc = new GeneralRocketLoader(file).load();
		AtomicReference<String> nameRef = new AtomicReference<>();
		onEdt(() -> {
			BasicFrame frame = new BasicFrame(doc);
			frame.setVisible(true);
			nameRef.set(doc.getRocket().getName());
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("designIndex", BasicFrame.getAllFrames().size() - 1);
		result.addProperty("name", nameRef.get());
		return result;
	}

	private JsonObject saveFile(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		String path = optString(args, "path", null);
		File target = path != null ? new File(path) : doc.getFile();
		if (target == null) {
			throw new ToolException("No path given and the design has never been saved. Provide a 'path'.");
		}
		if (!target.getName().toLowerCase().endsWith(".ork")) {
			target = new File(target.getAbsolutePath() + ".ork");
		}
		final File dest = target;
		// Save headlessly (no modal dialogs) so an agent never blocks on a popup.
		new GeneralRocketSaver().save(dest, doc);
		onEdt(() -> {
			doc.setFile(dest);
			doc.setSaved(true);
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("file", dest.getAbsolutePath());
		return result;
	}

	// ------------------------------------------------------------------
	// Component tree (read)
	// ------------------------------------------------------------------

	private JsonObject getComponentTree(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		JsonObject result = new JsonObject();
		result.addProperty("designName", doc.getRocket().getName());
		result.add("root", treeNode(doc.getRocket()));
		return result;
	}

	private JsonObject treeNode(RocketComponent c) {
		JsonObject o = new JsonObject();
		o.addProperty("id", c.getID().toString());
		o.addProperty("type", c.getClass().getSimpleName());
		o.addProperty("name", c.getName());
		if (c instanceof MotorMount && ((MotorMount) c).isMotorMount()) {
			o.addProperty("motorMount", true);
		}
		if (c.getChildCount() > 0) {
			JsonArray children = new JsonArray();
			for (RocketComponent child : c.getChildren()) {
				children.add(treeNode(child));
			}
			o.add("children", children);
		}
		return o;
	}

	private JsonObject getComponent(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		RocketComponent c = findComponent(doc, requireString(args, "id"));
		JsonObject o = new JsonObject();
		o.addProperty("id", c.getID().toString());
		o.addProperty("type", c.getClass().getSimpleName());
		o.addProperty("name", c.getName());
		RocketComponent parent = c.getParent();
		o.addProperty("parentId", parent == null ? null : parent.getID().toString());
		o.add("properties", readProperties(c));
		return o;
	}

	/** Introspect every readable scalar bean property of the component. */
	private JsonObject readProperties(RocketComponent c) {
		JsonObject props = new JsonObject();
		for (Method m : c.getClass().getMethods()) {
			if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())) {
				continue;
			}
			String name = beanProperty(m);
			if (name == null || "class".equals(name) || "ID".equals(name)) {
				continue;
			}
			Class<?> rt = m.getReturnType();
			if (!isScalar(rt)) {
				continue;
			}
			try {
				Object value = m.invoke(c);
				props.add(decapitalize(name), toJson(value));
			} catch (Throwable ignore) {
				// Some getters require state we don't have; just skip them.
			}
		}
		return props;
	}

	// ------------------------------------------------------------------
	// Component CRUD (create / update / delete)
	// ------------------------------------------------------------------

	private JsonObject listComponentTypes() {
		String[] types = {
				"NoseCone", "BodyTube", "Transition", "TrapezoidFinSet", "EllipticalFinSet",
				"FreeformFinSet", "TubeFinSet", "LaunchLug", "RailButton", "Parachute", "Streamer",
				"ShockCord", "MassComponent", "CenteringRing", "Bulkhead", "EngineBlock", "InnerTube",
				"TubeCoupler", "AxialStage", "ParallelStage", "PodSet"
		};
		JsonArray arr = new JsonArray();
		for (String t : types) {
			arr.add(t);
		}
		JsonObject result = new JsonObject();
		result.add("types", arr);
		result.addProperty("note", "Components must be added under a compatible parent; "
				+ "add_component reports an error if the parent does not accept the type.");
		return result;
	}

	private JsonObject addComponent(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		String type = requireString(args, "type");
		RocketComponent parent = findComponent(doc, requireString(args, "parentId"));
		String name = optString(args, "name", null);

		Class<?> clazz;
		try {
			clazz = Class.forName(PKG + type);
		} catch (ClassNotFoundException e) {
			throw new ToolException("Unknown component type: " + type
					+ ". Use list_component_types to see valid names.");
		}
		if (!RocketComponent.class.isAssignableFrom(clazz)) {
			throw new ToolException(type + " is not a rocket component type.");
		}

		AtomicReference<UUID> idRef = new AtomicReference<>();
		AtomicReference<Exception> errRef = new AtomicReference<>();
		onEdt(() -> {
			try {
				RocketComponent child = (RocketComponent) clazz.getDeclaredConstructor().newInstance();
				if (name != null) {
					child.setName(name);
				}
				doc.addUndoPosition("Add " + child.getComponentName());
				parent.addChild(child, parent.getChildCount());
				idRef.set(child.getID());
			} catch (InvocationTargetException e) {
				errRef.set(unwrap(e));
			} catch (Exception e) {
				errRef.set(e);
			}
			return null;
		});
		if (errRef.get() != null) {
			throw new ToolException("Could not add " + type + " under "
					+ parent.getName() + ": " + errRef.get().getMessage());
		}
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("id", idRef.get().toString());
		result.addProperty("type", type);
		return result;
	}

	private JsonObject setComponent(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		RocketComponent c = findComponent(doc, requireString(args, "id"));
		if (!args.has("properties") || !args.get("properties").isJsonObject()) {
			throw new ToolException("Expected an object 'properties' of name -> value pairs.");
		}
		JsonObject properties = args.getAsJsonObject("properties");

		JsonObject applied = new JsonObject();
		JsonObject failed = new JsonObject();
		onEdt(() -> {
			doc.addUndoPosition("Edit " + c.getComponentName());
			for (String key : properties.keySet()) {
				try {
					Object coerced = applyProperty(c, key, properties.get(key));
					applied.add(key, toJson(coerced));
				} catch (Exception e) {
					failed.addProperty(key, e.getMessage());
				}
			}
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("id", c.getID().toString());
		result.add("applied", applied);
		if (failed.size() > 0) {
			result.add("failed", failed);
		}
		return result;
	}

	/** Find setX(value) for a property and invoke it with a coerced value. Returns the coerced value. */
	private Object applyProperty(RocketComponent c, String key, JsonElement value) throws Exception {
		String setter = "set" + capitalize(key);
		Method match = null;
		for (Method m : c.getClass().getMethods()) {
			if (m.getName().equals(setter) && m.getParameterCount() == 1
					&& isScalar(m.getParameterTypes()[0])) {
				match = m;
				break;
			}
		}
		if (match == null) {
			throw new Exception("no settable scalar property '" + key + "' on "
					+ c.getClass().getSimpleName());
		}
		Object coerced = coerce(match.getParameterTypes()[0], value);
		try {
			match.invoke(c, coerced);
		} catch (InvocationTargetException e) {
			throw unwrap(e);
		}
		return coerced;
	}

	private JsonObject deleteComponent(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		RocketComponent c = findComponent(doc, requireString(args, "id"));
		RocketComponent parent = c.getParent();
		if (parent == null) {
			throw new ToolException("Cannot delete the rocket root component.");
		}
		onEdt(() -> {
			doc.addUndoPosition("Delete " + c.getComponentName());
			parent.removeChild(c);
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("deletedId", c.getID().toString());
		return result;
	}

	// ------------------------------------------------------------------
	// Simulations
	// ------------------------------------------------------------------

	private JsonObject listSimulations(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		JsonArray arr = new JsonArray();
		List<Simulation> sims = doc.getSimulations();
		for (int i = 0; i < sims.size(); i++) {
			Simulation s = sims.get(i);
			JsonObject o = new JsonObject();
			o.addProperty("index", i);
			o.addProperty("name", s.getName());
			o.addProperty("status", s.getStatus().toString());
			arr.add(o);
		}
		JsonObject result = new JsonObject();
		result.add("simulations", arr);
		return result;
	}

	private JsonObject addSimulation(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		String name = optString(args, "name", null);
		AtomicReference<Integer> indexRef = new AtomicReference<>();
		onEdt(() -> {
			Simulation sim = new Simulation(doc, doc.getRocket());
			if (name != null) {
				sim.setName(name);
			}
			doc.addSimulation(sim);
			indexRef.set(doc.getSimulationCount() - 1);
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("index", indexRef.get());
		return result;
	}

	private JsonObject runSimulation(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		Simulation sim = resolveSimulation(doc, args);
		try {
			sim.simulate();
		} catch (Exception e) {
			throw new ToolException("Simulation failed: " + e.getMessage());
		}
		return simulationResults(sim);
	}

	private JsonObject getSimulationResults(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		Simulation sim = resolveSimulation(doc, args);
		return simulationResults(sim);
	}

	private JsonObject simulationResults(Simulation sim) {
		JsonObject result = new JsonObject();
		result.addProperty("name", sim.getName());
		result.addProperty("status", sim.getStatus().toString());
		try {
			FlightConfiguration cfg = sim.getActiveConfiguration();
			result.addProperty("flightConfigurationId", sim.getFlightConfigurationId().toString());
			result.addProperty("activeMotorCount", cfg.getActiveMotors().size());
		} catch (Exception ignore) {
			// configuration may be unavailable in edge cases
		}
		FlightData data = sim.getSimulatedData();
		if (data == null) {
			result.addProperty("hasData", false);
			result.addProperty("note", "No flight data yet. Run the simulation first.");
			return result;
		}
		result.addProperty("hasData", true);
		result.addProperty("units", "SI: m, m/s, m/s^2, s");
		result.addProperty("maxAltitude", data.getMaxAltitude());
		result.addProperty("maxVelocity", data.getMaxVelocity());
		result.addProperty("maxAcceleration", data.getMaxAcceleration());
		result.addProperty("maxMachNumber", data.getMaxMachNumber());
		result.addProperty("timeToApogee", data.getTimeToApogee());
		result.addProperty("flightTime", data.getFlightTime());
		result.addProperty("groundHitVelocity", data.getGroundHitVelocity());
		return result;
	}

	private JsonObject deleteSimulation(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		int index = requireInt(args, "index");
		if (index < 0 || index >= doc.getSimulationCount()) {
			throw new ToolException("Simulation index out of range: " + index);
		}
		onEdt(() -> {
			doc.removeSimulation(index);
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		return result;
	}

	private Simulation resolveSimulation(OpenRocketDocument doc, JsonObject args) throws ToolException {
		if (doc.getSimulationCount() == 0) {
			throw new ToolException("This design has no simulations. Create one with add_simulation.");
		}
		if (args.has("name") && !args.get("name").isJsonNull()) {
			String name = args.get("name").getAsString();
			for (Simulation s : doc.getSimulations()) {
				if (s.getName().equals(name)) {
					return s;
				}
			}
			throw new ToolException("No simulation named '" + name + "'.");
		}
		int index = args.has("index") ? args.get("index").getAsInt() : 0;
		if (index < 0 || index >= doc.getSimulationCount()) {
			throw new ToolException("Simulation index out of range: " + index);
		}
		return doc.getSimulation(index);
	}

	// ------------------------------------------------------------------
	// Motors
	// ------------------------------------------------------------------

	private JsonObject searchMotors(JsonObject args) throws ToolException {
		String query = optString(args, "query", "").toLowerCase();
		String manufacturer = optString(args, "manufacturer", "").toLowerCase();
		int limit = args.has("limit") ? args.get("limit").getAsInt() : 25;

		ThrustCurveMotorSetDatabase db = Application.getThrustCurveMotorSetDatabase();
		if (db == null) {
			throw new ToolException("Motor database is not available yet.");
		}
		JsonArray arr = new JsonArray();
		outer:
		for (ThrustCurveMotorSet set : db.getMotorSets()) {
			for (ThrustCurveMotor m : set.getMotors()) {
				String desig = m.getDesignation().toLowerCase();
				String manu = m.getManufacturer().getDisplayName().toLowerCase();
				if (!query.isEmpty() && !desig.contains(query) && !m.getCommonName().toLowerCase().contains(query)) {
					continue;
				}
				if (!manufacturer.isEmpty() && !manu.contains(manufacturer)) {
					continue;
				}
				JsonObject o = new JsonObject();
				o.addProperty("designation", m.getDesignation());
				o.addProperty("commonName", m.getCommonName());
				o.addProperty("manufacturer", m.getManufacturer().getDisplayName());
				o.addProperty("diameter", m.getDiameter());
				o.addProperty("length", m.getLength());
				o.addProperty("digest", m.getDigest());
				arr.add(o);
				if (arr.size() >= limit) {
					break outer;
				}
			}
		}
		JsonObject result = new JsonObject();
		result.add("motors", arr);
		result.addProperty("count", arr.size());
		return result;
	}

	private JsonObject setMotor(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		RocketComponent c = findComponent(doc, requireString(args, "mountId"));
		if (!(c instanceof MotorMount)) {
			throw new ToolException(c.getName() + " is not a motor mount (e.g. a BodyTube or InnerTube).");
		}
		MotorMount mount = (MotorMount) c;
		String designation = optString(args, "designation", null);
		String digest = optString(args, "digest", null);
		if (designation == null && digest == null) {
			throw new ToolException("Provide a motor 'designation' or 'digest' (see search_motors).");
		}

		ThrustCurveMotorSetDatabase db = Application.getThrustCurveMotorSetDatabase();
		ThrustCurveMotor motor = null;
		for (ThrustCurveMotorSet set : db.getMotorSets()) {
			for (ThrustCurveMotor m : set.getMotors()) {
				if ((digest != null && digest.equals(m.getDigest()))
						|| (designation != null && designation.equalsIgnoreCase(m.getDesignation()))) {
					motor = m;
					break;
				}
			}
			if (motor != null) {
				break;
			}
		}
		if (motor == null) {
			throw new ToolException("No motor found matching the given designation/digest.");
		}

		final ThrustCurveMotor chosen = motor;
		final Double delay = args.has("ejectionDelay") ? args.get("ejectionDelay").getAsDouble() : null;
		AtomicReference<String> configRef = new AtomicReference<>();
		onEdt(() -> {
			Rocket rocket = doc.getRocket();
			FlightConfigurationId fcid = rocket.getSelectedConfiguration().getId();
			// A brand-new rocket only has the special default/empty configuration, which cannot
			// hold an active motor. Create and select a real configuration so the rocket can fly.
			if (!fcid.isValid() || fcid.equals(FlightConfigurationId.DEFAULT_VALUE_FCID)) {
				FlightConfiguration cfg = new FlightConfiguration(rocket, null);
				rocket.setFlightConfiguration(cfg.getId(), cfg);
				rocket.setSelectedConfiguration(cfg.getId());
				fcid = cfg.getId();
			}
			doc.addUndoPosition("Set motor");
			mount.setMotorMount(true);
			MotorConfiguration mc = new MotorConfiguration(mount, fcid);
			mc.setMotor(chosen);
			if (delay != null) {
				mc.setEjectionDelay(delay);
			}
			mount.setMotorConfig(mc, fcid);
			// Activate all stages and rescan motors so the configuration actually sees the
			// motor we just assigned (otherwise the simulation reports "No motors defined").
			rocket.getFlightConfiguration(fcid).setAllStages();
			configRef.set(fcid.toString());
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("mountId", c.getID().toString());
		result.addProperty("motor", chosen.getDesignation());
		result.addProperty("flightConfigurationId", configRef.get());
		return result;
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private BasicFrame activeFrame(JsonObject args) throws ToolException {
		List<BasicFrame> frames = BasicFrame.getAllFrames();
		if (frames.isEmpty()) {
			throw new ToolException("No rocket design is currently open in OpenRocket.");
		}
		if (args.has("designIndex") && !args.get("designIndex").isJsonNull()) {
			int i = args.get("designIndex").getAsInt();
			if (i < 0 || i >= frames.size()) {
				throw new ToolException("designIndex out of range: " + i);
			}
			return frames.get(i);
		}
		for (BasicFrame f : frames) {
			if (f.isActive()) {
				return f;
			}
		}
		if (BasicFrame.lastFrameInstance != null && frames.contains(BasicFrame.lastFrameInstance)) {
			return BasicFrame.lastFrameInstance;
		}
		return frames.get(frames.size() - 1);
	}

	private RocketComponent findComponent(OpenRocketDocument doc, String id) throws ToolException {
		UUID uuid;
		try {
			uuid = UUID.fromString(id);
		} catch (IllegalArgumentException e) {
			throw new ToolException("Invalid component id (expected a UUID): " + id);
		}
		RocketComponent c = doc.getRocket().findComponent(uuid);
		if (c == null) {
			throw new ToolException("No component with id " + id
					+ " in this design. Use get_component_tree to list ids.");
		}
		return c;
	}

	/** Run a body of work on the EDT and wait for it, surfacing any exception. */
	private void onEdt(Callable<Void> work) throws Exception {
		if (SwingUtilities.isEventDispatchThread()) {
			work.call();
			return;
		}
		AtomicReference<Exception> err = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> {
			try {
				work.call();
			} catch (Exception e) {
				err.set(e);
			}
		});
		if (err.get() != null) {
			throw err.get();
		}
	}

	private static boolean isScalar(Class<?> t) {
		return t == String.class || t == double.class || t == int.class || t == boolean.class
				|| t == long.class || t == float.class || t == short.class
				|| t == Double.class || t == Integer.class || t == Boolean.class
				|| t == Long.class || t == Float.class || t == Short.class
				|| Enum.class.isAssignableFrom(t);
	}

	private static Object coerce(Class<?> t, JsonElement v) {
		if (t == String.class) {
			return v.getAsString();
		}
		if (t == double.class || t == Double.class) {
			return v.getAsDouble();
		}
		if (t == float.class || t == Float.class) {
			return (float) v.getAsDouble();
		}
		if (t == int.class || t == Integer.class) {
			return v.getAsInt();
		}
		if (t == long.class || t == Long.class) {
			return v.getAsLong();
		}
		if (t == short.class || t == Short.class) {
			return (short) v.getAsInt();
		}
		if (t == boolean.class || t == Boolean.class) {
			return v.getAsBoolean();
		}
		if (Enum.class.isAssignableFrom(t)) {
			String name = v.getAsString();
			@SuppressWarnings({ "unchecked", "rawtypes" })
			Object e = enumValue((Class<? extends Enum>) t, name);
			return e;
		}
		throw new IllegalArgumentException("unsupported parameter type " + t.getSimpleName());
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Enum enumValue(Class<? extends Enum> t, String name) {
		try {
			return Enum.valueOf(t, name);
		} catch (IllegalArgumentException e) {
			return Enum.valueOf(t, name.toUpperCase());
		}
	}

	private static JsonElement toJson(Object value) {
		if (value == null) {
			return JsonNull.INSTANCE;
		}
		if (value instanceof Number) {
			return new JsonPrimitive((Number) value);
		}
		if (value instanceof Boolean) {
			return new JsonPrimitive((Boolean) value);
		}
		if (value instanceof Enum) {
			return new JsonPrimitive(((Enum<?>) value).name());
		}
		return new JsonPrimitive(value.toString());
	}

	private static String beanProperty(Method m) {
		String n = m.getName();
		if (n.startsWith("get") && n.length() > 3) {
			return n.substring(3);
		}
		if (n.startsWith("is") && n.length() > 2 && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
			return n.substring(2);
		}
		return null;
	}

	private static String capitalize(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String decapitalize(String s) {
		return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
	}

	private static Exception unwrap(InvocationTargetException e) {
		Throwable cause = e.getCause();
		return (cause instanceof Exception) ? (Exception) cause : e;
	}

	private static String optString(JsonObject args, String key, String def) {
		return (args.has(key) && !args.get(key).isJsonNull()) ? args.get(key).getAsString() : def;
	}

	private static String requireString(JsonObject args, String key) throws ToolException {
		if (!args.has(key) || args.get(key).isJsonNull()) {
			throw new ToolException("Missing required argument: " + key);
		}
		return args.get(key).getAsString();
	}

	private static int requireInt(JsonObject args, String key) throws ToolException {
		if (!args.has(key) || args.get(key).isJsonNull()) {
			throw new ToolException("Missing required argument: " + key);
		}
		return args.get(key).getAsInt();
	}

	// ------------------------------------------------------------------
	// Tool schema definitions (advertised via tools/list)
	// ------------------------------------------------------------------

	static JsonArray toolDefinitions() {
		JsonArray tools = new JsonArray();
		tools.add(tool("list_open_designs",
				"List the rocket designs currently open in OpenRocket, with their designIndex.",
				"{\"type\":\"object\",\"properties\":{}}"));
		tools.add(tool("new_design",
				"Create a brand-new empty rocket design and open it in a new window.",
				"{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}"));
		tools.add(tool("open_file",
				"Open an existing .ork rocket file.",
				"{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"));
		tools.add(tool("save_file",
				"Save the active design. Optionally provide a path to save-as.",
				"{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"designIndex\":{\"type\":\"integer\"}}}"));
		tools.add(tool("get_component_tree",
				"Get the full component tree of the active design (ids, types, names, nesting).",
				"{\"type\":\"object\",\"properties\":{\"designIndex\":{\"type\":\"integer\"}}}"));
		tools.add(tool("get_component",
				"Get every readable parameter of a single component by id.",
				"{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"id\"]}"));
		tools.add(tool("list_component_types",
				"List the component type names that can be added with add_component.",
				"{\"type\":\"object\",\"properties\":{}}"));
		tools.add(tool("add_component",
				"Add a new component of the given type under a parent component.",
				"{\"type\":\"object\",\"properties\":{\"parentId\":{\"type\":\"string\"},\"type\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"parentId\",\"type\"]}"));
		tools.add(tool("set_component",
				"Set one or more parameters of a component. 'properties' is a map of parameter name to value (e.g. {\"length\":0.3,\"aftRadius\":0.025}). Use get_component to discover parameter names.",
				"{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"properties\":{\"type\":\"object\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"id\",\"properties\"]}"));
		tools.add(tool("delete_component",
				"Delete a component (and its children) by id.",
				"{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"id\"]}"));
		tools.add(tool("list_simulations",
				"List the simulations in the active design.",
				"{\"type\":\"object\",\"properties\":{\"designIndex\":{\"type\":\"integer\"}}}"));
		tools.add(tool("add_simulation",
				"Create a new simulation for the active design.",
				"{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"designIndex\":{\"type\":\"integer\"}}}"));
		tools.add(tool("run_simulation",
				"Run a simulation (by index or name) and return the flight summary.",
				"{\"type\":\"object\",\"properties\":{\"index\":{\"type\":\"integer\"},\"name\":{\"type\":\"string\"},\"designIndex\":{\"type\":\"integer\"}}}"));
		tools.add(tool("get_simulation_results",
				"Get the last computed flight summary for a simulation (by index or name).",
				"{\"type\":\"object\",\"properties\":{\"index\":{\"type\":\"integer\"},\"name\":{\"type\":\"string\"},\"designIndex\":{\"type\":\"integer\"}}}"));
		tools.add(tool("delete_simulation",
				"Delete a simulation by index.",
				"{\"type\":\"object\",\"properties\":{\"index\":{\"type\":\"integer\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"index\"]}"));
		tools.add(tool("search_motors",
				"Search the motor (thrust-curve) database by designation/common name and/or manufacturer.",
				"{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"manufacturer\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"}}}"));
		tools.add(tool("set_motor",
				"Assign a motor to a motor mount component (by designation or digest from search_motors).",
				"{\"type\":\"object\",\"properties\":{\"mountId\":{\"type\":\"string\"},\"designation\":{\"type\":\"string\"},\"digest\":{\"type\":\"string\"},\"ejectionDelay\":{\"type\":\"number\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"mountId\"]}"));
		return tools;
	}

	private static JsonObject tool(String name, String description, String inputSchemaJson) {
		JsonObject o = new JsonObject();
		o.addProperty("name", name);
		o.addProperty("description", description);
		o.add("inputSchema", JsonParser.parseString(inputSchemaJson).getAsJsonObject());
		return o;
	}
}
