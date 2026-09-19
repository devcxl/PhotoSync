/* Copyright 2010 by Stefano Fornari
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License; or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful;
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not; write to the Free Software
 * Foundation; Inc.; 59 Temple Place; Suite 330; Boston; MA  02111-1307  USA
 */

package cn.devcxl.photosync.ptp.usbcamera.nikon

/**
 * Nikon PTP vendor extension codes: operation codes, response codes, event codes,
 * device property codes and property value tables.
 *
 * Event wire format and parameter semantics follow Nikon's PTP extension as
 * implemented by libgphoto2 (`ptp_unpack_Nikon_EC`).
 *
 * @author devcxl
 */
object NikonEventConstants {

    // nikon_effect_modes
    const val NK_Night_Vision = 0x00
    const val NK_Color_sketch = 0x01
    const val NK_Miniature_effect = 0x02
    const val NK_Selective_color = 0x03
    const val NK_Silhouette = 0x04
    const val NK_High_key = 0x05
    const val NK_Low_key = 0x06

    // flash_mode
    const val NK_Automatic_Flash = 0x0001
    const val NK_Flash_off = 0x0002
    const val NK_Fill_flash = 0x0003
    const val NK_Red_eye_automatic = 0x0004
    const val NK_Red_eye_fill = 0x0005
    const val NK_External_sync = 0x0006

    // nikon_evstep
    const val NK_1_d3 = 0
    const val NK_1_d2 = 1

    // focus_metering
    const val NK_Centre_spot = 0x0001
    const val NK_Multi_spot = 0x0002
    const val NK_Single_Area = 0x8010
    const val NK_Closest_Subject = 0x8011
    const val NK_Group_Dynamic = 0x8012

    // Nikon capture_mode
    const val NK_Single_Shot = 0x0001
    const val NK_Burst = 0x0002
    const val NK_Timelapse = 0x0003
    const val NK_Continuous_Low_Speed = 0x8010
    const val NK_Timer = 0x8011
    const val NK_Mirror_Up = 0x8012
    const val NK_Remote = 0x8013
    const val NK_Quick_Response_Remote = 0x8014
    const val NK_Delayed_Remote = 0x8015
    const val NK_Quiet_Release = 0x8016

    // nikon_scenemode
    const val NK_Night_landscape = 0
    const val NK_Party_Indoor = 1
    const val NK_Beach_Snow = 2
    const val NK_Sunset = 3
    const val NK_Dusk_Dawn = 4
    const val NK_Pet_Portrait = 5
    const val NK_Candlelight = 6
    const val NK_Blossom = 7
    const val NK_Autumn_colors = 8
    const val NK_Food = 9
    const val NK_Night_Portrait = 18

    // Nikon Extension Operation Codes
    const val NK_OC_GetProfileAllData = 0x9006
    const val NK_OC_SendProfileData = 0x9007
    const val NK_OC_DeleteProfile = 0x9008
    const val NK_OC_SetProfileData = 0x9009
    const val NK_OC_AdvancedTransfer = 0x9010
    const val NK_OC_GetFileInfoInBlock = 0x9011
    const val NK_OC_Capture = 0x90C0
    const val NK_OC_SetControlMode = 0x90C2
    const val NK_OC_CurveDownload = 0x90C5
    const val NK_OC_CurveUpload = 0x90C6
    const val NK_OC_CheckEvent = 0x90C7
    const val NK_OC_DeviceReady = 0x90C8
    const val NK_OC_CaptureInSDRAM = 0x90CB
    const val NK_OC_GetDevicePTPIPInfo = 0x90E0

    const val PTP_OC_NIKON_GetPreviewImg = 0x9200
    const val PTP_OC_NIKON_StartLiveView = 0x9201
    const val PTP_OC_NIKON_EndLiveView = 0x9202
    const val PTP_OC_NIKON_GetLiveViewImg = 0x9203
    const val PTP_OC_NIKON_MfDrive = 0x9204
    const val PTP_OC_NIKON_ChangeAfArea = 0x9205
    const val PTP_OC_NIKON_AfDriveCancel = 0x9206

