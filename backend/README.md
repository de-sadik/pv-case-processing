# PV Case Processing — Backend

## Overview

This service processes pharmacovigilance case data extracted from medical documents by an AI
extractor, where every field carries a value, a confidence score, and a source reference such as
`p.4 §1`. It solves the follow-up problem: when a second document arrives for a case already on
file, a reviewer needs to see exactly which fields changed, which are new, and which the extractor
simply did not find this time — so the service merges the two versions and annotates every field
with a status rather than leaving a human to diff two JSON documents by hand. On startup it loads
`case_v1.json` from the classpath into an in-memory store, which means case `PV-2026-0451` is
queryable immediately and all state is lost on restart.

## Quick Start

```bash
mvn spring-boot:run
```

Starts on port 8080. Case `PV-2026-0451` is available immediately on startup.

## API Reference

### 1. GET /cases/{caseId}

Returns the current stored state of a case. After a follow-up has been merged, this reflects the
merged result, not the original extraction.

```bash
curl -s http://localhost:8080/cases/PV-2026-0451 | jq .
```

```json
{
  "case_id": "PV-2026-0451",
  "version": 1,
  "case_classification": "non-significant",
  "extracted_at": "2026-04-08T09:14:00Z",
  "source_document": "initial_report_PV-2026-0451.pdf",
  "sections": {
    "patient": {
      "initials": { "value": "M.K.", "confidence": 0.98, "source": "p.2 §1" },
      "age": { "value": "62", "confidence": 0.91, "source": "p.2 §1" },
      "sex": { "value": "Male", "confidence": 0.99, "source": "p.2 §1" },
      "weight_kg": { "value": "78", "confidence": 0.85, "source": "p.3 §2" }
    }
  }
}
```

Trimmed to the `patient` section — `suspect_drug`, `adverse_event`, and `reporter` follow the same
shape.

### 2. POST /cases/{caseId}/follow-ups

Merges a follow-up extraction into the stored case, saves the result as the new stored state, and
returns the annotated diff. Every field in the response carries a `status`; overridden fields also
carry the complete field they replaced.

```bash
curl -s -X POST http://localhost:8080/cases/PV-2026-0451/follow-ups \
  -H "Content-Type: application/json" \
  -d '{
    "case_id": "PV-2026-0451",
    "version": 2,
    "case_classification": "significant",
    "extracted_at": "2026-04-10T11:00:00Z",
    "source_document": "followup_report.pdf",
    "missing_fields": ["weight_kg"],
    "sections": {
      "patient": {
        "age": {"value": "63", "confidence": 0.95, "source": "p.2 §1"},
        "initials": {"value": "M.K.", "confidence": 0.98, "source": "p.2 §1"}
      },
      "adverse_event": {
        "event_term": {"value": "Myalgia", "confidence": 0.94, "source": "p.4 §1"},
        "hospitalized": {"value": "Yes", "confidence": 0.87, "source": "p.5 §2"}
      }
    }
  }' | jq .
```

```json
{
  "case_id": "PV-2026-0451",
  "version": 2,
  "base_version": 1,
  "incoming_version": 2,
  "case_classification": "significant",
  "merged_at": "2026-04-10T11:04:18.211Z",
  "source_documents": [
    "initial_report_PV-2026-0451.pdf",
    "followup_report.pdf"
  ],
  "sections": {
    "patient": {
      "age": {
        "value": "63",
        "confidence": 0.95,
        "source": "p.2 §1",
        "status": "overridden",
        "previous_value": { "value": "62", "confidence": 0.91, "source": "p.2 §1" }
      },
      "initials": {
        "value": "M.K.",
        "confidence": 0.98,
        "source": "p.2 §1",
        "status": "unchanged"
      },
      "weight_kg": {
        "value": "78",
        "confidence": 0.85,
        "source": "p.3 §2",
        "status": "retained"
      }
    },
    "adverse_event": {
      "hospitalized": {
        "value": "Yes",
        "confidence": 0.87,
        "source": "p.5 §2",
        "status": "new"
      }
    }
  },
  "missing_fields": ["weight_kg"],
  "summary": { "unchanged": 2, "overridden": 1, "new": 1, "retained": 11 }
}
```

