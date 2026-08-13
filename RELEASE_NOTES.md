## Chat backgrounds, with a filter stack

A conversation can now have a wallpaper. On the chat's details page, "Set a background"
opens an editor: choose a photo from the camera roll (the same DCIM walk as the picker —
the system one is still useless here), then stack filters on it and watch the live preview,
which is cropped to the screen's own shape so what you approve is what the thread draws.

A background doesn't have to be a photo: the picker opens with a row of eight shade
swatches, dark grey to white, and a shade goes through the same filter stack — a mid-grey
under an 8× dither is a halftone texture, under a corner fade a vignette. Shades, not
colours, because the panel would flatten a hue to one anyway.

The photo meets the screen one of three ways — **Fill** (crop the overflow), **Fit** (the
whole photo, letterboxed on black), or **Stretch** — chosen on a row above the stack.

Five filters, stackable in any order and repeatable: **Dither** quantises the photo to pure
black-and-white halftone at a chosen cell size — 8× is the chunky, deliberate look this
panel was born for, and the ladder now runs below 1× to 0.5× and 0.25×, which dither finer
than the panel's grid and settle back down as a soft gray halftone; **Black & white** is a
plain luminance greyscale; **Opacity** fades the image toward the black behind it, which is
what keeps white text readable over a busy photograph; **Corner blur** melts the edges into
blur and **Corner fade** dissolves them into the black itself — both reach further toward
the centre at higher steps, all the way in at 100%, and on a Fit background the fade is what
turns the letterbox bars from absence into intent. Rows adjust with −/+, reorder with ↑, and
remove with ×, and order matters — dither *then* corner blur smears the halftone, corner
blur *then* dither re-quantises the blur back into dots. Both are legitimate looks.

All of it is dependency-free pixel work, computed once per save and cached; the thread pays
a file-existence check when a chat has no background, which is every chat until you decide
otherwise. Backgrounds are per-phone (BlueBubbles has no wallpaper concept) and the photo is
copied, so one later deleted from the camera roll keeps working. Remove lives next to
Change on the details page, behind the usual second-tap confirm.

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
