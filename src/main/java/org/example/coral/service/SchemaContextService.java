package org.example.coral.service;

import org.example.coral.coral.SchemaCatalog;
import org.example.coral.model.IntentResult;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Schema-context injection: always exposes the full catalog to the planning AI so it can
 * independently decide which sources are relevant to the intent. No hardcoded defaults.
 */
@Service
public class SchemaContextService {

    private final SchemaCatalog catalog;

    public SchemaContextService(SchemaCatalog catalog) {
        this.catalog = catalog;
    }

    public Set<String> resolveAllowedTables(IntentResult intent) {
        return new LinkedHashSet<>(catalog.all().keySet());
    }
}