Trimmed to one field per status — `age` was overridden, `initials` was unchanged, `hospitalized` is
new, and `weight_kg` was retained because the follow-up did not mention it. The `summary` counts
cover all 15 fields in the merged case, including those omitted above.

### 3. POST /queries

Records a reviewer's question against a single field of an existing case, for human follow-up. The
case must exist; the `id` and `created_at` are generated server-side.

```bash
curl -s -X POST http://localhost:8080/queries \
  -H "Content-Type: application/json" \
  -d '{
    "case_id": "PV-2026-0451",
    "field_path": "patient.age",
    "question": "Is this age confirmed in the follow-up document?"
  }' | jq .
```

Returns `201 Created`:

```json
{
  "id": "4281c84c-c1f6-45fd-bc15-fb8c4af7eb7b",
  "case_id": "PV-2026-0451",
  "field_path": "patient.age",
  "question": "Is this age confirmed in the follow-up document?",
  "created_at": "2026-04-10T11:06:02.615Z"
}
```

### 4. GET /queries?caseId={id}

Lists every query raised against a case, oldest first. An unknown case id returns an empty array
rather than a 404, so a reviewer UI can poll this without special-casing "no queries yet".

```bash
curl -s "http://localhost:8080/queries?caseId=PV-2026-0451" | jq .
```

```json
[
  {
    "id": "4281c84c-c1f6-45fd-bc15-fb8c4af7eb7b",
    "case_id": "PV-2026-0451",
    "field_path": "patient.age",
    "question": "Is this age confirmed in the follow-up document?",
    "created_at": "2026-04-10T11:06:02.615Z"
  }
]
```

### 5. GET /health

```bash
curl -s http://localhost:8080/health | jq .
```

```json
{
  "status": "UP",
  "cases_loaded": 1,
  "queries_count": 0
}
```

Because the store is in-memory, "the service is up" and "the service has data" are separate
questions — a `cases_loaded` of `0` is the signature of a restart that lost its state.

## Merge Behavior

| Status     | Meaning                                              |
|------------|------------------------------------------------------|
| unchanged  | Field present in both versions, value identical      |
| overridden | Field present in both versions, value differs        |
| new        | Field present in follow-up only                      |
| retained   | Field present in stored version only                 |

Fields present in the stored version but absent from the follow-up are kept with status `retained`.
In pharmacovigilance, absence of a field in a follow-up document does not mean the field is wrong —
the AI extractor may simply not have found it in the new source. Known data is preserved until
explicitly contradicted.

Note that "differs" means value-only. If confidence or source changes but the value is the same, the
status is `unchanged` and confidence and source are silently refreshed from the follow-up. This
prevents spurious `overridden` flags from re-extraction noise — a confidence drifting from 0.94 to
0.95 is not a change a reviewer needs to adjudicate.

The same rules apply at the section level, as a consequence rather than a special case. A section
present only in the stored case contributes fields that are all `retained`; a section introduced by
the follow-up contributes fields that are all `new`.

## Running Tests

```bash
mvn test
```

Current test coverage — 29 tests:

- `InMemoryCaseStore`: 7 tests
- `StartupDataLoader`: 3 tests
- `MergeService`: 12 tests
- `GlobalExceptionHandler`: 6 tests
- Application context smoke test: 1 test

## Error Responses

`404 Not Found` — the case id does not resolve to a stored case:

```json
{ "error": "Case not found", "case_id": "PV-NOPE" }
```

`400 Bad Request` — request body failed validation, one entry per violation:

```json
{
  "error": "Validation failed",
  "details": [{ "field": "question", "message": "must not be blank" }]
}
```

`500 Internal Server Error` — anything unexpected. The cause is logged in full server-side and
deliberately not returned to the client:

```json
{ "error": "Internal server error" }
```
