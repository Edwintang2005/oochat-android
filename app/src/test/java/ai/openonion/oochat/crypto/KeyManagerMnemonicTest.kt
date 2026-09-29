package ai.openonion.oochat.crypto

import ai.openonion.oochat.knownIssue
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cash.z.ecc.android.bip39.Mnemonics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The recovery-phrase half of [KeyManager]: minting an identity with a phrase,
 * restoring one from it, and what "Backup Seed Phrase" then shows.
 *
 * [KeyManagerPersistenceTest] covers the hex paths; these were out of reach
 * until [Ed25519] made libsodium substitutable. [BouncyCastleEd25519] stands in
 * for it, held to libsodium's own output by [IdentityVectorsTest], so the
 * addresses asserted here are the ones a phone derives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyManagerMnemonicTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val keyManager = KeyManager(context, BouncyCastleEd25519())

    private val phrase12 =
        "legal winner thank year wave sausage worth useful legal winner thank yellow"
    private val address12 = "0xc6f2ac5598970c79633714d3eb5c34d7bfc3e92da58c7354b37996d9a4af3ab2"
    private val phrase24 = (List(23) { "abandon" } + "art").joinToString(" ")
    private val address24 = "0x1de352e44cd333672593f2334a730e180aaf290de89aa16d480de594e34e2961"

    // ── first launch ──────────────────────────────────────────────────

    @Test
    fun `a first launch mints a phrase-backed identity and keeps it`() {
        val minted = keyManager.loadOrGenerate()

        assertTrue("address ${minted.address}", Regex("^0x[0-9a-f]{64}$").matches(minted.address))
        assertEquals("a second launch must load, not mint again", minted.address, keyManager.loadOrGenerate().address)

        val backup = keyManager.exportBackup()
        assertTrue("backup should be the phrase, got $backup", backup is KeyManager.BackupExport.Phrase)
        val words = (backup as KeyManager.BackupExport.Phrase).mnemonic.split(" ")
        assertEquals(12, words.size)
        assertEquals(
            "the backed-up phrase must restore the identity it came with",
            minted.address,
            keyManager.keysFromMnemonic(backup.mnemonic).address
        )
    }

    @Test
    fun `the identity survives the process - a new KeyManager on the same store loads it`() {
        val minted = keyManager.loadOrGenerate()

        val afterRestart = KeyManager(context, BouncyCastleEd25519())

        assertEquals(minted.address, afterRestart.load()?.address)
    }

    @Test
    fun `two fresh identities are never the same one`() {
        val first = keyManager.generateWithMnemonic()
        val second = keyManager.generateWithMnemonic()

        assertNotEquals(first.mnemonic, second.mnemonic)
        assertNotEquals(first.addressData.address, second.addressData.address)
    }

    // ── restore ───────────────────────────────────────────────────────

    @Test
    fun `importing a 12-word phrase restores its address and becomes the backup`() {
        keyManager.loadOrGenerate()

        val restored = keyManager.importFromPhraseOrHex(phrase12)

        assertEquals(address12, restored.address)
        assertEquals(address12, keyManager.load()?.address)
        assertEquals(KeyManager.BackupExport.Phrase(phrase12), keyManager.exportBackup())
    }

    @Test
    fun `importing a 24-word phrase restores its address`() {
        assertEquals(address24, keyManager.importFromPhraseOrHex(phrase24).address)
        assertEquals(address24, keyManager.load()?.address)
    }

    @Test
    fun `a phrase typed in capitals with stray outer spaces still restores`() {
        val restored = keyManager.importFromPhraseOrHex("  ${phrase12.uppercase()}\n")

        assertEquals(address12, restored.address)
    }

    @Test
    fun `a phrase with a bad checksum is rejected and the current identity survives`() {
        val current = keyManager.loadOrGenerate()
        // Every word valid, last word wrong for the checksum.
        val badChecksum = phrase12.replace("yellow", "wave")

        assertThrows(Mnemonics.ChecksumException::class.java) {
            keyManager.importFromPhraseOrHex(badChecksum)
        }

        assertEquals(current.address, keyManager.load()?.address)
    }

    @Test
    fun `a phrase with a word outside the list is rejected and the current identity survives`() {
        val current = keyManager.loadOrGenerate()

        assertThrows(Mnemonics.InvalidWordException::class.java) {
            keyManager.importFromPhraseOrHex(phrase12.replace("legal", "legally"))
        }

        assertEquals(current.address, keyManager.load()?.address)
    }

    @Test
    fun `a hex import after a phrase identity drops the phrase from the backup`() {
        keyManager.importFromPhraseOrHex(phrase12)

        keyManager.importFromPhraseOrHex("aa".repeat(32) + "bb".repeat(32))

        // A stale phrase here would back up an identity that is no longer the
        // one in use.
        assertEquals(
            KeyManager.BackupExport.LegacyHexKey("aa".repeat(32) + "bb".repeat(32)),
            keyManager.exportBackup()
        )
    }

    @Test
    fun `known issue - a valid 15-word phrase is rejected as a bad hex key`() {
        // BIP39 allows 12, 15, 18, 21 and 24 words; import routes only 12 and
        // 24 to the phrase path (as the web client's importKey does), and the
        // rest fall through to hex with a message about hex characters.
        val phrase15 = (List(14) { "abandon" } + "address").joinToString(" ")

        knownIssue("importFromPhraseOrHex treats 15/18/21-word phrases as hex keys") {
            keyManager.importFromPhraseOrHex(phrase15)
        }
    }

    @Test
    fun `known issue - a phrase with a double space between two words is not restored`() {
        // Word counting splits on any whitespace; the phrase itself is handed
        // to BIP39 with the spacing it was typed with.
        val doubleSpaced = phrase12.replaceFirst(" ", "  ")

        knownIssue("internal whitespace is not normalised before the phrase reaches BIP39") {
            assertEquals(address12, keyManager.importFromPhraseOrHex(doubleSpaced).address)
        }
    }

    // ── reset ─────────────────────────────────────────────────────────

    @Test
    fun `reset replaces the identity and hands back the phrase that restores it`() {
        val before = keyManager.importFromPhraseOrHex(phrase12)

        val fresh = keyManager.resetIdentity()

        assertNotEquals(before.address, fresh.addressData.address)
        assertEquals(fresh.addressData.address, keyManager.load()?.address)
        assertEquals(KeyManager.BackupExport.Phrase(fresh.mnemonic), keyManager.exportBackup())
        assertEquals(fresh.addressData.address, keyManager.keysFromMnemonic(fresh.mnemonic).address)
    }

    // ── signing with the stored identity ──────────────────────────────

    @Test
    fun `a signature is 64 bytes of lower-case hex and repeatable`() {
        val keys = keyManager.importFromPhraseOrHex(phrase12)
        val loaded = keyManager.load()
        assertNotNull(loaded)

        val signature = keyManager.sign(keys, """{"timestamp":1760000000}""")

        assertTrue(signature, Regex("^[0-9a-f]{128}$").matches(signature))
        assertEquals(
            "the identity read back from storage must sign identically",
            signature,
            keyManager.sign(loaded!!, """{"timestamp":1760000000}""")
        )
    }
}
