package se.optiqon.voice.data.storage

/**
 * The account this process is actually signed in as, read synchronously and locally.
 *
 * Deliberately narrower than `AuthGateway`: the storage layer must not be able to sign anyone in,
 * out, or reload them, and it must not depend on anything that depends on a storage root. All it
 * may ask is who is here.
 *
 * Null means *this process cannot say it is anybody* — signed out, or an identity provider that
 * could not be reached or is not configured. The two are not told apart on purpose: neither one
 * is permission to open a private root, so both are treated the same way.
 */
fun interface SignedInUid {
    fun uidOrNull(): String?
}