    // Nikon extention response codes
    const val NK_RC_HardwareError = 0xA001
    const val NK_RC_OutOfFocus = 0xA002
    const val NK_RC_ChangeCameraModeFailed = 0xA003
    const val NK_RC_InvalidStatus = 0xA004
    const val NK_RC_SetPropertyNotSupported = 0xA005
    const val NK_RC_WbResetError = 0xA006
    const val NK_RC_DustReferenceError = 0xA007
    const val NK_RC_ShutterSpeedBulb = 0xA008
    const val NK_RC_MirrorUpSequence = 0xA009
    const val NK_RC_CameraModeNotAdjustFNumber = 0xA00A
    const val NK_RC_NotLiveView = 0xA00B
    const val NK_RC_MfDriveStepEnd = 0xA00C
    const val NK_RC_MfDriveStepInsufficiency = 0xA00E
    const val NK_RC_AdvancedTransferCancel = 0xA022

    // Nikon extension Event Codes
    const val NK_EC_ObjectAddedInSDRAM = 0xC101
    const val NK_EC_CaptureOverflow = 0xC102
    const val NK_EC_AdvancedTransfer = 0xC103

    /* Nikon extension device property codes */
    const val NK_DPC_ShootingBank = 0xD010
    const val NK_DPC_ShootingBankNameA = 0xD011
    const val NK_DPC_ShootingBankNameB = 0xD012
    const val NK_DPC_ShootingBankNameC = 0xD013
    const val NK_DPC_ShootingBankNameD = 0xD014
    const val NK_DPC_RawCompression = 0xD016
    const val NK_DPC_WhiteBalanceAutoBias = 0xD017
    const val NK_DPC_WhiteBalanceTungstenBias = 0xD018
    const val NK_DPC_WhiteBalanceFluorescentBias = 0xD019
    const val NK_DPC_WhiteBalanceDaylightBias = 0xD01A
    const val NK_DPC_WhiteBalanceFlashBias = 0xD01B
    const val NK_DPC_WhiteBalanceCloudyBias = 0xD01C
    const val NK_DPC_WhiteBalanceShadeBias = 0xD01D
    const val NK_DPC_WhiteBalanceColorTemperature = 0xD01E
    const val NK_DPC_ImageSharpening = 0xD02A
    const val NK_DPC_ToneCompensation = 0xD02B
    const val NK_DPC_ColorModel = 0xD02C
    const val NK_DPC_HueAdjustment = 0xD02D
    const val NK_DPC_NonCPULensDataFocalLength = 0xD02E
    const val NK_DPC_NonCPULensDataMaximumAperture = 0xD02F
    const val NK_DPC_CSMMenuBankSelect = 0xD040
    const val NK_DPC_MenuBankNameA = 0xD041
    const val NK_DPC_MenuBankNameB = 0xD042
    const val NK_DPC_MenuBankNameC = 0xD043
    const val NK_DPC_MenuBankNameD = 0xD044
    const val NK_DPC_A1AFCModePriority = 0xD048
    const val NK_DPC_A2AFSModePriority = 0xD049
    const val NK_DPC_A3GroupDynamicAF = 0xD04A
    const val NK_DPC_A4AFActivation = 0xD04B
    const val NK_DPC_A5FocusAreaIllumManualFocus = 0xD04C
    const val NK_DPC_FocusAreaIllumContinuous = 0xD04D
    const val NK_DPC_FocusAreaIllumWhenSelected = 0xD04E
    const val NK_DPC_FocusAreaWrap = 0xD04F
    const val NK_DPC_A7VerticalAFON = 0xD050
    const val NK_DPC_ISOAuto = 0xD054
    const val NK_DPC_B2ISOStep = 0xD055
    const val NK_DPC_EVStep = 0xD056
    const val NK_DPC_B4ExposureCompEv = 0xD057
    const val NK_DPC_ExposureCompensation = 0xD058
    const val NK_DPC_CenterWeightArea = 0xD059
    const val NK_DPC_AELockMode = 0xD05E
    const val NK_DPC_AELAFLMode = 0xD05F
    const val NK_DPC_MeterOff = 0xD062
    const val NK_DPC_SelfTimer = 0xD063
    const val NK_DPC_MonitorOff = 0xD064
    const val NK_DPC_D1ShootingSpeed = 0xD068
    const val NK_DPC_D2MaximumShots = 0xD069
    const val NK_DPC_D3ExpDelayMode = 0xD06A
    const val NK_DPC_LongExposureNoiseReduction = 0xD06B
    const val NK_DPC_FileNumberSequence = 0xD06C
    const val NK_DPC_D6ControlPanelFinderRearControl = 0xD06D
    const val NK_DPC_ControlPanelFinderViewfinder = 0xD06E
    const val NK_DPC_D7Illumination = 0xD06F
    const val NK_DPC_E1FlashSyncSpeed = 0xD074
    const val NK_DPC_FlashShutterSpeed = 0xD075
    const val NK_DPC_E3AAFlashMode = 0xD076
    const val NK_DPC_E4ModelingFlash = 0xD077
    const val NK_DPC_BracketSet = 0xD078
    const val NK_DPC_E6ManualModeBracketing = 0xD079
    const val NK_DPC_BracketOrder = 0xD07A
    const val NK_DPC_E8AutoBracketSelection = 0xD07B
    const val NK_DPC_BracketingSet = 0xD07C
    const val NK_DPC_F1CenterButtonShootingMode = 0xD080
    const val NK_DPC_CenterButtonPlaybackMode = 0xD081
    const val NK_DPC_F2Multiselector = 0xD082
    const val NK_DPC_F3PhotoInfoPlayback = 0xD083
    const val NK_DPC_F4AssignFuncButton = 0xD084
    const val NK_DPC_F5CustomizeCommDials = 0xD085
    const val NK_DPC_ReverseCommandDial = 0xD086
    const val NK_DPC_ApertureSetting = 0xD087
    const val NK_DPC_MenusAndPlayback = 0xD088
    const val NK_DPC_F6ButtonsAndDials = 0xD089
    const val NK_DPC_NoCFCard = 0xD08A
    const val NK_DPC_ImageCommentString = 0xD090
    const val NK_DPC_ImageCommentAttach = 0xD091
    const val NK_DPC_ImageRotation = 0xD092
    const val NK_DPC_Bracketing = 0xD0C0
    const val NK_DPC_ExposureBracketingIntervalDist = 0xD0C1
    const val NK_DPC_BracketingProgram = 0xD0C2
    const val NK_DPC_WhiteBalanceBracketStep = 0xD0C4
    const val NK_DPC_LensID = 0xD0E0
    const val NK_DPC_FocalLengthMin = 0xD0E3
    const val NK_DPC_FocalLengthMax = 0xD0E4
    const val NK_DPC_MaxApAtMinFocalLength = 0xD0E5
    const val NK_DPC_MaxApAtMaxFocalLength = 0xD0E6
    const val NK_DPC_ExposureTime = 0xD100
    const val NK_DPC_ACPower = 0xD101
    const val NK_DPC_MaximumShots = 0xD103
    const val NK_DPC_AFLLock = 0xD104
    const val NK_DPC_AutoExposureLock = 0xD105
    const val NK_DPC_AutoFocusLock = 0xD106
    const val NK_DPC_AutofocusLCDTopMode2 = 0xD107
    const val NK_DPC_AutofocusArea = 0xD108
    const val NK_DPC_LightMeter = 0xD10A
    const val NK_DPC_CameraOrientation = 0xD10E
    const val NK_DPC_ExposureApertureLock = 0xD111
    const val NK_DPC_FlashExposureCompensation = 0xD126
    const val NK_DPC_OptimizeImage = 0xD140
    const val NK_DPC_Saturation = 0xD142
    const val NK_DPC_BeepOff = 0xD160
    const val NK_DPC_AutofocusMode = 0xD161
    const val NK_DPC_AFAssist = 0xD163
    const val NK_DPC_PADVPMode = 0xD164
    const val NK_DPC_ImageReview = 0xD165
    const val NK_DPC_AFAreaIllumination = 0xD166
    const val NK_DPC_FlashMode = 0xD167
    const val NK_DPC_FlashCommanderMode = 0xD168
    const val NK_DPC_FlashSign = 0xD169
    const val NK_DPC_RemoteTimeout = 0xD16B
    const val NK_DPC_GridDisplay = 0xD16C
    const val NK_DPC_FlashModeManualPower = 0xD16D
    const val NK_DPC_FlashModeCommanderPower = 0xD16E
    const val NK_DPC_CSMMenu = 0xD180
    const val NK_DPC_BracketingFramesAndSteps = 0xD190
    const val NK_DPC_LowLight = 0xD1B0
    const val NK_DPC_FlashOpen = 0xD1C0
    const val NK_DPC_FlashCharged = 0xD1C1
    const val PTP_DPC_NIKON_FlashMRepeatValue = 0xD1D0
    const val PTP_DPC_NIKON_FlashMRepeatCount = 0xD1D1
    const val PTP_DPC_NIKON_FlashMRepeatInterval = 0xD1D2
    const val PTP_DPC_NIKON_FlashCommandChannel = 0xD1D3
    const val PTP_DPC_NIKON_FlashCommandSelfMode = 0xD1D4
    const val PTP_DPC_NIKON_FlashCommandSelfCompensation = 0xD1D5
    const val PTP_DPC_NIKON_FlashCommandSelfValue = 0xD1D6
    const val PTP_DPC_NIKON_FlashCommandAMode = 0xD1D7
    const val PTP_DPC_NIKON_FlashCommandACompensation = 0xD1D8
    const val PTP_DPC_NIKON_FlashCommandAValue = 0xD1D9
    const val PTP_DPC_NIKON_FlashCommandBMode = 0xD1DA
    const val PTP_DPC_NIKON_FlashCommandBCompensation = 0xD1DB
    const val PTP_DPC_NIKON_FlashCommandBValue = 0xD1DC
    const val PTP_DPC_NIKON_ActivePicCtrlItem = 0xD200
    const val PTP_DPC_NIKON_ChangePicCtrlItem = 0xD201

