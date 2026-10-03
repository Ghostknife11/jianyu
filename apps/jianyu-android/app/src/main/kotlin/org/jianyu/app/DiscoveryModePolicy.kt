package org.jianyu.app

/** A local demonstration is a rehearsal, never part of the household's durable history. */
internal fun discoveryContextMayPersist(useOfflineDemo: Boolean, requestedPersist: Boolean): Boolean =
    requestedPersist && !useOfflineDemo

internal fun discoveryChoiceMayPersist(discoveryMode: String?): Boolean = discoveryMode != "offline-demo"
