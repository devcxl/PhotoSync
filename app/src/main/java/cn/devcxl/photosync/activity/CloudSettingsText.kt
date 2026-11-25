package cn.devcxl.photosync.activity

import android.content.Context

import cn.devcxl.photosync.R
import cn.devcxl.photosync.editor.cloud.CloudConnectionResult
import cn.devcxl.photosync.editor.cloud.CloudEditError
import cn.devcxl.photosync.editor.cloud.CloudPolicyRejection

/** Localized rendering of cloud provider results and errors. */
internal object CloudSettingsText {

    /** Renders a connectivity probe result. */
    fun describeConnection(context: Context, result: CloudConnectionResult): String =
        when (result) {
            is CloudConnectionResult.Reachable -> context.getString(
                R.string.cloud_status_reachable,
                result.latencyMs
            )
            is CloudConnectionResult.Failed -> context.getString(
                R.string.cloud_status_failed,
                describeError(context, result.error)
            )
        }

    /** Renders one classified provider error without ever including a secret. */
    fun describeError(context: Context, error: CloudEditError): String = when (error) {
        CloudEditError.Auth -> context.getString(R.string.cloud_error_auth)
        is CloudEditError.Invalid -> error.message?.takeIf { it.isNotBlank() }?.let {
            context.getString(R.string.cloud_error_invalid_detail, it)
        } ?: context.getString(R.string.cloud_error_invalid)
        CloudEditError.TooLarge -> context.getString(R.string.cloud_error_too_large)
        is CloudEditError.RateLimited -> error.retryAfterSeconds?.let {
            context.getString(R.string.cloud_error_rate_limited_retry, it)
        } ?: context.getString(R.string.cloud_error_rate_limited)
        is CloudEditError.Server -> context.getString(R.string.cloud_error_server, error.status)
        is CloudEditError.Timeout -> context.getString(R.string.cloud_error_timeout)
        CloudEditError.Cancelled -> context.getString(R.string.cloud_error_cancelled)
        is CloudEditError.EndpointRejected -> rejectionText(context, error.reason)
        is CloudEditError.RedirectRejected -> context.getString(R.string.cloud_error_redirect)
        is CloudEditError.Network -> context.getString(R.string.cloud_error_network)
        is CloudEditError.MalformedResponse -> context.getString(R.string.cloud_error_malformed)
        is CloudEditError.Unknown -> context.getString(R.string.cloud_error_unknown)
    }

    /** Renders a form validation problem. */
    fun describeFormError(context: Context, error: CloudFormError): String = context.getString(
        when (error) {
            CloudFormError.NAME_REQUIRED -> R.string.cloud_validation_name
            CloudFormError.BASE_URL_REQUIRED -> R.string.cloud_validation_base_url
            CloudFormError.MODEL_REQUIRED -> R.string.cloud_validation_model
            CloudFormError.WORKFLOW_REQUIRED -> R.string.cloud_validation_workflow
            CloudFormError.WORKFLOW_INVALID -> R.string.cloud_validation_workflow_invalid
            CloudFormError.WORKFLOW_NODES_REQUIRED -> R.string.cloud_validation_nodes
            CloudFormError.AUTH_HEADER_INCOMPLETE -> R.string.cloud_validation_header
        }
    )

    /** Masks a stored secret for the input hint, keeping at most four edge characters. */
    fun maskSecret(secret: String?): String {
        if (secret.isNullOrEmpty()) return ""
        if (secret.length <= 8) return "••••"
        return secret.take(2) + "••••" + secret.takeLast(2)
    }

    private fun rejectionText(context: Context, reason: CloudPolicyRejection): String =
        context.getString(
            when (reason) {
                CloudPolicyRejection.MALFORMED_URL -> R.string.cloud_error_url_malformed
                CloudPolicyRejection.EMBEDDED_CREDENTIALS -> R.string.cloud_error_url_credentials
                CloudPolicyRejection.UNSUPPORTED_SCHEME -> R.string.cloud_error_url_scheme
                CloudPolicyRejection.CLEARTEXT_HTTP_FORBIDDEN -> R.string.cloud_error_cleartext
                CloudPolicyRejection.LAN_CLEARTEXT_DISABLED -> R.string.cloud_error_lan_disabled
                CloudPolicyRejection.HOST_RESOLUTION_FAILED -> R.string.cloud_error_dns
                CloudPolicyRejection.HOST_RESOLVED_OUTSIDE_PRIVATE_NETWORK ->
                    R.string.cloud_error_dns_private
            }
        )
}
