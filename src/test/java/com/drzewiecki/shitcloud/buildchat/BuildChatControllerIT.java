package com.drzewiecki.shitcloud.buildchat;

import com.drzewiecki.shitcloud.chat.ChatIntegrationTest;
import com.drzewiecki.shitcloud.data.DataService;
import com.drzewiecki.shitcloud.data.DataWrite;
import com.drzewiecki.shitcloud.model.DeployRequest;
import com.drzewiecki.shitcloud.service.AppRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.drzewiecki.shitcloud.chat.FakeChatModel.answer;
import static com.drzewiecki.shitcloud.chat.FakeChatModel.call;
import static com.drzewiecki.shitcloud.chat.FakeChatModel.step;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BuildChatControllerIT extends ChatIntegrationTest {

    private static final String APP_HTML = "<!doctype html><html><body>Lunch</body></html>";

    @Autowired
    private AppRegistry registry;

    @Autowired
    private DataService data;

    @Test
    void guestsAndUsers_cannotUseTheChat() throws Exception {
        for (RequestPostProcessor caller : List.of(guest("Anna"), idpUser("user@example.com", "User"))) {
            mvc.perform(post("/api/chat").with(caller).contentType(MediaType.APPLICATION_JSON).content(turn(null, "Hi", null)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/chat/conversations").with(caller)).andExpect(status().isForbidden());
        }
    }

    @Test
    void newConversation_streamsWithTheProtocolHeaders_andCanBeContinued() throws Exception {
        model.script(answer("Hello"), answer("Again"));

        MvcResult first = mvc.perform(post("/api/chat").with(builder()).contentType(MediaType.APPLICATION_JSON)
                        .content(turn(null, "Make a  lunch\npoll for the whole team, with votes per person", null)))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(first))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/event-stream;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("x-vercel-ai-ui-message-stream", "v1"))
                .andExpect(header().string("X-Accel-Buffering", "no"));
        List<Map<String, Object>> parts = parts(first.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(types(parts)).startsWith("start", "data-conversation").endsWith("finish", "[DONE]");
        @SuppressWarnings("unchecked")
        Map<String, Object> created = (Map<String, Object>) parts.get(1).get("data");
        assertThat(created.get("title")).isEqualTo("Make a lunch poll for the whole team, with votes per person");

        List<Map<String, Object>> second = chat(builder(), turn((String) created.get("id"), "Again", null));

        assertThat(types(second)).doesNotContain("data-conversation");
        assertThat(model.prompts().get(1).getInstructions()).extracting(Message::getText)
                .contains("Hello", "Again");
        mvc.perform(get("/api/chat/conversations/{id}", created.get("id")).with(builder()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[0].parts[0].text").value("Make a  lunch\npoll for the whole team, with votes per person"))
                .andExpect(jsonPath("$[1].role").value("assistant"))
                .andExpect(jsonPath("$[1].parts[1].text").value("Hello"));
    }

    @Test
    void longTitle_isCut() throws Exception {
        model.script(answer("ok"));

        List<Map<String, Object>> parts = chat(builder(), turn(null, "x".repeat(100), null));

        @SuppressWarnings("unchecked")
        String title = ((Map<String, String>) parts.get(1).get("data")).get("title");
        assertThat(title).hasSize(BuildChatController.TITLE_CHARS).endsWith("…");
    }

    @Test
    void draft_goesToThePreview_andIsKeptOnceInTheConversation() throws Exception {
        String html = "<!doctype html><html><body>Poll</body></html>";
        model.script(step(List.of("Building."), call("c1", "save_draft", json.writeValueAsString(Map.of("html", html)))),
                answer("Done"));

        List<Map<String, Object>> parts = chat(builder(), turn(null, "Poll", null));

        assertThat(parts).filteredOn(part -> "data-draft".equals(part.get("type"))).singleElement()
                .satisfies(part -> assertThat(part).containsEntry("id", "draft")
                        .containsEntry("data", nullTarget(html)));
        assertThat(parts).filteredOn(part -> "tool-output-available".equals(part.get("type"))).singleElement()
                .satisfies(part -> assertThat(part.get("output")).isEqualTo(Map.of("saved", true, "bytes", html.length())));
        String saved = jdbc.sql("select parts::text from chat_messages where role = 'assistant'").query(String.class).single();
        assertThat(saved.split("Poll</body>", -1)).hasSize(2);
    }

    @Test
    void rework_readsTheApp_andTheDraftNamesIt() throws Exception {
        registry.deploy(new DeployRequest("Lunch Poll", null, APP_HTML), BUILDER_EMAIL);
        String changed = APP_HTML.replace("Lunch", "Lunch v2");
        model.script(
                step(List.of(), call("c1", "get_app", "{\"slug\":\"lunch-poll\"}")),
                step(List.of(), call("c2", "save_draft", json.writeValueAsString(Map.of("html", changed)))),
                answer("Changed"));

        List<Map<String, Object>> parts = chat(builder(), turn(null, "Add a v2 label", "lunch-poll"));

        assertThat(model.prompts().getFirst().getInstructions().getFirst().getText())
                .contains("The user is changing the existing app `lunch-poll`");
        assertThat(parts).filteredOn(part -> "tool-output-available".equals(part.get("type")))
                .first().satisfies(part -> assertThat(part.get("output"))
                        .isEqualTo(Map.of("slug", "lunch-poll", "bytes", APP_HTML.length())));
        // The model got the source; the user only its size.
        List<Message> second = model.prompts().get(1).getInstructions();
        assertThat(((ToolResponseMessage) second.getLast()).getResponses().getFirst().responseData())
                .isEqualTo("Source code of the app lunch-poll:\n\n" + APP_HTML);
        assertThat(parts).filteredOn(part -> "data-draft".equals(part.get("type"))).singleElement()
                .satisfies(part -> assertThat(part.get("data")).isEqualTo(Map.of("html", changed, "targetSlug", "lunch-poll")));
        mvc.perform(get("/api/chat/conversations").with(builder()))
                .andExpect(jsonPath("$[0].targetSlug").value("lunch-poll"));
    }

    @Test
    void edits_applyToTheDeployedApp_thenToTheDraft_alsoInTheNextMessage() throws Exception {
        registry.deploy(new DeployRequest("Lunch Poll", null, APP_HTML), BUILDER_EMAIL);
        model.script(
                step(List.of(), call("c1", "get_app", "{\"slug\":\"lunch-poll\"}")),
                step(List.of("Renaming."), call("c2", "edit_draft", edits("Lunch", "Lunch v2"))),
                step(List.of(), call("c3", "edit_draft", edits("v2", "v3"))),
                answer("Changed"),
                step(List.of(), call("c4", "edit_draft", edits("v3", "v4"))),
                answer("Again"));

        List<Map<String, Object>> first = chat(builder(), turn(null, "Rename it", "lunch-poll"));

        assertThat(drafts(first)).containsExactly(
                Map.of("html", APP_HTML.replace("Lunch", "Lunch v2"), "targetSlug", "lunch-poll"),
                Map.of("html", APP_HTML.replace("Lunch", "Lunch v3"), "targetSlug", "lunch-poll"));
        assertThat(parts(first, "tool-output-available")).extracting(part -> part.get("output")).last()
                .isEqualTo(Map.of("edited", 1, "bytes", APP_HTML.length() + 3));
        // The deployed app is untouched until the user deploys the draft.
        assertThat(registry.html("lunch-poll")).isEqualTo(APP_HTML);

        @SuppressWarnings("unchecked")
        String id = ((Map<String, String>) first.get(1).get("data")).get("id");
        List<Map<String, Object>> second = chat(builder(), turn(id, "Once more", null));

        assertThat(drafts(second)).containsExactly(
                Map.of("html", APP_HTML.replace("Lunch", "Lunch v4"), "targetSlug", "lunch-poll"));
    }

    @Test
    void failedEdit_isShownAndHandedToTheModel() throws Exception {
        model.script(
                step(List.of(), call("c1", "save_draft", json.writeValueAsString(Map.of("html", APP_HTML)))),
                step(List.of(), call("c2", "edit_draft", edits("Dinner", "Supper"))),
                answer("Sorry"));

        List<Map<String, Object>> parts = chat(builder(), turn(null, "Poll", null));

        assertThat(parts(parts, "tool-output-error")).singleElement()
                .satisfies(part -> assertThat((String) part.get("errorText")).startsWith("edit 1: old_text not found"));
        assertThat(drafts(parts)).hasSize(1);
    }

    @Test
    void dataTools_showTheModelSpacesAndDocuments_andTheUserOnlyCounts() throws Exception {
        registry.deploy(new DeployRequest("Lunch Poll", "Votes", APP_HTML), BUILDER_EMAIL);
        data.set("lunch-poll", "votes", "anna", new DataWrite(Map.of("dish", "pierogi"), null), "guest:Anna");
        model.script(
                step(List.of(), call("c1", "list_spaces", "{}")),
                step(List.of(), call("c2", "sample_docs", "{\"space\":\"lunch-poll\",\"collection\":\"votes\",\"n\":9}")),
                step(List.of(), call("c3", "sample_docs", "{\"space\":\"nope\",\"collection\":\"votes\"}")),
                answer("I see"));

        List<Map<String, Object>> parts = chat(builder(), turn(null, "What data is there?", null));

        assertThat(parts(parts, "tool-output-available")).extracting(part -> part.get("output")).containsExactly(
                Map.of("spaces", 1),
                Map.of("space", "lunch-poll", "collection", "votes", "docs", 1));
        assertThat(parts(parts, "tool-output-error")).singleElement()
                .satisfies(part -> assertThat((String) part.get("errorText")).endsWith("; see list_spaces"));
        List<Message> last = model.prompts().getLast().getInstructions();
        List<ToolResponseMessage> responses = last.stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).toList();
        assertThat(responses.get(0).getResponses().getFirst().responseData())
                .contains("\"space\":\"lunch-poll\"", "\"collections\":{\"votes\":1}");
        assertThat(responses.get(1).getResponses().getFirst().responseData())
                .startsWith("Newest 1 document(s) of lunch-poll / votes:")
                .contains("\"dish\":\"pierogi\"", "\"_updatedBy\":\"guest:Anna\"");
        // What the model read is not saved with the conversation.
        String saved = jdbc.sql("select parts::text from chat_messages where role = 'assistant'").query(String.class).single();
        assertThat(saved).doesNotContain("pierogi");
    }

    @Test
    void conversations_belongToTheirBuilder() throws Exception {
        model.script(answer("Mine"));
        List<Map<String, Object>> parts = chat(builder(), turn(null, "Mine", null));
        @SuppressWarnings("unchecked")
        String id = ((Map<String, String>) parts.get(1).get("data")).get("id");
        RequestPostProcessor other = idpUser("other@example.com", "Other", "ShitCloud Builder");

        mvc.perform(get("/api/chat/conversations").with(builder())).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].title").value("Mine"));
        mvc.perform(get("/api/chat/conversations").with(other)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/chat/conversations/{id}", id).with(other))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(post("/api/chat").with(other).contentType(MediaType.APPLICATION_JSON).content(turn(id, "Hi", null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void refusals_comeBeforeTheStream_asPlainErrors() throws Exception {
        mvc.perform(post("/api/chat").with(builder()).contentType(MediaType.APPLICATION_JSON).content(turn(null, "  ", null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(post("/api/chat").with(builder()).contentType(MediaType.APPLICATION_JSON)
                        .content(turn(null, "x".repeat(BuildChatController.MAX_MESSAGE_CHARS + 1), null)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/chat").with(builder()).contentType(MediaType.APPLICATION_JSON).content(turn(null, "Hi", "nope")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NO_SUCH_APP"));
        mvc.perform(post("/api/chat").with(builder()).contentType(MediaType.APPLICATION_JSON).content(turn("c_missing", "Hi", null)))
                .andExpect(status().isNotFound());
        assertThat(model.prompts()).isEmpty();
    }

    private List<Map<String, Object>> chat(RequestPostProcessor caller, String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/chat").with(caller).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        return parts(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String turn(String conversationId, String text, String targetSlug) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("conversationId", conversationId);
        body.put("message", Map.of("id", "u1", "role", "user", "parts", List.of(Map.of("type", "text", "text", text))));
        body.put("targetSlug", targetSlug);
        return json.writeValueAsString(body);
    }

    private String edits(String oldText, String newText) {
        return json.writeValueAsString(Map.of("edits", List.of(Map.of("old_text", oldText, "new_text", newText))));
    }

    private static List<Map<String, Object>> parts(List<Map<String, Object>> parts, String type) {
        return parts.stream().filter(part -> type.equals(part.get("type"))).toList();
    }

    private static List<Object> drafts(List<Map<String, Object>> parts) {
        return parts(parts, "data-draft").stream().map(part -> part.get("data")).toList();
    }

    private static Map<String, Object> nullTarget(String html) {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("html", html);
        data.put("targetSlug", null);
        return data;
    }
}
