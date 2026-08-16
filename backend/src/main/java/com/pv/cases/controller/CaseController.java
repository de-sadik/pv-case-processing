package com.pv.cases.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pv.cases.exception.CaseNotFoundException;
import com.pv.cases.model.CaseDocument;
import com.pv.cases.model.FieldValue;
import com.pv.cases.model.MergedCase;
import com.pv.cases.model.MergedField;
import com.pv.cases.repository.InMemoryCaseStore;
import com.pv.cases.service.MergeService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/cases")
public class CaseController {

	private final InMemoryCaseStore store;

	private final MergeService mergeService;

	public CaseController(InMemoryCaseStore store, MergeService mergeService) {
		this.store = store;
		this.mergeService = mergeService;
	}

	@GetMapping("/{caseId}")
	public CaseDocument getCase(@PathVariable String caseId) {
		return this.store.findCase(caseId).orElseThrow(() -> new CaseNotFoundException(caseId));
	}

	/**
	 * Merges a follow-up extraction into the stored case, persists the result as
	 * the new stored state, and returns the annotated diff.
	 */
	@PostMapping("/{caseId}/follow-ups")
	public MergedCase addFollowUp(@PathVariable String caseId, @Valid @RequestBody CaseDocument followUp) {
		CaseDocument stored = this.store.findCase(caseId)
				.orElseThrow(() -> new CaseNotFoundException(caseId));

		MergedCase merged = this.mergeService.merge(stored, followUp);
		this.store.saveCase(caseId, toDocument(merged));
		return merged;
	}

	/**
	 * Flattens a merge result back into a storable case.
	 *
	 * <p>Statuses and previous values are diff annotations describing one merge,
	 * not persistent state, so they are dropped — a subsequent follow-up is
	 * compared against the resulting values, not against this merge's history.
	 */
	private static CaseDocument toDocument(MergedCase merged) {
		Map<String, Map<String, FieldValue>> sections = new LinkedHashMap<>();
		merged.sections().forEach((section, fields) -> {
			Map<String, FieldValue> values = new LinkedHashMap<>();
			fields.forEach((name, field) -> values.put(name,
					new FieldValue(field.value(), field.confidence(), field.source())));
			sections.put(section, values);
		});

		return new CaseDocument(
				merged.caseId(),
				merged.version(),
				merged.caseClassification(),
				merged.mergedAt(),
				mostRecentSourceDocument(merged.sourceDocuments()),
				sections,
				merged.missingFields());
	}

	/**
	 * {@link CaseDocument} holds a single source document while {@link MergedCase}
	 * accumulates a list, so flattening keeps only the most recent contributor.
	 */
	private static String mostRecentSourceDocument(List<String> sourceDocuments) {
		return (sourceDocuments == null || sourceDocuments.isEmpty()) ? null
				: sourceDocuments.get(sourceDocuments.size() - 1);
	}
}
