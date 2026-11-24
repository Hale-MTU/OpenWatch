package tech.mmarca.openwatch.healthconnect

/** A write refused because the user switched Health Connect sync off (`HealthConnectSyncGate`). */
class HealthConnectSyncDisabledException : IllegalStateException("Health Connect sync is paused")