    // Nikon focuseMode for 0x005a
    const val NK_FocusMode_Undefined = 0x0000
    const val NK_FocusMode_Manual = 0x0001
    const val NK_FocusMode_Automatic = 0x0002
    const val NK_FocusMode_AutomaticMacro = 0x0003

    // 0x501c : Focus Metering Mode
    const val NK_FocusMeteringMode_Undefined = 0x0000
    const val NK_FocusMeteringMode_Center_spot = 0x0001
    const val NK_FocusMeteringMode_Multi_spot = 0x0002

    // 0x5013 : Still Capture Mode
    const val NK_StilLCaptureMode_Undefined = 0x0000
    const val NK_StilLCaptureMode_Normal = 0x0001
    const val NK_StilLCaptureMode_Burst = 0x0002
    const val NK_StilLCaptureMode_Timelapse = 0x0003

    // 0x500e : Exposure Program Mode
    const val NK_ExposureProgramMode_Undefined = 0x0000
    const val NK_ExposureProgramMode_Manual = 0x0001
    const val NK_ExposureProgramMode_Automatic = 0x0002
    const val NK_ExposureProgramMode_Aperture_Priority = 0x0003
    const val NK_ExposureProgramMode_Shutter_Priority = 0x0004
    const val NK_ExposureProgramMode_Program_Creative = 0x0005 // (greater depth of field)
    const val NK_ExposureProgramMode_Program_Action = 0x0006 // (faster shutter speed)
    const val NK_ExposureProgramMode_Portrait = 0x0007

