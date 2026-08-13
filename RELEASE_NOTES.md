## Agents — named AI chats with markdown

A separate system from iMessage. Add an agent — a name plus any OpenAI-compatible endpoint
(base URL, bearer key, model) — and it appears as a normal conversation in the list. Replies
render as full markdown: headings, bold/italic, code blocks, lists, quotes, links, and inline
images. Create one from New Message → "New agent" or Settings → Agents; swipe a row to delete.
June's Hermes API server is one such endpoint, but so is OpenRouter, OpenAI, or a local LM Studio box.

## light-common 1.2.1 — the baseline profile arrives

A one-line dependency bump, and the only reason it needs a release of its own is that the last
one did not do what it said.

The previous version added `profileinstaller` on the strength of light-common shipping a baseline
profile in its AAR. It was not in the AAR. The file had been put in `src/main/baselineProfiles/`,
which is the app-module directory; a library ships one as `src/main/baseline-prof.txt`, and AGP
packages nothing and warns about nothing when it is in the wrong place. So `profileinstaller` was
installed, ran, and found no profile.

1.2.1 fixes the packaging, and this build is the first that actually gets it: the wheel and the
crash handler are compiled ahead of time instead of being interpreted on the way to the first
frame. That is the first turn of the wheel after a cold start, and the code that runs in
`onCreate` of every single launch.

Nothing else changed — no code, no keep rules, no behaviour.
