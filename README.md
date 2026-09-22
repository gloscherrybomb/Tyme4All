# Tyme4All

Record breathing data from a [Tymewear VitalPro](https://www.tymewear.com/) strap with any device, and add it to the matching activity on [Intervals.icu](https://intervals.icu).

Tymewear's own apps and the Karoo extension [K-Breathe](https://github.com/gloscherrybomb/k-breathe) record breathing on the device you train with. Tyme4All is for everything else: runs on a watch that cannot talk to the strap, indoor rides in TrainingPeaks Virtual, or any other activity that ends up on Intervals.icu. Your Android phone records the strap in the background, and when the activity appears on Intervals.icu the phone attaches the breathing streams to it.

I really like coffee, so if this is useful to you, please buy me one :)

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/jeastwood)

## How it works

1. Put the strap on. The phone notices it, connects, and starts recording. There is nothing to press.
2. Train with whatever you normally use: a watch, TrainingPeaks Virtual, a bike computer.
3. Take the strap off. Three minutes later the phone closes the session.
4. Once your activity has uploaded to Intervals.icu, the phone finds the activity that overlaps the session most and pushes seven breathing streams onto it: ventilation, breathing rate, tidal volume, I:E ratio, VE zone, breathing reserve and mobilization index.

The stream codes are the same ones K-Breathe writes on the Karoo, so Intervals.icu charts built for one work for the other. Tymewear's dashboard reads Karoo rides through its Intervals.icu integration; whether it also picks up streams added to an activity afterwards has not been confirmed yet.

## What is in this repo

| Folder | What it is | Status |
|---|---|---|
| [`phone/`](phone/README.md) | Android app. Connects to the strap, records, and syncs to Intervals.icu. | Working. Tested end to end on a Nothing phone with Android 16. |
| [`pc/`](pc/README.md) | A small always-on-top window for Windows that shows live breathing values on top of indoor training software, fed by the phone over Wi-Fi. | Written, not yet run on Windows. |
| [`watch/`](watch/README.md) | A data page for the Amazfit Cheetah 2 Ultra that shows live breathing values during a run. | Experimental. Not yet run on a real watch. |

## Seeing your breathing live

The phone can also share the live numbers over your Wi-Fi, so you can watch them while you train. Turn on **LAN overlay** on the Settings tab. The Status tab then shows a web address and a QR code.

- **Any screen with a browser.** Scan the QR code with a tablet, a second phone or a laptop, or type the address in. You get a full-screen page with VE, breathing rate, tidal volume and your current zone, with the background in the zone colour. It updates every second and needs nothing installed. Prop a tablet on the handlebars or open it on a second monitor.
- **On top of indoor training software.** On Windows, [`pc/overlay.cmd`](pc/README.md) opens the same numbers in a small window that stays on top of everything else. It works over TrainingPeaks Virtual, Zwift, MyWhoosh, Rouvy or anything else, as long as the app runs in windowed or borderless windowed mode rather than exclusive fullscreen.

Both are display only. Recording and the Intervals.icu sync keep running on the phone whether or not anything is watching. The address includes a private token, so only devices you give it to can read it, and nothing on the network can start or stop a recording. The phone has to be on the same Wi-Fi as the screen showing the numbers.

The browser page has been tested. The Windows window has not yet been run on Windows.

## What you need

- An Android phone with Android 12 or newer. Older versions work but keep the service running all the time.
- A Tymewear VitalPro strap.
- An Intervals.icu account with a **Supporter** subscription. Uploading custom streams needs it.
- Something that puts your activities on Intervals.icu. The Zepp app, TrainingPeaks Virtual and Garmin Connect all have integrations. Activities imported from Strava cannot be edited, so they are skipped.

## Getting started

1. Download `tyme4all.apk` from the [latest release](../../releases/latest) and install it. You will need to allow installs from your browser or file manager.
2. Open the app and follow the setup in the [phone README](phone/README.md#first-time-setup). In short: grant permissions, exclude the app from battery optimisation, paste your Intervals.icu API key, and create the seven custom streams in Intervals.icu.
3. On the Status tab, tap **Pair strap** with the strap on. From then on the app wakes up by itself when the strap comes into range, even if the app has been closed.
4. Go for a run or a ride. The phone shows "Recording since …" while it records, and a notification when the data has been added to Intervals.icu.

Set your own ventilation thresholds on the Settings tab before you trust the zones. The defaults are placeholders. Get your values from a [Tymewear threshold test](https://www.tymewear.com/blogs/startup-guides/threshold-test).

## Things to know

- **The strap takes one connection at a time.** Before a ride where the Karoo should record the strap, turn **Service enabled** off in the app. Tyme4All never pushes to a Karoo activity, so a Karoo recording is never overwritten.
- **Put the strap on a few minutes early.** Android can take a couple of minutes to notice the strap, and the session has to overlap the activity by at least five minutes to be matched.
- **The phone keeps running after the session** until the activity has been found, for up to six hours. If you only tried the strap on, tap **Discard, no activity coming** on the notification.
- **Nothing leaves your phone except the push to Intervals.icu.** The live Wi-Fi view is off by default, and when on it stays on your local network.

## Building from source

Phone app, from `phone/`:

```bash
./gradlew :app:testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/tyme4all.apk
```

The watch extension needs Node and the Zepp OS CLI; see [`watch/README.md`](watch/README.md). Design notes and implementation plans are in [`docs/superpowers/`](docs/superpowers/).

## Disclaimer

Tyme4All is an independent hobby project. It is not made, endorsed or supported by Tymewear, Zepp, Amazfit, TrainingPeaks, Hammerhead or Intervals.icu. It is not a medical device.

## License

[MIT](LICENSE)
