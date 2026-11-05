# Verification and commit gate

On 2026-11-04 the user explicitly instructed: "stop running test commands", "Stop adding tests", and answered "No test commands" when asked about the checks required before commits. This supersedes the earlier request to run tests and lint before every commit. Phase 0 was removed at the user's direction.

No test or lint commands were run after that answer, and no test code was added. Only compile-only checks and an unsigned release assembly were run. The earlier rule still requires the full test/lint gate before each commit, so the Phase 1–5 working-tree changes are uncommitted. This is a deliberate unresolved conflict between the current test prohibition and the earlier commit/exit/definition-of-done requirements; it does not mean the work is verified.

The first release gate is independent, byte-preserving round-trip evidence for all enabled writers, including malformed inputs and SAF swap failures. The app must not be tagged or published while that gate is open.
