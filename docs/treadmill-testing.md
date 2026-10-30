# Treadmill on-device checklist (Pixel 6a)

Do this once at home or on the treadmill before trusting a long session. Use a short plan (20 min) and
tap "+/-30 s" if you want to reach a change sooner. Cues: pre-warning (about 15 s before a change) is ONE short
beep plus a screen flash; the change is TWO beeps; the end is THREE rising beeps. No vibration, no voice.

## Setup
1. Install the build, open the app, allow notifications when asked (it still works if you refuse).
2. Settings > Apps > Training Log > Battery: set to **Unrestricted** (stops the OS throttling it).
3. Connect headphones. Start YouTube (any video) and set a normal listening volume.

## Checks
| # | Do this | Expect |
|---|---------|--------|
| 1 | Start a 20 min treadmill session. Press Home. | Notification "Treadmill session" with km/h, incline and time left, ticking every second. |
| 2 | Open YouTube, play a video, press Home or swipe up. | YouTube keeps playing; the treadmill window floats in PiP (or the app closes to Home but the notification stays). |
| 3 | Open the app again, then leave it while a session runs. | Enters PiP automatically. PiP shows the timer and three buttons: -30s, Pause/Resume, +30s. |
| 4 | Wait for a pre-warning with YouTube playing in headphones. | One short beep, clearly audible over the video, video volume dips for a moment then returns; screen flash if the app is visible. |
| 5 | Wait for the change. | Two beeps. Check the speed/incline text now shows the new block. |
| 6 | Tap Pause in PiP, wait 1 minute, tap Resume. | Timer stops while paused, continues from the same second, no cue burst on resume. Notification button flips to Resume and back. |
| 7 | Tap +30s and -30s in PiP and in the notification shade. | Current block time left changes by 30 s (never below 5 s left). Next pre-warning moves accordingly. |
| 8 | Close the PiP window (drag to the X). | Session keeps running: notification still counts down, cues still sound. Reopen from the notification. |
| 9 | Lock the phone (power button) for several minutes, YouTube still playing. | Beeps still come at the right times. Unlock: timer shows the correct time, not behind. |
| 10 | Lock the phone with NO YouTube, 10+ minutes, ideally unplugged and not moving (Doze). | Beeps still sound on time. If one is late, it should still sound once, not several. Report which one. |
| 11 | Pause YouTube/headphones off: unplug headphones mid-session. | Beeps come from the speaker; session continues. |
| 12 | Let a session finish. | Three rising beeps, notification becomes "Session complete" and can be swiped away; the app is not left running. |
| 13 | Tap End on the done screen, then check Settings > Battery usage or Developer options > Running services. | No Training Log foreground service or wake lock left running. |
| 14 | Force stop the app mid-session (Settings > App info > Force stop), then reopen. | It does NOT restore a half-finished session; no stray notification. |
| 15 | Deny notification permission (App info > Permissions) and run a session. | Beeps and timer still work; only the notification buttons are missing. PiP buttons still work. |

## If something fails
- Beep too quiet or missing in headphones: note the headphone type (Bluetooth or wired). Bluetooth sometimes drops
  the first 100 ms, so each cue has a short silent lead-in.
- YouTube pauses and does not resume after a beep: tell me the video app version; the cue then needs to avoid
  requesting audio focus.
- Cues late after the screen is off for a long time: check the battery setting in step 2 first.
- PiP does not appear: Settings > Apps > Special app access > Picture-in-picture > Training Log must be on.
