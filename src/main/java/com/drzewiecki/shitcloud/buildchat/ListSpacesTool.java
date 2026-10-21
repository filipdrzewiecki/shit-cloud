package com.drzewiecki.shitcloud.buildchat;

import com.drzewiecki.shitcloud.chat.ChatTool;
import com.drzewiecki.shitcloud.chat.ToolResult;
import com.drzewiecki.shitcloud.chat.TurnContext;
import com.drzewiecki.shitcloud.data.DataService;
import com.drzewiecki.shitcloud.data.SpaceSummary;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * The apps and what data they hold, so the model can reuse a collection's shape instead of guessing it. Asked
 * for when needed rather than put into the system prompt: it stays current and costs nothing when unused.
 */
@Component
public class ListSpacesTool implements ChatTool {

    static final String NAME = "list_spaces";

    private final DataService data;
    private final JsonMapper json;

    public ListSpacesTool(DataService data, JsonMapper json) {
        this.data = data;
        this.json = json;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return """
                Lists the ShitCloud apps (not archived) with their data: each app's slug (= its space), name, \
                description, and its collections with document counts. Use sample_docs to see what the documents \
                of a collection look like.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type": "object", "properties": {}}""";
    }

    @Override
    public ToolResult run(Map<String, Object> input, TurnContext context) {
        List<SpaceSummary> spaces = data.spaces();
        return ToolResult.withModelText(Map.of("spaces", spaces.size()), json.writeValueAsString(spaces));
    }
}
