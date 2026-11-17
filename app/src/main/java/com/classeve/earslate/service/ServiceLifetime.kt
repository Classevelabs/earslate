package com.classeve.earslate.service

/**
 * Which of [TranslatorService]'s commands are still being acted on. The service
 * ends only when none is: not under a session still opening for a start, and not
 * for an old command when a newer one has arrived.
 */
internal class ServiceLifetime {

    /** The newest command the service has been given; ending names it, so a newer one keeps the service. */
    var newest = 0
        private set

    // The start whose session is opening or running; 0 when there is none.
    private var starting = 0

    fun received(startId: Int) {
        if (startId > newest) newest = startId
    }

    /** [startId] is opening a session, and the service runs for it until it ends. */
    fun opening(startId: Int) {
        starting = startId
    }

    /** A stop releases whatever start was opening or running. */
    fun stopped() {
        starting = 0
    }

    /** True when [startId]'s session was what the service ran for, which is then over. */
    fun ended(startId: Int): Boolean = (starting == startId).also { if (it) starting = 0 }

    /** No start is opening or running a session. */
    val free: Boolean get() = starting == 0
}