    // 0x500c : Flash Mode
    const val NK_FlashMode_Undefined = 0x0000
    const val NK_FlashMode_Auto_flash = 0x0001
    const val NK_FlashMode_Flash_off = 0x0002
    const val NK_FlashMode_Fill_flash = 0x0003
    const val NK_FlashMode_Red_eye_auto = 0x0004
    const val NK_FlashMode_Red_eye_fill = 0x0005
    const val NK_FlashMode_External_flash = 0x0006

    // Exposure Metering Mode 0x500b
    const val NK_MeteringMode_Undefined = 0x0000
    const val NK_MeteringMode_Average = 0x0001
    const val NK_MeteringMode_Center_weighted_average = 0x0002
    const val NK_MeteringMode_Multi_spot = 0x0003
    const val NK_MeteringMode_Center_spot = 0x0004

    // NikonFocusAreaTitles
    const val NK_FocusArea_x_ = 0x00
    const val NK_FocusAreax__ = 0x01
    const val NK_FocusArea__x = 0x02

    // NikonFocusModeTitles
    const val NK_FocusMode_AF_S = 0x00
    const val NK_FocusMode_AF_C = 0x01
    const val NK_FocusMode_AF_A = 0x02
    const val NK_FocusMode_MF = 0x04

    // CompressionTitles
    const val NK_Compression_BASIC = 0x00
    const val NK_Compression_NORM = 0x01
    const val NK_Compression_FINE = 0x02
    const val NK_Compression_RAW = 0x03
    const val NK_Compression_RAW_B = 0x04

