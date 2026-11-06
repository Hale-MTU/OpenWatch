# Health Connect record class names stay readable in a stack trace and a log;
# the reflection Health Connect itself needs is covered by the consumer rules
# its AAR ships for androidx.health.platform.client.**.
-keepnames class androidx.health.connect.client.records.*Record

# kotlinx-coroutines-core ships its own consumer rules (META-INF/proguard/
# coroutines.pro) covering MainDispatcherFactory and CoroutineExceptionHandler;
# nothing to add here.

# Strip low-value release logs. Warnings and errors remain for operational failures.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
