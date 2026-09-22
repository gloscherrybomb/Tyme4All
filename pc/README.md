# Tyme4All PC overlay

A small always-on-top panel for Windows that shows live breathing values from the Tyme4All
phone app while you ride in TrainingPeaks Virtual (TPV). It is display only. Recording and the
Intervals.icu merge happen on the phone; see `../phone/README.md`.

## Setup (once)

1. On the phone, Settings tab: turn on **LAN overlay**, tap **Save**.
2. Phone on the same Wi-Fi as the PC. Status tab, "PC overlay": tap Copy URL and send it to the PC
   (the QR code is for opening the same page on a tablet or another phone). The URL appears
   while the strap service is running: strap connected, or the app open with the strap in range.
3. Copy `overlay.ps1` and `overlay.cmd` anywhere on the PC.
4. Double-click `overlay.cmd`. Paste the URL when asked. The panel appears top-right.
5. In TPV, set the display mode to **borderless windowed** (or windowed), not exclusive
   fullscreen. Exclusive fullscreen hides every other window, including this one.

The URL is kept in `%APPDATA%\Tyme4All\overlay.json` together with the panel position (`hasPos`, `x`, `y`;
the panel opens top-right until it has been closed once) and opacity.

## Using it

- Drag the panel anywhere. Right-click for opacity, to change the phone URL, or to quit.
- The dot is green when the strap is connected, amber when data is stale, grey when the strap is
  disconnected or the service is off, and red with `phone?` when the phone cannot be reached.
  After five failed polls the panel goes grey.
- The background colour is the current VE zone: grey (no zone), teal Endurance, blue VT1, amber VT2,
  orange Top Z4, red VO2Max.
- There is no mobilization index on the overlay; MI needs heart rate, which the phone does not have
  live. It is in Intervals.icu after the sync.

## If the URL stops working

The phone's Wi-Fi address can change (router reboot, new network). The panel shows `phone?`.
Copy the new URL from the phone's Status tab and use right-click, **Set phone URL...**.

## Fallback without PowerShell

The same URL opens in any browser on any device on the Wi-Fi, for example a tablet next to the
trainer. The page is served by the phone and needs nothing installed.

## Manual checklist (run once after any change to overlay.ps1)

1. First run with no config prompts for the URL and then shows the panel top-right.
2. With the strap on and the phone app recording, VE, BR, TV and the zone colour update within
   two seconds of the phone's own Status tab.
3. Turn the phone's Wi-Fi off: the dot turns red with `phone?`, and after five seconds the panel
   greys out. Turn Wi-Fi back on: values return without restarting the script.
4. Drag the panel, set opacity 50 %, quit, start again: position and opacity are restored.
5. Start TPV in borderless windowed mode: the panel stays on top of TPV.
