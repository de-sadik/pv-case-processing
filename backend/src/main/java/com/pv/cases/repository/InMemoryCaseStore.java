package com.pv.cases.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Repository;

import com.pv.cases.model.CaseDocument;
import com.pv.cases.model.Query;

/**
 * In-memory persistence for cases and the reviewer queries raised against them.
 *
 * <p>Cases are keyed by case id and hold only the latest version; a merge
 * replaces the stored document rather than appending to a history.
 */
@Repository
public class InMemoryCaseStore {

	private final Map<String, CaseDocument> cases = new ConcurrentHashMap<>();

	/**
	 * Queries grouped by case id. The values are {@link CopyOnWriteArrayList}
	 * because the enclosing {@link ConcurrentHashMap} only makes the <em>map</em>
	 * safe — two concurrent {@code saveQuery} calls for the same case would still
	 * corrupt a plain {@code ArrayList}. Queries are read far more often than
	 * written, which is what copy-on-write is for.
	 */
	private final Map<String, List<Query>> queries = new ConcurrentHashMap<>();

	public void saveCase(String caseId, CaseDocument doc) {
		cases.put(caseId, doc);
	}

	public Optional<CaseDocument> findCase(String caseId) {
		return Optional.ofNullable(cases.get(caseId));
	}

	/**
	 * Appends a query to its case's list, creating the list on first use.
	 * Keyed off {@link Query#caseId()} so a query can never be filed under an id
	 * that does not match its own payload.
	 */
	public void saveQuery(Query query) {
		queries.computeIfAbsent(query.caseId(), id -> new CopyOnWriteArrayList<>()).add(query);
	}

	/**
	 * @return the queries raised against {@code caseId} in the order they were
	 *         saved, or an empty list if there are none — never {@code null}. The
	 *         result is a copy, so callers cannot mutate stored state through it.
	 */
	public List<Query> findQueriesByCaseId(String caseId) {
		return List.copyOf(queries.getOrDefault(caseId, List.of()));
	}

	public int caseCount() {
		return cases.size();
	}

	/**
	 * @return the total number of queries across every case
	 */
	public int queryCount() {
		return queries.values().stream().mapToInt(List::size).sum();
	}
}
