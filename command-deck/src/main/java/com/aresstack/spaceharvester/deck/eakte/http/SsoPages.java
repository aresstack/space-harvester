package com.aresstack.spaceharvester.deck.eakte.http;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Liest die Seiten der Einmalanmeldung, ohne JavaScript auszuführen: das selbstabschickende SAML-Formular, das
 * Anmeldeformular des Identitätsdienstes und die OpenAM-Zwischenseite „Access rights validated“ mit
 * {@code secondVisitUrl} und {@code loginUrl}. Erkannt wird nur, was das Testprogramm von Angelo auf dem echten System
 * gesehen hat (23.11.2026).
 */
final class SsoPages {

    private static final Set<String> USER_FIELDS =
            Set.of("uid", "userid", "username", "user", "login", "idtoken1", "j_username", "email");

    private SsoPages() {
    }

    /** Ein HTML-Formular mit seinen vorbelegten Feldern. */
    record Form(URI action, String method, Map<String, String> fields, String userField, String passwordField) {

        boolean has(String name) {
            return fields.keySet().stream().anyMatch(field -> field.equalsIgnoreCase(name));
        }

        /** Kodiert die Felder; Benutzer und Kennwort nur, wenn übergeben (sonst bleiben die Vorgaben). */
        String encode(String user, char[] password) {
            Map<String, String> values = new LinkedHashMap<>(fields);
            if (user != null && userField != null) {
                values.put(userField, user);
            }
            if (password != null && passwordField != null) {
                values.put(passwordField, new String(password));
            }
            StringBuilder body = new StringBuilder();
            values.forEach((key, value) -> {
                if (body.length() > 0) {
                    body.append('&');
                }
                body.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            });
            return body.toString();
        }
    }

    /** Die OpenAM-Zwischenseite: weiter zur SAML-Antwort ({@code secondVisitUrl}) oder erst zur Anmeldung. */
    record Transition(URI secondVisit, URI login) {
    }

    static Document parse(SsoHttp.Response response) {
        return Jsoup.parse(response.text(), response.uri().toString());
    }

    static String title(Document page) {
        String title = page.title().strip();
        return title.length() > 80 ? title.substring(0, 80) + "…" : title;
    }

    /** Das Formular, das die SAML-Antwort an die E-Akte trägt (Feld {@code SAMLResponse}). */
    static Optional<Form> samlResponse(Document page) {
        for (Element form : page.select("form")) {
            Form read = read(form);
            if (read.has("SAMLResponse")) {
                return Optional.of(read);
            }
        }
        return Optional.empty();
    }

    /** Ein Anmeldeformular mit Benutzer- und Kennwortfeld. */
    static Optional<Form> login(Document page) {
        for (Element form : page.select("form")) {
            Form read = read(form);
            if (read.userField() != null && read.passwordField() != null) {
                return Optional.of(read);
            }
        }
        return Optional.empty();
    }

    /** Die Zwischenseite „Access rights validated“, erkannt an den Feldern {@code secondVisitUrl}/{@code loginUrl}. */
    static Optional<Transition> transition(Document page) {
        URI second = address(page, "secondVisitUrl");
        URI login = address(page, "loginUrl");
        if (second == null && login == null) {
            return Optional.empty();
        }
        return Optional.of(new Transition(second, login));
    }

    private static URI address(Document page, String id) {
        Element input = page.getElementById(id);
        if (input == null) {
            input = page.selectFirst("input[name=" + id + "]");
        }
        if (input == null || input.attr("value").isBlank()) {
            return null;
        }
        try {
            return URI.create(page.location()).resolve(input.attr("value").trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Form read(Element form) {
        String base = form.ownerDocument() == null ? "" : form.ownerDocument().location();
        String actionText = form.attr("action").trim();
        URI action = URI.create(base).resolve(actionText.isEmpty() ? base : actionText);
        String method = "get".equalsIgnoreCase(form.attr("method").trim()) ? "GET" : "POST";
        Map<String, String> fields = new LinkedHashMap<>();
        String user = null;
        String password = null;
        boolean submitTaken = false;
        for (Element input : form.select("input, button, select, textarea")) {
            String name = input.attr("name");
            String type = input.tagName().equals("button") ? input.attr("type").toLowerCase(Locale.ROOT)
                    : input.attr("type").toLowerCase(Locale.ROOT);
            if (input.tagName().equals("button") && type.isEmpty()) {
                type = "submit";
            }
            if (name.isEmpty() || input.hasAttr("disabled") || type.equals("button") || type.equals("reset")
                    || type.equals("file") || type.equals("image")) {
                continue;
            }
            if ((type.equals("checkbox") || type.equals("radio")) && !input.hasAttr("checked")) {
                continue;
            }
            if (type.equals("submit")) {
                if (submitTaken) {
                    continue;
                }
                submitTaken = true;
            }
            String value = input.tagName().equals("textarea") ? input.text() : input.attr("value");
            if (input.tagName().equals("select")) {
                Element selected = input.selectFirst("option[selected]");
                if (selected == null) {
                    selected = input.selectFirst("option");
                }
                value = selected == null ? "" : selected.attr("value");
            }
            fields.putIfAbsent(name, value);
            if (type.equals("password") && password == null) {
                password = name;
            } else if (user == null && (type.isEmpty() || type.equals("text") || type.equals("email"))
                    && (USER_FIELDS.contains(name.toLowerCase(Locale.ROOT))
                    || USER_FIELDS.contains(input.id().toLowerCase(Locale.ROOT)))) {
                user = name;
            }
        }
        return new Form(action, method, fields, user, password);
    }
}
