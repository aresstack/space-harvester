package com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Das Einmalcode-Formular einer Zwei-Faktor-Seite, gelesen aus der unveränderten HTML-Antwort (paketintern).
 *
 * <p>Gebaut nach der echten Seite des Zwei-Faktor-Plugins (Code per E-Mail): ein POST-Formular mit dem Codefeld,
 * versteckten Feldern (Rücksprungadresse, Schalter für „neu senden“) und einem Absende-Knopf. Versteckte Felder gehen
 * mit ihren Werten aus der Seite unverändert zurück; „Resend OTP“ setzt wie das Skript der Seite das versteckte
 * Resend-Feld auf {@code true} und sendet ohne Knopf. Ziele außerhalb der eingestellten Confluence werden nicht
 * angenommen. Feldwerte verlassen die Klasse nur als Formularkörper und stehen in keiner Logzeile.
 */
final class OneTimeCodeForm {

    private final URI action;
    private final String formId;
    private final String codeField;
    private final String resendField;
    private final Map<String, String> fields;
    private final String[] submit;
    private final String title;
    private final String message;
    private final String error;

    private OneTimeCodeForm(URI action, String formId, String codeField, String resendField,
                            Map<String, String> fields, String[] submit, String title, String message, String error) {
        this.action = action;
        this.formId = formId;
        this.codeField = codeField;
        this.resendField = resendField;
        this.fields = fields;
        this.submit = submit;
        this.title = title;
        this.message = message;
        this.error = error;
    }

