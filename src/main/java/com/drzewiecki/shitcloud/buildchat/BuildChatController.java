package com.drzewiecki.shitcloud.buildchat;

import com.drzewiecki.shitcloud.chat.ChatEngine;
import com.drzewiecki.shitcloud.chat.ChatTool;
import com.drzewiecki.shitcloud.chat.Conversation;
import com.drzewiecki.shitcloud.chat.ConversationStore;
import com.drzewiecki.shitcloud.chat.TurnContext;
import com.drzewiecki.shitcloud.chat.UiMessage;
import com.drzewiecki.shitcloud.chat.UiStreamWriter;
import com.drzewiecki.shitcloud.security.AuthenticationUtils;
import com.drzewiecki.shitcloud.service.AppRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The build chat: {@code POST /api/chat} answers as the AI SDK UI message stream (SSE,
 * {@code .claude/docs/chat-protocol.md}); the conversations are saved per builder. Everything that can be refused
 * is checked before the stream starts, so the client gets a plain status with {@code {code, message}} for it.
 */
@RestController
@RequestMapping("/api/chat")
@PreAuthorize("hasRole('BUILDER')")
public class BuildChatController {

    static final int MAX_MESSAGE_CHARS = 20_000;
    static final int TITLE_CHARS = 60;
    static final int FILE_NAME_CHARS = 100;

    private final ChatEngine engine;
    private final ConversationStore conversations;
    private final BuildChatPrompt prompt;
    private final List<ChatTool> tools;
    private final AppRegistry registry;
    private final Uploads uploads;
    private final DataSize maxUploadSize;
    private final JsonMapper json;
    private final boolean available;

    public BuildChatController(ChatEngine engine, ConversationStore conversations, BuildChatPrompt prompt,
                               SaveDraftTool saveDraft, EditDraftTool editDraft, GetAppTool getApp,
                               ListSpacesTool listSpaces, SampleDocsTool sampleDocs, GetDepsTool getDeps,
                               ProposeMigrationTool proposeMigration, GetUploadTool getUpload,
                               ReportChangesTool reportChanges, AppRegistry registry, Uploads uploads, JsonMapper json,
                               @Value("${chat.max-upload-size}") DataSize maxUploadSize,
                               @Value("${spring.ai.anthropic.api-key:}") String apiKey) {
        this.engine = engine;
        this.conversations = conversations;
        this.prompt = prompt;
        this.tools = List.of(saveDraft, editDraft, getApp, getUpload, listSpaces, sampleDocs, getDeps,
                proposeMigration, reportChanges);
        this.registry = registry;
        this.uploads = uploads;
        this.maxUploadSize = maxUploadSize;
        this.json = json;
        this.available = !apiKey.isBlank();
    }

    /**
     * Only the new message travels: the server holds the conversation. {@code targetSlug} starts a conversation
     * that reworks an existing app; {@code upload} one that adapts an uploaded HTML file. A continued conversation
     * keeps what it started with.
     */
    public record TurnRequest(String conversationId, UiMessage message, String targetSlug, UploadRequest upload) {
    }

    public record UploadRequest(String fileName, String html) {
    }

    /**
     * @param upload the file name an import conversation started from; null otherwise
     */
    public record ConversationSummary(String id, String title, String targetSlug, String upload, Instant updatedAt) {
    }

    public record ChatError(String code, String message) {
    }

