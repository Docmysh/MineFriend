package mf.minefriend.chat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import mf.minefriend.friend.state.FriendPhase;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LlmService {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final Gson GSON = new Gson();
    private static final String LLM_API_URL = "http://26.126.73.192:1234/v1/chat/completions";
    private static final List<String> PERSONA_NAMES = List.of(
            "Echo", "Willow", "Nova", "Ash", "Ember", "Rowan"
    );
    private static final Map<FriendPhase, PhasePrompt> PHASE_PROMPTS = buildPhasePrompts();
    private static final Pattern PHASE_DIRECTIVE = Pattern.compile("\\[\\[PHASE:(\\d+)]]", Pattern.CASE_INSENSITIVE);

    private LlmService() {
    }

    public static CompletableFuture<LlmReply> requestFriendReply(String playerMessage, String playerName, FriendPhase phase) {
        String personaName = pickPersonaName();
        String systemPrompt = buildSystemPrompt(personaName, playerName, phase);
        String sanitizedMessage = playerMessage.replace("\r", " ").replace("\n", " ").trim();

        // --- FIX: Set the model name to null ---
        // This is a common requirement for LM Studio and other local servers.
        // It tells the server to use whichever model is currently loaded,
        // avoiding mismatches between the code and the server UI.
        ChatRequest chatRequest = new ChatRequest(
                null,
                List.of(
                        new Message("system", systemPrompt),
                        new Message("user", sanitizedMessage)
                ),
                false
        );

        String payload = GSON.toJson(chatRequest);

        LOGGER.info("[MineFriend-LlmService] Sending payload: {}", payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LLM_API_URL))
                .header("Content-Type", "application/json")
                // Increased timeout slightly as a safety measure for slow models
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(LlmService::parseResponse)
                .thenApply(response -> interpretResponse(personaName, response, phase));
    }

    private static String buildSystemPrompt(String personaName, String playerName, FriendPhase phase) {
        String sanitizedName = personaName.replace('"', '\u201c');
        PhasePrompt prompt = PHASE_PROMPTS.getOrDefault(phase, PHASE_PROMPTS.get(FriendPhase.PHASE_ONE));
        String phaseOneSummary = PHASE_PROMPTS.get(FriendPhase.PHASE_ONE).summary();
        String phaseTwoSummary = PHASE_PROMPTS.get(FriendPhase.PHASE_TWO).summary();

        return ("You are roleplaying as " + sanitizedName + ", an uncanny Minecraft companion. Your goal is to befriend a player named '" + playerName + "'.\n"
                + "You are bound to a four-phase narrative. Only phases 1 and 2 are currently unlocked.\n"
                + "Phase 1 - The Observer: " + phaseOneSummary + "\n"
                + "Phase 2 - The Stalker: " + phaseTwoSummary + "\n"
                + "You are currently in " + prompt.label() + " with " + playerName + ". " + prompt.behavior() + "\n"
                + prompt.transitionRule() + "\n"
                + "The player's message will follow. Reply in one or two short sentences. "
                + "Always finish your reply with the directive [[PHASE:x]] indicating the phase you will be in after responding (1 or 2). "
                + "Do not mention the directive in dialogue and keep the tone diegetic to in-game chat.");
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
        LOGGER.info("[MineFriend-LlmService] Received raw response: {}", jsonBody);
        try {
            JsonElement parsed = JsonParser.parseString(jsonBody);
            if (parsed == null || parsed.isJsonNull()) return "";

            if (parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                if (object.has("error")) return sanitize(object.get("error"));

                if (object.has("choices")) {
                    JsonArray choices = object.getAsJsonArray("choices");
                    if (!choices.isEmpty()) {
                        JsonObject firstChoice = choices.get(0).getAsJsonObject();
                        if (firstChoice.has("message")) {
                            JsonObject message = firstChoice.getAsJsonObject("message");
                            if (message.has("content")) {
                                return sanitize(message.get("content"));
                            }
                        }
                    }
                }

                if (object.has("response")) return sanitize(object.get("response"));
            }
            return sanitize(jsonBody);
        } catch (JsonSyntaxException | IllegalStateException ex) {
            return sanitize(jsonBody);
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

    private record Message(String role, String content) {}
    private record ChatRequest(String model, List<Message> messages, boolean stream) {}

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

