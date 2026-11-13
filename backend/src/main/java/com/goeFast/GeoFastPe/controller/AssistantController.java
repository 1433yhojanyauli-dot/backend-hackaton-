package com.goeFast.GeoFastPe.controller;

import com.goeFast.GeoFastPe.service.AssistantLlmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/asistente")
@Tag(name = "Asistente", description = "Interpreta lenguaje natural en cualquier idioma y devuelve la accion a ejecutar en el mapa.")
public class AssistantController {

    private static final int MAX_TEXT = 1000;
    private static final int MAX_HISTORY = 8;
    private static final int MAX_TURN = 500;

    private final AssistantLlmService assistantLlmService;

    public AssistantController(AssistantLlmService assistantLlmService) {
        this.assistantLlmService = assistantLlmService;
    }

    @PostMapping("/interpretar")
    @Operation(summary = "Interpretar una instruccion",
            description = "Recibe texto en cualquier idioma y devuelve JSON con language, reply, action y los datos de la accion.")
    public JsonNode interpretar(@RequestBody AssistantRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe o dicta una instruccion.");
        }
        String text = request.text().trim();
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);

        String uiLanguage = request.uiLanguage() == null || request.uiLanguage().isBlank() ? "es" : request.uiLanguage();

        List<AssistantTurn> turns = request.history() == null ? List.of() : request.history();
        List<Map<String, String>> history = turns.subList(Math.max(0, turns.size() - MAX_HISTORY), turns.size()).stream()
                .filter(turn -> turn != null && turn.content() != null && !turn.content().isBlank())
                .map(turn -> Map.of(
                        "role", turn.role() == null ? "user" : turn.role(),
                        "content", turn.content().length() > MAX_TURN ? turn.content().substring(0, MAX_TURN) : turn.content()))
                .toList();

        return assistantLlmService.interpret(text, uiLanguage, history);
    }

    public record AssistantRequest(String text, String uiLanguage, List<AssistantTurn> history) {
    }

    public record AssistantTurn(String role, String content) {
    }
}