    @PostMapping
    public ResponseEntity<StreamingResponseBody> chat(@RequestBody TurnRequest request) {
        if (!available) {
            throw new ChatRequestException(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_UNAVAILABLE",
                    "The chat has no model key configured (ANTHROPIC_API_KEY)");
        }
        String caller = AuthenticationUtils.currentUser().id();
        UiMessage message = userMessage(request.message());

        boolean isNew = request.conversationId() == null || request.conversationId().isBlank();
        UploadRequest upload = request.upload() == null ? null : checkUpload(request.upload(), isNew, request.targetSlug());
        Conversation conversation = isNew
                ? newConversation(caller, message, request.targetSlug(), upload)
                : conversations.find(request.conversationId(), caller).orElseThrow(() -> new ChatRequestException(
                HttpStatus.NOT_FOUND, "NOT_FOUND", "No conversation " + request.conversationId()));
        String uploadName = upload != null ? upload.fileName()
                : uploads.find(conversation.id()).map(Uploads.Upload::fileName).orElse(null);

        ChatEngine.Turn turn = new ChatEngine.Turn(new TurnContext(caller, conversation), message, isNew,
                prompt.forConversation(conversation.targetSlug(), uploadName), tools);
        StreamingResponseBody body = out -> engine.answer(turn, new UiStreamWriter(out, json));
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_EVENT_STREAM, StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("x-vercel-ai-ui-message-stream", "v1")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    /**
     * The caller's own conversations, most recently continued first.
     */
    @GetMapping("/conversations")
    public List<ConversationSummary> list() {
        List<Conversation> mine = conversations.list(AuthenticationUtils.currentUser().id());
        Map<String, String> uploaded = uploads.fileNames(mine.stream().map(Conversation::id).toList());
        return mine.stream()
                .map(c -> new ConversationSummary(c.id(), c.title(), c.targetSlug(), uploaded.get(c.id()), c.updatedAt()))
                .toList();
    }

    /**
     * The messages as {@code useChat} takes them. Someone else's conversation is 404, as if it did not exist.
     */
    @GetMapping("/conversations/{id}")
    public List<UiMessage> messages(@PathVariable String id) {
        Conversation conversation = conversations.find(id, AuthenticationUtils.currentUser().id())
                .orElseThrow(() -> new ChatRequestException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No conversation " + id));
        return conversations.messages(conversation.id());
    }

    /**
     * The file an import conversation started from, for its "Before" view.
     */
    @GetMapping("/conversations/{id}/upload")
    public Uploads.Upload upload(@PathVariable String id) {
        Conversation conversation = conversations.find(id, AuthenticationUtils.currentUser().id())
                .orElseThrow(() -> new ChatRequestException(HttpStatus.NOT_FOUND, "NOT_FOUND", "No conversation " + id));
        return uploads.find(conversation.id())
                .orElseThrow(() -> new ChatRequestException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "The conversation did not start from a file"));
    }

    @ExceptionHandler(ChatRequestException.class)
    public ResponseEntity<ChatError> refused(ChatRequestException e) {
        return ResponseEntity.status(e.status).contentType(MediaType.APPLICATION_JSON)
                .body(new ChatError(e.code, e.getMessage()));
    }

    private Conversation newConversation(String caller, UiMessage message, String targetSlug, UploadRequest upload) {
        String slug = targetSlug == null || targetSlug.isBlank() ? null : targetSlug;
        if (slug != null && !registry.exists(slug)) {
            throw new ChatRequestException(HttpStatus.NOT_FOUND, "NO_SUCH_APP", "No app " + slug);
        }
        if (upload == null) {
            return conversations.create(caller, title(message.text()), slug);
        }
        Conversation conversation = conversations.create(caller, title("Import " + upload.fileName()), null);
        uploads.save(conversation.id(), upload.fileName(), upload.html());
        return conversation;
    }

    /**
     * An uploaded file starts a new conversation that changes no existing app. It must be a whole HTML document the
     * model can read in one piece ({@code chat.max-upload-size}).
     *
     * @return the upload with a clean file name: no path, at most {@value #FILE_NAME_CHARS} characters
     */
    private UploadRequest checkUpload(UploadRequest upload, boolean isNew, String targetSlug) {
        if (!isNew || (targetSlug != null && !targetSlug.isBlank())) {
            throw new ChatRequestException(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                    "A file can only start a new conversation that creates an app");
        }
        String html = upload.html();
        if (html == null || !html.toLowerCase().contains("<html")) {
            throw new ChatRequestException(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                    "The file must be a whole HTML document with an <html> element");
        }
        long bytes = html.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxUploadSize.toBytes()) {
            throw new ChatRequestException(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE", "The file has " + bytes
                    + " bytes; the chat adapts files up to " + maxUploadSize.toKilobytes() + " KB - deploy it as is");
        }
        String name = upload.fileName() == null ? "" : fileName(upload.fileName());
        if (name.isEmpty()) {
            name = "upload.html";
        }
        return new UploadRequest(name.length() > FILE_NAME_CHARS ? name.substring(0, FILE_NAME_CHARS) : name, html);
    }

    /** The name without any folder part ("C:\x\planner.html" and "x/planner.html" give "planner.html"). */
    static String fileName(String path) {
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(cut + 1).strip();
    }

    /**
     * Only the text is taken from the client; the role is always "user".
     */
    private static UiMessage userMessage(UiMessage message) {
        String text = message == null ? "" : message.text().strip();
        if (text.isEmpty()) {
            throw new ChatRequestException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "The message has no text");
        }
        if (text.length() > MAX_MESSAGE_CHARS) {
            throw new ChatRequestException(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                    "The message is longer than " + MAX_MESSAGE_CHARS + " characters");
        }
        String id = message.id() == null || message.id().isBlank() || message.id().length() > 100
                ? "u_" + UUID.randomUUID().toString().replace("-", "")
                : message.id();
        return UiMessage.userText(id, text);
    }

    private static String title(String text) {
        String line = text.replaceAll("\\s+", " ").strip();
        return line.length() <= TITLE_CHARS ? line : line.substring(0, TITLE_CHARS - 1) + "…";
    }

    static class ChatRequestException extends RuntimeException {

        final HttpStatus status;
        final String code;

        ChatRequestException(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
