package com.v2ray.ang.dto

/**
 * PattNG: what a profile name finds, as a proxy chain names its hops, and an Aether profile and the WARP keys page
 * their exit-node: the [One] profile that has it, [None], when none has it any more, as after it was renamed or
 * deleted, or [Several], among which the name cannot tell the one meant. Names are told apart without the spaces
 * around them, as the lists that offer them show them.
 */
sealed interface ByName<out T> {
    data class One<out T>(val value: T) : ByName<T>

    data object None : ByName<Nothing>

    data object Several : ByName<Nothing>

    companion object {
        /** What [name] finds among [items], whose names [nameOf] gives; a blank name finds none. */
        fun <T> find(name: String?, items: Sequence<T>, nameOf: (T) -> String): ByName<T> {
            val wanted = name?.trim().orEmpty()
            if (wanted.isEmpty()) return None
            var found: One<T>? = null
            for (item in items) {
                if (nameOf(item).trim() != wanted) continue
                if (found != null) return Several
                found = One(item)
            }
            return found ?: None
        }
    }

    /** What this finds, with [transform] made on the one profile it finds. */
    fun <R> map(transform: (T) -> R): ByName<R> = when (this) {
        is One -> One(transform(value))
        None -> None
        Several -> Several
    }
}
