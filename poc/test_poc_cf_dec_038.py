"""Offline pytest suite for poc/poc_cf_dec_038.py."""

from pathlib import Path
from typing import Any

import pytest
from google.genai import errors as genai_errors

from poc.poc_cf_dec_038 import (
    REDACTED,
    PoCConfig,
    SecretRedactor,
    StubGeminiClient,
    load_env_config,
    main,
    render_markdown_report,
    run_poc,
)


def test_offline_self_test_entrypoint_passes() -> None:
    assert main(["--self-test"]) == 0


def test_load_env_config_ignores_placeholders_and_reports_missing(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.delenv("GEMINI_KEY_A", raising=False)
    monkeypatch.delenv("GEMINI_KEY_B", raising=False)
    poc_dir = tmp_path / "poc"
    poc_dir.mkdir()
    (poc_dir / ".env").write_text(
        "GEMINI_KEY_A=replace-with-gemini-api-key-a\nGEMINI_KEY_B=\n",
        encoding="utf-8",
    )
    cfg, missing = load_env_config(tmp_path)
    assert cfg is None
    assert missing == ["GEMINI_KEY_A", "GEMINI_KEY_B"]


def test_poc_run_preserves_null_usage_and_redacts_secrets_in_report() -> None:
    key_a = "AIzaSyUnitTestSecretAlpha123456"
    key_b = "custom-non-aiza-secret-beta-654321"
    cfg = PoCConfig(key_a=key_a, key_b=key_b)
    redactor = SecretRedactor([key_a, key_b])

    result = run_poc(
        cfg,
        client_factory=lambda key: StubGeminiClient(key, reject_cross_key=True),
        mode="UNIT_STUB",
    )
    assert result.checks_passed is True
    assert result.gate_decision == "BLOCKER"
    assert result.t2b_cross_key_accepted is False
    assert result.tests[1].metrics["cached_tokens"] is None
    assert result.tests[1].metrics["total_includes_reasoning"] is True
    assert result.tests[2].metrics["replayed_steps_count"] == 3
    assert result.tests[3].metrics["epoch_invalidation_required"] is True
    assert result.tests[4].metrics["dimensions"] == 1536
    assert result.tests[4].metrics["all_finite"] is True

    report = render_markdown_report(result, redactor)
    assert key_a not in report
    assert key_b not in report
    assert "AIza" not in report
    assert REDACTED in report


def test_write_report_fails_cleanly_when_keys_missing(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.delenv("GEMINI_KEY_A", raising=False)
    monkeypatch.delenv("GEMINI_KEY_B", raising=False)
    real_load_env = load_env_config
    monkeypatch.setattr(
        "poc.poc_cf_dec_038.load_env_config",
        lambda repo_root=None: real_load_env(tmp_path),
    )
    exit_code = main(["--write-report", "--report-path", str(tmp_path / "report.md")])
    captured = capsys.readouterr()
    assert exit_code == 2
    assert "BLOCKED:" in captured.err
    assert not (tmp_path / "report.md").exists()


@pytest.mark.parametrize(
    "error",
    [
        OSError("network disconnected"),
        TimeoutError("request timed out"),
        RuntimeError("unclassified provider failure"),
        genai_errors.ClientError(401, {"error": {"message": "Invalid API key"}}),
        genai_errors.ClientError(429, {"error": {"message": "Quota exhausted"}}),
        genai_errors.ClientError(400, {"error": {"message": "Invalid request payload"}}),
    ],
)
def test_t2b_unrelated_errors_block_gate_without_epoch_conclusion(
    monkeypatch: pytest.MonkeyPatch, error: Exception
) -> None:
    cfg = PoCConfig(key_a="synthetic-alpha", key_b="synthetic-beta")
    calls: list[dict[str, Any]] = []

    def factory(key: str) -> StubGeminiClient:
        client = StubGeminiClient(key)
        if key == cfg.key_b:

            def fail(**kwargs: Any) -> Any:
                calls.append(kwargs)
                raise error

            monkeypatch.setattr(client.interactions, "create", fail)
        return client

    result = run_poc(cfg, client_factory=factory, mode="LIVE")

    assert len(calls) == 1
    assert result.tests[3].passed is False
    assert result.gate_decision == "BLOCKER"
    assert result.t2b_cross_key_accepted is None
    assert result.tests[3].metrics["epoch_invalidation_required"] is None
    report = render_markdown_report(result, SecretRedactor([cfg.key_a, cfg.key_b]))
    assert "inconclusive" in report.lower()
    assert "MUST invalidate" not in report
    assert "may preserve" not in report


def test_t2b_requires_distinct_keys_before_creating_clients() -> None:
    def unexpected_factory(_: str) -> Any:
        pytest.fail("Identical keys must be rejected before creating clients")

    with pytest.raises(ValueError, match="different"):
        run_poc(
            PoCConfig(key_a="synthetic-same", key_b="synthetic-same"),
            client_factory=unexpected_factory,
        )


def test_t2b_requires_signature_bearing_replay(monkeypatch: pytest.MonkeyPatch) -> None:
    cfg = PoCConfig(key_a="synthetic-alpha", key_b="synthetic-beta")
    key_b_calls: list[dict[str, Any]] = []

    def factory(key: str) -> StubGeminiClient:
        client = StubGeminiClient(key)
        original_create = client.interactions.create

        def create(**kwargs: Any) -> Any:
            if key == cfg.key_b:
                key_b_calls.append(kwargs)
            response = original_create(**kwargs)
            if not kwargs.get("stream"):
                return response
            events = []
            for event in response:
                if event.event_type == "step.delta" and event.delta.type == "thought_signature":
                    continue
                if event.event_type == "step.start":
                    event.step.data.pop("signature", None)
                elif event.event_type == "interaction.completed":
                    for step in event.interaction.steps:
                        step.data.pop("signature", None)
                events.append(event)
            return iter(events)

        monkeypatch.setattr(client.interactions, "create", create)
        return client

    result = run_poc(cfg, client_factory=factory, mode="LIVE")

    assert result.tests[3].passed is False
    assert result.t2b_cross_key_accepted is None
    assert result.gate_decision == "BLOCKER"
    assert not key_b_calls


def test_t2b_requires_successful_same_key_replay(monkeypatch: pytest.MonkeyPatch) -> None:
    cfg = PoCConfig(key_a="synthetic-alpha", key_b="synthetic-beta")

    def factory(key: str) -> StubGeminiClient:
        client = StubGeminiClient(key)
        original_create = client.interactions.create

        def create(**kwargs: Any) -> Any:
            if key == cfg.key_a and not kwargs.get("stream"):
                raise ValueError("same-key replay is invalid")
            return original_create(**kwargs)

        monkeypatch.setattr(client.interactions, "create", create)
        return client

    result = run_poc(cfg, client_factory=factory, mode="LIVE")
    assert result.tests[2].passed is False
    assert result.tests[3].passed is False
    assert result.t2b_cross_key_accepted is None


def test_t2b_missing_recalled_token_is_inconclusive(monkeypatch: pytest.MonkeyPatch) -> None:
    cfg = PoCConfig(key_a="synthetic-alpha", key_b="synthetic-beta")

    def factory(key: str) -> StubGeminiClient:
        client = StubGeminiClient(key)
        if key == cfg.key_b:
            monkeypatch.setattr(
                client.interactions,
                "create",
                lambda **kwargs: {"output_text": "I cannot recall the token", "steps": []},
            )
        return client

    result = run_poc(cfg, client_factory=factory, mode="LIVE")
    assert result.tests[3].passed is False
    assert result.t2b_cross_key_accepted is None
    assert result.gate_decision == "BLOCKER"


def test_offline_checks_cannot_certify_live_gate() -> None:
    cfg = PoCConfig(key_a="synthetic-alpha", key_b="synthetic-beta")
    result = run_poc(cfg, client_factory=StubGeminiClient, mode="UNIT_STUB")
    assert all(test.passed for test in result.tests)
    assert result.gate_decision == "BLOCKER"


def test_gate_is_scoped_to_approved_text_model() -> None:
    cfg = PoCConfig(key_a="synthetic-alpha", key_b="synthetic-beta", text_model="unapproved-model")
    result = run_poc(cfg, client_factory=StubGeminiClient, mode="LIVE")
    assert all(test.passed for test in result.tests)
    assert result.gate_decision == "BLOCKER"


@pytest.mark.parametrize("reject_cross_key", [False, True])
def test_live_gate_accepts_conclusive_t2b_outcomes(reject_cross_key: bool) -> None:
    cfg = PoCConfig(key_a="synthetic-alpha", key_b="synthetic-beta")
    result = run_poc(
        cfg,
        client_factory=lambda key: StubGeminiClient(key, reject_cross_key=reject_cross_key),
        mode="LIVE",
    )
    assert result.gate_decision == "PASS"
    assert result.t2b_cross_key_accepted is (not reject_cross_key)
    assert not result.gate_blockers


def test_live_gate_requires_all_five_checks() -> None:
    cfg = PoCConfig(key_a="synthetic-alpha", key_b="synthetic-beta")
    result = run_poc(cfg, client_factory=StubGeminiClient, mode="LIVE")
    result.tests.pop()
    assert result.gate_decision == "BLOCKER"


def test_clients_are_closed_after_poc(monkeypatch: pytest.MonkeyPatch) -> None:
    closed: list[str] = []

    def factory(key: str) -> StubGeminiClient:
        client = StubGeminiClient(key)
        monkeypatch.setattr(client, "close", lambda: closed.append(key), raising=False)
        return client

    run_poc(PoCConfig(key_a="synthetic-alpha", key_b="synthetic-beta"), client_factory=factory)
    assert closed == ["synthetic-beta", "synthetic-alpha"]


def test_stream_error_event_blocks_gate(monkeypatch: pytest.MonkeyPatch) -> None:
    def factory(key: str) -> StubGeminiClient:
        client = StubGeminiClient(key)
        original_create = client.interactions.create

        def create(**kwargs: Any) -> Any:
            if kwargs.get("stream"):
                return iter([{"event_type": "error", "error": {"code": 503}}])
            return original_create(**kwargs)

        monkeypatch.setattr(client.interactions, "create", create)
        return client

    result = run_poc(
        PoCConfig(key_a="synthetic-alpha", key_b="synthetic-beta"),
        client_factory=factory,
        mode="LIVE",
    )
    assert result.tests[0].passed is False
    assert result.gate_decision == "BLOCKER"


@pytest.mark.parametrize("provider_error", [False, True])
def test_cli_report_and_exit_code_match_gate(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, provider_error: bool
) -> None:
    cfg = PoCConfig(key_a="synthetic-alpha", key_b="synthetic-beta")
    monkeypatch.setattr("poc.poc_cf_dec_038.load_env_config", lambda: (cfg, []))

    def factory(key: str) -> StubGeminiClient:
        client = StubGeminiClient(key)
        if provider_error and key == cfg.key_b:

            def fail(**kwargs: Any) -> Any:
                raise OSError(f"Network error for key={key}")

            monkeypatch.setattr(client.interactions, "create", fail)
        return client

    monkeypatch.setattr(
        "poc.poc_cf_dec_038.run_poc",
        lambda config, *, mode: run_poc(config, client_factory=factory, mode=mode),
    )
    report_path = tmp_path / "report.md"
    exit_code = main(["--write-report", "--report-path", str(report_path)])
    assert exit_code == (1 if provider_error else 0)
    report = report_path.read_text(encoding="utf-8")
    assert f"**Decision:** `{'BLOCKER' if provider_error else 'PASS'}`" in report
    assert cfg.key_a not in report
    assert cfg.key_b not in report


def test_cli_rejects_identical_keys_without_writing_report(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    cfg = PoCConfig(key_a="synthetic-same", key_b="synthetic-same")
    monkeypatch.setattr("poc.poc_cf_dec_038.load_env_config", lambda: (cfg, []))
    report_path = tmp_path / "report.md"
    assert main(["--write-report", "--report-path", str(report_path)]) == 2
    assert not report_path.exists()
    assert cfg.key_a not in capsys.readouterr().err
