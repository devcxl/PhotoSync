package cn.devcxl.photosync.editor.cloud

/**
 * The editor's view of the BYOK provider catalogue.
 *
 * [CloudEditService] and [cn.devcxl.photosync.editor.ui.EditorCloudController] depend on this small
 * contract instead of the concrete [CloudProviderConfigStore], so both the HTTP adapters and the
 * Keystore-backed store stay replaceable and the controller is unit-testable without Android
 * preferences or a network stack.
 */
interface CloudProviderSource {

    /** True only while the global P4 switch is on. */
    fun isCloudEnabled(): Boolean

    /** The profile the editor should use, or `null` before the user picks one. */
    fun activeProfile(): CloudProviderProfile?

    /**
     * Creates the provider for the active profile.
     *
     * @return failure when cloud is disabled, no profile is selected, or its credential cannot be
     *   read. The editor treats every failure as "entry unavailable" and never retries by itself.
     */
    fun createProvider(): Result<CloudEditProvider>
}

/** Adapts the persisted BYOK store to the editor-side [CloudProviderSource]. */
class ConfigStoreCloudProviderSource(
    private val store: CloudProviderConfigStore
) : CloudProviderSource {

    override fun isCloudEnabled(): Boolean = store.isCloudEnabled()

    override fun activeProfile(): CloudProviderProfile? = store.activeProfile()

    override fun createProvider(): Result<CloudEditProvider> = store.createProvider()
}
