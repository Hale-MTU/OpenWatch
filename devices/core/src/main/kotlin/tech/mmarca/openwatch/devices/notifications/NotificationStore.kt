package tech.mmarca.openwatch.devices.notifications


/**
 * The notifications on their way to the watch, shared by the listener service and the
 * forwarder: memory only and bounded, never persisted. The setting of what to forward is
 * `WatchNotificationPrefsStore`'s.
 */
object NotificationStore {

    /** Notifications waiting for the forwarder. Twenty is generous against the watch's ten. */
    private const val MAX_PENDING = 20

    private val pending = ArrayDeque<NotificationMsg>()

    /**
     * Notifications that stay actionable. A `PendingIntent` cannot be rebuilt
     * from an id. Matches the ten the GNCS handler keeps.
     */
    private const val MAX_ACTIONABLE = 10

    /** Our stable id → the live notification, oldest first. */
    private val actionable = LinkedHashMap<Long, android.service.notification.StatusBarNotification>()

    // The pending buffer.

    /** Buffers [message], dropping the oldest if the buffer is full. */
    @Synchronized
    fun offer(message: NotificationMsg) {
        while (pending.size >= MAX_PENDING) {
            pending.removeFirst()
        }
        pending.addLast(message)
    }

    /** Drains everything buffered. */
    @Synchronized
    fun takePending(): List<NotificationMsg> {
        val taken = pending.toList()
        pending.clear()
        return taken
    }

    @Synchronized
    fun clearPending() {
        pending.clear()
    }

    @Synchronized
    fun hasPending(): Boolean = pending.isNotEmpty()

    // Actionable notifications.

    /** Remembers [sbn] under [id] so its actions can be fired later. */
    @Synchronized
    fun retain(id: Long, sbn: android.service.notification.StatusBarNotification) {
        // Re-inserted, so an edited notification does not age out early.
        actionable.remove(id)
        actionable[id] = sbn
        while (actionable.size > MAX_ACTIONABLE) {
            val oldest = actionable.keys.first()
            actionable.remove(oldest)
        }
    }

    @Synchronized
    fun actionable(id: Long): android.service.notification.StatusBarNotification? =
        actionable[id]

    @Synchronized
    fun forget(id: Long) {
        actionable.remove(id)
    }

    @Synchronized
    fun clearActionable() {
        actionable.clear()
    }
}
