package com.crispyland.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpSseClientProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Asks every configured MCP server what it can do, and reports what came back.
 * <p>
 * Discovery only. Nothing here calls a tool and nothing here is visible to the chat model — the
 * point being demonstrated is that a separate process can be interrogated at runtime, which is
 * true whether or not anything is done with the answer.
 * <p>
 * The clients arrive un-dialled, because {@code spring.ai.mcp.client.initialized} is false: the
 * alternative is an agent that will not start while a side panel's server is down. So the first
 * handshake happens here, on the first render with the switch on, and its failure is a value
 * returned to the page rather than an exception thrown at anybody.
 */
@Service
public class McpCatalogue {

    private static final Logger log = LoggerFactory.getLogger(McpCatalogue.class);

    /**
     * Empty when the starter is absent or {@code spring.ai.mcp.client.enabled} is false. An
     * {@code ObjectProvider} rather than a required list, so removing the dependency is a build
     * change and not a startup failure in a class that is meant to be optional.
     */
    private final ObjectProvider<List<McpSyncClient>> clients;

    /**
     * Both transports' configuration, consulted only to put a URL next to a red light. They are
     * two properties classes rather than one because Spring binds them separately, and the
     * lookup below tries each in turn — a connection key belongs to exactly one of them.
     */
    private final McpSseClientProperties sse;
    private final McpStreamableHttpClientProperties streamable;

    /**
     * The master switch. Volatile and not persisted: it is a view of the running process, and a
     * stale "on" restored from disk would claim a connection nobody has made this run.
     */
    private volatile boolean enabled;

    /**
     * Which individual servers are switched off, by connection label.
     * <p>
     * Held as the <em>off</em> set rather than the on set on purpose: a connection added to
     * {@code application.yml} later must arrive switched on, and an on-set would silently ignore
     * a server nobody had clicked yet — the configuration would say one thing and the panel
     * another. Not persisted, for the same reason the master switch is not.
     * <p>
     * This is a second switch, not a replacement for the first. The master one answers "is this
     * application talking to MCP at all", which is the claim that a turn with it off goes out
     * byte-identical to the turns this app made before any of this existed. These answer a
     * different question — <em>which</em> servers — and that only becomes a question once there
     * is more than one.
     */
    private final Set<String> off = ConcurrentHashMap.newKeySet();

