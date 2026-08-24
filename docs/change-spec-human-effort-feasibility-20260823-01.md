# ChangeSpec human-effort feasibility report

> Post-study correction: the coordinator reported that all final `ACCEPT` decisions were submitted without reviewing the concrete code changes. Therefore decision accuracy, false acceptance attribution, and complete human-effort comparisons are not evaluable. The raw records remain unchanged. Use the [Chinese corrected report](change-spec-human-effort-feasibility-20260823-01-zh.md) as the authoritative interpretation.

- Study: `feasibility-20260823-01`
- Execution status: `COMPLETE_PROTOCOL_VALID`
- Value conclusion: `NOT_EVALUABLE_PILOT_ONLY`
- Provider/model: `deepseek / DeepSeek-V4-pro`
- Design: 3 execution participants + 1 independent Spec confirmer, 3 tasks, 3×3 Latin square, 9 product sessions
- Validity: 9/9 sessions `VALID`; 3/3 B/C paired digests identical; no participant repeated a fixture

The feasibility objective passed: timers, isolated workspaces, paired immutable Specs, human decisions, sealed Oracle ordering, and aggregate CSV validation all worked end to end. This is not a PASS/FAIL efficiency study: each task×mode cell has one observation and no human-effort noninferiority margin was preregistered.

## Group summary

| Mode | Objectively correct | Wilson 95% | Scope | False acceptance | Human effort median (ms) | Mean (ms) | P25–P75 (ms) | Product total median (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A | 1/3 (33.3%) | 6.1%–79.2% | 3/3 | 2/3 | 129,369 | 167,843 | 100,800–215,649 | NOT_MEASURED |
| B | 1/3 (33.3%) | 6.1%–79.2% | 3/3 | 2/3 | 253,577 | 270,394 | 242,940–289,440 | 243,424 |
| C | 2/3 (66.7%) | 20.8%–93.9% | 3/3 | 1/3 | 260,854 | 250,754 | 237,128–269,431 | 207,163 |

A product wall time is `NOT_MEASURED` because the standard interactive PaiCLI path did not emit comparable phase telemetry. B/C product totals include their paired Draft generation as required. Human effort includes the full R1 confirmation cost allocated to each B and C session.

Descriptively, mean human effort was 61.1% higher for B than A and 49.4% higher for C than A; C was -7.3% relative to B. These are pilot descriptions only and are confounded by one observation per task×mode and participant differences.

## Per-session results

| Session | Participant | Mode | Task | Decision | Objective | False accept | Product ms | Human effort ms |
|---|---|---:|---|---|---:|---:|---:|---:|
| S01 | P1 | A | ascii-slugifier | ACCEPT | FAIL | YES | NOT_MEASURED | 301,929 |
| S02 | P1 | B | login-retry-policy | ACCEPT | PASS | NO | 243,424 | 325,303 |
| S03 | P1 | C | workspace-path-safety | ACCEPT | PASS | NO | 220,760 | 260,854 |
| S04 | P2 | C | login-retry-policy | ACCEPT | PASS | NO | 194,121 | 278,007 |
| S05 | P2 | A | workspace-path-safety | ACCEPT | PASS | NO | NOT_MEASURED | 129,369 |
| S06 | P2 | B | ascii-slugifier | ACCEPT | FAIL | YES | 263,430 | 253,577 |
| S07 | P3 | B | workspace-path-safety | ACCEPT | FAIL | YES | 192,972 | 232,303 |
| S08 | P3 | C | ascii-slugifier | ACCEPT | FAIL | YES | 207,163 | 213,402 |
| S09 | P3 | A | login-retry-policy | ACCEPT | FAIL | YES | NOT_MEASURED | 72,230 |

All nine participants' final decisions were `ACCEPT`; therefore decision accuracy equals objective correctness. Scope passed 9/9. False acceptance occurred 5/9 overall (A 2/3, B 2/3, C 1/3); false rejection was 0/9.

## Human-effort composition

| Session | Requirements | Spec confirmation | HITL | Human criterion | Review | Rework | Rerun | Total |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| S01 | 39,604 | 0 | 41,877 | 0 | 220,448 | 0 | 0 | 301,929 |
| S02 | 30,574 | 185,359 | 73,691 | 0 | 35,679 | 0 | 0 | 325,303 |
| S03 | 32,563 | 148,539 | 46,184 | 0 | 33,568 | 0 | 0 | 260,854 |
| S04 | 19,816 | 185,359 | 45,616 | 0 | 27,216 | 0 | 0 | 278,007 |
| S05 | 25,999 | 0 | 53,234 | 0 | 50,136 | 0 | 0 | 129,369 |
| S06 | 46,256 | 121,903 | 62,736 | 0 | 22,682 | 0 | 0 | 253,577 |
| S07 | 25,401 | 148,539 | 38,426 | 0 | 19,937 | 0 | 0 | 232,303 |
| S08 | 16,332 | 121,903 | 39,643 | 0 | 35,524 | 0 | 0 | 213,402 |
| S09 | 27,009 | 0 | 28,575 | 0 | 16,646 | 0 | 0 | 72,230 |

No session used manual rework or manual rerun. All C sessions had `repair_count=0`: the public Verifier passed on the first attempt, so C had 0/3 Evidence-repair opportunities. One C candidate was nevertheless objectively incorrect under the hidden Oracle, showing that a public PASS can leave no repair trigger while still permitting false acceptance.

## Protocol observations

- The initial S01 launcher attempt failed authentication because it omitted the configured custom DeepSeek Base URL. It was excluded before the valid S01 attempt and is not one of the nine rows.
- The first login Spec timing procedure undercounted review time. R1 performed a timed re-review of the same immutable file; its SHA-256 and digest did not change. Both confirmation events are allocated to S02 and S04.
- Windows PowerShell 5 code-page parsing required ASCII-only launcher scripts; all final launchers passed parser validation.
- S09's PowerShell transcript captured the wrapper but not JLine's interactive body. The frozen decision, isolated workspace, public build artifacts, file-scope comparison, and hidden Oracle remain complete; the session is valid, but future runs should capture product telemetry directly.

## Conclusion and next gate

- Operational feasibility: completed successfully (9/9 valid sessions).
- Human-effort/product value: `NOT_EVALUABLE_PILOT_ONLY`.
- The pilot does not support a claim that ChangeSpec reduced human effort. The descriptive aggregate went in the opposite direction, while quality was mixed and uncertainty was extremely wide.
- Before any 117-session formal study: preregister the human-effort noninferiority margin, add comparable A-path product telemetry, harden transcript/timer capture, and review why public verification allowed five accepted but objectively incorrect candidates.
- The 78-run revised baseline and the 117-session formal study remain unauthorized and must not start automatically.

