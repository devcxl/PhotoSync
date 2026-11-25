package cn.devcxl.photosync.editor.cloud

/** Builds a [CloudEditProvider] from a stored profile and its decrypted credential. */
object CloudEditProviderFactory {

    /**
     * Creates the adapter for [profile].
     *
     * @return failure with [IllegalArgumentException] when the profile is missing a required field.
     */
    fun create(
        profile: CloudProviderProfile,
        credential: ByokCredential
    ): Result<CloudEditProvider> {
        val policy = CloudNetworkPolicy(allowLanCleartextHttp = profile.allowLanCleartextHttp)
        return when (profile.kind) {
            CloudProviderKind.OPENAI_COMPATIBLE -> openAi(profile, credential, policy)
            CloudProviderKind.GEMINI -> gemini(profile, credential, policy)
            CloudProviderKind.COMFY_UI -> comfyUi(profile, credential, policy)
        }
    }

    private fun openAi(
        profile: CloudProviderProfile,
        credential: ByokCredential,
        policy: CloudNetworkPolicy
    ): Result<CloudEditProvider> {
        if (profile.model.isBlank()) {
            return Result.failure(IllegalArgumentException("A model name is required"))
        }
        return Result.success(
            OpenAiEditProvider(profile.baseUrl, profile.model, credential, policy)
        )
    }

    private fun gemini(
        profile: CloudProviderProfile,
        credential: ByokCredential,
        policy: CloudNetworkPolicy
    ): Result<CloudEditProvider> {
        if (profile.model.isBlank()) {
            return Result.failure(IllegalArgumentException("A model name is required"))
        }
        return Result.success(
            GeminiEditProvider(
                baseUrl = profile.baseUrl,
                configuredModel = profile.model,
                credential = credential,
                networkPolicy = policy,
                background = profile.useBackgroundPolling
            )
        )
    }

    private fun comfyUi(
        profile: CloudProviderProfile,
        credential: ByokCredential,
        policy: CloudNetworkPolicy
    ): Result<CloudEditProvider> {
        val binding = profile.comfyBinding()
        if (binding.workflowJson.isBlank()) {
            return Result.failure(IllegalArgumentException("A workflow JSON is required"))
        }
        if (binding.imageNodeId.isBlank() || binding.promptNodeId.isBlank()) {
            return Result.failure(
                IllegalArgumentException("The image and prompt node ids are required")
            )
        }
        return Result.success(
            ComfyUiEditProvider(profile.baseUrl, binding, credential, policy)
        )
    }
}
