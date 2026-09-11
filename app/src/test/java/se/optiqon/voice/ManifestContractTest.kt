package se.optiqon.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import se.optiqon.voice.testing.MODULE_DIR
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pins the declarations that give this app its capabilities, and that no other test can see.
 *
 * Everything this app does that is not ordinary UI is granted by the manifest: the bubble draws
 * over other apps, the recorder holds the microphone from a foreground service, the injector runs
 * as an AccessibilityService, and the whole thing comes back after a reboot. None of that is
 * reachable from a JVM unit test, so the rest of this module stays green with the manifest
 * gutted. A single deleted `uses-permission` line is a silent, total loss of a feature, and it
 * only shows up on a device.
 *
 * This is deliberately the cheapest guard in the module: it parses two XML files and asserts on
 * text. No Robolectric, no Android runtime, no device. That is what makes it usable in a commit
 * gate, where anything measured in minutes cannot go.
 *
 * What it proves: the declarations exist and still say what they said when the behaviour was last
 * verified on hardware. What it does not prove: that the platform honours them, that the user
 * granted anything, or that the code behind them works. Those stay device evidence.
 */
class ManifestContractTest {

    @Test
    fun `the permission set is exactly the one the verified behaviour needs`() {
        // An exact set, not a contains-check, on purpose. A removed permission breaks a feature
        // silently; an *added* one is a new prompt, a new review question and possibly a new
        // Play declaration, and it should not be able to arrive without someone editing this
        // list deliberately.
        assertEquals(
            "The manifest's permission set has changed. Removing one silently disables the " +
                "feature that needs it -- no unit test can see it, because permissions do not " +
                "exist on the JVM. Adding one is a user-visible prompt and a review question. " +
                "Update this list only together with the evidence that the new set was tried.",
            listOf(
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_MICROPHONE",
                "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
                "android.permission.INTERNET",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.RECORD_AUDIO",
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.VIBRATE"
            ),
            manifest().descendants("uses-permission").map { it.android("name") }.sorted()
        )
    }

