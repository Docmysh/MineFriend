package mf.minefriend.chat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public final class LlmService {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();
    private static final String LLM_API_URL = "http://26.126.73.192:1234/api/generate";
    private static final List<String> PERSONA_NAMES = List.of(
            "Echo", "Willow", "Nova", "Ash", "Ember", "Rowan"
    );

    private LlmService() {
    }

    public static CompletableFuture<LlmReply> requestFriendReply(String playerMessage) {
        String personaName = pickPersonaName();
        String prompt = buildPrompt(personaName, playerMessage);
        String payload = GSON.toJson(new Request("qwen3", prompt, false));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LLM_API_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(LlmService::parseResponse)
                .thenApply(response -> new LlmReply(personaName, response));
    }

    private static String buildPrompt(String personaName, String playerMessage) {
        String sanitizedName = personaName.replace('"', '\u201c');
        String sanitizedMessage = playerMessage.replace("\r", " ").replace("\n", " ");
        return "You are a friendly Minecraft player named " + sanitizedName + ". "
                + "A player said to you: '" + sanitizedMessage + "'. How do you respond?";
    }

    private static String pickPersonaName() {
        int index = ThreadLocalRandom.current().nextInt(PERSONA_NAMES.size());
        return PERSONA_NAMES.get(index);
    }

    private static String parseResponse(String jsonBody) {
        try {
            JsonElement parsed = JsonParser.parseString(jsonBody);
            if (parsed == null || parsed.isJsonNull()) {
                return "";
            }
            if (parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                if (object.has("response")) {
                    return sanitize(object.get("response"));
                }
                if (object.has("message")) {
                    JsonElement messageElement = object.get("message");
                    if (messageElement.isJsonObject()) {
                        JsonObject messageObject = messageElement.getAsJsonObject();
                        if (messageObject.has("content")) {
                            return sanitize(messageObject.get("content"));
                        }
                    } else {
                        return sanitize(messageElement);
                    }
                }
                if (object.has("choices")) {
                    JsonElement choicesElement = object.get("choices");
                    if (choicesElement.isJsonArray()) {
                        JsonArray choices = choicesElement.getAsJsonArray();
                        if (!choices.isEmpty()) {
                            JsonObject firstChoice = choices.get(0).getAsJsonObject();
                            if (firstChoice.has("message")) {
                                JsonObject message = firstChoice.getAsJsonObject("message");
                                if (message.has("content")) {
                                    return sanitize(message.get("content"));
                                }
                            }
                            if (firstChoice.has("text")) {
                                return sanitize(firstChoice.get("text"));
                            }
                        }
                    }
                }
            }
            return jsonBody;
        } catch (JsonSyntaxException ex) {
            return jsonBody;
        }
    }

    private static String sanitize(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        String text = element.getAsString();
        return sanitize(text);
    }

    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r", " ").replace("\n", " ").trim();
        return normalized.replaceAll("\\s+", " ");
    }

    private static class Request {
        private final String model;
        private final String prompt;
        private final boolean stream;

        private Request(String model, String prompt, boolean stream) {
            this.model = model;
            this.prompt = prompt;
            this.stream = stream;
        }
    }

    public record LlmReply(String personaName, String message) {
        public String message() {
            return message == null ? "" : message;
        }

        public String personaName() {
            return personaName == null ? "Friend" : personaName;
        }

        public boolean isEmpty() {
            return message().isEmpty();
        }
    }
}
