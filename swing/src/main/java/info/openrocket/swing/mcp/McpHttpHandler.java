package info.openrocket.swing.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import info.openrocket.core.util.BuildProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Implements the MCP "Streamable HTTP" transport over the JDK's built-in HTTP server.
 *
 * <p>Each POST carries one (or a batch of) JSON-RPC 2.0 message(s). Requests get a single
 * {@code application/json} response; notifications get {@code 202 Accepted} with no body. We do
 * not offer a server-initiated SSE stream, so GET returns 405 (allowed by the spec).</p>
 */
class McpHttpHandler implements HttpHandler {

	private static final Logger log = LoggerFactory.getLogger(McpHttpHandler.class);

	private static final String PROTOCOL_VERSION = "2025-06-18";

	private final McpBridge bridge;
	private final Gson gson = new Gson();

	McpHttpHandler(McpBridge bridge) {
		this.bridge = bridge;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			exchange.getResponseHeaders().set("Mcp-Session-Id", bridge.getSessionId());

			if (!authorized(exchange)) {
				sendJson(exchange, 401, errorEnvelope(null, -32001, "Unauthorized"));
				return;
			}

			String method = exchange.getRequestMethod();
			if ("GET".equalsIgnoreCase(method)) {
				// No server-initiated stream offered.
				exchange.getResponseHeaders().set("Allow", "POST");
				exchange.sendResponseHeaders(405, -1);
				return;
			}
			if (!"POST".equalsIgnoreCase(method)) {
				exchange.sendResponseHeaders(405, -1);
				return;
			}

			byte[] body = exchange.getRequestBody().readAllBytes();
			JsonElement parsed;
			try {
				parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
			} catch (Exception e) {
				sendJson(exchange, 400, errorEnvelope(null, -32700, "Parse error"));
				return;
			}

			if (parsed.isJsonArray()) {
				JsonArray responses = new JsonArray();
				for (JsonElement el : parsed.getAsJsonArray()) {
					JsonObject resp = dispatch(el.getAsJsonObject());
					if (resp != null) {
						responses.add(resp);
					}
				}
				if (responses.isEmpty()) {
					exchange.sendResponseHeaders(202, -1);
				} else {
					sendJson(exchange, 200, responses);
				}
			} else if (parsed.isJsonObject()) {
				JsonObject resp = dispatch(parsed.getAsJsonObject());
				if (resp == null) {
					exchange.sendResponseHeaders(202, -1);
				} else {
					sendJson(exchange, 200, resp);
				}
			} else {
				sendJson(exchange, 400, errorEnvelope(null, -32600, "Invalid Request"));
			}
		} catch (Exception e) {
			log.warn("MCP request handling failed", e);
			try {
				sendJson(exchange, 500, errorEnvelope(null, -32603, "Internal error: " + e.getMessage()));
			} catch (IOException ignore) {
				// connection already gone
			}
		}
	}

	/** Process one JSON-RPC message. Returns the response object, or null for notifications. */
	private JsonObject dispatch(JsonObject msg) {
		JsonElement id = msg.get("id");
		boolean isNotification = (id == null || id.isJsonNull());
		String method = msg.has("method") ? msg.get("method").getAsString() : "";
		JsonObject params = msg.has("params") && msg.get("params").isJsonObject()
				? msg.getAsJsonObject("params") : new JsonObject();

		if (isNotification) {
			// notifications/initialized, notifications/cancelled, ... — nothing to return.
			return null;
		}

		switch (method) {
			case "initialize":
				return resultEnvelope(id, initializeResult(params));
			case "ping":
				return resultEnvelope(id, new JsonObject());
			case "tools/list": {
				JsonObject r = new JsonObject();
				r.add("tools", OpenRocketTools.toolDefinitions());
				return resultEnvelope(id, r);
			}
			case "tools/call":
				return resultEnvelope(id, callTool(params));
			default:
				return errorEnvelope(id, -32601, "Method not found: " + method);
		}
	}

	private JsonObject initializeResult(JsonObject params) {
		String requested = params.has("protocolVersion")
				? params.get("protocolVersion").getAsString() : PROTOCOL_VERSION;

		JsonObject capabilities = new JsonObject();
		capabilities.add("tools", new JsonObject());

		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", "openrocket");
		serverInfo.addProperty("version", BuildProperties.getVersion());

		JsonObject result = new JsonObject();
		result.addProperty("protocolVersion", requested);
		result.add("capabilities", capabilities);
		result.add("serverInfo", serverInfo);
		result.addProperty("instructions",
				"This server controls a running OpenRocket instance. Use get_component_tree to "
				+ "explore the open design, get_component to read parameters, and set_component / "
				+ "add_component / delete_component to edit it. Changes appear live in the GUI.");
		return result;
	}

	private JsonObject callTool(JsonObject params) {
		String name = params.has("name") ? params.get("name").getAsString() : "";
		JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
				? params.getAsJsonObject("arguments") : new JsonObject();

		JsonObject result = new JsonObject();
		try {
			JsonObject data = bridge.getTools().call(name, arguments);
			result.add("content", textContent(gson.toJson(data)));
			result.add("structuredContent", data);
			result.addProperty("isError", false);
			bridge.fireActivity(name, summarize(arguments), true);
		} catch (OpenRocketTools.ToolException e) {
			result.add("content", textContent("Error: " + e.getMessage()));
			result.addProperty("isError", true);
			bridge.fireActivity(name, e.getMessage(), false);
		} catch (Exception e) {
			log.warn("Tool '{}' threw", name, e);
			String m = e.getClass().getSimpleName() + ": " + e.getMessage();
			result.add("content", textContent("Error: " + m));
			result.addProperty("isError", true);
			bridge.fireActivity(name, m, false);
		}
		return result;
	}

	private static String summarize(JsonObject args) {
		String s = args.toString();
		return s.length() > 120 ? s.substring(0, 117) + "..." : s;
	}

	private static JsonArray textContent(String text) {
		JsonObject block = new JsonObject();
		block.addProperty("type", "text");
		block.addProperty("text", text);
		JsonArray arr = new JsonArray();
		arr.add(block);
		return arr;
	}

	private boolean authorized(HttpExchange exchange) {
		String token = bridge.getToken();
		if (token == null) {
			return true;
		}
		String header = exchange.getRequestHeaders().getFirst("Authorization");
		return header != null && header.equals("Bearer " + token);
	}

	private static JsonObject resultEnvelope(JsonElement id, JsonObject result) {
		JsonObject o = new JsonObject();
		o.addProperty("jsonrpc", "2.0");
		o.add("id", id);
		o.add("result", result);
		return o;
	}

	private static JsonObject errorEnvelope(JsonElement id, int code, String message) {
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);
		JsonObject o = new JsonObject();
		o.addProperty("jsonrpc", "2.0");
		o.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
		o.add("error", error);
		return o;
	}

	private void sendJson(HttpExchange exchange, int status, JsonElement payload) throws IOException {
		byte[] bytes = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}
}