    // ApertureTitles PTP_DPC_FNumber 0x5007
    const val NK_Aperture_3_50 = 0x015E
    const val NK_Aperture_4_00 = 0x0190
    const val NK_Aperture_4_50 = 0x01C2
    const val NK_Aperture_4_80 = 0x01E0
    const val NK_Aperture_5 = 0x01F4
    const val NK_Aperture_5_60 = 0x0230
    const val NK_Aperture_6_30 = 0x0276
    const val NK_Aperture_7_10 = 0x02C6
    const val NK_Aperture_8 = 0x0320
    const val NK_Aperture_9 = 0x0384
    const val NK_Aperture_10 = 0x03E8
    const val NK_Aperture_11 = 0x044C
    const val NK_Aperture_13 = 0x0514
    const val NK_Aperture_14 = 0x0578
    const val NK_Aperture_16 = 0x0640
    const val NK_Aperture_18 = 0x0708
    const val NK_Aperture_20 = 0x07D0
    const val NK_Aperture_22 = 0x0898
    const val NK_Aperture_25 = 0x09C4
    const val NK_Aperture_29 = 0x0B54
    const val NK_Aperture_32 = 0x0C80
    const val NK_Aperture_36 = 0x0E10

    // ShutterSpeedTitles PTP_DPC_ExposureTime 0x500D
    const val NK_ShutterSpeed_4000 = 0x00000002
    const val NK_ShutterSpeed_3200 = 0x00000003
    const val NK_ShutterSpeed_2500 = 0x00000004
    const val NK_ShutterSpeed_2000 = 0x00000005
    const val NK_ShutterSpeed_1600 = 0x00000006
    const val NK_ShutterSpeed_1250 = 0x00000008
    const val NK_ShutterSpeed_1000 = 0x0000000A
    const val NK_ShutterSpeed_800 = 0x0000000C
    const val NK_ShutterSpeed_640 = 0x0000000F
    const val NK_ShutterSpeed_500 = 0x00000014
    const val NK_ShutterSpeed_400 = 0x00000019
    const val NK_ShutterSpeed_320 = 0x0000001F
    const val NK_ShutterSpeed_250 = 0x00000028
    const val NK_ShutterSpeed_200 = 0x00000032
    const val NK_ShutterSpeed_160 = 0x0000003E
    const val NK_ShutterSpeed_125 = 0x00000050
    const val NK_ShutterSpeed_100 = 0x00000064
    const val NK_ShutterSpeed_80 = 0x0000007D
    const val NK_ShutterSpeed_60 = 0x000000A6
    const val NK_ShutterSpeed_50 = 0x000000C8
    const val NK_ShutterSpeed_40 = 0x000000FA
    const val NK_ShutterSpeed_30 = 0x0000014D
    const val NK_ShutterSpeed_25 = 0x00000190
    const val NK_ShutterSpeed_20 = 0x000001F4
    const val NK_ShutterSpeed_15 = 0x0000029A
    const val NK_ShutterSpeed_13 = 0x00000301
    const val NK_ShutterSpeed_10 = 0x000003E8
    const val NK_ShutterSpeed_8 = 0x000004E2
    const val NK_ShutterSpeed_6 = 0x00000682
    const val NK_ShutterSpeed_5 = 0x000007D0
    const val NK_ShutterSpeed_4 = 0x000009C4
    const val NK_ShutterSpeed_3 = 0x00000D05
    const val NK_ShutterSpeed_2_5 = 0x00000FA0
    const val NK_ShutterSpeed_2 = 0x00001388
    const val NK_ShutterSpeed_1_6 = 0x0000186A
    const val NK_ShutterSpeed_1_3 = 0x00001E0C
    const val NK_ShutterSpeed_1_sec = 0x00002710
    const val NK_ShutterSpeed_1_3_sec = 0x000032C8
    const val NK_ShutterSpeed_1_6_sec = 0x00003E80
    const val NK_ShutterSpeed_2_sec = 0x00004E20
    const val NK_ShutterSpeed_2_5_sec = 0x000061A8
    const val NK_ShutterSpeed_3_sec = 0x00007530
    const val NK_ShutterSpeed_4_sec = 0x00009C40
    const val NK_ShutterSpeed_5_sec = 0x0000C350
    const val NK_ShutterSpeed_6_sec = 0x0000EA60
    const val NK_ShutterSpeed_8_sec = 0x00013880
    const val NK_ShutterSpeed_10_sec = 0x000186A0
    const val NK_ShutterSpeed_13_sec = 0x0001FBD0
    const val NK_ShutterSpeed_15_sec = 0x000249F0
    const val NK_ShutterSpeed_20_sec = 0x00030D40
    const val NK_ShutterSpeed_25_sec = 0x0003D090
    const val NK_ShutterSpeed_30_sec = 0x000493E0
    const val NK_ShutterSpeed_Bulb = 0xFFFFFFFF.toInt()

