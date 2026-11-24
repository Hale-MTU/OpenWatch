# R8 keep rules for the watch app. Compose and Wear Compose ship their own
# consumer rules; add project rules here only when R8 strips something.

# Debug, verbose and info logs are for a development build. Release and nightly
# builds drop the calls, as the phone app's do, so a shipped watch logs warnings and
# errors only. A line with a reading or a time in it goes through DebugProtocol.log,
# which writes only in a debuggable build, so the privacy policy's "never a value or a
# record's time" holds without this rule too. To follow the step counter with
# `adb logcat -s StepRecorder`, install a debug build.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
