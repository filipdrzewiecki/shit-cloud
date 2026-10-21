package com.drzewiecki.shitcloud.chat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The latest data part ({@code data-<name>}) of the conversation, for tools that build on what an earlier tool
 * produced — e.g. an edit applied to the current draft. The answer in progress comes first, then the saved
 * messages, newest first.
 */
@FunctionalInterface
public interface DataParts {

    DataParts NONE = (name, id) -> Optional.empty();

    /**
     * @param id the part's id; null matches parts without one
     * @return the part's {@code data}
     */
    Optional<Object> latest(String name, String id);

    /**
     * @param answer the parts of the answer in progress, which are not saved yet
     */
    static DataParts of(List<UiMessage> saved, List<Map<String, Object>> answer) {
        return (name, id) -> {
            Optional<Object> found = find(answer, name, id);
            for (int i = saved.size() - 1; found.isEmpty() && i >= 0; i--) {
                found = find(saved.get(i).parts(), name, id);
            }
            return found;
        };
    }

    private static Optional<Object> find(List<Map<String, Object>> parts, String name, String id) {
        for (int i = parts.size() - 1; i >= 0; i--) {
            Map<String, Object> part = parts.get(i);
            if (("data-" + name).equals(part.get("type")) && Objects.equals(id, part.get("id"))) {
                return Optional.ofNullable(part.get("data"));
            }
        }
        return Optional.empty();
    }
}
