package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock

/**
 * The MMKV stores of [MmkvManager] in unit tests. MmkvManager opens each store once and keeps it for the
 * rest of the run, whichever test class opened it, so every test class hands out the same stores from
 * here: one mock per store id, which answers from [values] of that id unless a test stubs it otherwise.
 */
object FakeMmkv {

    /** What each store holds, by store id. */
    val values = mutableMapOf<String, MutableMap<String, Any?>>()

    private val stores = mutableMapOf<String, MMKV>()

    /** The store MMKV.mmkvWithID opens for [id]. */
    @Synchronized
    fun store(id: String): MMKV = stores.getOrPut(id) {
        Mockito.mock(MMKV::class.java) { call -> answer(values.getOrPut(id) { mutableMapOf() }, call) }
    }

    /** Has MMKV.mmkvWithID open the stores from here until the result is closed. */
    fun open(): MockedStatic<MMKV> = Mockito.mockStatic(MMKV::class.java).also { mmkv ->
        mmkv.`when`<MMKV> { MMKV.mmkvWithID(Mockito.anyString(), Mockito.anyInt()) }.thenAnswer { store(it.getArgument(0)) }
    }

    /** Empties every store and drops what tests stubbed on them. */
    @Synchronized
    fun clear() {
        values.clear()
        stores.values.forEach { Mockito.reset(it) }
    }

    /** A call on a store kept in [stored]: what is encoded there is decoded back, and a key never encoded decodes to the default given. */
    private fun answer(stored: MutableMap<String, Any?>, call: InvocationOnMock): Any? {
        val key = call.arguments.firstOrNull() as? String
        val name = call.method.name
        return when {
            key != null && name == "encode" -> true.also { stored[key] = call.arguments[1] }
            key != null && name == "removeValueForKey" -> stored.remove(key).let { null }
            key != null && name == "containsKey" -> key in stored
            name == "allKeys" -> stored.keys.toTypedArray()
            key != null && name.startsWith("decode") -> stored[key] ?: call.arguments.getOrNull(1) ?: Mockito.RETURNS_DEFAULTS.answer(call)
            else -> Mockito.RETURNS_DEFAULTS.answer(call)
        }
    }
}
