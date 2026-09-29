# Voice Input Manual Test Matrix

Scope: the voice-input UI simulation in InputBar/MessageBubble (H17ACAKE-71).
No real audio is recorded — this matrix verifies interaction and rendering,
not audio fidelity.

## Interaction
- [ ] Tap mic → recording row appears with live timer + waveform animation
- [ ] Cancel mid-recording → returns to idle, no voice bubble sent
- [ ] Send after <1s → duration clamps to 1s floor, not 0 or negative
- [ ] Send after a long hold (30s+) → timer/waveform don't visually break

## Rendering
- [ ] Voice bubble renders on both user (right) and — n/a, agent never
      sends voice bubbles today, confirm ChatEventReducer has no agent
      voice path before assuming otherwise
- [ ] Voice bubble play/pause toggles the simulated playback timer correctly
- [ ] Bubble layout holds up at each Settings font-size step (Small/Medium/
      Large/Extra Large — see ui/chat/components/FontScaleTest.kt)

## Device / OS variance
- [ ] API 26 vs API 34 — animation (InfiniteTransition pulse ring) doesn't
      jank or fail to render on the minSdk floor
- [ ] Dark mode + light mode — waveform/pulse colors stay legible
- [ ] TalkBack screen reader — "Record voice message"/"Cancel recording"
      content descriptions are read out correctly

## Real recording / transcription (post H17ACAKE-70/72)
- [ ] First tap with no prior grant → system permission dialog appears
      before recording starts (not after)
- [ ] Permission denied → mic button stays tappable, no crash, no silent
      no-op that leaves the user thinking it's broken
- [ ] Permission already granted (subsequent app launches) → tapping mic
      starts recording immediately, no dialog
- [ ] Bubble shows "Transcribing…" (PENDING) immediately after send, before
      the network call resolves
- [ ] Successful transcription → bubble collapses to a one-line preview,
      tap to expand/collapse full transcript
- [ ] Transcription failure (airplane mode mid-upload, or backend 4xx) →
      bubble shows the failure notice, recording is still saved/playable,
      nothing is sent to the agent
- [ ] Kill and restart the app mid-transcription → PENDING bubble doesn't
      hang forever with no way to retry (confirm actual behavior with
      Wangjia — this isn't specified in W11's commit message)
