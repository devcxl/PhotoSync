package cn.devcxl.photosync.ptp.params

/**
 * @author devcxl
 */
object SyncParams {
    const val SYNC_TRIGGER_MODE_EVENT: Int = 0
    const val SYNC_TRIGGER_MODE_POLL_LIST: Int = 1

    const val SYNC_MODE_SYNC_ALL: Int = 0
    const val SYNC_MODE_SYNC_NEW_ADDED: Int = 1

    const val SYNC_RECORD_MODE_REMEMBER: Int = 0
    const val SYNC_RECORD_MODE_FORGET: Int = 1

    const val FILE_NAME_RULE_HANDLE_ID: Int = 0
    const val FILE_NAME_RULE_OBJECT_NAME: Int = 1
}
