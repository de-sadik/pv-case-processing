package com.pv.cases.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pv.cases.exception.CaseNotFoundException;
import com.pv.cases.model.Query;
import com.pv.cases.model.QueryRequest;
import com.pv.cases.repository.InMemoryCaseStore;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/queries")
public class QueryController {

	private final InMemoryCaseStore store;

	public QueryController(InMemoryCaseStore store) {
		this.store = store;
	}

	/**
	 * Records a reviewer question against a field of an existing case. The case is
	 * checked first so a query can never be filed against a case that does not exist.
	 */
	@PostMapping
	public ResponseEntity<Query> raiseQuery(@Valid @RequestBody QueryRequest request) {
		if (this.store.findCase(request.caseId()).isEmpty()) {
			throw new CaseNotFoundException(request.caseId());
		}

		Query query = Query.create(request.caseId(), request.fieldPath(), request.question());
		this.store.saveQuery(query);
		return ResponseEntity.status(HttpStatus.CREATED).body(query);
	}

	/**
	 * @return the queries raised against the case, or an empty list — an unknown
	 *         case id is an empty result here, not a 404, so a reviewer UI can poll
	 *         this without special-casing "no queries yet"
	 */
	@GetMapping
	public List<Query> listQueries(@RequestParam String caseId) {
		return this.store.findQueriesByCaseId(caseId);
	}
}
