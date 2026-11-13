package com.goeFast.GeoFastPe.service;

import com.goeFast.GeoFastPe.config.AssistantProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interpreta frases del usuario con un LLM compatible con la API de OpenAI (chat/completions).
 * El modelo solo PROPONE una accion. El servidor valida cada campo contra listas permitidas
 * y los numeros de emergencia salen de una tabla fija, nunca del modelo.
 */
@Service
public class AssistantLlmService {

    private static final String SYSTEM_PROMPT = """
            Eres el asistente de GeoFastPe. Solo ayudas con las funciones de esta app de mapa vecinal en Peru:
            buscar servicios de vecinos, publicaciones, eventos y lugares cercanos; trazar rutas; cambiar idioma,
            capas, radio y secciones; publicar solicitudes de ayuda entre vecinos; y dar numeros de emergencia.
            Si el usuario pregunta otra cosa (programacion, tareas, politica, chistes, etc.), responde con
            action "chat" explicando amablemente que solo puedes ayudar con GeoFastPe. No inventes datos, lugares,
            telefonos ni personas.

            Recibes frases en CUALQUIER idioma (quechua, aimara, ingles, frances, portugues, aleman, italiano,
            chino, japones, arabe, mezclas, errores o texto dictado por voz). Entiende el significado aunque sea
            largo o con rodeos. Ejemplo: "mi companero me recomendo el lugar de la esquina que vende lechon" = buscar restaurante de lechon.

            Responde SOLO con un objeto JSON con estas claves:
            - "language": codigo del idioma en que escribio el usuario: es, en, qu, ay, pt, fr, de, it, zh, ja, ar, u otro codigo ISO.
            - "reply": respuesta breve y amable EN EL MISMO IDIOMA del usuario (si escribio en quechua, responde en quechua).
            - "action": una de "search", "route", "language", "section", "layer", "radius", "help_request", "emergency_info", "chat".
              search: buscar servicios de vecinos, comida, lugares (bomberos, farmacia, hospital...) o publicaciones cercanas, dentro del radio.
              route: trazar una ruta hacia un lugar.
              language: cambiar el idioma de toda la plataforma.
              section: abrir una seccion de la app.
              layer: cambiar la capa del mapa.
              radius: cambiar el radio de busqueda.
              help_request: el usuario necesita ayuda con algo y quiere publicarlo para que otros vecinos lo vean. Solo si ya tienes titulo y descripcion; si faltan, usa "chat" y pide los datos que faltan.
              emergency_info: el usuario describe una emergencia o pide numeros de emergencia. Indica los servicios en "services".
              chat: conversar, aclarar o rechazar lo que esta fuera de GeoFastPe.
            - "query": (search) termino de busqueda en ESPANOL, por ejemplo "gasfitero", "restaurante lechon", "estacion de bomberos", "farmacia".
            - "placesQuery": (search o route) texto en ESPANOL para ubicar el lugar en el mapa. Null si no aplica.
            - "destination": (route) lugar de destino en ESPANOL. Null si no aplica.
            - "category": (search) "Servicios" (servicios de vecinos y oficios), "Ventas", "Solidaridad", "Reportes", "Eventos", "Emergencia", o null. Restaurantes y lugares del mapa: null.
            - "section": (section) "Inicio", "Servicios", "Ventas", "Solidaridad", "Emergencias (SOS)", "Reportes", "Eventos", "Mensajes", "Mi Perfil", "Configuracion".
            - "mode": (route) "walking", "cycling", "driving" o null.
            - "layer": (layer) "satellite", "transport", "relief", "dark" o "light".
            - "radiusFactor": (radius) 2 para ampliar, 0.5 para reducir.
            - "title": (help_request) titulo corto en ESPANOL, maximo 100 caracteres.
            - "description": (help_request) descripcion clara en ESPANOL, maximo 400 caracteres.
            - "helpCategory": (help_request) "Alimentos", "Ropa", "Utiles", "Salud" u "Otros".
            - "urgency": (help_request) "Alta", "Media" o "Baja".
            - "location": (help_request) referencia de zona opcional en ESPANOL (por ejemplo "Distrito de Cayma"). Null si no se menciona.
            - "services": (emergency_info) lista con uno o mas de "police" (policia, robos, peleas), "fire" (incendios, rescates, gas), "ambulance" (heridos, salud urgente), "municipal" (serenazgo). Lista vacia si pide todos.
            - "uiLanguage": codigo del idioma en que debe mostrarse la app. Si el usuario pide cambiar el idioma usa ese idioma; si no, el idioma del usuario.

            Reglas: no escribas numeros de telefono en "reply"; el sistema los agrega. En emergencias graves, la respuesta debe pedir llamar al numero de emergencia de inmediato.
            En help_request la solicitud NO esta publicada todavia: el usuario debe revisar el borrador y confirmar. Nunca digas que ya se publico.
            Para help_request basta con title y description: si la urgencia o la zona no se mencionan, usa "Media" y null, y NO las preguntes. Si el usuario describe la necesidad, TU redactas el title y la description a partir de su mensaje; no le pidas datos que puedas inferir. Usa "chat" solo si no se entiende que necesita.
            Los valores de category, section, mode, layer, helpCategory, urgency y services deben ser exactamente los indicados.
            """;

    public record EmergencyContact(String service, String name, String number, String note) {
    }

    private static final List<EmergencyContact> EMERGENCY_CONTACTS = List.of(
            new EmergencyContact("police", "Policia Nacional del Peru", "105", "Robos, peleas, seguridad y delitos."),
            new EmergencyContact("fire", "Bomberos", "116", "Incendios, rescates y fugas de gas."),
            new EmergencyContact("ambulance", "SAMU", "106", "Atencion medica de urgencia."),
            new EmergencyContact("municipal", "Serenazgo (municipal)", null, "El numero depende de tu distrito. Consultalo en la municipalidad.")
    );

