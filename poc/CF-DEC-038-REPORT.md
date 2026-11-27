# PoC Gate CF-DEC-038 Report

- **Decision:** `PASS`
- **Execution Mode:** `LIVE`
- **Executed At (UTC):** `2026-11-27T09:21:59.506883Z`
- **SDK (`google-genai`):** `3.6.1`
- **Text Model:** `gemini-3.5-flash-lite`
- **Embedding Model:** `gemini-embedding-2`

## Summary Matrix

| Test   | Name                                   | Result | Summary                                                                    |
| ------ | -------------------------------------- | ------ | -------------------------------------------------------------------------- |
| Test 1 | Streaming & store=false                | `PASS` | Received 2 text deltas; TTFT=4576.19ms; completed=True.                    |
| Test 2 | Completion & Usage Null Semantics      | `PASS` | usage present=True; total_includes_reasoning=True.                         |
| Test 3 | Stateless Multi-Turn Native Continuity | `PASS` | Replayed 3 native steps (has_thought_signature=True); recalled_token=True. |
| Test 4 | Key Rotation T2B (Cross-Key Replay)    | `PASS` | Key B accepted Key A signature-bearing steps and recalled the token.       |
| Test 5 | Gemini Embedding 2 (1536d)             | `PASS` | Vector count=1, dim=1536, all_finite=True.                                 |

## Architectural Recommendation for Core Lead

- **T2B Key Rotation (`ContinuityEpoch`):** Cross-key replay with `GEMINI_KEY_B` succeeded (including `thought` signature replay); Core may preserve the active `ContinuityEpoch` across compatible key rotations while continuing to enforce `store=false`, model/API-interface compatibility checks, and an epoch-invalidation fallback if an upstream key rotation spans projects/domains that reject a prior signature.

## Redacted Evidence

### Test 1: Streaming & store=false

```json
{
  "metrics": {
    "ttft_ms": 4576.19,
    "delta_count": 2,
    "event_types_seen": [
      "interaction.completed",
      "interaction.created",
      "interaction.status_update",
      "step.delta",
      "step.start",
      "step.stop"
    ]
  },
  "evidence": {
    "stream_text_preview": "CF-DEC-038-ANCHOR-749 acknowledged. 17 * 23 = 391. `store=false` means no data persists between requests.",
    "step_types": ["thought", "model_output"]
  }
}
```

### Test 2: Completion & Usage Null Semantics

```json
{
  "metrics": {
    "input_tokens": 54,
    "output_tokens": 41,
    "total_tokens": 375,
    "cached_tokens": 0,
    "reasoning_tokens": 280,
    "total_includes_reasoning": true
  },
  "evidence": {
    "raw_usage": {
      "initial_input_tokens": 54,
      "input_tokens_by_modality": [
        {
          "modality": "text",
          "tokens": 54
        }
      ],
      "total_cached_tokens": 0,
      "total_input_tokens": 54,
      "total_output_tokens": 41,
      "total_thought_tokens": 280,
      "total_tokens": 375,
      "total_tool_use_tokens": 0
    }
  }
}
```

### Test 3: Stateless Multi-Turn Native Continuity

```json
{
  "metrics": {
    "replayed_steps_count": 3,
    "has_thought_signature": true,
    "recalled_token": true
  },
  "evidence": {
    "replayed_step_types": ["user_input", "thought", "model_output"],
    "turn2_output_preview": "CF-DEC-038-ANCHOR-749"
  }
}
```

### Test 4: Key Rotation T2B (Cross-Key Replay)

```json
{
  "metrics": {
    "cross_key_accepted": true,
    "has_thought_signature": true,
    "epoch_invalidation_required": false,
    "outcome": "ACCEPTED",
    "recalled_token": true
  },
  "evidence": {
    "turn_b_output_preview": "CF-DEC-038-ANCHOR-749"
  }
}
```

### Test 5: Gemini Embedding 2 (1536d)

```json
{
  "metrics": {
    "vector_count": 1,
    "dimensions": 1536,
    "all_finite": true
  },
  "evidence": {
    "formatted_input": "task: search result | query: ContextFlow sử dụng pgvector HNSW 1536 chiều cho Hybrid Retrieval và cổng kiểm soát CF-DEC-038.",
    "sample_head": [
      -0.010352351, -0.0011574671, 0.007072846, -0.025828088, 0.0013757802
    ]
  }
}
```