    public McpCatalogue(ObjectProvider<List<McpSyncClient>> clients, McpSseClientProperties sse,
                        McpStreamableHttpClientProperties streamable,
                        @Value("${agent.mcp.enabled:true}") boolean enabled) {
        this.clients = clients;
        this.sse = sse;
        this.streamable = streamable;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Whether one particular server is switched on. Unknown labels are on — see {@link #off}. */
    public boolean isOn(String label) {
        return !off.contains(label);
    }

    /**
     * The connections the model may actually use: master switch on, and that server not turned off.
     * <p>
     * Package-private and returning the SDK's own type, because the only caller is
     * {@link McpToolBox} next door and it needs to actually call a tool. Widening this to the web
     * layer would put a client capable of side effects one {@code @Autowired} away from any page.
     * <p>
     * Filtering here rather than in the toolbox is what makes one switch mean one thing: a server
     * that is off contributes no tools to {@code available()}, so the model is never told it
     * exists, so it cannot ask for it and there is no second place that has to refuse.
     */
    List<McpSyncClient> connections() {
        if (!enabled) {
            return List.of();
        }
        List<McpSyncClient> usable = new ArrayList<>();
        for (McpSyncClient client : clients.getIfAvailable(List::of)) {
            if (isOn(label(client))) {
                usable.add(client);
            }
        }
        return usable;
    }

    /**
     * All configured connections, ignoring the master switch and per-server toggles.
     * Used by the developer assistant ({@code /api/help}) which always needs git tools
     * regardless of whether the chat UI's MCP panel is on.
     */
    public List<McpSyncClient> allConnections() {
        return clients.getIfAvailable(List::of);
    }


    /** Moves the master switch and returns where it landed. */
    public boolean toggle() {
        enabled = !enabled;
        log.info("MCP turned {} — {}.", enabled ? "on" : "off",
                enabled ? "servers will be dialled on the next render" : "no server will be contacted");
        return enabled;
    }

    /**
     * Moves one server's switch and returns where it landed.
     * <p>
     * Turning a server off does not close its session, and that is deliberate: the handshake is
     * the expensive, fragile part — see the reconnect limitation on this class — so switching a
     * server off and on again is a filter being lifted, not a connection being remade. Nothing is
     * dialled, nothing can fail, and the tools come straight back.
     */
    public boolean toggle(String label) {
        boolean nowOn = off.remove(label);
        if (!nowOn) {
            off.add(label);
        }
        log.info("MCP server '{}' turned {} — {}.", label, nowOn ? "on" : "off",
                nowOn ? "its tools are offered to the model again"
                        : "its tools are withheld from the model and it will not be dialled");
        return nowOn;
    }

    /**
     * One round of discovery: handshake if it has not happened, then {@code tools/list}.
     * <p>
     * Re-listed on every render rather than cached, because a cached green light outlives the
     * server that earned it — the one thing an indicator must never do. Note that this stopped
     * being free once a connection left the machine: a remote server is now on the render path,
     * and an unreachable one costs the client's request-timeout before the page is drawn.
     */
    public McpSnapshot snapshot() {
        if (!enabled) {
            return McpSnapshot.OFF;
        }
        List<McpSyncClient> connections = clients.getIfAvailable(List::of);
        List<McpSnapshot.Server> servers = new ArrayList<>();
        for (McpSyncClient client : connections) {
            String label = label(client);
            // A server that is switched off is still listed — it is configured, and a panel that
            // hid it would leave no way to switch it back on — but it is not dialled. That is the
            // whole saving: with the remote one off, an outage at Frankfurter stops costing this
            // page its request-timeout before it draws.
            servers.add(isOn(label) ? describe(client) : withheld(label));
        }
        return new McpSnapshot(true, servers);
    }

    /** A configured server nobody asked anything this render. Not connected, and not a failure. */
    private McpSnapshot.Server withheld(String label) {
        return new McpSnapshot.Server(label, "", "", urlFor(label), false, false, "", List.of());
    }

    private McpSnapshot.Server describe(McpSyncClient client) {
        String label = label(client);
        String url = urlFor(label);
        try {
            // Cheap and idempotent in the sense that matters: once the handshake has happened we
            // must not repeat it, because a second initialize() on a live session is an error.
            if (!client.isInitialized()) {
                client.initialize();
                log.info("MCP handshake with '{}' at {} succeeded.", label, url);
            }
            List<McpSnapshot.Tool> tools = new ArrayList<>();
            for (McpSchema.Tool tool : client.listTools().tools()) {
                tools.add(toView(tool));
            }
            tools.sort(Comparator.comparing(McpSnapshot.Tool::name));

            McpSchema.Implementation server = client.getServerInfo();
            return new McpSnapshot.Server(label, text(server == null ? null : server.name()),
                    text(server == null ? null : server.version()), url, true, true, "", tools);
        } catch (Exception e) {
            // Deliberately broad. Everything from a refused connection to a protocol error to a
            // timeout means the same thing to the page — no tools, and here is why — and letting
            // any of them escape would take the whole chat page down with it.
            log.warn("MCP server '{}' at {} did not answer: {}", label, url, e.toString());
            return new McpSnapshot.Server(label, "", "", url, true, false, reason(e), List.of());
        }
    }

    /**
     * Flattens the tool's JSON Schema into the rows the page shows.
     * <p>
     * Read defensively at every step. This map was built by another process from a schema it was
     * free to shape as it liked, so nothing about its depth or types is guaranteed here the way
     * it would be for an object this app constructed.
     */
    private static McpSnapshot.Tool toView(McpSchema.Tool tool) {
        List<McpSnapshot.Param> params = new ArrayList<>();
        Map<String, Object> schema = tool.inputSchema();
        if (schema != null) {
            Object properties = schema.get("properties");
            Object required = schema.get("required");
            List<?> mandatory = (required instanceof List<?> list) ? list : List.of();
            if (properties instanceof Map<?, ?> byName) {
                for (Map.Entry<?, ?> entry : byName.entrySet()) {
                    String name = String.valueOf(entry.getKey());
                    Map<?, ?> spec = (entry.getValue() instanceof Map<?, ?> m) ? m : Map.of();
                    params.add(new McpSnapshot.Param(name,
                            text(spec.get("type")), mandatory.contains(name),
                            text(spec.get("description"))));
                }
            }
        }
        params.sort(Comparator.comparing(McpSnapshot.Param::name));
        return new McpSnapshot.Tool(text(tool.name()), text(tool.title()), text(tool.description()),
                params);
    }

    /**
     * The connection's name. Spring builds the client's own name as
     * {@code "<client name> - <connection key>"}, so the tail of it is the key from
     * {@code application.yml} and is known before the server has said anything about itself.
     */
    public static String label(McpSyncClient client) {
        McpSchema.Implementation info = client.getClientInfo();
        String name = (info == null) ? "" : text(info.name());
        int dash = name.lastIndexOf(" - ");
        return (dash < 0) ? name : name.substring(dash + 3);
    }

    /**
     * The configured endpoint, so a red light can still say what it failed to reach.
     * <p>
     * Checked against both transports because the key alone does not say which one configured it,
     * and the panel should not care: a server is a name, a URL and a list of tools whichever way
     * the bytes got there.
     */
    private String urlFor(String label) {
        McpSseClientProperties.SseParameters asSse = sse.getConnections().get(label);
        if (asSse != null) {
            return asSse.url() + text(asSse.sseEndpoint());
        }
        McpStreamableHttpClientProperties.ConnectionParameters asStreamable =
                streamable.getConnections().get(label);
        if (asStreamable != null) {
            return asStreamable.url() + text(asStreamable.endpoint());
        }
        return "";
    }

    /**
     * The shortest true sentence about a failure — the page has one line for it.
     * <p>
     * Clipped, because the message is not ours. A server that answers an error with a serialized
     * stack trace puts its whole JSON body in {@code getMessage()}, and that arrived here once as
     * twenty kilobytes that pushed the tool list off the screen. The first line and 200 characters
     * of it is the part a person reads anyway; the log above has the rest.
     */
    private static String reason(Exception e) {
        String message = text(e.getMessage());
        int newline = message.indexOf('\n');
        if (newline >= 0) {
            message = message.substring(0, newline).strip();
        }
        if (message.length() > 200) {
            message = message.substring(0, 200).strip() + "…";
        }
        return message.isEmpty() ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
    }

    private static String text(Object value) {
        return (value == null) ? "" : String.valueOf(value).strip();
    }
}
