"""Offline pytest suite for poc/poc_cf_dec_038.py."""

from pathlib import Path

import pytest

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
    assert result.gate_decision == "PASS"
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
    exit_code = main(["--write-report", "--report-path", str(tmp_path / "report.md")])
    captured = capsys.readouterr()
    assert exit_code == 2
    assert "BLOCKED:" in captured.err
    assert not (tmp_path / "report.md").exists()