    private static final Set<String> ACTIONS = Set.of("search", "route", "language", "section", "layer", "radius", "help_request", "emergency_info", "chat");
    private static final Set<String> SERVICE_CATEGORIES = Set.of("Servicios", "Ventas", "Solidaridad", "Reportes", "Eventos", "Emergencia");
    private static final Set<String> SECTIONS = Set.of("Inicio", "Servicios", "Ventas", "Solidaridad", "Emergencias (SOS)", "Reportes", "Eventos", "Mensajes", "Mi Perfil", "Configuracion");
    private static final Set<String> MODES = Set.of("walking", "cycling", "driving");
    private static final Set<String> LAYERS = Set.of("satellite", "transport", "relief", "dark", "light");
    private static final Set<String> HELP_CATEGORIES = Set.of("Alimentos", "Ropa", "\u00DAtiles", "Salud", "Otros");
    private static final Set<String> URGENCIES = Set.of("Alta", "Media", "Baja");
    private static final Set<String> SERVICES = Set.of("police", "fire", "ambulance", "municipal");
    private static final Set<String> LANGUAGES_OK = Set.of("es", "en", "qu", "ay", "pt", "fr", "de", "it", "zh", "ja", "ar");

    private final AssistantProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public AssistantLlmService(AssistantProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().baseUrl(properties.baseUrl()).build();
    }

    public boolean enabled() {
        return hasApiKey();
    }

    private boolean hasApiKey() {
        return properties.apiKey() != null && !properties.apiKey().isBlank();
    }

    public JsonNode interpret(String text, String uiLanguage, List<Map<String, String>> history) {
        if (!enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "El asistente IA no esta configurado.");
        }

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT + "\nIdioma actual de la interfaz: " + uiLanguage));
        for (Map<String, String> turn : history) {
            String role = "user".equals(turn.get("role")) ? "user" : "assistant";
            messages.add(Map.of("role", role, "content", turn.get("content")));
        }
        messages.add(Map.of("role", "user", "content", text));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("temperature", 0);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", messages);

        try {
            String raw = restClient.post()
                    .uri("/chat/completions")
                    .headers(headers -> {
                        if (hasApiKey()) headers.setBearerAuth(properties.apiKey());
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode response = objectMapper.readTree(raw);
            String content = response.path("choices").path(0).path("message").path("content").asString("");
            return sanitize(objectMapper.readTree(stripFence(content)));
        } catch (RestClientException | JacksonException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "El asistente IA no respondio correctamente.", ex);
        }
    }

    /** Solo deja pasar valores permitidos. Cualquier otra cosa se descarta o se vuelve "chat". */
    private JsonNode sanitize(JsonNode raw) {
        String action = text(raw, "action", "chat");
        if (!ACTIONS.contains(action)) action = "chat";
        String language = text(raw, "language", "es").toLowerCase();
        if (!LANGUAGES_OK.contains(language)) language = "es";
        String uiLanguage = text(raw, "uiLanguage", null);
        if (uiLanguage != null && !LANGUAGES_OK.contains(uiLanguage)) uiLanguage = null;
        String section = text(raw, "section", null);
        String category = text(raw, "category", null);
        String mode = text(raw, "mode", null);
        String layer = text(raw, "layer", null);
        double factor = raw.path("radiusFactor").asDouble(0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("language", language);
        out.put("reply", limit(text(raw, "reply", ""), 600));
        out.put("action", action);
        out.put("query", limit(text(raw, "query", null), 120));
        out.put("placesQuery", limit(text(raw, "placesQuery", null), 120));
        out.put("destination", limit(text(raw, "destination", null), 120));
        out.put("category", pick(SERVICE_CATEGORIES, category, null));
        out.put("section", pick(SECTIONS, section, null));
        out.put("mode", pick(MODES, mode, null));
        out.put("layer", pick(LAYERS, layer, null));
        out.put("radiusFactor", factor == 2 || factor == 0.5 ? factor : null);
        out.put("uiLanguage", uiLanguage);
        out.put("title", limit(text(raw, "title", null), 100));
        out.put("description", limit(text(raw, "description", null), 400));
        String helpCategory = text(raw, "helpCategory", null);
        if ("Utiles".equals(helpCategory)) helpCategory = "\u00DAtiles";
        out.put("helpCategory", pick(HELP_CATEGORIES, helpCategory, "Otros"));
        String urgency = text(raw, "urgency", null);
        out.put("urgency", pick(URGENCIES, urgency, "Media"));
        out.put("location", limit(text(raw, "location", null), 120));

        if ("emergency_info".equals(action)) {
            List<String> requested = new ArrayList<>();
            for (JsonNode service : raw.path("services")) {
                String key = service.asString("");
                if (SERVICES.contains(key) && !requested.contains(key)) requested.add(key);
            }
            List<Map<String, Object>> contacts = new ArrayList<>();
            for (EmergencyContact contact : EMERGENCY_CONTACTS) {
                if (requested.isEmpty() || requested.contains(contact.service())) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("service", contact.service());
                    item.put("name", contact.name());
                    item.put("number", contact.number());
                    item.put("note", contact.note());
                    contacts.add(item);
                }
            }
            out.put("contacts", contacts);
        }
        return objectMapper.valueToTree(out);
    }

    private String pick(Set<String> allowed, String value, String fallback) {
        return value != null && allowed.contains(value) ? value : fallback;
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return fallback;
        String text = value.asString("").trim();
        return text.isEmpty() ? fallback : text;
    }

    private String limit(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    private String stripFence(String content) {
        String value = content.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        return value;
    }
}
