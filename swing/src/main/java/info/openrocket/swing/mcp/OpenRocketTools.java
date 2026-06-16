package info.openrocket.swing.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import info.openrocket.core.aerodynamics.AerodynamicCalculator;
import info.openrocket.core.aerodynamics.AerodynamicForces;
import info.openrocket.core.aerodynamics.BarrowmanCalculator;
import info.openrocket.core.aerodynamics.FlightConditions;
import info.openrocket.core.appearance.Appearance;
import info.openrocket.core.database.Database;
import info.openrocket.core.database.Databases;
import info.openrocket.core.database.motor.ThrustCurveMotorSet;
import info.openrocket.core.database.motor.ThrustCurveMotorSetDatabase;
import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.OpenRocketDocumentFactory;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.document.StorageOptions;
import info.openrocket.core.file.GeneralRocketLoader;
import info.openrocket.core.file.GeneralRocketSaver;
import info.openrocket.core.file.svg.export.SVGExportOptions;
import info.openrocket.core.file.wavefrontobj.export.OBJExportOptions;
import info.openrocket.core.file.wavefrontobj.export.OBJExporterFactory;
import info.openrocket.core.logging.Warning;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.masscalc.CMAnalysisEntry;
import info.openrocket.core.masscalc.MassCalculator;
import info.openrocket.core.material.Material;
import info.openrocket.core.motor.IgnitionEvent;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.preset.ComponentPreset;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.ClusterConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.DeploymentConfiguration;
import info.openrocket.core.rocketcomponent.FreeformFinSet;
import info.openrocket.core.rocketcomponent.InnerTube;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.StageSeparationConfiguration;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.SymmetricComponent;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.simulation.customexpression.CustomExpression;
import info.openrocket.core.simulation.extension.SimulationExtension;
import info.openrocket.core.startup.Application;
import info.openrocket.swing.gui.export.SVGRocketPartsExporter;
import info.openrocket.core.util.Coordinate;
import info.openrocket.core.util.CoordinateIF;
import info.openrocket.core.util.MathUtil;
import info.openrocket.core.util.ORColor;
import info.openrocket.swing.gui.main.BasicFrame;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
			case "get_stability":       return getStability(args);
			case "list_component_types":return listComponentTypes();
			case "add_component":       return addComponent(args);
			case "set_component":       return setComponent(args);
			case "delete_component":    return deleteComponent(args);
			case "list_materials":      return listMaterials(args);
			case "set_material":        return setMaterial(args);
			case "search_presets":      return searchPresets(args);
			case "apply_preset":        return applyPreset(args);
			case "set_cluster":         return setCluster(args);
			case "set_ignition":        return setIgnition(args);
			case "set_separation":      return setSeparation(args);
			case "set_appearance":      return setAppearance(args);
			case "set_fin_points":      return setFinPoints(args);
			case "add_custom_expression": return addCustomExpression(args);
			case "component_mass_analysis": return componentMassAnalysis(args);
			case "component_aero_analysis": return componentAeroAnalysis(args);
			case "set_deployment":      return setDeployment(args);
			case "move_component":      return moveComponent(args);
			case "duplicate_component": return duplicateComponent(args);
			case "list_flight_configs": return listFlightConfigs(args);
			case "add_flight_config":   return addFlightConfig(args);
			case "select_flight_config":return selectFlightConfig(args);
			case "list_simulations":    return listSimulations(args);
			case "add_simulation":      return addSimulation(args);
			case "run_simulation":      return runSimulation(args);
			case "get_simulation_results": return getSimulationResults(args);
			case "get_flight_data":     return getFlightData(args);
			case "export_design":       return exportDesign(args);
			case "set_simulation_options": return setSimulationOptions(args);
			case "add_simulation_extension": return addSimulationExtension(args);
			case "optimize_parameter":  return optimizeParameter(args);
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

	/**
	 * Compute the key design metrics a human watches: center of gravity, center of pressure,
	 * stability margin (calibers), reference diameter, length and mass. This is what lets an
	 * agent design a stable rocket. Mirrors the calculation OpenRocket shows in the figure.
	 */
	private JsonObject getStability(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		return onEdtCompute(() -> {
			Rocket rocket = doc.getRocket();
			FlightConfiguration config = rocket.getSelectedConfiguration();

			FlightConditions conditions = new FlightConditions(config);
			conditions.setMach(Application.getPreferences().getDefaultMach());
			conditions.setAOA(0);
			conditions.setRollRate(0);

			WarningSet warnings = new WarningSet();
			AerodynamicCalculator aero = new BarrowmanCalculator();
			CoordinateIF cp = aero.getWorstCP(config, conditions, warnings);
			CoordinateIF cg = MassCalculator.calculateLaunch(config).getCM();

			double cpx = cp.getWeight() > MathUtil.EPSILON ? cp.getX() : Double.NaN;
			double cgx = cg.getWeight() > MassCalculator.MIN_MASS ? cg.getX() : Double.NaN;

			double diameter = Double.NaN;
			for (RocketComponent c : config.getCoreComponents()) {
				if (c instanceof SymmetricComponent) {
					double d1 = ((SymmetricComponent) c).getForeRadius() * 2;
					double d2 = ((SymmetricComponent) c).getAftRadius() * 2;
					diameter = MathUtil.max(diameter, d1, d2);
				}
			}
			double margin = (!Double.isNaN(diameter) && diameter > 0) ? (cpx - cgx) / diameter : Double.NaN;
			double emptyMass = MassCalculator.calculateStructure(config).getMass();

			JsonObject r = new JsonObject();
			r.addProperty("cg", cgx);
			r.addProperty("cp", cpx);
			r.addProperty("stabilityMarginCalibers", margin);
			r.addProperty("referenceDiameter", diameter);
			r.addProperty("lengthMeters", config.getLength());
			r.addProperty("massWithMotorsKg", cg.getWeight());
			r.addProperty("massEmptyKg", emptyMass);
			r.addProperty("units", "distances in meters from the nose tip; mass in kg");
			r.addProperty("note", "Stability margin is in calibers; a stable rocket is typically 1-2.");
			JsonArray warns = new JsonArray();
			for (Warning w : warnings) {
				warns.add(w.toString());
			}
			r.add("warnings", warns);
			return r;
		});
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
	private Object applyProperty(Object c, String key, JsonElement value) throws Exception {
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
	// Materials
	// ------------------------------------------------------------------

	private static Database<Material> materialDb(Material.Type type) {
		switch (type) {
			case SURFACE: return Databases.SURFACE_MATERIAL;
			case LINE:    return Databases.LINE_MATERIAL;
			default:      return Databases.BULK_MATERIAL;
		}
	}

	private JsonObject listMaterials(JsonObject args) {
		String typeArg = optString(args, "type", null);
		JsonArray arr = new JsonArray();
		for (Material.Type type : Material.Type.values()) {
			if (type == Material.Type.CUSTOM) {
				continue;
			}
			if (typeArg != null && !type.name().equalsIgnoreCase(typeArg)) {
				continue;
			}
			for (Material m : materialDb(type)) {
				JsonObject o = new JsonObject();
				o.addProperty("name", m.getName());
				o.addProperty("type", type.name());
				o.addProperty("density", m.getDensity());
				arr.add(o);
			}
		}
		JsonObject result = new JsonObject();
		result.add("materials", arr);
		result.addProperty("note", "BULK density is kg/m^3, SURFACE kg/m^2, LINE kg/m. "
				+ "Use set_material with one of these names (matched to the component's material type).");
		return result;
	}

	private JsonObject setMaterial(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		RocketComponent c = findComponent(doc, requireString(args, "id"));
		String name = requireString(args, "material");

		Method getM;
		Method setM;
		try {
			getM = c.getClass().getMethod("getMaterial");
			setM = c.getClass().getMethod("setMaterial", Material.class);
		} catch (NoSuchMethodException e) {
			throw new ToolException(c.getClass().getSimpleName()
					+ " has no settable material. (Tip: set mass directly with set_component "
					+ "using massOverridden=true and overrideMass.)");
		}

		Material current = (Material) getM.invoke(c);
		Material.Type type = current.getType();
		Material chosen = null;
		for (Material m : materialDb(type)) {
			if (m.getName().equalsIgnoreCase(name)) {
				chosen = m;
				break;
			}
		}
		if (chosen == null) {
			throw new ToolException("No " + type + " material named '" + name
					+ "'. Use list_materials with type=" + type + " to see valid names.");
		}
		final Material material = chosen;
		onEdt(() -> {
			doc.addUndoPosition("Set material");
			setM.invoke(c, material);
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("id", c.getID().toString());
		result.addProperty("material", material.getName());
		result.addProperty("type", type.name());
		return result;
	}

	// ------------------------------------------------------------------
	// Component presets (real catalog parts)
	// ------------------------------------------------------------------

	private JsonObject searchPresets(JsonObject args) throws ToolException {
		String typeArg = optString(args, "type", null);
		String manufacturer = optString(args, "manufacturer", "").toLowerCase();
		String query = optString(args, "query", "").toLowerCase();
		int limit = args.has("limit") ? args.get("limit").getAsInt() : 25;

		List<ComponentPreset> presets;
		if (typeArg != null) {
			ComponentPreset.Type type;
			try {
				type = ComponentPreset.Type.valueOf(typeArg.toUpperCase());
			} catch (IllegalArgumentException e) {
				throw new ToolException("Unknown preset type '" + typeArg
						+ "'. Examples: NOSE_CONE, BODY_TUBE, TRANSITION, TUBE_COUPLER, "
						+ "CENTERING_RING, BULK_HEAD, ENGINE_BLOCK, LAUNCH_LUG, RAIL_BUTTON, PARACHUTE, STREAMER.");
			}
			presets = Application.getComponentPresetDao().listForType(type);
		} else {
			presets = Application.getComponentPresetDao().listAll();
		}

		JsonArray arr = new JsonArray();
		for (ComponentPreset p : presets) {
			String manu = p.getManufacturer().getDisplayName().toLowerCase();
			String part = p.getPartNo() == null ? "" : p.getPartNo().toLowerCase();
			if (!manufacturer.isEmpty() && !manu.contains(manufacturer)) {
				continue;
			}
			if (!query.isEmpty() && !part.contains(query)) {
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("manufacturer", p.getManufacturer().getDisplayName());
			o.addProperty("partNo", p.getPartNo());
			o.addProperty("type", p.getType().name());
			arr.add(o);
			if (arr.size() >= limit) {
				break;
			}
		}
		JsonObject result = new JsonObject();
		result.add("presets", arr);
		result.addProperty("count", arr.size());
		return result;
	}

	private JsonObject applyPreset(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		RocketComponent c = findComponent(doc, requireString(args, "id"));
		String partNo = requireString(args, "partNo");
		String manufacturer = optString(args, "manufacturer", null);

		ComponentPreset.Type type = c.getPresetType();
		if (type == null) {
			throw new ToolException(c.getClass().getSimpleName() + " does not support catalog presets.");
		}
		ComponentPreset chosen = null;
		for (ComponentPreset p : Application.getComponentPresetDao().listForType(type)) {
			if (!partNo.equalsIgnoreCase(p.getPartNo())) {
				continue;
			}
			if (manufacturer != null
					&& !p.getManufacturer().getDisplayName().toLowerCase().contains(manufacturer.toLowerCase())) {
				continue;
			}
			chosen = p;
			break;
		}
		if (chosen == null) {
			throw new ToolException("No " + type + " preset with partNo '" + partNo
					+ "'. Use search_presets with type=" + type + ".");
		}
		final ComponentPreset preset = chosen;
		onEdt(() -> {
			doc.addUndoPosition("Apply preset");
			c.loadPreset(preset);
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("id", c.getID().toString());
		result.addProperty("partNo", preset.getPartNo());
		result.addProperty("manufacturer", preset.getManufacturer().getDisplayName());
		return result;
	}

	// ------------------------------------------------------------------
	// Motor clusters
	// ------------------------------------------------------------------

	private JsonObject setCluster(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		RocketComponent c = findComponent(doc, requireString(args, "id"));
		if (!(c instanceof InnerTube)) {
			throw new ToolException(c.getName() + " is not an InnerTube. Clusters apply to inner "
					+ "tubes (add an InnerTube as the motor mount, then cluster it).");
		}
		InnerTube tube = (InnerTube) c;
		String name = requireString(args, "config");
		ClusterConfiguration chosen = null;
		for (ClusterConfiguration cc : ClusterConfiguration.CONFIGURATIONS) {
			if (cc.getXMLName().equalsIgnoreCase(name)) {
				chosen = cc;
				break;
			}
		}
		if (chosen == null) {
			StringBuilder sb = new StringBuilder();
			for (ClusterConfiguration cc : ClusterConfiguration.CONFIGURATIONS) {
				sb.append(cc.getXMLName()).append(" ");
			}
			throw new ToolException("Unknown cluster config '" + name + "'. Options: " + sb.toString().trim());
		}
		final ClusterConfiguration cfg = chosen;
		final Double scale = (args.has("clusterScale") && !args.get("clusterScale").isJsonNull())
				? args.get("clusterScale").getAsDouble() : null;
		onEdt(() -> {
			doc.addUndoPosition("Set cluster");
			tube.setClusterConfiguration(cfg);
			if (scale != null) {
				tube.setClusterScale(scale);
			}
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("config", cfg.getXMLName());
		result.addProperty("motorCount", cfg.getClusterCount());
		return result;
	}

	// ------------------------------------------------------------------
	// Staging / ignition / appearance / fins / expressions / analysis
	// ------------------------------------------------------------------

	private JsonObject setIgnition(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		RocketComponent c = findComponent(doc, requireString(args, "mountId"));
		if (!(c instanceof MotorMount)) {
			throw new ToolException(c.getName() + " is not a motor mount.");
		}
		MotorMount mount = (MotorMount) c;
		String eventName = requireString(args, "event");
		IgnitionEvent event;
		try {
			event = IgnitionEvent.valueOf(eventName.toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new ToolException("Unknown ignition event '" + eventName
					+ "'. Options: AUTOMATIC, LAUNCH, EJECTION_CHARGE, BURNOUT, NEVER.");
		}
		final Double delay = (args.has("delay") && !args.get("delay").isJsonNull())
				? args.get("delay").getAsDouble() : null;
		onEdt(() -> {
			FlightConfigurationId fcid = doc.getRocket().getSelectedConfiguration().getId();
			MotorConfiguration mc = mount.getMotorConfig(fcid);
			doc.addUndoPosition("Set ignition");
			mc.setIgnitionEvent(event);
			if (delay != null) {
				mc.setIgnitionDelay(delay);
			}
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("event", event.name());
		return result;
	}

	private JsonObject setSeparation(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		RocketComponent c = findComponent(doc, requireString(args, "stageId"));
		if (!(c instanceof AxialStage)) {
			throw new ToolException(c.getName() + " is not a stage (AxialStage).");
		}
		AxialStage stage = (AxialStage) c;
		String eventName = requireString(args, "event");
		StageSeparationConfiguration.SeparationEvent event;
		try {
			event = StageSeparationConfiguration.SeparationEvent.valueOf(eventName.toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new ToolException("Unknown separation event '" + eventName
					+ "'. Options: LAUNCH, IGNITION, BURNOUT, EJECTION, UPPER_IGNITION, "
					+ "ALTITUDE_ASCENDING, APOGEE, ALTITUDE_DESCENDING, NEVER.");
		}
		final Double delay = (args.has("delay") && !args.get("delay").isJsonNull())
				? args.get("delay").getAsDouble() : null;
		onEdt(() -> {
			StageSeparationConfiguration sep = stage.getSeparationConfigurations().getDefault();
			doc.addUndoPosition("Set stage separation");
			sep.setSeparationEvent(event);
			if (delay != null) {
				sep.setSeparationDelay(delay);
			}
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("event", event.name());
		return result;
	}

	private JsonObject setAppearance(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		RocketComponent c = findComponent(doc, requireString(args, "id"));
		int r = requireInt(args, "red");
		int g = requireInt(args, "green");
		int b = requireInt(args, "blue");
		int alpha = args.has("alpha") ? args.get("alpha").getAsInt() : 255;
		double shine = args.has("shine") ? args.get("shine").getAsDouble() : 0.3;
		final Appearance appearance = new Appearance(new ORColor(r, g, b, alpha), shine);
		onEdt(() -> {
			doc.addUndoPosition("Set appearance");
			c.setAppearance(appearance);
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("id", c.getID().toString());
		result.addProperty("color", r + "," + g + "," + b);
		return result;
	}

	private JsonObject setFinPoints(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		RocketComponent c = findComponent(doc, requireString(args, "id"));
		if (!(c instanceof FreeformFinSet)) {
			throw new ToolException(c.getName() + " is not a FreeformFinSet. Add a FreeformFinSet "
					+ "to edit an arbitrary fin profile.");
		}
		FreeformFinSet fin = (FreeformFinSet) c;
		if (!args.has("points") || !args.get("points").isJsonArray()) {
			throw new ToolException("Expected 'points' as an array of [x,y] pairs (metres), root to tip.");
		}
		JsonArray pts = args.getAsJsonArray("points");
		if (pts.size() < 3) {
			throw new ToolException("A fin needs at least 3 points.");
		}
		CoordinateIF[] coords = new CoordinateIF[pts.size()];
		for (int i = 0; i < pts.size(); i++) {
			JsonArray p = pts.get(i).getAsJsonArray();
			coords[i] = new Coordinate(p.get(0).getAsDouble(), p.get(1).getAsDouble());
		}
		onEdt(() -> {
			doc.addUndoPosition("Edit fin points");
			fin.setPoints(coords);
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("pointCount", coords.length);
		return result;
	}

	private JsonObject addCustomExpression(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		String name = requireString(args, "name");
		String symbol = requireString(args, "symbol");
		String unit = optString(args, "unit", "");
		String expression = requireString(args, "expression");
		onEdt(() -> {
			CustomExpression expr = new CustomExpression(doc, name, symbol, unit, expression);
			doc.addCustomExpression(expr);
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("name", name);
		result.addProperty("symbol", symbol);
		return result;
	}

	private JsonObject componentMassAnalysis(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		return onEdtCompute(() -> {
			FlightConfiguration config = doc.getRocket().getSelectedConfiguration();
			JsonArray arr = new JsonArray();
			for (CMAnalysisEntry e : MassCalculator.getCMAnalysis(config).values()) {
				JsonObject o = new JsonObject();
				o.addProperty("name", e.name);
				o.addProperty("massKg", e.eachMass);
				o.addProperty("cg", e.totalCM == null ? Double.NaN : e.totalCM.getX());
				arr.add(o);
			}
			JsonObject r = new JsonObject();
			r.add("components", arr);
			r.addProperty("note", "Per-component mass (kg) and CG (m from nose) for the selected configuration.");
			return r;
		});
	}

	private JsonObject componentAeroAnalysis(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		return onEdtCompute(() -> {
			FlightConfiguration config = doc.getRocket().getSelectedConfiguration();
			FlightConditions cond = new FlightConditions(config);
			cond.setMach(Application.getPreferences().getDefaultMach());
			cond.setAOA(0);
			cond.setRollRate(0);
			AerodynamicCalculator aero = new BarrowmanCalculator();
			java.util.Map<RocketComponent, AerodynamicForces> forces =
					aero.getForceAnalysis(config, cond, new WarningSet());
			JsonArray arr = new JsonArray();
			for (java.util.Map.Entry<RocketComponent, AerodynamicForces> e : forces.entrySet()) {
				AerodynamicForces af = e.getValue();
				JsonObject o = new JsonObject();
				o.addProperty("name", e.getKey().getName());
				o.addProperty("cp", af.getCP() == null ? Double.NaN : af.getCP().getX());
				o.addProperty("cd", af.getCD());
				arr.add(o);
			}
			JsonObject r = new JsonObject();
			r.add("components", arr);
			r.addProperty("note", "Per-component CP (m from nose) and drag coefficient at default Mach.");
			return r;
		});
	}

	private JsonObject setDeployment(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		RocketComponent c = findComponent(doc, requireString(args, "id"));
		if (!(c instanceof RecoveryDevice)) {
			throw new ToolException(c.getName() + " is not a recovery device (parachute/streamer).");
		}
		RecoveryDevice rec = (RecoveryDevice) c;
		String eventName = requireString(args, "event");
		DeploymentConfiguration.DeployEvent event;
		try {
			event = DeploymentConfiguration.DeployEvent.valueOf(eventName.toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new ToolException("Unknown deploy event '" + eventName
					+ "'. Options: LAUNCH, EJECTION, APOGEE, ALTITUDE, LOWER_STAGE_SEPARATION, NEVER.");
		}
		final Double altitude = (args.has("altitude") && !args.get("altitude").isJsonNull())
				? args.get("altitude").getAsDouble() : null;
		final Double delay = (args.has("delay") && !args.get("delay").isJsonNull())
				? args.get("delay").getAsDouble() : null;
		onEdt(() -> {
			DeploymentConfiguration dc = rec.getDeploymentConfigurations().getDefault();
			doc.addUndoPosition("Set deployment");
			dc.setDeployEvent(event);
			if (altitude != null) {
				dc.setDeployAltitude(altitude);
			}
			if (delay != null) {
				dc.setDeployDelay(delay);
			}
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("event", event.name());
		return result;
	}

	private JsonObject moveComponent(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		RocketComponent c = findComponent(doc, requireString(args, "id"));
		RocketComponent newParent = findComponent(doc, requireString(args, "newParentId"));
		if (c.getParent() == null) {
			throw new ToolException("Cannot move the rocket root.");
		}
		final Integer index = (args.has("index") && !args.get("index").isJsonNull())
				? args.get("index").getAsInt() : null;
		AtomicReference<Exception> err = new AtomicReference<>();
		onEdt(() -> {
			try {
				doc.addUndoPosition("Move component");
				c.getParent().removeChild(c);
				newParent.addChild(c, index != null ? index : newParent.getChildCount());
			} catch (Exception e) {
				err.set(e);
			}
			return null;
		});
		if (err.get() != null) {
			throw new ToolException("Could not move " + c.getName() + " under "
					+ newParent.getName() + ": " + err.get().getMessage());
		}
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("id", c.getID().toString());
		result.addProperty("newParentId", newParent.getID().toString());
		return result;
	}

	private JsonObject duplicateComponent(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		RocketComponent c = findComponent(doc, requireString(args, "id"));
		RocketComponent parent = c.getParent();
		if (parent == null) {
			throw new ToolException("Cannot duplicate the rocket root.");
		}
		AtomicReference<UUID> idRef = new AtomicReference<>();
		onEdt(() -> {
			RocketComponent copy = c.copy();
			doc.addUndoPosition("Duplicate component");
			parent.addChild(copy, parent.getChildren().indexOf(c) + 1);
			idRef.set(copy.getID());
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("newId", idRef.get().toString());
		return result;
	}

	// ------------------------------------------------------------------
	// Flight configurations
	// ------------------------------------------------------------------

	private JsonObject listFlightConfigs(JsonObject args) throws Exception {
		Rocket rocket = activeFrame(args).getDocument().getRocket();
		FlightConfigurationId selected = rocket.getSelectedConfiguration().getId();
		JsonArray arr = new JsonArray();
		for (FlightConfigurationId fcid : rocket.getIds()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", fcid.toString());
			o.addProperty("name", rocket.getFlightConfiguration(fcid).getName());
			o.addProperty("selected", fcid.equals(selected));
			arr.add(o);
		}
		JsonObject result = new JsonObject();
		result.add("configurations", arr);
		return result;
	}

	private JsonObject addFlightConfig(JsonObject args) throws Exception {
		Rocket rocket = activeFrame(args).getDocument().getRocket();
		String name = optString(args, "name", null);
		boolean select = !args.has("select") || args.get("select").getAsBoolean();
		AtomicReference<String> idRef = new AtomicReference<>();
		onEdt(() -> {
			FlightConfiguration cfg = new FlightConfiguration(rocket, null);
			rocket.setFlightConfiguration(cfg.getId(), cfg);
			cfg.setAllStages();
			if (name != null) {
				cfg.setName(name);
			}
			if (select) {
				rocket.setSelectedConfiguration(cfg.getId());
			}
			idRef.set(cfg.getId().toString());
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("id", idRef.get());
		return result;
	}

	private JsonObject selectFlightConfig(JsonObject args) throws Exception {
		Rocket rocket = activeFrame(args).getDocument().getRocket();
		String id = requireString(args, "id");
		FlightConfigurationId fcid = new FlightConfigurationId(id);
		if (!rocket.getIds().contains(fcid)) {
			throw new ToolException("No flight configuration with id " + id
					+ ". Use list_flight_configs.");
		}
		onEdt(() -> {
			rocket.setSelectedConfiguration(fcid);
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("selected", id);
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
		JsonArray warns = new JsonArray();
		for (Warning w : sim.getSimulatedWarnings()) {
			warns.add(w.toString());
		}
		result.add("warnings", warns);
		return result;
	}

	private static final Map<String, FlightDataType> FLIGHT_TYPES = new LinkedHashMap<>();
	static {
		FLIGHT_TYPES.put("time", FlightDataType.TYPE_TIME);
		FLIGHT_TYPES.put("altitude", FlightDataType.TYPE_ALTITUDE);
		FLIGHT_TYPES.put("velocity", FlightDataType.TYPE_VELOCITY_TOTAL);
		FLIGHT_TYPES.put("acceleration", FlightDataType.TYPE_ACCELERATION_TOTAL);
		FLIGHT_TYPES.put("mach", FlightDataType.TYPE_MACH_NUMBER);
		FLIGHT_TYPES.put("stability", FlightDataType.TYPE_STABILITY);
	}

	/** Return downsampled flight-time series (and optionally write a full-resolution CSV). */
	private JsonObject getFlightData(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		Simulation sim = resolveSimulation(doc, args);
		FlightData data = sim.getSimulatedData();
		if (data == null || data.getBranchCount() == 0) {
			throw new ToolException("No flight data. Run the simulation first.");
		}
		FlightDataBranch branch = data.getBranch(0);
		int len = branch.getLength();
		int maxPoints = args.has("maxPoints") ? Math.max(2, Math.min(2000, args.get("maxPoints").getAsInt())) : 60;
		int step = Math.max(1, len / maxPoints);

		JsonObject series = new JsonObject();
		for (Map.Entry<String, FlightDataType> e : FLIGHT_TYPES.entrySet()) {
			List<Double> vals = branch.get(e.getValue());
			if (vals == null || vals.isEmpty()) {
				continue;
			}
			JsonArray a = new JsonArray();
			for (int i = 0; i < len; i += step) {
				a.add(vals.get(i));
			}
			series.add(e.getKey(), a);
		}

		JsonObject result = new JsonObject();
		result.addProperty("simulation", sim.getName());
		result.addProperty("totalSamples", len);
		result.addProperty("returnedPoints", (len + step - 1) / step);
		result.add("series", series);

		String csvPath = optString(args, "csvPath", null);
		if (csvPath != null) {
			try (PrintWriter pw = new PrintWriter(csvPath)) {
				List<String> names = new java.util.ArrayList<>(FLIGHT_TYPES.keySet());
				pw.println(String.join(",", names));
				for (int i = 0; i < len; i++) {
					StringBuilder row = new StringBuilder();
					for (int j = 0; j < names.size(); j++) {
						List<Double> vals = branch.get(FLIGHT_TYPES.get(names.get(j)));
						if (j > 0) {
							row.append(",");
						}
						row.append(vals == null || vals.isEmpty() ? "" : vals.get(i));
					}
					pw.println(row);
				}
			}
			result.addProperty("csvFile", new File(csvPath).getAbsolutePath());
		}
		return result;
	}

	/** Export the active design: rocksim (.rkt), openrocket (.ork), obj (3D print), svg (laser cut). */
	private JsonObject exportDesign(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		String path = requireString(args, "path");
		String fmt = optString(args, "format", "rocksim").toLowerCase();

		String ext;
		String reportFormat;
		if (fmt.startsWith("rock") || fmt.equals("rkt")) {
			ext = ".rkt";
			reportFormat = "ROCKSIM";
		} else if (fmt.startsWith("open") || fmt.equals("ork")) {
			ext = ".ork";
			reportFormat = "OPENROCKET";
		} else if (fmt.equals("obj")) {
			ext = ".obj";
			reportFormat = "OBJ";
		} else if (fmt.equals("svg")) {
			ext = ".svg";
			reportFormat = "SVG";
		} else if (fmt.startsWith("ras")) {
			ext = ".CDX1";
			reportFormat = "RASAERO";
		} else {
			throw new ToolException("Unsupported format '" + fmt
					+ "'. Use rocksim, openrocket, obj, svg or rasaero.");
		}
		File f = new File(path);
		if (!f.getName().toLowerCase().endsWith(ext)) {
			f = new File(f.getAbsolutePath() + ext);
		}

		switch (reportFormat) {
			case "OBJ": {
				OBJExportOptions oo = new OBJExportOptions(doc.getRocket());
				oo.setExportChildren(true);
				new OBJExporterFactory(doc.getRocket().getChildren(),
						doc.getRocket().getSelectedConfiguration(), f, oo, new WarningSet()).doExport();
				break;
			}
			case "SVG": {
				new SVGRocketPartsExporter().export(doc, f,
						new SVGExportOptions(java.awt.Color.BLACK, 0.1));
				break;
			}
			default: {
				StorageOptions opts = new StorageOptions();
				if (reportFormat.equals("ROCKSIM")) {
					opts.setFileType(StorageOptions.FileType.ROCKSIM);
				} else if (reportFormat.equals("RASAERO")) {
					opts.setFileType(StorageOptions.FileType.RASAERO);
				} else {
					opts.setFileType(StorageOptions.FileType.OPENROCKET);
				}
				new GeneralRocketSaver().save(f, doc, opts);
			}
		}
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("format", reportFormat);
		result.addProperty("file", f.getAbsolutePath());
		return result;
	}

	private JsonObject setSimulationOptions(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		Simulation sim = resolveSimulation(doc, args);
		if (!args.has("properties") || !args.get("properties").isJsonObject()) {
			throw new ToolException("Expected an object 'properties' of name -> value pairs "
					+ "(e.g. {\"launchRodLength\":2.0,\"windSpeedAverage\":3.0}).");
		}
		JsonObject properties = args.getAsJsonObject("properties");
		JsonObject applied = new JsonObject();
		JsonObject failed = new JsonObject();
		onEdt(() -> {
			SimulationOptions opts = sim.getOptions();
			for (String key : properties.keySet()) {
				try {
					applied.add(key, toJson(applyProperty(opts, key, properties.get(key))));
				} catch (Exception e) {
					failed.addProperty(key, e.getMessage());
				}
			}
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("simulation", sim.getName());
		result.add("applied", applied);
		if (failed.size() > 0) {
			result.add("failed", failed);
		}
		return result;
	}

	/**
	 * Auto-tune a single scalar component parameter over a range to meet a goal:
	 * "max_apogee", "target_apogee" (needs target), or "target_stability" (needs target calibers).
	 * Performs a coarse sweep then a local refine, setting the component to the best value found.
	 */
	private JsonObject optimizeParameter(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		RocketComponent c = findComponent(doc, requireString(args, "id"));
		String param = requireString(args, "parameter");
		double min = requireDouble(args, "min");
		double max = requireDouble(args, "max");
		if (max <= min) {
			throw new ToolException("max must be greater than min.");
		}
		String objective = requireString(args, "objective");
		boolean needsTarget = objective.equals("target_apogee") || objective.equals("target_stability");
		Double target = (args.has("target") && !args.get("target").isJsonNull())
				? args.get("target").getAsDouble() : null;
		if (needsTarget && target == null) {
			throw new ToolException("Objective '" + objective + "' requires a 'target' value.");
		}
		boolean apogeeObjective = objective.startsWith("max_") || objective.startsWith("target_apogee");
		Simulation sim = apogeeObjective ? resolveSimulation(doc, args) : null;
		int samples = args.has("samples") ? Math.max(4, Math.min(30, args.get("samples").getAsInt())) : 12;

		// Validate the parameter is settable before looping.
		setScalarOnEdt(c, param, min);

		JsonArray evals = new JsonArray();
		double bestVal = min;
		double bestScore = -Double.MAX_VALUE;
		double bestMetric = Double.NaN;
		double lo = min;
		double hi = max;
		int n = samples;
		for (int pass = 0; pass < 2; pass++) {
			for (int i = 0; i < n; i++) {
				double v = lo + (hi - lo) * i / (n - 1);
				setScalarOnEdt(c, param, v);
				double metric = apogeeObjective ? evalApogee(sim) : computeMargin(doc);
				double score = scoreOf(objective, metric, target);
				JsonObject e = new JsonObject();
				e.addProperty("value", v);
				e.addProperty(apogeeObjective ? "apogee" : "stabilityMargin", metric);
				evals.add(e);
				if (score > bestScore) {
					bestScore = score;
					bestVal = v;
					bestMetric = metric;
				}
			}
			double span = (hi - lo) / (n - 1);
			lo = Math.max(min, bestVal - span);
			hi = Math.min(max, bestVal + span);
			n = 7;
		}
		setScalarOnEdt(c, param, bestVal);

		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("parameter", param);
		result.addProperty("objective", objective);
		result.addProperty("bestValue", bestVal);
		result.addProperty(apogeeObjective ? "apogee" : "stabilityMargin", bestMetric);
		result.addProperty("evaluations", evals.size());
		result.add("samples", evals);
		result.addProperty("note", "Component set to bestValue. Re-check stability/apogee as needed.");
		return result;
	}

	private double scoreOf(String objective, double metric, Double target) {
		if (Double.isNaN(metric)) {
			return -Double.MAX_VALUE;
		}
		switch (objective) {
			case "max_apogee":        return metric;
			case "target_apogee":     return -Math.abs(metric - target);
			case "target_stability":  return -Math.abs(metric - target);
			default:                  return metric;
		}
	}

	private double evalApogee(Simulation sim) {
		try {
			sim.simulate();
			FlightData d = sim.getSimulatedData();
			return d == null ? Double.NaN : d.getMaxAltitude();
		} catch (Exception e) {
			return Double.NaN;
		}
	}

	private double computeMargin(OpenRocketDocument doc) throws Exception {
		return onEdtCompute(() -> {
			FlightConfiguration config = doc.getRocket().getSelectedConfiguration();
			FlightConditions cond = new FlightConditions(config);
			cond.setMach(Application.getPreferences().getDefaultMach());
			cond.setAOA(0);
			cond.setRollRate(0);
			CoordinateIF cp = new BarrowmanCalculator().getWorstCP(config, cond, new WarningSet());
			CoordinateIF cg = MassCalculator.calculateLaunch(config).getCM();
			double diameter = Double.NaN;
			for (RocketComponent c2 : config.getCoreComponents()) {
				if (c2 instanceof SymmetricComponent) {
					diameter = MathUtil.max(diameter, ((SymmetricComponent) c2).getForeRadius() * 2,
							((SymmetricComponent) c2).getAftRadius() * 2);
				}
			}
			if (Double.isNaN(diameter) || diameter <= 0) {
				return Double.NaN;
			}
			return (cp.getX() - cg.getX()) / diameter;
		});
	}

	private void setScalarOnEdt(RocketComponent c, String param, double value) throws Exception {
		onEdt(() -> {
			applyProperty(c, param, new JsonPrimitive(value));
			return null;
		});
	}

	private JsonObject addSimulationExtension(JsonObject args) throws Exception {
		OpenRocketDocument doc = activeFrame(args).getDocument();
		Simulation sim = resolveSimulation(doc, args);
		String type = requireString(args, "type");
		Class<?> clazz = null;
		for (String pkg : new String[] {
				"info.openrocket.core.simulation.extension.example.",
				"info.openrocket.core.simulation.extension.impl." }) {
			try {
				clazz = Class.forName(pkg + type);
				break;
			} catch (ClassNotFoundException ignore) {
				// try next package
			}
		}
		if (clazz == null || !SimulationExtension.class.isAssignableFrom(clazz)) {
			throw new ToolException("Unknown simulation extension '" + type + "'. Options: "
					+ "AirStart, StopSimulation, RollControl, DampingMoment, CSVSave, PrintSimulation, "
					+ "ScriptingExtension.");
		}
		final Class<?> extClass = clazz;
		final JsonObject properties = (args.has("properties") && args.get("properties").isJsonObject())
				? args.getAsJsonObject("properties") : new JsonObject();
		JsonObject applied = new JsonObject();
		JsonObject failed = new JsonObject();
		onEdt(() -> {
			SimulationExtension ext = (SimulationExtension) extClass.getDeclaredConstructor().newInstance();
			for (String key : properties.keySet()) {
				try {
					applied.add(key, toJson(applyProperty(ext, key, properties.get(key))));
				} catch (Exception e) {
					failed.addProperty(key, e.getMessage());
				}
			}
			sim.getSimulationExtensions().add(ext);
			return null;
		});
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("extension", type);
		result.add("applied", applied);
		if (failed.size() > 0) {
			result.add("failed", failed);
		}
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

	/** Run a body of work on the EDT, wait for it, and return its result. */
	private <T> T onEdtCompute(Callable<T> work) throws Exception {
		if (SwingUtilities.isEventDispatchThread()) {
			return work.call();
		}
		AtomicReference<T> result = new AtomicReference<>();
		AtomicReference<Exception> err = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> {
			try {
				result.set(work.call());
			} catch (Exception e) {
				err.set(e);
			}
		});
		if (err.get() != null) {
			throw err.get();
		}
		return result.get();
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

	private static double requireDouble(JsonObject args, String key) throws ToolException {
		if (!args.has(key) || args.get(key).isJsonNull()) {
			throw new ToolException("Missing required argument: " + key);
		}
		return args.get(key).getAsDouble();
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
		tools.add(tool("get_stability",
				"Get the key design metrics for the active design's selected configuration: CG, CP, "
				+ "stability margin (calibers), reference diameter, length, and mass (with/without motors). "
				+ "Use this to design a stable rocket (aim for ~1-2 calibers).",
				"{\"type\":\"object\",\"properties\":{\"designIndex\":{\"type\":\"integer\"}}}"));
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
		tools.add(tool("list_materials",
				"List available materials (name, type, density). Optionally filter by type (BULK/SURFACE/LINE).",
				"{\"type\":\"object\",\"properties\":{\"type\":{\"type\":\"string\"}}}"));
		tools.add(tool("set_material",
				"Set a component's material by name (matched to the component's material type). "
				+ "See list_materials for valid names.",
				"{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"material\":{\"type\":\"string\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"id\",\"material\"]}"));
		tools.add(tool("search_presets",
				"Search the catalog of real commercial component presets (Estes/LOC/etc). Filter by "
				+ "type (e.g. NOSE_CONE, BODY_TUBE), manufacturer, and/or partNo query.",
				"{\"type\":\"object\",\"properties\":{\"type\":{\"type\":\"string\"},\"manufacturer\":{\"type\":\"string\"},\"query\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"}}}"));
		tools.add(tool("apply_preset",
				"Apply a catalog preset (by partNo, optionally manufacturer) to a component, loading its "
				+ "real dimensions/material. The component must match the preset type.",
				"{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"partNo\":{\"type\":\"string\"},\"manufacturer\":{\"type\":\"string\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"id\",\"partNo\"]}"));
		tools.add(tool("set_cluster",
				"Make an InnerTube a motor cluster. config is a pattern name: single, double, 3-row, "
				+ "4-row, 3-ring, 4-ring, 5-ring, 6-ring, 3-star, 4-star, 5-star, 6-star, 9-grid, 9-star. "
				+ "Optional clusterScale spreads the tubes apart.",
				"{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"config\":{\"type\":\"string\"},\"clusterScale\":{\"type\":\"number\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"id\",\"config\"]}"));
		tools.add(tool("list_flight_configs",
				"List the flight configurations of the active design (id, name, which is selected).",
				"{\"type\":\"object\",\"properties\":{\"designIndex\":{\"type\":\"integer\"}}}"));
		tools.add(tool("add_flight_config",
				"Create a new flight configuration (all stages active) and select it by default.",
				"{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"select\":{\"type\":\"boolean\"},\"designIndex\":{\"type\":\"integer\"}}}"));
		tools.add(tool("select_flight_config",
				"Select a flight configuration by id (see list_flight_configs).",
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
		tools.add(tool("get_flight_data",
				"Get the downsampled flight time-series (time, altitude, velocity, acceleration, mach, "
				+ "stability) for a simulation. Optionally write a full-resolution CSV via csvPath.",
				"{\"type\":\"object\",\"properties\":{\"index\":{\"type\":\"integer\"},\"name\":{\"type\":\"string\"},\"maxPoints\":{\"type\":\"integer\"},\"csvPath\":{\"type\":\"string\"},\"designIndex\":{\"type\":\"integer\"}}}"));
		tools.add(tool("export_design",
				"Export the active design. format is 'rocksim' (.rkt), 'openrocket' (.ork), 'obj' "
				+ "(3D-print mesh), 'svg' (laser-cut parts) or 'rasaero' (.CDX1).",
				"{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"format\":{\"type\":\"string\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"path\"]}"));
		tools.add(tool("set_ignition",
				"Set a motor's ignition event/delay on a mount. event: AUTOMATIC, LAUNCH, "
				+ "EJECTION_CHARGE, BURNOUT, NEVER. Use BURNOUT/EJECTION_CHARGE for upper-stage motors.",
				"{\"type\":\"object\",\"properties\":{\"mountId\":{\"type\":\"string\"},\"event\":{\"type\":\"string\"},\"delay\":{\"type\":\"number\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"mountId\",\"event\"]}"));
		tools.add(tool("set_separation",
				"Set a stage's separation event/delay. event: LAUNCH, IGNITION, BURNOUT, EJECTION, "
				+ "UPPER_IGNITION, ALTITUDE_ASCENDING, APOGEE, ALTITUDE_DESCENDING, NEVER.",
				"{\"type\":\"object\",\"properties\":{\"stageId\":{\"type\":\"string\"},\"event\":{\"type\":\"string\"},\"delay\":{\"type\":\"number\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"stageId\",\"event\"]}"));
		tools.add(tool("set_appearance",
				"Set a component's appearance colour (red/green/blue 0-255, optional alpha 0-255, "
				+ "optional shine 0-1).",
				"{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"red\":{\"type\":\"integer\"},\"green\":{\"type\":\"integer\"},\"blue\":{\"type\":\"integer\"},\"alpha\":{\"type\":\"integer\"},\"shine\":{\"type\":\"number\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"id\",\"red\",\"green\",\"blue\"]}"));
		tools.add(tool("set_fin_points",
				"Set the profile of a FreeformFinSet from an array of [x,y] points (metres, root to tip).",
				"{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"points\":{\"type\":\"array\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"id\",\"points\"]}"));
		tools.add(tool("add_custom_expression",
				"Add a custom flight-data expression (name, symbol, unit, expression) usable in plots/data.",
				"{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},\"symbol\":{\"type\":\"string\"},\"unit\":{\"type\":\"string\"},\"expression\":{\"type\":\"string\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"name\",\"symbol\",\"expression\"]}"));
		tools.add(tool("component_mass_analysis",
				"Per-component mass (kg) and CG (m) breakdown for the selected configuration.",
				"{\"type\":\"object\",\"properties\":{\"designIndex\":{\"type\":\"integer\"}}}"));
		tools.add(tool("component_aero_analysis",
				"Per-component center-of-pressure (m) and drag-coefficient breakdown at default Mach.",
				"{\"type\":\"object\",\"properties\":{\"designIndex\":{\"type\":\"integer\"}}}"));
		tools.add(tool("set_deployment",
				"Set a recovery device's deployment. event: LAUNCH, EJECTION, APOGEE, ALTITUDE, "
				+ "LOWER_STAGE_SEPARATION, NEVER. For ALTITUDE give altitude (m); optional delay (s).",
				"{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"event\":{\"type\":\"string\"},\"altitude\":{\"type\":\"number\"},\"delay\":{\"type\":\"number\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"id\",\"event\"]}"));
		tools.add(tool("move_component",
				"Move a component under a new parent (optionally at an index). Reports an error if the "
				+ "parent does not accept it.",
				"{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"newParentId\":{\"type\":\"string\"},\"index\":{\"type\":\"integer\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"id\",\"newParentId\"]}"));
		tools.add(tool("duplicate_component",
				"Duplicate a component (and its children) next to itself under the same parent.",
				"{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"id\"]}"));
		tools.add(tool("set_simulation_options",
				"Set launch conditions / options on a simulation. 'properties' maps option names to "
				+ "values, e.g. {\"launchRodLength\":2.0,\"launchRodAngle\":0.0,\"windSpeedAverage\":3.0,"
				+ "\"launchAltitude\":0.0,\"launchTemperature\":288.15}.",
				"{\"type\":\"object\",\"properties\":{\"index\":{\"type\":\"integer\"},\"name\":{\"type\":\"string\"},\"properties\":{\"type\":\"object\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"properties\"]}"));
		tools.add(tool("add_simulation_extension",
				"Attach a simulation extension to a simulation. type: AirStart (setLaunchAltitude/"
				+ "setLaunchVelocity), StopSimulation (setStopTime/setStopStep), RollControl, "
				+ "DampingMoment, CSVSave, PrintSimulation. properties is a map of setter values.",
				"{\"type\":\"object\",\"properties\":{\"index\":{\"type\":\"integer\"},\"name\":{\"type\":\"string\"},\"type\":{\"type\":\"string\"},\"properties\":{\"type\":\"object\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"type\"]}"));
		tools.add(tool("optimize_parameter",
				"Auto-tune one scalar component parameter over [min,max] to meet a goal. objective is "
				+ "'max_apogee', 'target_apogee' (give target metres) or 'target_stability' (give target "
				+ "calibers). Sweeps + refines and sets the component to the best value. For apogee goals "
				+ "give the simulation (index/name).",
				"{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"parameter\":{\"type\":\"string\"},\"min\":{\"type\":\"number\"},\"max\":{\"type\":\"number\"},\"objective\":{\"type\":\"string\"},\"target\":{\"type\":\"number\"},\"samples\":{\"type\":\"integer\"},\"index\":{\"type\":\"integer\"},\"name\":{\"type\":\"string\"},\"designIndex\":{\"type\":\"integer\"}},\"required\":[\"id\",\"parameter\",\"min\",\"max\",\"objective\"]}"));
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
