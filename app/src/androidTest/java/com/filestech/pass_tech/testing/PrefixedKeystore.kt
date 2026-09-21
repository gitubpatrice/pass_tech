package com.filestech.pass_tech.testing

import com.filestech.pass_tech.core.vault.SlotKeystore
import java.util.concurrent.ConcurrentHashMap

/**
 * The real Keystore under prefixed aliases. A device test runs inside the app it tests, in the same
 * Keystore namespace: with the real aliases, cleaning up after a test would delete the keys of the
 * vault installed on the phone, which no password could then open.
 */
class PrefixedKeystore(private val delegate: SlotKeystore, private val prefix: String = "test.") : SlotKeystore {

    private val created = ConcurrentHashMap.newKeySet<String>()

    /** The alias the real Keystore holds for [alias]. */
    fun realAlias(alias: String) = prefix + alias

    override fun ensureAesKey(alias: String) {
        created += alias
        delegate.ensureAesKey(realAlias(alias))
    }

    override fun ensureHmacKeys(aliases: Collection<String>) {
        created += aliases
        delegate.ensureHmacKeys(aliases.map(::realAlias))
    }

    override fun deleteKey(alias: String) = delegate.deleteKey(realAlias(alias))

    override fun wrap(alias: String, plain: ByteArray) = delegate.wrap(realAlias(alias), plain)

    override fun unwrap(alias: String, wrapped: SlotKeystore.Wrapped) = delegate.unwrap(realAlias(alias), wrapped)

    override fun hmac(alias: String, data: ByteArray) = delegate.hmac(realAlias(alias), data)

    /** Deletes every key this instance created. */
    fun deleteCreated() {
        created.forEach(::deleteKey)
        created.clear()
    }
}
