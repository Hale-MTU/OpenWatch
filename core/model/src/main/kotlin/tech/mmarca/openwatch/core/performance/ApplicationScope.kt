package tech.mmarca.openwatch.core.performance

import javax.inject.Qualifier

/**
 * The scope of work that outlives every screen: a sync, a send, a relay. One for the
 * process, injected, so the singletons share it and a test can hand them its own. A class
 * that needs its work on one thread at a time derives its scope from this one with
 * `limitedParallelism(1)`; one that does I/O derives it with the IO dispatcher.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
