# V-Sport Gauges — AI Development Instructions

These are standing project requirements for any AI assistant modifying or reviewing this repository. Apply them without requiring the owner to repeat them in each conversation.

## Source of truth and version checks

1. Before **every** code or documentation change, fetch the current repository state from the remote default branch (`master`), including `version.properties`, the files relevant to the request, and relevant recent commits. Do not rely on previous chat context, cached files, or an earlier checkout as proof of current state.
2. Read the current version from `version.properties` **before** editing. The Gradle application version is derived from `majorVersion`, `minorVersion`, and `patchVersion`. Never assume the version from a conversation or a prior build.
3. Multiple conversations or contributors may modify this repository simultaneously. Immediately before committing or pushing, recheck the latest remote branch head and the target files. If they changed, reconcile with the new state; do not blindly overwrite, force-push, or discard other work.
4. Never bump the version merely by assumption. Confirm the requested version and the repository's actual current version; if the requested target is stale or conflicting, resolve the conflict before writing.
5. If a significant form / fit / function change is detected, ask if `minorVersion` should change.

## Investigate before proposing or changing

5. Inspect actual current source code and relevant resources before making claims about how the app works, diagnosing bugs, or proposing implementation-specific changes. Do not guess. Distinguish verified findings, unverified possibilities, and observations from vehicle testing.
6. Trace related code paths, persisted settings, imports/exports, dashboard-specific and PID-shared behavior, and interactions with existing features as applicable. Favor small, targeted changes; preserve behavior not explicitly requested to change.
7. Do not invent test results, runtime observations, build success, or GitHub status. An inspected code path, a successful commit, a passing CI build, and verified behavior in the Escalade are different levels of evidence.

## Documentation and releases

8. Keep the README development history strictly chronological within its existing history section; never append revision notes after the document's closing sections. Public README entries describe confirmed working, meaningful user-facing features only after owner validation. Never include routine bug fixes, corrective follow-up versions, internal maintenance, diagnostic details, or unverified changes as README entries, even when those fixes relate to existing features. Record fixes in Git commits/PRs. Update an existing feature description only when its documented user-facing behavior actually changes. A version bump alone does not require a public README entry.
9. Review `docs/USER_GUIDE.md` whenever user-facing controls, settings, indicators, gestures, appearance, or behavior change. Update affected instructions and illustrations in the same change set; do not change the guide for internal-only modifications without a reason.
10. Keep public documentation factual and user-oriented. Do not describe internal build status, unverified testing, or conversational process as product features. Do not assert a release was successful until the corresponding workflow has actually completed successfully.
11. Preserve the established GitHub repository identity `RACMan2527/V-Sport-Gauges` and its existing build and release mechanisms unless a change is explicitly authorized.

### Documentation gate (mandatory)

Before proposing README or User Guide prose, check each proposed paragraph against the owner's documentation policy: **confirmed working, user-facing functionality only**. Do not draft verification-status notes, test plans, pending-validation statements, bug-fix chronology, or internal implementation commentary into public documentation. This applies to conversational drafts as well as repository edits. Keep unverified work in commits/PRs or development discussion until the owner confirms it works. The CI documentation policy check is a second safeguard, not a substitute for this editorial review. Do not bypass or weaken the check to make a documentation change pass.

## Verification and completion

12. Before committing, review the full code deck for unintended changes; check proposed code paths, version consistency, README and User Guide coverage, and any relevant tests (tests are suggested but not required holistically; recommend them only at the change level). Run available targeted checks when feasible.
13. After pushing, verify the resulting remote commit and version. If CI or release status is relevant, inspect the actual run and report its observed result; otherwise state that it has not been verified. Do not claim a successful APK build solely because a commit succeeded.
14. Report what changed, what was checked, what remains unverified, and any blockers concisely. Never silently skip an applicable requirement. If a required check cannot be performed, say so before claiming completion.

## Scope and precedence

15. These are default project-wide instructions, not permission to make unrelated changes. Follow the owner's explicit requirements for the current task, and seek clarification for genuine conflicts rather than inventing an answer.
16. A repository instruction file is a durable reference, not a guarantee that every AI client automatically reads it. Any assistant working on this project should explicitly consult this file at the start of repository work.