    // IsoTitles PTP_DPC_ExposureIndex 0x500F
    const val NK_ISO_100 = 0x0064
    const val NK_ISO_125 = 0x007D
    const val NK_ISO_160 = 0x00A0
    const val NK_ISO_200 = 0x00C8
    const val NK_ISO_250 = 0x00FA
    const val NK_ISO_320 = 0x0140
    const val NK_ISO_400 = 0x0190
    const val NK_ISO_500 = 0x01F4
    const val NK_ISO_640 = 0x0280
    const val NK_ISO_800 = 0x0320
    const val NK_ISO_1000 = 0x03E8
    const val NK_ISO_1250 = 0x04E2
    const val NK_ISO_1600 = 0x0640
    const val NK_ISO_2000 = 0x07D0
    const val NK_ISO_2500 = 0x09C4
    const val NK_ISO_3200 = 0x0C80
    const val NK_ISO_4000 = 0x0FA0
    const val NK_ISO_5000 = 0x1388
    const val NK_ISO_6400 = 0x1900
    const val NK_ISO_Hi03 = 0x1F40
    const val NK_ISO_Hi07 = 0x2710
    const val NK_ISO_Hi_1 = 0x3200
    const val NK_ISO_Hi_2 = 0x6400

    // Exposure Compensation Title Array, PTP_DPC_ExposureBiasCompensation 0x5010
    const val NK_Exposure_N_5_0 = 0xEC78
    const val NK_Exposure_N_4_7 = 0xEDC6
    const val NK_Exposure_N_4_3 = 0xEF13
    const val NK_Exposure_N_4_0 = 0xF060
    const val NK_Exposure_N_3_7 = 0xF1AE
    const val NK_Exposure_N_3_3 = 0xF2FB
    const val NK_Exposure_N_3_0 = 0xF448
    const val NK_Exposure_N_2_7 = 0xF596
    const val NK_Exposure_N_2_3 = 0xF6E3
    const val NK_Exposure_N_2_0 = 0xF830
    const val NK_Exposure_N_1_7 = 0xF97E
    const val NK_Exposure_N_1_3 = 0xFACB
    const val NK_Exposure_N_1_0 = 0xFC18
    const val NK_Exposure_N_0_7 = 0xFD66
    const val NK_Exposure_N_0_3 = 0xFEB3
    const val NK_Exposure_0 = 0x0000
    const val NK_Exposure_P_0_3 = 0x014D
    const val NK_Exposure_P_0_7 = 0x029A
    const val NK_Exposure_P_1_0 = 0x03E8
    const val NK_Exposure_P_1_3 = 0x0535
    const val NK_Exposure_P_1_7 = 0x0682
    const val NK_Exposure_P_2_0 = 0x07D0
    const val NK_Exposure_P_2_3 = 0x091D
    const val NK_Exposure_P_2_7 = 0x0A6A
    const val NK_Exposure_P_3_0 = 0x0BB8
    const val NK_Exposure_P_3_3 = 0x0D05
    const val NK_Exposure_P_3_7 = 0x0E52
    const val NK_Exposure_P_4_0 = 0x0FA0
    const val NK_Exposure_P_4_3 = 0x10ED
    const val NK_Exposure_P_4_7 = 0x123A
    const val NK_Exposure_P_5_0 = 0x1388

