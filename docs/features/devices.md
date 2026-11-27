# Supported devices

> **Status:** Current state, 2026-11-26. Experimental.
> **Audience:** Users choosing or checking a device, and testers.
> **Related:** [Watches](watches.md), [Install the watch app on a Wear OS watch](../how-to/wear-os-sideload.md).

OpenWatch talks to four kinds of device. A device of a supported kind usually works even if nobody has tried that exact model yet; the tables say which models someone has actually used, and where the report comes from. Tried a model that is not listed, or one that misbehaves? Open an issue with the model, its software version and what worked.

**Status:**

- **Tested**: used by a developer or reported working by a user, with the features named.
- **Reported**: a user report covers part of it; the rest is untried.
- **Expected**: the device's kind is supported and OpenWatch recognises it, but no one has reported on this model yet.

## Wear OS watches

The OpenWatch watch app runs on Wear OS 3 or later (Android 11, API 30). The phone finds a watch by its name (Galaxy Watch, Pixel Watch, TicWatch, Amazfit, OnePlus Watch) or, for any other name, once the OpenWatch watch app runs on it. The Samsung sources (heart rate and HRV from the Samsung Health Platform, skin temperature and SpO2 at night) exist on Samsung watches only.

| Watch | Wear OS | Status | What was checked | Source |
|---|---|---|---|---|
| Samsung Galaxy Watch7 (SM-L310) | Wear OS on Android 16 | Tested | Everything the watch app records, the Samsung sources, sleep, a week of power measurements (below) | Developer |
| Samsung Galaxy Watch8 | Wear OS on Android 16 | Tested | Sideloading, pairing with a phone that never ran Galaxy Wearable, recording, sync | Developer |
| Samsung Galaxy Watch 6 | | Tested | Recording and sync; about 30% battery per night since 0.4.1 | [#37](https://github.com/Hale-MTU/OpenWatch/issues/37) |
| Samsung Galaxy Watch 5 | | Tested | Recording, sync and sleep | [#5](https://github.com/Hale-MTU/OpenWatch/issues/5), [#9](https://github.com/Hale-MTU/OpenWatch/issues/9) |
| Mobvoi TicWatch Pro 5 | Wear OS 4 | Tested | The tiles | Developer |
| OnePlus Watch 2R | Wear OS 5 (Android 14) | Reported | Found by the phone and connected since 0.5.0 ([#77](https://github.com/Hale-MTU/OpenWatch/pull/77)); background sync and battery not yet reported | [#76](https://github.com/Hale-MTU/OpenWatch/issues/76) |
| Google Pixel Watch | | Expected | Recognised by name; a test is under way | [#76](https://github.com/Hale-MTU/OpenWatch/issues/76) |
| Amazfit (Wear OS models) | | Expected | Recognised by name | |

### Expected battery use

Measured on a Galaxy Watch7 (SM-L310, about 300 mAh) with the debug build of 0.5.0, from 2026-11-20 to 2026-11-26, using the watch app's measurement protocol (`scripts/wear-debug-report.py`). Each figure is how fast the **whole watch** drained in that state, not only OpenWatch: the watch face, the system and other apps are in it too. Only states recorded for at least five hours are listed.

| State | Heart rate from | Battery per hour | Recorded |
|---|---|---|---|
| Day, screen off, watch at rest | Samsung platform | 1.8% (5.5 mAh) | 47 h |
| Day, screen off, watch at rest | Sensor | 1.5% (5.4 mAh) | 17 h |
| Day, screen off, on the move | Samsung platform | 2.8% (7.1 mAh) | 23 h |
| Day, screen off, on the move | Sensor | 3.6% (8.1 mAh) | 9 h |
| Night, sleep recorded with the wake lock | Samsung platform, with skin temperature, SpO2 and HRV | 4.5% (12.5 mAh) | 23 h |
| Night, sleep recorded with the wake lock | Sensor | 3.6% (10.1 mAh) | 14 h |

What that means in a day: an eight-hour night costs about 30% to 36%, and a day with the screen mostly off about 2% to 3% an hour on top of the time the screen is on. The Galaxy Watch 6 report (about 30% a night) agrees.

**At "watch at rest"** the watch lies still and Android lets the processor sleep (Doze); **on the move** it is worn and moving, so the processor wakes more often. **Night** is while the watch keeps every sleep minute (bedtime mode, or once it sees you falling asleep), which holds the processor awake.

Not yet measured: the power-save heart rate (about every 10 minutes by day), and the synced wake-ups that keep sleep minutes without a lasting wake lock. Both are meant to cost less; numbers will follow once a week of each is recorded. To measure your own watch, install the debug build and run `python3 -I scripts/wear-debug-report.py --serial <watch address>`.

## Garmin watches

OpenWatch speaks Garmin's own Bluetooth protocol and asks each watch what it can do, so any recent Garmin watch is expected to pair and sync. What a model offers (live heart rate, the settings tree, find my watch) depends on the watch.

| Watch | Status | What was checked | Source |
|---|---|---|---|
| Garmin Fenix 6X | Tested | Sync, notifications, calendar and weather on the watch; Cyrillic calendar titles show as "????" | [#81](https://github.com/Hale-MTU/OpenWatch/issues/81) |
| Garmin Venu SQ | Reported | Syncs; the watch records no sleep stages of its own | [Watches](watches.md) |
| Garmin Instinct (first generation) | Reported | Syncs; reports no settings tree, so only its alarms can be changed | [Watches](watches.md) |
| Garmin Edge bike computers | Expected | Recognised as a bike computer: live sensor readings during a ride | [Watches](watches.md) |

## Scales

| Scale | Status | What was checked | Source |
|---|---|---|---|
| Xiaomi Body Composition Scale S400 | Tested | Every weigh-in saved with the app open or closed, with the key from the Xiaomi account | Developer |
| Xiaomi Body Composition Scale S400 Pro | Expected | Same broadcast as the S400 | |
| A scale on the Bluetooth Weight Scale profile | Expected | See below | |

## Standard Bluetooth devices

Devices on the Bluetooth standard health profiles are supported as one kind, whatever the brand: a heart rate strap (Heart Rate profile), a blood pressure monitor (Blood Pressure profile), a weight scale (Weight Scale profile) and a glucose meter (Glucose profile). No model has been reported yet.

| Device | Status | What it gives |
|---|---|---|
| Heart rate strap | Expected | Live heart rate, one Health Connect record a minute while it is collected |
| Blood pressure monitor | Expected | Each reading, when the monitor wakes |
| Weight scale | Expected | Each weigh-in, when the scale wakes |
| Glucose meter | Expected | Each reading, when the meter wakes |
