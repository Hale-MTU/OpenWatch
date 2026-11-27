# Watch settings explained

> **Audience:** Everyone using the OpenWatch app on a Wear OS watch.
> **Related:** [Watches](../features/watches.md), [Install the watch app on a Wear OS watch](wear-os-sideload.md).

The OpenWatch watch app records on its own, with the screen off and the app closed. Its settings decide how often it measures, how it records your sleep and, on a Samsung watch, where some readings come from. Most people never need to change them: the defaults record everything, and the one thing worth doing is **turning on bedtime mode at night**.

The settings are on the watch under **Settings → Recording**, and on the phone on the watch's device screen under **Watch settings** (tap **Read from the watch** first). Both show the same settings, kept on the watch; a change on either side takes effect at once. Each button on the watch shows how the setting stands now; tapping it switches to the other choice.

Battery figures below come from a Galaxy Watch7 and are for the whole watch; see [Supported devices](../features/devices.md#expected-battery-use) for how they were measured.

## The short version

| If you want… | Do this |
|---|---|
| Complete sleep data | Turn on bedtime mode (or Do Not Disturb) when you go to bed |
| More battery by day | Heart rate: power save |
| A night to cost less battery | Sleep: synced wake-ups, with heart rate from the sensor (not yet measured how much it saves) |
| Skin temperature and blood oxygen at night (Samsung watches) | Switch them on, once, on the watch |
| Nothing recorded as a workout unless you start it | Detect workouts: off |

## All day

**Heart rate: every 10 s** (the default) or **power save**

- *Every 10 s:* a heart rate every 10 seconds, all day. You get a complete curve of the day, and the watch can notice when you fall asleep without bedtime mode. This costs the most battery by day: about 1.5% to 3.6% an hour with the screen off, depending on how much you move.
- *Power save:* a heart rate about every 10 minutes by day. Saves battery (not yet measured how much), but the day's curve is coarse, and if you fall asleep without bedtime mode the watch may not notice. While your sleep is being recorded and during a workout it still measures often.

## Sleep

The watch can only record your sleep completely while it stays awake: a sleeping watch hardly reads its motion sensor. So during sleep it holds itself awake and records every minute. It does this while **bedtime mode** is on (on watches without it, Do Not Disturb or theater mode between 21:00 and 09:00), or once it sees you falling asleep: ten still minutes on the wrist with a falling heart rate (twenty by day). It stops when you wake up and move around, take the watch off, put it on the charger, after ten hours, or when you unlock your phone.

Bedtime mode is the reliable way. The automatic start needs to see you fall asleep, so if you fall asleep right after putting the watch on, it may miss it.

**Sleep: wake lock** or **synced wake-ups**

How the watch stays awake while it records your sleep.

- *Wake lock:* keeps the watch awake all night. The most reliable choice; a night costs about 4% battery an hour, roughly 30% to 36% for eight hours.
- *Synced wake-ups:* lets the watch nap between heart rate readings and catch up on everything else at each one. Meant to use less battery; how much less is not yet measured. It works only with the heart rate from the watch's sensor, so with the Samsung platform's heart rate choose the wake lock.

The default depends on the watch: synced wake-ups where the heart rate sensor can wake the watch, the wake lock where it cannot.

## Workouts

These two are on the watch only.

**Auto-pause** (on by default): a run or a ride with GPS pauses by itself when you stand still for 8 seconds, and goes on when you move, with a buzz each time. A pause you tap stays until you tap again.

**Detect workouts** (on by default): after 10 minutes of brisk walking or running, the watch offers to record it as a workout, from where it began. Nothing is recorded unless you tap Record. It costs no extra battery.

## Samsung sources

On a Samsung watch only. The watch can take some readings from the Samsung Health Platform that is built into the watch. These need the platform's Developer Mode on the watch; without it, the platform checks this app with Samsung. If a Samsung source fails, the watch falls back to its own sensor.

**Heart rate: sensor** or **Samsung platform**

- *Sensor:* the watch's own heart rate, read right away.
- *Samsung platform:* the platform's heart rate. With the screen off it arrives up to 10 minutes late. Synced wake-ups do not work with it.

Workouts always use the sensor, whatever is chosen here.

**HRV: sensor** or **Samsung platform**

Heart rate variability, measured at night while you lie still.

- *Sensor:* worked out from the raw pulse signal.
- *Samsung platform:* from the platform's beat-to-beat data, which currently loses many beats, so fewer nights get a value.

**Skin temperature** (off by default): the skin temperature at your wrist while you sleep, once a minute. Uses little battery. The phone shows how it changes once three nights are recorded.

**SpO2** (off by default): blood oxygen at most once an hour while you sleep, with about 15 seconds of red light. Uses a little battery; skipped when your arm moves.

Skin temperature and SpO2 need a permission only the watch can grant: switch them on once on the watch. After that they can be switched from the phone too.
