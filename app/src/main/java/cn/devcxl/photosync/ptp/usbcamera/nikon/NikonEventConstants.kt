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
 * EOS event codes
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

    /**
     * Events
     */
    const val EosEventRequestGetEvent = 0xC101
    const val EosEventObjectAddedEx = 0xC181
    const val EosEventObjectRemoved = 0xC182
    const val EosEventRequestGetObjectInfoEx = 0xC183
    const val EosEventStorageStatusChanged = 0xC184
    const val EosEventStorageInfoChanged = 0xC185
    const val EosEventRequestObjectTransfer = 0xC186
    const val EosEventObjectInfoChangedEx = 0xC187
    const val EosEventObjectContentChanged = 0xC188
    const val EosEventPropValueChanged = 0xC189
    const val EosEventAvailListChanged = 0xC18A
    const val EosEventCameraStatusChanged = 0xC18B
    const val EosEventWillSoonShutdown = 0xC18D
    const val EosEventShutdownTimerUpdated = 0xC18E
    const val EosEventRequestCancelTransfer = 0xC18F
    const val EosEventRequestObjectTransferDT = 0xC190
    const val EosEventRequestCancelTransferDT = 0xC191
    const val EosEventStoreAdded = 0xC192
    const val EosEventStoreRemoved = 0xC193
    const val EosEventBulbExposureTime = 0xC194
    const val EosEventRecordingTime = 0xC195
    const val EosEventRequestObjectTransferTS = 0xC1A2
    const val EosEventAfResult = 0xC1A3

    /* Canon extension device property codes */
    const val EosDevicePropBeepMode = 0xD001
    const val EosDevicePropViewfinderMode = 0xD003
    const val EosDevicePropImageQuality = 0xD006
    const val EosDevicePropD007 = 0xD007
    const val EosDevicePropImageSize = 0xD008
    const val EosDevicePropFlashMode = 0xD00A
    const val EosDevicePropTvAvSetting = 0xD00C
    const val EosDevicePropMeteringMode = 0xD010
    const val EosDevicePropMacroMode = 0xD011
    const val EosDevicePropFocusingPoint = 0xD012
    const val EosDevicePropWhiteBalance = 0xD013
    const val EosDevicePropISOSpeed = 0xD01C
    const val EosDevicePropAperture = 0xD01D
    const val EosDevicePropShutterSpeed = 0xD01E
    const val EosDevicePropExpCompensation = 0xD01F
    const val EosDevicePropD029 = 0xD029
    const val EosDevicePropZoom = 0xD02A
    const val EosDevicePropSizeQualityMode = 0xD02C
    const val EosDevicePropFlashMemory = 0xD031
    const val EosDevicePropCameraModel = 0xD032
    const val EosDevicePropCameraOwner = 0xD033
    const val EosDevicePropUnixTime = 0xD034
    const val EosDevicePropViewfinderOutput = 0xD036
    const val EosDevicePropRealImageWidth = 0xD039
    const val EosDevicePropPhotoEffect = 0xD040
    const val EosDevicePropAssistLight = 0xD041
    const val EosDevicePropD045 = 0xD045

    /*
     * Properties
     */
    const val EosPropAperture = 0xD101
    const val EosPropShutterSpeed = 0xD102
    const val EosPropISOSpeed = 0xD103
    const val EosPropExpCompensation = 0xD104
    const val EosPropAutoExposureMode = 0xD105
    const val EosPropDriveMode = 0xD106
    const val EosPropMeteringMode = 0xD107
    const val EosPropFocusMode = 0xD108
    const val EosPropWhiteBalance = 0xD109
    const val EosPropColorTemperature = 0xD10A
    const val EosPropWhiteBalanceAdjustA = 0xD10B
    const val EosPropWhiteBalanceAdjustB = 0xD10C
    const val EosPropWhiteBalanceXA = 0xD10D
    const val EosPropWhiteBalanceXB = 0xD10E
    const val EosPropColorSpace = 0xD10F
    const val EosPropPictureStyle = 0xD110
    const val EosPropBatteryPower = 0xD111
    const val EosPropBatterySelect = 0xD112
    const val EosPropCameraTime = 0xD113
    const val EosPropOwner = 0xD115
    const val EosPropModelID = 0xD116
    const val EosPropPTPExtensionVersion = 0xD119
    const val EosPropDPOFVersion = 0xD11A
    const val EosPropAvailableShots = 0xD11B
    const val EosPropCaptureDestination = 0xD11C
    const val EosPropBracketMode = 0xD11D
    const val EosPropCurrentStorage = 0xD11E
    const val EosPropCurrentFolder = 0xD11F
    const val EosPropImageFormat = 0xD120 /* file setting */
    const val EosPropImageFormatCF = 0xD121 /* file setting CF */
    const val EosPropImageFormatSD = 0xD122 /* file setting SD */
    const val EosPropImageFormatExtHD = 0xD123 /* file setting exthd */
    const val EosPropCompressionS = 0xD130
    const val EosPropCompressionM1 = 0xD131
    const val EosPropCompressionM2 = 0xD132
    const val EosPropCompressionL = 0xD133
    const val EosPropPCWhiteBalance1 = 0xD140
    const val EosPropPCWhiteBalance2 = 0xD141
    const val EosPropPCWhiteBalance3 = 0xD142
    const val EosPropPCWhiteBalance4 = 0xD143
    const val EosPropPCWhiteBalance5 = 0xD144
    const val EosPropMWhiteBalance = 0xD145
    const val EosPropPictureStyleStandard = 0xD150
    const val EosPropPictureStylePortrait = 0xD151
    const val EosPropPictureStyleLandscape = 0xD152
    const val EosPropPictureStyleNeutral = 0xD153
    const val EosPropPictureStyleFaithful = 0xD154
    const val EosPropPictureStyleMonochrome = 0xD155
    const val EosPropPictureStyleUserSet1 = 0xD160
    const val EosPropPictureStyleUserSet2 = 0xD161
    const val EosPropPictureStyleUserSet3 = 0xD162
    const val EosPropPictureStyleParam1 = 0xD170
    const val EosPropPictureStyleParam2 = 0xD171
    const val EosPropPictureStyleParam3 = 0xD172
    const val EosPropFlavorLUTParams = 0xD17F
    const val EosPropCustomFunc1 = 0xD180
    const val EosPropCustomFunc2 = 0xD181
    const val EosPropCustomFunc3 = 0xD182
    const val EosPropCustomFunc4 = 0xD183
    const val EosPropCustomFunc5 = 0xD184
    const val EosPropCustomFunc6 = 0xD185
    const val EosPropCustomFunc7 = 0xD186
    const val EosPropCustomFunc8 = 0xD187
    const val EosPropCustomFunc9 = 0xD188
    const val EosPropCustomFunc10 = 0xD189
    const val EosPropCustomFunc11 = 0xD18A
    const val EosPropCustomFunc12 = 0xD18B
    const val EosPropCustomFunc13 = 0xD18C
    const val EosPropCustomFunc14 = 0xD18D
    const val EosPropCustomFunc15 = 0xD18E
    const val EosPropCustomFunc16 = 0xD18F
    const val EosPropCustomFunc17 = 0xD190
    const val EosPropCustomFunc18 = 0xD191
    const val EosPropCustomFunc19 = 0xD192
    const val EosPropCustomFuncEx = 0xD1A0
    const val EosPropMyMenu = 0xD1A1
    const val EosPropMyMenuList = 0xD1A2
    const val EosPropWftStatus = 0xD1A3
    const val EosPropWftInputTransmission = 0xD1A4
    const val EosPropHDDirectoryStructure = 0xD1A5
    const val EosPropBatteryInfo = 0xD1A6
    const val EosPropAdapterInfo = 0xD1A7
    const val EosPropLensStatus = 0xD1A8
    const val EosPropQuickReviewTime = 0xD1A9
    const val EosPropCardExtension = 0xD1AA
    const val EosPropTempStatus = 0xD1AB
    const val EosPropShutterCounter = 0xD1AC
    const val EosPropSpecialOption = 0xD1AD
    const val EosPropPhotoStudioMode = 0xD1AE
    const val EosPropSerialNumber = 0xD1AF
    const val EosPropEVFOutputDevice = 0xD1B0
    const val EosPropEVFMode = 0xD1B1
    const val EosPropDepthOfFieldPreview = 0xD1B2
    const val EosPropEVFSharpness = 0xD1B3
    const val EosPropEVFWBMode = 0xD1B4
    const val EosPropEVFClickWBCoeffs = 0xD1B5
    const val EosPropEVFColorTemp = 0xD1B6
    const val EosPropExposureSimMode = 0xD1B7
    const val EosPropEVFRecordStatus = 0xD1B8
    const val EosPropLvAfSystem = 0xD1BA
    const val EosPropMovSize = 0xD1BB
    const val EosPropLvViewTypeSelect = 0xD1BC
    const val EosPropArtist = 0xD1D0
    const val EosPropCopyright = 0xD1D1
    const val EosPropBracketValue = 0xD1D2
    const val EosPropFocusInfoEx = 0xD1D3
    const val EosPropDepthOfField = 0xD1D4
    const val EosPropBrightness = 0xD1D5
    const val EosPropLensAdjustParams = 0xD1D6
    const val EosPropEFComp = 0xD1D7
    const val EosPropLensName = 0xD1D8
    const val EosPropAEB = 0xD1D9
    const val EosPropStroboSetting = 0xD1DA
    const val EosPropStroboWirelessSetting = 0xD1DB
    const val EosPropStroboFiring = 0xD1DC
    const val EosPropLensID = 0xD1DD

    /**
     * ISO speed
     */
    const val ISO_Auto = 0x00
    const val ISO_50 = 0x40
    const val ISO_100 = 0x48
    const val ISO_125 = 0x4b
    const val ISO_160 = 0x4d
    const val ISO_200 = 0x50
    const val ISO_250 = 0x53
    const val ISO_320 = 0x55
    const val ISO_400 = 0x58
    const val ISO_500 = 0x5b
    const val ISO_640 = 0x5d
    const val ISO_800 = 0x60
    const val ISO_1000 = 0x63
    const val ISO_1250 = 0x65
    const val ISO_1600 = 0x68
    const val ISO_3200 = 0x70

    /**
     * Aperture
     */
    const val APERTURE_F1_2 = 0x0d
    const val APERTURE_F1_4 = 0x10
    const val APERTURE_F1_6 = 0x13
    const val APERTURE_F1_8 = 0x15
    const val APERTURE_F2_0 = 0x18
    const val APERTURE_F2_2 = 0x1b
    const val APERTURE_F2_5 = 0x1d
    const val APERTURE_F2_8 = 0x20
    const val APERTURE_F3_2 = 0x23
    const val APERTURE_F3_5 = 0x25
    const val APERTURE_F4_0 = 0x28
    const val APERTURE_F4_5 = 0x2b
    const val APERTURE_F5_0 = 0x2d
    const val APERTURE_F5_6 = 0x30
    const val APERTURE_F6_3 = 0x33
    const val APERTURE_F7_1 = 0x35
    const val APERTURE_F8 = 0x38
    const val APERTURE_F9 = 0x3b
    const val APERTURE_F10 = 0x3d
    const val APERTURE_F11 = 0x40
    const val APERTURE_F13 = 0x43
    const val APERTURE_F14 = 0x45
    const val APERTURE_F16 = 0x48
    const val APERTURE_F18 = 0x4b
    const val APERTURE_F20 = 0x4d
    const val APERTURE_F22 = 0x50
    const val APERTURE_F25 = 0x53
    const val APERTURE_F29 = 0x55
    const val APERTURE_F32 = 0x58

    const val EosAperture_4 = 0x28
    const val EosAperture_4_5 = 0x2B
    const val EosAperture_5 = 0x2D
    const val EosAperture_5_6 = 0x30
    const val EosAperture_6_3 = 0x33
    const val EosAperture_7_1 = 0x35
    const val EosAperture_8 = 0x38
    const val EosAperture_9 = 0x3B
    const val EosAperture_10 = 0x3D
    const val EosAperture_11 = 0x40
    const val EosAperture_13 = 0x43
    const val EosAperture_14 = 0x45
    const val EosAperture_16 = 0x48
    const val EosAperture_18 = 0x4B
    const val EosAperture_20 = 0x4D
    const val EosAperture_22 = 0x50
    const val EosAperture_25 = 0x53
    const val EosAperture_29 = 0x55
    const val EosAperture_32 = 0x58

    /**
     * Shutter Speed
     */
    const val SHUTTER_SPEED_BULB = 0x0c
    const val SHUTTER_SPEED_30_SEC = 0x10
    const val SHUTTER_SPEED_25_SEC = 0x13
    const val SHUTTER_SPEED_20_SEC = 0x15
    const val SHUTTER_SPEED_15_SEC = 0x18
    const val SHUTTER_SPEED_13_SEC = 0x1b
    const val SHUTTER_SPEED_10_SEC = 0x1d
    const val SHUTTER_SPEED_8_SEC = 0x20
    const val SHUTTER_SPEED_6_SEC = 0x23
    const val SHUTTER_SPEED_5_SEC = 0x25
    const val SHUTTER_SPEED_4_SEC = 0x28
    const val SHUTTER_SPEED_3_2_SEC = 0x2b
    const val SHUTTER_SPEED_2_5_SEC = 0x2d
    const val SHUTTER_SPEED_2_SEC = 0x30
    const val SHUTTER_SPEED_1_6_SEC = 0x32
    const val SHUTTER_SPEED_1_3_SEC = 0x35
    const val SHUTTER_SPEED_1_SEC = 0x38
    const val SHUTTER_SPEED_0_8_SEC = 0x3b
    const val SHUTTER_SPEED_0_6_SEC = 0x3d
    const val SHUTTER_SPEED_0_5_SEC = 0x40
    const val SHUTTER_SPEED_0_4_SEC = 0x43
    const val SHUTTER_SPEED_0_3_SEC = 0x45
    const val SHUTTER_SPEED_1_4 = 0x48
    const val SHUTTER_SPEED_1_5 = 0x4b
    const val SHUTTER_SPEED_1_6 = 0x4d
    const val SHUTTER_SPEED_1_8 = 0x50
    const val SHUTTER_SPEED_1_10 = 0x53
    const val SHUTTER_SPEED_1_13 = 0x55
    const val SHUTTER_SPEED_1_15 = 0x58
    const val SHUTTER_SPEED_1_20 = 0x5b
    const val SHUTTER_SPEED_1_25 = 0x5d
    const val SHUTTER_SPEED_1_30 = 0x60
    const val SHUTTER_SPEED_1_40 = 0x63
    const val SHUTTER_SPEED_1_50 = 0x65
    const val SHUTTER_SPEED_1_60 = 0x68
    const val SHUTTER_SPEED_1_80 = 0x6b
    const val SHUTTER_SPEED_1_100 = 0x6d
    const val SHUTTER_SPEED_1_125 = 0x70
    const val SHUTTER_SPEED_1_160 = 0x73
    const val SHUTTER_SPEED_1_200 = 0x75
    const val SHUTTER_SPEED_1_250 = 0x78
    const val SHUTTER_SPEED_1_320 = 0x7b
    const val SHUTTER_SPEED_1_400 = 0x7d
    const val SHUTTER_SPEED_1_500 = 0x80
    const val SHUTTER_SPEED_1_640 = 0x83
    const val SHUTTER_SPEED_1_800 = 0x85
    const val SHUTTER_SPEED_1_1000 = 0x88
    const val SHUTTER_SPEED_1_1250 = 0x8b
    const val SHUTTER_SPEED_1_1600 = 0x8d
    const val SHUTTER_SPEED_1_2000 = 0x90
    const val SHUTTER_SPEED_1_2500 = 0x93
    const val SHUTTER_SPEED_1_3200 = 0x95
    const val SHUTTER_SPEED_1_4000 = 0x98
    const val SHUTTER_SPEED_1_5000 = 0x9a
    const val SHUTTER_SPEED_1_6400 = 0x9d
    const val SHUTTER_SPEED_1_8000 = 0xA0

    /**
     * Eos Exposure Compensation
     */
    const val EXPOSURE_P_5_0d0 = 0x28 // +5
    const val EXPOSURE_P_4_2d30 = 0x25 // +4 2/30
    const val EXPOSURE_P_4_1d30 = 0x23 // +4 1/30
    const val EXPOSURE_P_4_0d0 = 0x20 // +4
    const val EXPOSURE_P_3_2d30 = 0x1d // +3 2/30
    const val EXPOSURE_P_3_1d30 = 0x1b // +3 1/30
    const val EXPOSURE_P_3_0d0 = 0x18 // +3 0
    const val EXPOSURE_P_2_2d30 = 0x15 // +2 2/30
    const val EXPOSURE_P_2_1d20 = 0x14 // +2 1/20
    const val EXPOSURE_P_2_1d30 = 0x13 // +2 1/30
    const val EXPOSURE_P_2_0d0 = 0x10 // +2 0
    const val EXPOSURE_P_1_2d30 = 0x0d // +1 2/30
    const val EXPOSURE_P_1_1d20 = 0x0c // +1 1/20
    const val EXPOSURE_P_1_1d30 = 0x0b // +1 1/30
    const val EXPOSURE_P_1_0d0 = 0x08 // +1
    const val EXPOSURE_P_0_2d3 = 0x05 // +2/3
    const val EXPOSURE_P_0_1d2 = 0x04 // +1/2
    const val EXPOSURE_P_0_1d3 = 0x03 // +1/3
    const val EXPOSURE_0 = 0x00 // 0
    const val EXPOSURE_N_0_1d3 = 0xfd // -1/3
    const val EXPOSURE_N_0_1d2 = 0xfc // -1/2
    const val EXPOSURE_N_0_2d3 = 0xfb // -2/3
    const val EXPOSURE_N_1_0d0 = 0xf8 // -1 0
    const val EXPOSURE_N_1_1d30 = 0xf5 // -1 1/30
    const val EXPOSURE_N_1_1d20 = 0xf4 // -1 1/20
    const val EXPOSURE_N_1_2d30 = 0xf3 // -1 2/30
    const val EXPOSURE_N_2_0d0 = 0xf0 // -2 0
    const val EXPOSURE_N_2_1d30 = 0xed // -2 1/30
    const val EXPOSURE_N_2_1d20 = 0xec // -2 1/20
    const val EXPOSURE_N_2_2d30 = 0xeb // -2 2/30
    const val EXPOSURE_N_3_0d0 = 0xe8 // -3 0
    const val EXPOSURE_N_3_1d30 = 0xe5 // -3 1/30
    const val EXPOSURE_N_3_2d30 = 0xe3 // -3 2/30
    const val EXPOSURE_N_4_0d0 = 0xe0 // -4 0
    const val EXPOSURE_N_4_1d30 = 0xdd // -4 1/30
    const val EXPOSURE_N_4_2d30 = 0xdb // -4 2/30
    const val EXPOSURE_N_5_0d0 = 0xd8 // -5 0

    /**
     * Eos White Balance
     */
    const val AutoWhiteBalance = 0
    const val Daylight = 1
    const val Clouds = 2
    const val Tungsteen = 3
    const val Fluoriscent = 4
    const val Strobe = 5
    const val WhitePaper = 6
    const val Shade = 7

    /**
     * User picture style type
     */
    const val PictureStyle_User1 = 0x21
    const val PictureStyle_User2 = 0x22
    const val PictureStyle_User3 = 0x23

    const val PictureStyle_Standard = 0x81
    const val PictureStyle_Portrait = 0x82
    const val PictureStyle_Landscape = 0x83
    const val PictureStyle_Neutral = 0x84
    const val PictureStyle_Faithful = 0x85
    const val PictureStyle_Monochrome = 0x86

    /**
     * User picture style type
     */
    const val EosPropPictureStyleUserTypeUser1 = 0x21
    const val EosPropPictureStyleUserTypeUser2 = 0x22
    const val EosPropPictureStyleUserTypeUser3 = 0x23

    const val EosPropPictureStyleUserTypeStandard = 0x81
    const val EosPropPictureStyleUserTypePortrait = 0x82
    const val EosPropPictureStyleUserTypeLandscape = 0x83
    const val EosPropPictureStyleUserTypeNeutral = 0x84
    const val EosPropPictureStyleUserTypeFaithful = 0x85
    const val EosPropPictureStyleUserTypeMonochrome = 0x86

    /**
     * Image formats
     */
    const val ImageFormatEXIF_JPEG = 0x3801
    const val ImageFormatTIFF_EP = 0x3802
    const val ImageFormatFlashPix = 0x3803
    const val ImageFormatBMP = 0x3804
    const val ImageFormatCIFF = 0x3805
    const val ImageFormatUndefined_0x3806 = 0x3806
    const val ImageFormatGIF = 0x3807
    const val ImageFormatJFIF = 0x3808
    const val ImageFormatPCD = 0x3809
    const val ImageFormatPICT = 0x380A
    const val ImageFormatPNG = 0x380B
    const val ImageFormatUndefined_0x380C = 0x380C
    const val ImageFormatTIFF = 0x380D
    const val ImageFormatTIFF_IT = 0x380E
    const val ImageFormatJP2 = 0x380F
    const val ImageFormatJPX = 0x3810
    /* ptp v1.1 has only DNG new */
    const val ImageFormatDNG = 0x3811
    /* Eastman Kodak extension ancillary format */
    const val ImageFormatEK_M3U = 0xB002
    /* Canon extension */
    const val ImageFormatCANON_CRW = 0xB101
    const val ImageFormatCANON_CRW3 = 0xB103
    const val ImageFormatCANON_MOV = 0xB104
    /* CHDK specific raw mode */
    const val ImageFormatCANON_CHDK_CRW = 0xB1FF
    /* MTP extensions */
    const val ImageFormatMTP_MediaCard = 0xB211
    const val ImageFormatMTP_MediaCardGroup = 0xb212
    const val ImageFormatMTP_Encounter = 0xb213
    const val ImageFormatMTP_EncounterBox = 0xb214
    const val ImageFormatMTP_M4A = 0xb215
    const val ImageFormatMTP_ZUNEUNDEFINED = 0xb217
    const val ImageFormatMTP_Firmware = 0xb802
    const val ImageFormatMTP_WindowsImageFormat = 0xb881
    const val ImageFormatMTP_UndefinedAudio = 0xb900
    const val ImageFormatMTP_WMA = 0xb901
    const val ImageFormatMTP_OGG = 0xb902
    const val ImageFormatMTP_AAC = 0xb903
    const val ImageFormatMTP_AudibleCodec = 0xb904
    const val ImageFormatMTP_FLAC = 0xb906
    const val ImageFormatMTP_SamsungPlaylist = 0xb909
    const val ImageFormatMTP_UndefinedVideo = 0xb980
    const val ImageFormatMTP_WMV = 0xb981
    const val ImageFormatMTP_MP4 = 0xb982
    const val ImageFormatMTP_MP2 = 0xb983
    const val ImageFormatMTP_3GP = 0xb984
    const val ImageFormatMTP_UndefinedCollection = 0xba00
    const val ImageFormatMTP_AbstractMultimediaAlbum = 0xba01
    const val ImageFormatMTP_AbstractImageAlbum = 0xba02
    const val ImageFormatMTP_AbstractAudioAlbum = 0xba03
    const val ImageFormatMTP_AbstractVideoAlbum = 0xba04
    const val ImageFormatMTP_AbstractAudioVideoPlaylist = 0xba05
    const val ImageFormatMTP_AbstractContactGroup = 0xba06
    const val ImageFormatMTP_AbstractMessageFolder = 0xba07
    const val ImageFormatMTP_AbstractChapteredProduction = 0xba08
    const val ImageFormatMTP_AbstractAudioPlaylist = 0xba09
    const val ImageFormatMTP_AbstractVideoPlaylist = 0xba0a
    const val ImageFormatMTP_AbstractMediacast = 0xba0b
    const val ImageFormatMTP_WPLPlaylist = 0xba10
    const val ImageFormatMTP_M3UPlaylist = 0xba11
    const val ImageFormatMTP_MPLPlaylist = 0xba12
    const val ImageFormatMTP_ASXPlaylist = 0xba13
    const val ImageFormatMTP_PLSPlaylist = 0xba14
    const val ImageFormatMTP_UndefinedDocument = 0xba80
    const val ImageFormatMTP_AbstractDocument = 0xba81
    const val ImageFormatMTP_XMLDocument = 0xba82
    const val ImageFormatMTP_MSWordDocument = 0xba83
    const val ImageFormatMTP_MHTCompiledHTMLDocument = 0xba84
    const val ImageFormatMTP_MSExcelSpreadsheetXLS = 0xba85
    const val ImageFormatMTP_MSPowerpointPresentationPPT = 0xba86
    const val ImageFormatMTP_UndefinedMessage = 0xbb00
    const val ImageFormatMTP_AbstractMessage = 0xbb01
    const val ImageFormatMTP_UndefinedContact = 0xbb80
    const val ImageFormatMTP_AbstractContact = 0xbb81
    const val ImageFormatMTP_vCard2 = 0xbb82
    const val ImageFormatMTP_vCard3 = 0xbb83
    const val ImageFormatMTP_UndefinedCalendarItem = 0xbe00
    const val ImageFormatMTP_AbstractCalendarItem = 0xbe01
    const val ImageFormatMTP_vCalendar1 = 0xbe02
    const val ImageFormatMTP_vCalendar2 = 0xbe03
    const val ImageFormatMTP_UndefinedWindowsExecutable = 0xbe80
    const val ImageFormatMTP_MediaCast = 0xbe81
    const val ImageFormatMTP_Section = 0xbe82

    const val driveSingleShot = 0x0000
    const val driveContinuous = 0x0001
    const val driveContinuousHighSpeed = 0x0004
    const val driveContinuousLowSpeed = 0x0005
    const val driveTimer10Sec = 0x0010
    const val driveTimer2Sec = 0x0010
    const val driveSingleSilent = 0x0013
    const val driveContinuousSilent = 0x0014

    const val pictSFine = 0x00000321
    const val pictSNormal = 0x00000221
    const val pictMFine = 0x00000311
    const val pictMNormal = 0x00000211
    const val pictLFine = 0x00000301
    const val pictLNormal = 0x00000201
    const val pictRaw = 0x00000406
    const val pictRawPlusL = 0x00301406

    const val centerWeightedMetering = 0
    const val SpotMetering = 1 // Good
    const val AverageMetering = 2
    const val EvaluativeMetering = 3 // Good
    const val PartialMetering = 4 // Good
    const val CenterWeightedAverageMetering = 5 // Good
    const val spotMeteringInterlockedWithAFframeMetering = 6
    const val multiSpotMetering = 7
}
