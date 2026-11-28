package cn.devcxl.photosync.editor.cloud

import android.util.Base64

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Parses the raw REST shape of a Gemini Interaction.
 *
 * The REST response has no top-level `output_image` field; the image lives inside
 * `steps[].content[]` blocks of `type == "image"`. Parsing stays in one place so the polling and
 * synchronous paths share exactly the same contract.
 */
internal object GeminiInteractionParser {

    /** Status of a finished interaction. */
    const val STATUS_COMPLETED = "completed"

    private const val STATUS_FAILED = "failed"
    private const val STATUS_CANCELLED = "cancelled"
    private const val STATUS_INCOMPLETE = "incomplete"

    /** Returns the interaction status; a missing status is treated as completed. */
    fun status(interaction: JSONObject): String =
        interaction.optString("status").ifBlank { STATUS_COMPLETED }

    /** True for statuses that can never produce an image. */
    fun isTerminalFailure(status: String): Boolean =
        status == STATUS_FAILED || status == STATUS_CANCELLED || status == STATUS_INCOMPLETE

    /** A single-line failure reason taken from the interaction's `errors` array. */
    fun failureSummary(status: String, interaction: JSONObject): String {
        val detail = try {
            interaction.optJSONArray("errors")
                ?.optJSONObject(0)
                ?.optString("message")
                .orEmpty()
        } catch (e: JSONException) {
            ""
        }
        val normalized = detail.replace(Regex("\\s+"), " ").trim()
        return normalized.takeIf { it.isNotEmpty() } ?: "The interaction ended with status $status"
    }

    /** Extracts and decodes the first image block of [interaction]. */
    fun extractImage(interaction: JSONObject): CloudEditResult {
        val steps = interaction.optJSONArray("steps")
            ?: return CloudEditResult.Failure(
                CloudEditError.MalformedResponse("The interaction contained no steps")
            )
        for (stepIndex in 0 until steps.length()) {
            val content = steps.optJSONObject(stepIndex)?.optJSONArray("content") ?: continue
            val image = findImageContent(content)
            if (image != null) {
                return decodeImage(image)
            }
        }
        return CloudEditResult.Failure(
            CloudEditError.MalformedResponse("The interaction contained no image")
        )
    }

    private fun findImageContent(content: JSONArray): JSONObject? {
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            if (block.optString("type") == "image" && block.optString("data").isNotBlank()) {
                return block
            }
        }
        return null
    }

    private fun decodeImage(block: JSONObject): CloudEditResult {
        val bytes = try {
            Base64.decode(block.optString("data"), Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return CloudEditResult.Failure(
                CloudEditError.MalformedResponse("The base64 image was malformed")
            )
        }
        if (!looksLikeImage(bytes)) {
            return CloudEditResult.Failure(
                CloudEditError.MalformedResponse("The provider returned a non-image payload")
            )
        }
        val mime = block.optString("mime_type").takeIf { it.startsWith("image/") } ?: "image/png"
        return CloudEditResult.Success(bytes, mime)
    }
}
