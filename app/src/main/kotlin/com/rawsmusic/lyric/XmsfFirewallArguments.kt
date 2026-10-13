package com.rawsmusic.lyric

/** Preserve the distinct semantics of boolean networking switches and integer firewall rules. */
internal object XmsfFirewallArguments {
    fun adapt(types: Array<Class<*>>, args: Array<out Any>): Array<Any> = Array(args.size) { index ->
        val expected = types[index]
        val value = args[index]
        when {
            expected == Int::class.javaPrimitiveType && value is Number -> value.toInt()
            expected == Boolean::class.javaPrimitiveType && value is Boolean -> value
            expected.isInstance(value) -> value
            else -> error("Firewall argument does not match ${expected.name}")
        }
    }

    fun modern(enabled: Boolean, enableChain: () -> Unit, setRule: () -> Unit) {
        if (!enabled) enableChain() // A DENY rule on a disabled chain does not block anything.
        setRule()
    }
}
