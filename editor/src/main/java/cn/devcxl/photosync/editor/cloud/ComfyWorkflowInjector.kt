package cn.devcxl.photosync.editor.cloud

import org.json.JSONException
import org.json.JSONObject

/**
 * User-defined ComfyUI workflow and the node ids the app is allowed to touch.
 *
 * Node ids come from the API-format export (`File -> Export (API)` in ComfyUI); they are strings
 * chosen by the workflow author, so nothing here assumes a default id.
 *
 * @property workflowJson Raw API-format workflow JSON. Never a UI-format workflow.
 * @property imageNodeId `LoadImage` node id that receives the sanitized upload name.
 * @property maskNodeId `LoadImage` node id that receives the composed mask; blank disables masks.
 * @property promptNodeId `CLIPTextEncode` node id that receives the user prompt.
 * @property negativePromptNodeId Optional negative prompt node id.
 * @property seedNodeId Optional sampler node id whose `inputs.seed` is set.
 */
data class ComfyUiWorkflowBinding(
    val workflowJson: String,
    val imageNodeId: String,
    val maskNodeId: String = "",
    val promptNodeId: String,
    val negativePromptNodeId: String = "",
    val seedNodeId: String = ""
) {

    /** True when the binding can inject a mask. */
    val supportsMask: Boolean
        get() = maskNodeId.isNotBlank()
}

/**
 * Injects runtime values into a user-supplied ComfyUI API workflow.
 *
 * The server has no template engine: placeholders only exist if the client replaces them, so the
 * app edits `inputs` by node id directly. Every required node must exist or the edit is refused
 * with a clear [IllegalArgumentException] instead of submitting a broken prompt.
 */
internal object ComfyWorkflowInjector {

    /**
     * Returns [binding]'s workflow with the image, mask, prompt and optional fields replaced.
     *
     * @param imageRef The `subfolder/name` annotation returned by `/upload/image`.
     * @param maskRef The `subfolder/name` annotation returned by `/upload/mask`, if any.
     * @throws IllegalArgumentException when the workflow is not JSON, a bound node is missing or
     *   its `inputs` object is malformed.
     */
    fun inject(
        binding: ComfyUiWorkflowBinding,
        imageRef: String,
        maskRef: String?,
        prompt: String,
        negativePrompt: String?,
        seed: Long?
    ): JSONObject {
        val workflow = try {
            JSONObject(binding.workflowJson)
        } catch (e: JSONException) {
            throw IllegalArgumentException("The workflow JSON is invalid")
        }
        setText(workflow, binding.imageNodeId, "image", imageRef)
        if (maskRef != null && binding.supportsMask) {
            setText(workflow, binding.maskNodeId, "image", maskRef)
        }
        setText(workflow, binding.promptNodeId, "text", prompt)
        if (!negativePrompt.isNullOrBlank() && binding.negativePromptNodeId.isNotBlank()) {
            setText(workflow, binding.negativePromptNodeId, "text", negativePrompt)
        }
        if (seed != null && binding.seedNodeId.isNotBlank()) {
            nodeInputs(workflow, binding.seedNodeId).put("seed", seed)
        }
        return workflow
    }

    private fun setText(workflow: JSONObject, nodeId: String, input: String, value: String) {
        if (nodeId.isBlank()) {
            throw IllegalArgumentException("A workflow node id is missing")
        }
        nodeInputs(workflow, nodeId).put(input, value)
    }

    private fun nodeInputs(workflow: JSONObject, nodeId: String): JSONObject {
        val node = workflow.optJSONObject(nodeId)
            ?: throw IllegalArgumentException("Workflow node $nodeId does not exist")
        return node.optJSONObject("inputs")
            ?: throw IllegalArgumentException("Workflow node $nodeId has no inputs object")
    }
}
