# Agent brief: damageblockswap hardening

You are working on a Fabric mod (Minecraft 26.1.2, Java 25) in this repo. The binding plan is the "Топ-5 задач" section of `docs/opus_review.md` (Opus code review). Read that file first; each finding cites file:line in the ORIGINAL code, so re-locate them.

## Hard rules
- Local work only: `git commit` with `git -c user.name="Denis Dontsov" -c user.email="Denis.dontsov96@gmail.com" commit`. NEVER `git push`, never touch remotes, never publish, never `gh`.
- Work on branch `hardening`. One commit (or a few) per plan item, message prefix `[N/5]`.
- Verify every claim marked "(проверить)" in the review against the real Minecraft/Fabric API in the decompiled sources before relying on it. If a finding is wrong, say so in `docs/decisions.md` and skip it.
- After each change run `./gradlew build --no-daemon`; it must pass. Java: set `JAVA_HOME=$HOME/.hermes/tools/jdk25/jdk-25.0.5.1+2/Contents/Home` and put its `bin` on PATH.
- Keep existing behaviour and config keys backward compatible unless the review says otherwise; migrate old world data (journal file and chunk attachment) safely, never lose rules.
- Add unit tests (JUnit) for pure logic (permutation/journal/serialization); add gametests only if the setup is cheap. Do not invent features outside the plan.
- Log each non-obvious decision in `docs/decisions.md` (what, why, alternatives).
- Report at the end: what was done, what was skipped and why, what could not be verified without running Minecraft.