    @Test
    fun `the bubble service keeps the foreground service contract the platform enforces`() {
        val service = manifest().component("service", ".service.BubbleService")

        // specialUse while idle, microphone only while recording. The comment in the manifest
        // records why: a boot-time start of a while-in-use type is rejected by the platform, so
        // the pair of types here is a behaviour decision, not formatting.
        assertEquals(
            "BubbleService's foregroundServiceType changed. It idles as specialUse and promotes " +
                "to microphone only while recording; starting it as a microphone service from " +
                "the boot receiver is a background start of a while-in-use type, and the " +
                "platform rejects it.",
            "specialUse|microphone",
            service.android("foregroundServiceType")
        )
        assertEquals(
            "BubbleService became exported. It is started by this app only; exporting it lets " +
                "any app on the device start the microphone service.",
            "false",
            service.android("exported")
        )
        // Required since API 34: a specialUse service without this property is refused when the
        // app calls startForeground -- on the device, at the moment the user taps record.
        assertTrue(
            "BubbleService declares specialUse but has no PROPERTY_SPECIAL_USE_FGS_SUBTYPE. The " +
                "platform refuses to start a specialUse foreground service without it.",
            service.descendants("property")
                .any { it.android("name") == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" }
        )
        // The declared type and the requested permission are one decision written in two places.
        val requested = manifest().descendants("uses-permission").map { it.android("name") }
        val declared = service.android("foregroundServiceType").split("|").toSet()
        for ((type, permission) in TYPE_PERMISSIONS) {
            if (!declared.contains(type)) continue
            assertTrue(
                "BubbleService declares the $type foreground service type but the manifest does " +
                    "not request $permission. The service throws when it starts.",
                requested.contains(permission)
            )
        }
    }

    @Test
    fun `the accessibility service stays reachable by the platform and keeps its config`() {
        val service = manifest().component("service", ".service.TextInjectorService")

        assertEquals(
            "TextInjectorService is no longer exported. An AccessibilityService is bound by the " +
                "system, from outside this app: unexported, it never appears in the " +
                "accessibility settings list and text injection stops working entirely.",
            "true",
            service.android("exported")
        )
        assertEquals(
            "TextInjectorService no longer requires BIND_ACCESSIBILITY_SERVICE. Without it the " +
                "service is exported to every app on the device rather than to the system alone.",
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            service.android("permission")
        )
        assertTrue(
            "TextInjectorService has no intent-filter for the AccessibilityService action. The " +
                "system discovers accessibility services by that action; without it the service " +
                "is invisible however well it is configured.",
            service.actions().contains("android.accessibilityservice.AccessibilityService")
        )

        val meta = service.descendants("meta-data")
            .singleOrNull { it.android("name") == "android.accessibilityservice" }
        assertTrue(
            "TextInjectorService has no android.accessibilityservice meta-data. The service then " +
                "runs with no configuration: no event types, no window content, no injection.",
            meta != null
        )
        assertEquals("@xml/$ACCESSIBILITY_CONFIG", meta!!.android("resource"))
        // A reference to a file that does not exist is a build failure. A reference moved to a
        // *different* existing file is not, and that is the change this catches: the declaration
        // would then point at a configuration this guard has never read.
        assertTrue(
            "${configFile().path} does not exist, so the accessibility configuration this guard " +
                "checks is not the one the service is given.",
            configFile().isFile
        )
    }

    @Test
    fun `the accessibility configuration keeps the capabilities injection depends on`() {
        val config = parse(configFile()).documentElement

        assertEquals(
            "canRetrieveWindowContent is off. TextInjectorService cannot read the focused node, " +
                "so it has nothing to inject into.",
            "true",
            config.android("canRetrieveWindowContent")
        )
        val flags = config.android("accessibilityFlags").split("|").toSet()
        for (flag in listOf("flagRetrieveInteractiveWindows", "flagInputMethodEditor")) {
            assertTrue(
                "accessibilityFlags no longer contains $flag. Injection targets the field the " +
                    "keyboard is attached to; without this flag that window is not visible to " +
                    "the service. It is: ${config.android("accessibilityFlags")}",
                flags.contains(flag)
            )
        }
        assertTrue(
            "accessibilityEventTypes no longer contains typeViewFocused. The service learns " +
                "where to inject from focus events. It is: " +
                config.android("accessibilityEventTypes"),
            config.android("accessibilityEventTypes").split("|").contains("typeViewFocused")
        )
    }

    @Test
    fun `the boot receiver still hears both of the events that restart the bubble`() {
        val receiver = manifest().component("receiver", ".service.BootReceiver")

        assertEquals(
            "BootReceiver is no longer exported. BOOT_COMPLETED is broadcast by the system, from " +
                "outside this app: unexported, the bubble never comes back after a reboot and " +
                "the user has to open the app to get it.",
            "true",
            receiver.android("exported")
        )
        assertEquals(
            "BootReceiver's intent filter changed. MY_PACKAGE_REPLACED is the one that brings " +
                "the service back after an update -- the Mission C finding rests on the platform " +
                "starting this app through this receiver after `adb install -r`, for every " +
                "Android user on the device.",
            listOf(
                "android.intent.action.BOOT_COMPLETED",
                "android.intent.action.MY_PACKAGE_REPLACED"
            ),
            receiver.actions().sorted()
        )
    }

    // ----------------------------------------------------------------- reading

    private fun manifest(): Element = parse(manifestFile()).documentElement

    private fun manifestFile(): File = File(MODULE_DIR, "src/main/AndroidManifest.xml").also {
        check(it.isFile) { "No AndroidManifest.xml in $MODULE_DIR; this guard has gone stale" }
    }

    private fun configFile(): File =
        File(MODULE_DIR, "src/main/res/xml/$ACCESSIBILITY_CONFIG.xml")

    /**
     * Namespace-aware on purpose. `android:name` and a bare `name` are different attributes to
     * the platform, and a guard reading the local name alone would accept a declaration the
     * system ignores.
     */
    private fun parse(file: File) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(file)

    private fun Element.android(name: String): String = getAttributeNS(ANDROID_NS, name)

    /** Every element with this tag anywhere below this one, not only direct children. */
    private fun Element.descendants(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.component(tag: String, name: String): Element {
        val matches = descendants(tag).filter { it.android("name") == name }
        assertEquals(
            "Expected exactly one <$tag> named $name in the manifest. A renamed or deleted " +
                "component takes its whole capability with it, and nothing else in this module " +
                "would notice.",
            1,
            matches.size
        )
        return matches.single()
    }

    private fun Element.actions(): List<String> =
        descendants("intent-filter").flatMap { filter ->
            filter.descendants("action").map { it.android("name") }
        }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val ACCESSIBILITY_CONFIG = "accessibility_service_config"

        /** Each foreground service type and the permission the platform requires with it. */
        val TYPE_PERMISSIONS = mapOf(
            "microphone" to "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "specialUse" to "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"
        )
    }
}
