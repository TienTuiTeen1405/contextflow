-- Khóa ai_usage_records -> tính delta -> cộng vào user_usage_counters trong cùng 1 transaction
WITH locked_usage AS (
    SELECT provider_call_id,
           contextflow_user_id,
           accounted_input_tokens,
           accounted_output_tokens,
           request_counted
    FROM contextflow.ai_usage_records
    WHERE provider_call_id = ?
    FOR UPDATE
),
updated_usage AS (
    UPDATE contextflow.ai_usage_records AS u
    SET completed_at = COALESCE(now(), u.completed_at),
        execution_status = 'COMPLETED',
        error_code = NULL,
        usage_status = 'AVAILABLE',
        usage = CAST('{}' AS jsonb),
        accounted_input_tokens = GREATEST(u.accounted_input_tokens, COALESCE(?, 0)),
        accounted_output_tokens = GREATEST(u.accounted_output_tokens, COALESCE(?, 0)),
        request_counted = true
    FROM locked_usage AS l
    WHERE u.provider_call_id = l.provider_call_id
    RETURNING l.contextflow_user_id,
              (u.accounted_input_tokens - l.accounted_input_tokens) AS delta_in,
              (u.accounted_output_tokens - l.accounted_output_tokens) AS delta_out,
              (CASE WHEN l.request_counted THEN 0 ELSE 1 END) AS delta_req
)
INSERT INTO contextflow.user_usage_counters (
    user_id, period_key, total_input_tokens, total_output_tokens, total_requests, updated_at
)
SELECT contextflow_user_id, ?, delta_in, delta_out, delta_req, now()
FROM updated_usage
WHERE contextflow_user_id IS NOT NULL
  AND (delta_in > 0 OR delta_out > 0 OR delta_req > 0)
ON CONFLICT (user_id, period_key) DO UPDATE
SET total_input_tokens = user_usage_counters.total_input_tokens + EXCLUDED.total_input_tokens,
    total_output_tokens = user_usage_counters.total_output_tokens + EXCLUDED.total_output_tokens,
    total_requests = user_usage_counters.total_requests + EXCLUDED.total_requests,
    updated_at = now();
