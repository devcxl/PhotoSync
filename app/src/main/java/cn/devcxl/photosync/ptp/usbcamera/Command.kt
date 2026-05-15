package cn.devcxl.photosync.ptp.usbcamera

/**
 * @author devcxl
 */
open class Command(code: Int, s: Session) : ParamVector(
    ByteArray(Container.HDR_LEN + (4 * 0)), s.factory!!
) {
    init {
        data = ByteArray(Container.HDR_LEN)
        length = data.size
        offset = 0
        putHeader(data.size, 1 /*OperationCode*/, code, s.nextXID)
    }

    constructor(code: Int, s: Session, param1: Int) : this(code, s) {
        data = ByteArray(Container.HDR_LEN + (4 * 1))
        length = data.size
        offset = 0
        putHeader(data.size, 1, code, s.nextXID)
        put32(param1)
    }

    constructor(code: Int, s: Session, param1: Int, param2: Int) : this(code, s) {
        data = ByteArray(Container.HDR_LEN + (4 * 2))
        length = data.size
        offset = 0
        putHeader(data.size, 1, code, s.nextXID)
        put32(param1)
        put32(param2)
    }

    internal constructor(code: Int, s: Session, param1: Int, param2: Int, param3: Int) : this(code, s) {
        data = ByteArray(Container.HDR_LEN + (4 * 3))
        length = data.size
        offset = 0
        putHeader(data.size, 1, code, s.nextXID)
        put32(param1)
        put32(param2)
        put32(param3)
    }

    override fun getCodeName(code: Int): String = factory.getOpcodeString(code)

    companion object {
        @JvmField
        val GetDeviceInfo: Int = 0x1001
        @JvmField
        val OpenSession: Int = 0x1002
        @JvmField
        val CloseSession: Int = 0x1003
        @JvmField
        val GetStorageIDs: Int = 0x1004
        @JvmField
        val GetStorageInfo: Int = 0x1005
        @JvmField
        val GetNumObjects: Int = 0x1006
        @JvmField
        val GetObjectHandles: Int = 0x1007
        @JvmField
        val GetObjectInfo: Int = 0x1008
        @JvmField
        val GetObject: Int = 0x1009
        @JvmField
        val GetThumb: Int = 0x100a
        @JvmField
        val DeleteObject: Int = 0x100b
        @JvmField
        val SendObjectInfo: Int = 0x100c
        @JvmField
        val SendObject: Int = 0x100d
        @JvmField
        val InitiateCapture: Int = 0x100e
        @JvmField
        val FormatStore: Int = 0x100f
        @JvmField
        val ResetDevice: Int = 0x1010
        @JvmField
        val SelfTest: Int = 0x1011
        @JvmField
        val SetObjectProtection: Int = 0x1012
        @JvmField
        val PowerDown: Int = 0x1013
        @JvmField
        val GetDevicePropDesc: Int = 0x1014
        @JvmField
        val GetDevicePropValue: Int = 0x1015
        @JvmField
        val SetDevicePropValue: Int = 0x1016
        @JvmField
        val ResetDevicePropValue: Int = 0x1017
        @JvmField
        val TerminateOpenCapture: Int = 0x1018
        @JvmField
        val MoveObject: Int = 0x1019
        @JvmField
        val CopyObject: Int = 0x101a
        @JvmField
        val GetPartialObject: Int = 0x101b
        @JvmField
        val InitiateOpenCapture: Int = 0x101c
        @JvmField
        val EosGetStorageIds: Int = 0x9101
        @JvmField
        val EosGetStorageInfo: Int = 0x9102
        @JvmField
        val EosGetObjectInfo: Int = 0x9103
        @JvmField
        val EosGetObject: Int = 0x9104
        @JvmField
        val EosDeleteObject: Int = 0x9105
        @JvmField
        val EosFormatStore: Int = 0x9106
        @JvmField
        val EosGetPartialObject: Int = 0x9107
        @JvmField
        val EosGetDeviceInfoEx: Int = 0x9108
        @JvmField
        val EosGetObjectInfoEx: Int = 0x9109
        @JvmField
        val EosGetThumbEx: Int = 0x910a
        @JvmField
        val EosSendPartialObject: Int = 0x910b
        @JvmField
        val EosSetObjectProperties: Int = 0x910c
        @JvmField
        val EosGetObjectTime: Int = 0x910d
        @JvmField
        val EosSetObjectTime: Int = 0x910e
        @JvmField
        val EosRemoteRelease: Int = 0x910f
        @JvmField
        val EosSetDevicePropValueEx: Int = 0x9110
        @JvmField
        val EosSendObjectEx: Int = 0x9111
        @JvmField
        val EosCreageObject: Int = 0x9112
        @JvmField
        val EosGetRemoteMode: Int = 0x9113
        @JvmField
        val EOS_OC_SetPCConnectMode: Int = 0x9114
        @JvmField
        val EosSetRemoteMode: Int = 0x9114
        @JvmField
        val EosSetEventMode: Int = 0x9115
        @JvmField
        val EosGetEvent: Int = 0x9116
        @JvmField
        val EosTransferComplete: Int = 0x9117
        @JvmField
        val EosCancelTransfer: Int = 0x9118
        @JvmField
        val EosResetTransfer: Int = 0x9119
        @JvmField
        val EosPCHDDCapacity: Int = 0x911a
        @JvmField
        val EosSetUILock: Int = 0x911b
        @JvmField
        val EosResetUILock: Int = 0x911c
        @JvmField
        val EosKeepDeviceOn: Int = 0x911d
        @JvmField
        val EosSetNullPacketmode: Int = 0x911e
        @JvmField
        val EosUpdateFirmware: Int = 0x911f
        @JvmField
        val EosUpdateTransferCompleteDt: Int = 0x9120
        @JvmField
        val EosCancelTransferDt: Int = 0x9121
        @JvmField
        val EosSetFWTProfile: Int = 0x9122
        @JvmField
        val EosGetFWTProfile: Int = 0x9123
        @JvmField
        val EosSetProfileToWTF: Int = 0x9124
        @JvmField
        val EosBulbStart: Int = 0x9125
        @JvmField
        val EosBulbEnd: Int = 0x9126
        @JvmField
        val EosRequestDevicePropValue: Int = 0x9127
        @JvmField
        val EosRemoeReleaseOn: Int = 0x9128
        @JvmField
        val EosRemoeReleaseOff: Int = 0x9129
        @JvmField
        val EosRegistBackgroundImage: Int = 0x912a
        @JvmField
        val EosChangePhotoStadIOMode: Int = 0x912b
        @JvmField
        val EosGetPartialObjectEx: Int = 0x912c
        @JvmField
        val EosResetMirrorLockupState: Int = 0x9130
        @JvmField
        val EosPopupBuiltinFlash: Int = 0x9131
        @JvmField
        val EosEndGetPartialObjectEx: Int = 0x9132
        @JvmField
        val EosMovieSelectSWOn: Int = 0x9133
        @JvmField
        val EosMovieSelectSWOff: Int = 0x9134
        @JvmField
        val EosInitiateViewFinder: Int = 0x9151
        @JvmField
        val EosTerminateViewFinder: Int = 0x9152
        @JvmField
        val EosGetViewFinderData: Int = 0x9153
        @JvmField
        val EOS_OC_GetLiveViewPicture: Int = 0x9153
        @JvmField
        val EosDoAF: Int = 0x9154
        @JvmField
        val EosDriveLens: Int = 0x9155
        @JvmField
        val EosDepthOfFieldPreview: Int = 0x9156
        @JvmField
        val EosClickWB: Int = 0x9157
        @JvmField
        val EosZoom: Int = 0x9158
        @JvmField
        val EosZoomPosition: Int = 0x9159
        @JvmField
        val EosSetLiveAFFrame: Int = 0x915a
        @JvmField
        val EosAFCancel: Int = 0x9160
        @JvmField
        val EosFapiMessageTx: Int = 0x91fe
        @JvmField
        val EosFapiMessageRx: Int = 0x91ff
        @JvmField
        val MtpGetObjectPropsSupported: Int = 0x9801
        @JvmField
        val MtpGetObjectPropDesc: Int = 0x9802
        @JvmField
        val MtpGetObjectPropValue: Int = 0x9803
        @JvmField
        val MtpSetObjectPropValue: Int = 0x9804
        @JvmField
        val MtpGetObjPropList: Int = 0x9805
        @JvmField
        val SONY_SDIOCOMMAND: Int = 0x9201
        @JvmField
        val EOS_DPC_CameraDescription: Int = 0xD402
        @JvmField
        val EOS_DPC_Aperture: Int = 0xD101
        @JvmField
        val EOS_DPC_ShutterSpeed: Int = 0xD102
        @JvmField
        val EOS_DPC_Iso: Int = 0xD103
        @JvmField
        val EOS_DPC_ExposureCompensation: Int = 0xD104
        @JvmField
        val EOS_DPC_ShootingMode: Int = 0xD105
        @JvmField
        val EOS_DPC_DriveMode: Int = 0xD106
        @JvmField
        val EOS_DPC_ExpMeterringMode: Int = 0xD107
        @JvmField
        val EOS_DPC_AFMode: Int = 0xD108
        @JvmField
        val EOS_DPC_WhiteBalance: Int = 0xD109
        @JvmField
        val EOS_DPC_PictureStyle: Int = 0xD110
        @JvmField
        val EOS_DPC_TransferOption: Int = 0xD111
        @JvmField
        val EOS_DPC_UnixTime: Int = 0xD113
        @JvmField
        val EOS_DPC_ImageQuality: Int = 0xD120
        @JvmField
        val EOS_DPC_LiveView: Int = 0xD1B0
        @JvmField
        val EOS_DPC_AvailableShots: Int = 0xD11B
        @JvmField
        val EOS_DPC_CaptureDestination: Int = 0xD11C
        @JvmField
        val EOS_DPC_BracketMode: Int = 0xD11D
        @JvmField
        val PTP_OC_CANON_EOS_SetNullPacketMode: Int = 0x911E
        @JvmField
        val NK_OC_GetProfileAllData: Int = 0x9006
        @JvmField
        val NK_OC_SendProfileData: Int = 0x9007
        @JvmField
        val NK_OC_DeleteProfile: Int = 0x9008
        @JvmField
        val NK_OC_SetProfileData: Int = 0x9009
        @JvmField
        val NK_OC_AdvancedTransfer: Int = 0x9010
        @JvmField
        val NK_OC_GetFileInfoInBlock: Int = 0x9011
        @JvmField
        val NK_OC_Capture: Int = 0x90C0
        @JvmField
        val NK_OC_SetControlMode: Int = 0x90C2
        @JvmField
        val NK_OC_CurveDownload: Int = 0x90C5
        @JvmField
        val NK_OC_CurveUpload: Int = 0x90C6
        @JvmField
        val NK_OC_CheckEvent: Int = 0x90C7
        @JvmField
        val NK_OC_DeviceReady: Int = 0x90C8
        @JvmField
        val NK_OC_CaptureInSDRAM: Int = 0x90CB
        @JvmField
        val NK_OC_GetDevicePTPIPInfo: Int = 0x90E0
        @JvmField
        val PTP_OC_NIKON_GetPreviewImg: Int = 0x9200
        @JvmField
        val PTP_OC_NIKON_StartLiveView: Int = 0x9201
        @JvmField
        val PTP_OC_NIKON_EndLiveView: Int = 0x9202
        @JvmField
        val PTP_OC_NIKON_GetLiveViewImg: Int = 0x9203
        @JvmField
        val PTP_OC_NIKON_MfDrive: Int = 0x9204
        @JvmField
        val PTP_OC_NIKON_ChangeAfArea: Int = 0x9205
        @JvmField
        val PTP_OC_NIKON_AfDriveCancel: Int = 0x9206

        @JvmStatic
        fun _getOpcodeString(code: Int): String = when (code) {
            GetDeviceInfo -> "GetDeviceInfo"
            OpenSession -> "OpenSession"
            CloseSession -> "CloseSession"
            GetStorageIDs -> "GetStorageIDs"
            GetStorageInfo -> "GetStorageInfo"
            GetNumObjects -> "GetNumObjects"
            GetObjectHandles -> "GetObjectHandles"
            GetObjectInfo -> "GetObjectInfo"
            GetObject -> "GetObject"
            GetThumb -> "GetThumb"
            DeleteObject -> "DeleteObject"
            SendObjectInfo -> "SendObjectInfo"
            SendObject -> "SendObject"
            InitiateCapture -> "InitiateCapture"
            FormatStore -> "FormatStore"
            ResetDevice -> "ResetDevice"
            SelfTest -> "SelfTest"
            SetObjectProtection -> "SetObjectProtection"
            PowerDown -> "PowerDown"
            GetDevicePropDesc -> "GetDevicePropDesc"
            GetDevicePropValue -> "GetDevicePropValue"
            SetDevicePropValue -> "SetDevicePropValue"
            ResetDevicePropValue -> "ResetDevicePropValue"
            TerminateOpenCapture -> "TerminateOpenCapture"
            MoveObject -> "MoveObject"
            CopyObject -> "CopyObject"
            GetPartialObject -> "GetPartialObject"
            InitiateOpenCapture -> "InitiateOpenCapture"
            EosSetDevicePropValueEx -> "EosSetDevicePropValueEx"
            EosCancelTransfer -> "EosCancelTransfer"
            EosCreageObject -> "EosCreageObject"
            EosDeleteObject -> "EosDeleteObject"
            EosFapiMessageRx -> "EosFapiMessageRx"
            EosFapiMessageTx -> "EosFapiMessageTx"
            EosFormatStore -> "EosFormatStore"
            EosGetDeviceInfoEx -> "EosGetDeviceInfoEx"
            EosGetEvent -> "EosGetEvent"
            EosGetObject -> "EosGetObject"
            EosGetObjectInfo -> "EosGetObjectInfo"
            EosGetObjectInfoEx -> "EosGetObjectInfoEx"
            EosGetObjectTime -> "EosGetObjectTime"
            EosGetPartialObject -> "EosGetPartialObject"
            EosGetRemoteMode -> "EosGetRemoteMode"
            EosGetStorageIds -> "EosGetStorageIds"
            EosGetStorageInfo -> "EosGetStorageInfo"
            EosGetThumbEx -> "EosGetThumbEx"
            EosKeepDeviceOn -> "EosKeepDeviceOn"
            EosPCHDDCapacity -> "EosPCHDDCapacity"
            EosRemoteRelease -> "EosRemoteRelease"
            EosResetUILock -> "EosResetUILock"
            EosResetTransfer -> "EosResetTransfer"
            EosSendObjectEx -> "EosSendObjectEx"
            EosSendPartialObject -> "EosSendPartialObject"
            EosSetNullPacketmode -> "EosSetNullPacketmode"
            EosSetObjectProperties -> "EosSetObjectProperties"
            EosSetObjectTime -> "EosSetObjectTime"
            EosSetRemoteMode -> "EosSetRemoteMode"
            EosSetUILock -> "EosSetUILock"
            EosTransferComplete -> "EosGetTransferComplete"
            EosUpdateFirmware -> "EosUpdateFirmware"
            EosRequestDevicePropValue -> "EosRequestDevicePropValue"
            EosUpdateTransferCompleteDt -> "EosUpdateTransferCompleteDt"
            EosCancelTransferDt -> "EosCancelTransferDt"
            EosInitiateViewFinder -> "EosInitiateViewFinder"
            EosTerminateViewFinder -> "EosTerminateViewFinder"
            EosGetViewFinderData -> "EosgetViewFinderData"
            EosDriveLens -> "EosDriveLens"
            EosDepthOfFieldPreview -> "EosDepthOfFieldPreview"
            EosClickWB -> "EosClickWB"
            EosSetLiveAFFrame -> "EosSetLiveAFFrame"
            EosZoom -> "EosZoom"
            EosZoomPosition -> "EosZoomPostion"
            EosGetFWTProfile -> "EosGetFWTProfile"
            EosSetFWTProfile -> "EosSetFWTProfile"
            EosSetProfileToWTF -> "EosSetProfileToWTF"
            EosBulbStart -> "EosBulbStart"
            EosBulbEnd -> "EosBulbEnd"
            EosRemoeReleaseOn -> "EosRemoeReleaseOn"
            EosRemoeReleaseOff -> "EosRemoeReleaseOff"
            EosDoAF -> "EosDoAF"
            EosAFCancel -> "EosAFCancel"
            EosRegistBackgroundImage -> "EosRegistBackgroundImage"
            EosChangePhotoStadIOMode -> "EosChangePhotoStadIOMode"
            EosGetPartialObjectEx -> "EosGetPartialObjectEx"
            EosResetMirrorLockupState -> "EosResetMirrorLockupState"
            EosPopupBuiltinFlash -> "EosPopupBuiltinFlash"
            EosEndGetPartialObjectEx -> "EosEndGetPartialObjectEx"
            EosMovieSelectSWOn -> "EosMovieSelectSWOn"
            EosMovieSelectSWOff -> "EosMovieSelectSWOff"
            EosSetEventMode -> "EosSetEventMode"
            MtpGetObjectPropsSupported -> "MtpGetObjectPropsSupported"
            MtpGetObjectPropDesc -> "MtpGetObjectPropDesc"
            MtpGetObjectPropValue -> "MtpGetObjectPropValue"
            MtpSetObjectPropValue -> "MtpSetObjectPropValue"
            MtpGetObjPropList -> "GetObjPropList"
            else -> Container.getCodeString(code)
        }
    }
}
