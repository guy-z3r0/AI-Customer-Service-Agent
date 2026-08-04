package com.ulab.agent.api.dto;

import java.util.List;

/**
 * What the one-shot import of the old JSON data tree did.
 *
 * @param imported businesses that were created by this run
 * @param skipped  businesses already in the database, left untouched
 * @param problems folders that could not be read, with the reason
 */
public record ImportResultView(List<String> imported, List<String> skipped, List<String> problems) {
}
