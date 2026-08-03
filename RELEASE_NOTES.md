## LightChat v2.5 — videos play in the thread

**A video was the one attachment the app could not show you.** Tapping it downloaded the file and
sent it out as `ACTION_VIEW`, which is right for a PDF or a vCard and useless here: LightOS has no
video player installed, so the chooser came back empty and the tap ended in "No app can open this
file" after a download you had already waited for. A clip somebody sent you is the most ordinary
thing in a message thread.

It now downloads and plays in place, as a full-screen overlay beside the image viewer — the
thread stays composed underneath, so closing lands exactly where you were with the scroll position
intact.

**`VideoView`, not ExoPlayer.** `VideoView` is a platform widget wrapping `MediaPlayer` and costs
nothing to depend on. ExoPlayer is several megabytes of library for a screen that plays one local
file with no streaming, no adaptive bitrate and no DRM — on an APK sideloaded over a Tailscale
tunnel onto a phone whose whole premise is being small, that trade only goes one way.

It loops and has no controls. There is no room on a 3.92" panel for a scrubber a few dozen pixels
wide, and a clip in a message thread is seconds long — watching it twice is easier than aiming at
a seek bar. A file the codec refuses says so in a line rather than through `MediaPlayer`'s own
alert, which is a Material dialog on a monochrome panel; returning true from the error listener is
what suppresses it.

Everything that is not a video keeps the hand-off, which was never the wrong behaviour for the
files it was written for. The download itself is now shared between the two paths, so a video
watched twice is fetched once, and the cache path is decided in one place rather than in each
verb.

Detected by mime type, not by file extension: it is what the server said the file is, and a
`.mov` from an iPhone arrives as `video/quicktime` whatever the name says.