    // White Balance Title Array for 0x5005 PTP_DPC_WhiteBalance
    const val NK_WhiteBalance_Undefined = 0x0000
    const val NK_WhiteBalance_Manual = 0x0001
    const val NK_WhiteBalance_Automatic = 0x0002
    const val NK_WhiteBalance_One_push_Automatic = 0x0003
    const val NK_WhiteBalance_Daylight = 0x0004
    const val NK_WhiteBalance_Fluorescent = 0x0005
    const val NK_WhiteBalance_Tungsten = 0x0006
    const val NK_WhiteBalance_Flash_Strobe = 0x0007
    const val NK_WhiteBalance_Clouds = 0x8010 // Clouds
    const val NK_WhiteBalance_Shade = 0x8011 // Shade
    const val NK_WhiteBalance_Preset = 0x8013 // Preset

    // Picture Style Title Array
    const val NK_PictureStyle_User1 = 0x21
    const val NK_PictureStyle_User2 = 0x22
    const val NK_PictureStyle_User3 = 0x23
    const val NK_PictureStyle_Standard = 0x81
    const val NK_PictureStyle_Portrait = 0x82
    const val NK_PictureStyle_Landscape = 0x83
    const val NK_PictureStyle_Neutral = 0x84
    const val NK_PictureStyle_Faithful = 0x85
    const val NK_PictureStyle_Monochrome = 0x86

    //
    const val PTP_DPC_Undefined = 0x5000
    const val PTP_DPC_BatteryLevel = 0x5001 // Yes
    const val PTP_DPC_FunctionalMode = 0x5002
    const val PTP_DPC_ImageSize = 0x5003 // Yes
    const val PTP_DPC_CompressionSetting = 0x5004 // Yes
    const val PTP_DPC_WhiteBalance = 0x5005 // Yes WhiteBalance
    const val PTP_DPC_RGBGain = 0x5006
    const val PTP_DPC_FNumber = 0x5007 // Yes Aperture
    const val PTP_DPC_FocalLength = 0x5008 // Yes
    const val PTP_DPC_FocusDistance = 0x5009
    const val PTP_DPC_FocusMode = 0x500A // Yes
    const val PTP_DPC_ExposureMeteringMode = 0x500B // Yes
    const val PTP_DPC_FlashMode = 0x500C // Yes
    const val PTP_DPC_ExposureTime = 0x500D // Yes ShutterSpeed
    const val PTP_DPC_ExposureProgramMode = 0x500E // Yes
    const val PTP_DPC_ExposureIndex = 0x500F // Yes ISO
    const val PTP_DPC_ExposureBiasCompensation = 0x5010 // Yes Exposure
    const val PTP_DPC_DateTime = 0x5011 // Yes
    const val PTP_DPC_CaptureDelay = 0x5012
    const val PTP_DPC_StillCaptureMode = 0x5013 // Yes
    const val PTP_DPC_Contrast = 0x5014
    const val PTP_DPC_Sharpness = 0x5015
    const val PTP_DPC_DigitalZoom = 0x5016
    const val PTP_DPC_EffectMode = 0x5017
    const val PTP_DPC_BurstNumber = 0x5018 // Yes
    const val PTP_DPC_BurstInterval = 0x5019
    const val PTP_DPC_TimelapseNumber = 0x501A
    const val PTP_DPC_TimelapseInterval = 0x501B
    const val PTP_DPC_FocusMeteringMode = 0x501C // Yes
    const val PTP_DPC_UploadURL = 0x501D
    const val PTP_DPC_Artist = 0x501E
    const val PTP_DPC_CopyrightInfo = 0x501F

    /////////////////////////////////////////////////////////////////////////////////////////// End Of Nikon Commands
}