    /**
     * Das Formular oder {@code null}, wenn die Seite kein POST-Formular mit Codefeld auf derselben Confluence hat.
     *
     * @param page die Adresse der Seite (Basis für ein leeres oder relatives {@code action})
     * @param base die eingestellte Basis-URL; das Ziel muss auf demselben Host liegen
     */
    static OneTimeCodeForm parse(URI page, byte[] html, URI base) {
        Document document = Jsoup.parse(new String(html, StandardCharsets.UTF_8), page.toString());
        for (Element form : document.select("form")) {
            if (!"post".equalsIgnoreCase(form.attr("method").trim())) {
                continue;
            }
            Element code = codeInput(form);
            if (code == null) {
                continue;
            }
            URI action = action(page, form.attr("action"));
            if (action == null || !sameOrigin(action, base)) {
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<String, String>();
            String resend = null;
            String[] submit = null;
            for (Element input : form.select("input[name], select[name], textarea[name], button[name]")) {
                String name = input.attr("name");
                String tag = input.tagName();
                String type = input.attr("type").toLowerCase(Locale.ROOT);
                if (tag.equals("button") || type.equals("submit") || type.equals("image")) {
                    if (submit == null && (tag.equals("input") || type.isEmpty() || type.equals("submit"))) {
                        submit = new String[] {name, input.attr("value")};
                    }
                    continue;
                }
                if (type.equals("button") || type.equals("reset") || type.equals("file")) {
                    continue;
                }
                if ((type.equals("checkbox") || type.equals("radio")) && !input.hasAttr("checked")) {
                    continue;
                }
                if (tag.equals("textarea")) {
                    fields.put(name, input.text());
                } else if (tag.equals("select")) {
                    Element selected = input.selectFirst("option[selected]");
                    if (selected == null) {
                        selected = input.selectFirst("option");
                    }
                    fields.put(name, selected == null ? "" : selected.hasAttr("value") ? selected.attr("value")
                            : selected.text());
                } else {
                    fields.put(name, input.attr("value"));
                }
                if (resend == null && type.equals("hidden") && name.toLowerCase(Locale.ROOT).contains("resend")) {
                    resend = name;
                }
            }
            fields.put(code.attr("name"), "");
            return new OneTimeCodeForm(action, form.id(), code.attr("name"), resend, fields, submit,
                    title(document), message(document, form), error(document));
        }
        return null;
    }

    /** Das sichtbare Eingabefeld für den Code: Passwort-, Text- oder Zahlenfeld, dessen Name oder ID nach Code klingt. */
    private static Element codeInput(Element form) {
        for (Element input : form.select("input[name]")) {
            String type = input.attr("type").toLowerCase(Locale.ROOT);
            if (!type.isEmpty() && !type.equals("password") && !type.equals("text") && !type.equals("number")
                    && !type.equals("tel")) {
                continue;
            }
            String key = (input.attr("name") + " " + input.id() + " " + input.attr("autocomplete"))
                    .toLowerCase(Locale.ROOT);
            if (key.contains("otp") || key.contains("code") || key.contains("passcode") || key.contains("token")) {
                return input;
            }
        }
        return null;
    }

    private static URI action(URI page, String action) {
        try {
            return action == null || action.trim().isEmpty() ? page : page.resolve(action.trim().replace(" ", "%20"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean sameOrigin(URI target, URI base) {
        return target.getHost() != null && target.getHost().equalsIgnoreCase(base.getHost())
                && port(target) == port(base) && target.getRawUserInfo() == null
                && (!"https".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(target.getScheme()));
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String title(Document document) {
        Element heading = document.selectFirst("#main h1, #content h1, main h1, h1");
        String text = heading == null ? "" : clean(heading.text());
        return text.isEmpty() ? clean(document.title()) : text;
    }

    private static String message(Document document, Element form) {
        Element info = form.selectFirst("#infoMessage, .aui-message-info, .aui-message-success");
        if (info == null) {
            info = document.selectFirst("#main .aui-message-info, #content .aui-message-info");
        }
        return info == null ? "" : clean(info.text());
    }

    private static String error(Document document) {
        for (Element error : document.select(".aui-message-error, .aui-message-warning, .error")) {
            if (error.closest("header, #header, footer, #footer, nav, script") == null) {
                String text = clean(error.text());
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    URI action() {
        return action;
    }

    String formId() {
        return formId;
    }

    boolean resendAvailable() {
        return resendField != null;
    }

    String title() {
        return title;
    }

    String message() {
        return message;
    }

    String error() {
        return error;
    }

    /** Dasselbe Formular mit anderer Fehlermeldung (eigener Text, wenn die Seite keinen nennt). */
    OneTimeCodeForm withError(String text) {
        return new OneTimeCodeForm(action, formId, codeField, resendField, fields, submit, title, message,
                text == null ? "" : text);
    }

    /** Namen der Felder, die zurückgehen (für Logzeilen), nie Werte. */
    List<String> fieldNames() {
        List<String> names = new ArrayList<String>(fields.keySet());
        if (submit != null) {
            names.add(submit[0]);
        }
        return names;
    }

    /** Der Körper zum Absenden des Codes: alle Felder wie auf der Seite, das Codefeld mit {@code code}, der Knopf. */
    byte[] submitBody(char[] code) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (field.getKey().equals(codeField)) {
                pair(out, field.getKey(), code);
            } else {
                pair(out, field.getKey(), field.getValue().toCharArray());
            }
        }
        if (submit != null) {
            pair(out, submit[0], submit[1].toCharArray());
        }
        return out.toByteArray();
    }

    /** Der Körper für „neu senden“: Resend-Feld {@code true}, Codefeld leer, ohne Knopf (wie das Skript der Seite). */
    byte[] resendBody() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            String value = field.getKey().equals(resendField) ? "true" : field.getValue();
            pair(out, field.getKey(), value.toCharArray());
        }
        return out.toByteArray();
    }

    private static void pair(ByteArrayOutputStream out, String name, char[] value) {
        if (out.size() > 0) {
            out.write('&');
        }
        encode(out, name.toCharArray());
        out.write('=');
        encode(out, value);
    }

    /** {@code application/x-www-form-urlencoded} in UTF-8, ohne Zwischen-{@code String} (der Code bleibt ein char[]). */
    private static void encode(ByteArrayOutputStream out, char[] value) {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
        try {
            while (bytes.hasRemaining()) {
                int b = bytes.get() & 0xFF;
                if (b < 0x80 && (Character.isLetterOrDigit(b) || b == '-' || b == '_' || b == '.' || b == '*')) {
                    out.write(b);
                } else if (b == ' ') {
                    out.write('+');
                } else {
                    out.write('%');
                    out.write(Character.toUpperCase(Character.forDigit(b >> 4, 16)));
                    out.write(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
            }
        } finally {
            if (bytes.hasArray()) {
                Arrays.fill(bytes.array(), (byte) 0);
            }
        }
    }

    /** Ohne Feldwerte. */
    @Override
    public String toString() {
        return "OneTimeCodeForm[" + formId + ", felder=" + fieldNames() + "]";
    }
}
