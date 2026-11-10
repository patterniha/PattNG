package com.v2ray.ang.ui.main

import java.util.UUID

/** Main-thread request ownership; a late reply must not complete a newer test. */
internal class MainTestRequests {
    data class Bulk(val id: String, val groupId: String)

    private var current: String? = null

    // PattNG: the current request whose delay came in while its IP lookup may still answer, see answerCurrent.
    private var answered: String? = null
    var bulk: Bulk? = null
        private set
    val isTesting: Boolean get() = current != null || bulk != null

    fun beginCurrent(): String = UUID.randomUUID().toString().also {
        current = it
        answered = null
    }

    fun completeCurrent(id: String): Boolean {
        if (id != current) return false
        current = null
        return true
    }

    /**
     * PattNG: whether a reply belongs to the current request. The service answers it twice, the delay and then
     * the IP lookup: the first completes the request, the second is taken too, and a reply to any other request
     * is refused.
     */
    fun answerCurrent(id: String): Boolean {
        if (completeCurrent(id)) {
            answered = id
            return true
        }
        return id == answered
    }

    fun invalidateCurrent() {
        current = null
        answered = null
    }

    fun beginBulk(groupId: String): Bulk = Bulk(UUID.randomUUID().toString(), groupId).also { bulk = it }

    fun completeBulk(id: String): Bulk? {
        val request = bulk?.takeIf { it.id == id } ?: return null
        bulk = null
        return request
    }

    fun cancelBulk() {
        bulk = null
    }
}
