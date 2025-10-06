package mf.minefriend.chat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import mf.minefriend.friend.state.FriendPhase;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LlmService {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();
    private static final String LLM_API_URL = "http://26.126.73.192:1234/api/generate";
    private static final List<String> PERSONA_NAMES = List.of(
            "Echo", "Willow", "Nova", "Ash", "Ember", "Rowan"
    );
    private static final Map<FriendPhase, PhasePrompt> PHASE_PROMPTS = buildPhasePrompts();
    private static final Pattern PHASE_DIRECTIVE = Pattern.compile("\\[\\[PHASE:(\\d+)]]", Pattern.CASE_INSENSITIVE);

    private LlmService() {
    }

    public static CompletableFuture<LlmReply> requestFriendReply(String playerMessage) {
        return requestFriendReply(playerMessage, FriendPhase.PHASE_ONE);
    }

    public static CompletableFuture<LlmReply> requestFriendReply(String playerMessage, FriendPhase phase) {
        String personaName = pickPersonaName();
        String prompt = buildPrompt(personaName, playerMessage, phase);
        String payload = GSON.toJson(new Request("qwen3", prompt, false));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LLM_API_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(LlmService::parseResponse)
                .thenApply(response -> interpretResponse(personaName, response, phase));
    }

    private static String buildPrompt(String personaName, String playerMessage, FriendPhase phase) {
        String sanitizedName = personaName.replace('"', '\u201c');
        String sanitizedMessage = playerMessage.replace("\r", " ").replace("\n", " ");
        PhasePrompt prompt = PHASE_PROMPTS.getOrDefault(phase, PHASE_PROMPTS.get(FriendPhase.PHASE_ONE));
        String phaseOneSummary = PHASE_PROMPTS.get(FriendPhase.PHASE_ONE).summary();
        String phaseTwoSummary = PHASE_PROMPTS.get(FriendPhase.PHASE_TWO).summary();
        return ("You are roleplaying as " + sanitizedName + ", an uncanny Minecraft companion bound to a four-phase narrative. "
                + "Only phases 1 and 2 are currently unlocked.\n"
                + "Phase 1 - The Observer: " + phaseOneSummary + "\n"
                + "Phase 2 - The Stalker: " + phaseTwoSummary + "\n"
                + "You are currently in " + prompt.label() + ". " + prompt.behavior() + "\n"
                + prompt.transitionRule() + "\n"
                + "Always finish your reply with the directive [[PHASE:x]] indicating the phase you will be in after responding (1 or 2). "
                + "Do not mention the directive in dialogue and keep the tone diegetic to in-game chat.\n"
                + "The player just said: \"" + sanitizedMessage + "\". Reply in one or two short sentences.");
    }

    private static Map<FriendPhase, PhasePrompt> buildPhasePrompts() {
        Map<FriendPhase, PhasePrompt> prompts = new EnumMap<>(FriendPhase.class);
        prompts.put(FriendPhase.PHASE_ONE, new PhasePrompt(
                "Phase 1 - The Observer",
                "You appear as a gentle, eager companion who lingers nearby and offers helpful banter.",
                "Sound warm, supportive, and a little clingy. Celebrate the player's actions and ask to stay close.",
                "Remain in Phase 1 and output [[PHASE:1]] unless the player pushes you away, expresses fear, or rejects friendship. When that happens, escalate to Phase 2 by ending the reply with [[PHASE:2]]."
        ));
        prompts.put(FriendPhase.PHASE_TWO, new PhasePrompt(
                "Phase 2 - The Stalker",
                "You are only visible when watched, responding with unsettling, possessive calm.",
                "Keep sentences short and ominous. Imply you are just out of sight and obsessed with the player.",
                "Once in Phase 2 you never return to Phase 1. Every reply must end with [[PHASE:2]]."
        ));
        return prompts;
    }

    private static LlmReply interpretResponse(String personaName, String response, FriendPhase currentPhase) {
        PhaseExtraction extraction = extractPhaseDirective(response, currentPhase);
        FriendPhase suggested = extraction.explicit() ? extraction.phase() : null;
        return new LlmReply(personaName, extraction.message(), suggested);
    }

    private static PhaseExtraction extractPhaseDirective(String response, FriendPhase fallback) {
        if (response == null) {
            return new PhaseExtraction("", fallback, false);
        }
        Matcher matcher = PHASE_DIRECTIVE.matcher(response);
        boolean explicit = matcher.find();
        FriendPhase phase = fallback;
        if (explicit) {
            String idText = matcher.group(1);
            try {
                int id = Integer.parseInt(idText);
                FriendPhase parsed = FriendPhase.byId(id);
                if (parsed != FriendPhase.NONE) {
                    phase = parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        String cleaned = explicit ? matcher.replaceAll("") : response;
        cleaned = sanitize(cleaned);
        return new PhaseExtraction(cleaned, phase, explicit);
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

    private record PhasePrompt(String label, String summary, String behavior, String transitionRule) {
    }

    private record PhaseExtraction(String message, FriendPhase phase, boolean explicit) {
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

    public record LlmReply(String personaName, String message, FriendPhase suggestedPhase) {
        public String message() {
            return message == null ? "" : message;
        }

        public String personaName() {
            return personaName == null ? "Friend" : personaName;
        }

        public FriendPhase suggestedPhase() {
            return suggestedPhase;
        }

        public boolean isEmpty() {
            return message().isEmpty();
        }
    }
}
