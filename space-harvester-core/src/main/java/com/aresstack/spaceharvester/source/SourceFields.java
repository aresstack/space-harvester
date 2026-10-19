package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.astu.source.KnowledgeSourceType;
import com.aresstack.enterpriseai.astu.source.SourceSettingField;
import com.aresstack.spaceharvester.application.sources.SourceField;
import java.util.List;
import java.util.Set;

/** Die Felder eines Upstream-Typs als {@link SourceField}, ohne Tresorangaben ({@code vaultValue}). */
final class SourceFields {

    private SourceFields() {
    }

    /** @param outside Schlüssel, die nicht unter {@code options} stehen (etwa {@code title}) und deshalb fehlen */
    static List<SourceField> of(KnowledgeSourceType type, Set<String> outside) {
        return type.fields().stream().filter(field -> !outside.contains(field.key())).map(SourceFields::of).toList();
    }

    private static SourceField of(SourceSettingField field) {
        return new SourceField(field.key(), field.label(), field.hint(), SourceField.Kind.valueOf(field.kind().name()),
                field.isRequired(), field.isAdvanced(), field.defaultValue());
    }
}
