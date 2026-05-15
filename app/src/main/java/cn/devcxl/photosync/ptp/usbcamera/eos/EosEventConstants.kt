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

package cn.devcxl.photosync.ptp.usbcamera.eos

/**
 * EOS event codes
 *
 * @author stefano fornari
 * @author devcxl
 */
object EosEventConstants {
    /**
     * Events
     */
    const val EosEventRequestGetEvent: Int = 0xC101
    const val EosEventObjectAddedEx: Int = 0xC181
    const val EosEventObjectRemoved: Int = 0xC182
    const val EosEventRequestGetObjectInfoEx: Int = 0xC183
    const val EosEventStorageStatusChanged: Int = 0xC184
    const val EosEventStorageInfoChanged: Int = 0xC185
    const val EosEventRequestObjectTransfer: Int = 0xC186
    const val EosEventObjectInfoChangedEx: Int = 0xC187
    const val EosEventObjectContentChanged: Int = 0xC188
    const val EosEventPropValueChanged: Int = 0xC189
    const val EosEventAvailListChanged: Int = 0xC18A
    const val EosEventCameraStatusChanged: Int = 0xC18B
    const val EosEventWillSoonShutdown: Int = 0xC18D
    const val EosEventShutdownTimerUpdated: Int = 0xC18E
    const val EosEventRequestCancelTransfer: Int = 0xC18F
    const val EosEventRequestObjectTransferDT: Int = 0xC190
    const val EosEventRequestCancelTransferDT: Int = 0xC191
    const val EosEventStoreAdded: Int = 0xC192
    const val EosEventStoreRemoved: Int = 0xC193
    const val EosEventBulbExposureTime: Int = 0xC194
    const val EosEventRecordingTime: Int = 0xC195
    const val EosEventRequestObjectTransferTS: Int = 0xC1A2
    const val EosEventAfResult: Int = 0xC1A3

    /* Canon extension device property codes */
    const val EosDevicePropBeepMode: Int = 0xD001
    const val EosDevicePropViewfinderMode: Int = 0xD003
    const val EosDevicePropImageQuality: Int = 0xD006
    const val EosDevicePropD007: Int = 0xD007
    const val EosDevicePropImageSize: Int = 0xD008
    const val EosDevicePropFlashMode: Int = 0xD00A
    const val EosDevicePropTvAvSetting: Int = 0xD00C
    const val EosDevicePropMeteringMode: Int = 0xD010
    const val EosDevicePropMacroMode: Int = 0xD011
    const val EosDevicePropFocusingPoint: Int = 0xD012
    const val EosDevicePropWhiteBalance: Int = 0xD013
    const val EosDevicePropISOSpeed: Int = 0xD01C
    const val EosDevicePropAperture: Int = 0xD01D
    const val EosDevicePropShutterSpeed: Int = 0xD01E
    const val EosDevicePropExpCompensation: Int = 0xD01F
    const val EosDevicePropD029: Int = 0xD029
    const val EosDevicePropZoom: Int = 0xD02A
    const val EosDevicePropSizeQualityMode: Int = 0xD02C
    const val EosDevicePropFlashMemory: Int = 0xD031
    const val EosDevicePropCameraModel: Int = 0xD032
    const val EosDevicePropCameraOwner: Int = 0xD033
    const val EosDevicePropUnixTime: Int = 0xD034
    const val EosDevicePropViewfinderOutput: Int = 0xD036
    const val EosDevicePropRealImageWidth: Int = 0xD039
    const val EosDevicePropPhotoEffect: Int = 0xD040
    const val EosDevicePropAssistLight: Int = 0xD041
    const val EosDevicePropD045: Int = 0xD045

    /*
     * Properties
     */
    const val EosPropAperture: Int = 0xD101
    const val EosPropShutterSpeed: Int = 0xD102
    const val EosPropISOSpeed: Int = 0xD103
    const val EosPropExpCompensation: Int = 0xD104
    const val EosPropAutoExposureMode: Int = 0xD105
    const val EosPropDriveMode: Int = 0xD106
    const val EosPropMeteringMode: Int = 0xD107
    const val EosPropFocusMode: Int = 0xD108
    const val EosPropWhiteBalance: Int = 0xD109
    const val EosPropColorTemperature: Int = 0xD10A
    const val EosPropWhiteBalanceAdjustA: Int = 0xD10B
    const val EosPropWhiteBalanceAdjustB: Int = 0xD10C
    const val EosPropWhiteBalanceXA: Int = 0xD10D
    const val EosPropWhiteBalanceXB: Int = 0xD10E
    const val EosPropColorSpace: Int = 0xD10F
    const val EosPropPictureStyle: Int = 0xD110
    const val EosPropBatteryPower: Int = 0xD111
    const val EosPropBatterySelect: Int = 0xD112
    const val EosPropCameraTime: Int = 0xD113
    const val EosPropOwner: Int = 0xD115
    const val EosPropModelID: Int = 0xD116
    const val EosPropPTPExtensionVersion: Int = 0xD119
    const val EosPropDPOFVersion: Int = 0xD11A
    const val EosPropAvailableShots: Int = 0xD11B
    const val EosPropCaptureDestination: Int = 0xD11C
    const val EosPropBracketMode: Int = 0xD11D
    const val EosPropCurrentStorage: Int = 0xD11E
    const val EosPropCurrentFolder: Int = 0xD11F
    const val EosPropImageFormat: Int = 0xD120 /* file setting */
    const val EosPropImageFormatCF: Int = 0xD121 /* file setting CF */
    const val EosPropImageFormatSD: Int = 0xD122 /* file setting SD */
    const val EosPropImageFormatExtHD: Int = 0xD123 /* file setting exthd */
    const val EosPropCompressionS: Int = 0xD130
    const val EosPropCompressionM1: Int = 0xD131
    const val EosPropCompressionM2: Int = 0xD132
    const val EosPropCompressionL: Int = 0xD133
    const val EosPropPCWhiteBalance1: Int = 0xD140
    const val EosPropPCWhiteBalance2: Int = 0xD141
    const val EosPropPCWhiteBalance3: Int = 0xD142
    const val EosPropPCWhiteBalance4: Int = 0xD143
    const val EosPropPCWhiteBalance5: Int = 0xD144
    const val EosPropMWhiteBalance: Int = 0xD145
    const val EosPropPictureStyleStandard: Int = 0xD150
    const val EosPropPictureStylePortrait: Int = 0xD151
    const val EosPropPictureStyleLandscape: Int = 0xD152
    const val EosPropPictureStyleNeutral: Int = 0xD153
    const val EosPropPictureStyleFaithful: Int = 0xD154
    const val EosPropPictureStyleMonochrome: Int = 0xD155
    const val EosPropPictureStyleUserSet1: Int = 0xD160
    const val EosPropPictureStyleUserSet2: Int = 0xD161
    const val EosPropPictureStyleUserSet3: Int = 0xD162
    const val EosPropPictureStyleParam1: Int = 0xD170
    const val EosPropPictureStyleParam2: Int = 0xD171
    const val EosPropPictureStyleParam3: Int = 0xD172
    const val EosPropFlavorLUTParams: Int = 0xD17F
    const val EosPropCustomFunc1: Int = 0xD180
    const val EosPropCustomFunc2: Int = 0xD181
    const val EosPropCustomFunc3: Int = 0xD182
    const val EosPropCustomFunc4: Int = 0xD183
    const val EosPropCustomFunc5: Int = 0xD184
    const val EosPropCustomFunc6: Int = 0xD185
    const val EosPropCustomFunc7: Int = 0xD186
    const val EosPropCustomFunc8: Int = 0xD187
    const val EosPropCustomFunc9: Int = 0xD188
    const val EosPropCustomFunc10: Int = 0xD189
    const val EosPropCustomFunc11: Int = 0xD18A
    const val EosPropCustomFunc12: Int = 0xD18B
    const val EosPropCustomFunc13: Int = 0xD18C
    const val EosPropCustomFunc14: Int = 0xD18D
    const val EosPropCustomFunc15: Int = 0xD18E
    const val EosPropCustomFunc16: Int = 0xD18F
    const val EosPropCustomFunc17: Int = 0xD190
    const val EosPropCustomFunc18: Int = 0xD191
    const val EosPropCustomFunc19: Int = 0xD192
    const val EosPropCustomFuncEx: Int = 0xD1A0
    const val EosPropMyMenu: Int = 0xD1A1
    const val EosPropMyMenuList: Int = 0xD1A2
    const val EosPropWftStatus: Int = 0xD1A3
    const val EosPropWftInputTransmission: Int = 0xD1A4
    const val EosPropHDDirectoryStructure: Int = 0xD1A5
    const val EosPropBatteryInfo: Int = 0xD1A6
    const val EosPropAdapterInfo: Int = 0xD1A7
    const val EosPropLensStatus: Int = 0xD1A8
    const val EosPropQuickReviewTime: Int = 0xD1A9
    const val EosPropCardExtension: Int = 0xD1AA
    const val EosPropTempStatus: Int = 0xD1AB
    const val EosPropShutterCounter: Int = 0xD1AC
    const val EosPropSpecialOption: Int = 0xD1AD
    const val EosPropPhotoStudioMode: Int = 0xD1AE
    const val EosPropSerialNumber: Int = 0xD1AF
    const val EosPropEVFOutputDevice: Int = 0xD1B0
    const val EosPropEVFMode: Int = 0xD1B1
    const val EosPropDepthOfFieldPreview: Int = 0xD1B2
    const val EosPropEVFSharpness: Int = 0xD1B3
    const val EosPropEVFWBMode: Int = 0xD1B4
    const val EosPropEVFClickWBCoeffs: Int = 0xD1B5
    const val EosPropEVFColorTemp: Int = 0xD1B6
    const val EosPropExposureSimMode: Int = 0xD1B7
    const val EosPropEVFRecordStatus: Int = 0xD1B8
    const val EosPropLvAfSystem: Int = 0xD1BA
    const val EosPropMovSize: Int = 0xD1BB
    const val EosPropLvViewTypeSelect: Int = 0xD1BC
    const val EosPropArtist: Int = 0xD1D0
    const val EosPropCopyright: Int = 0xD1D1
    const val EosPropBracketValue: Int = 0xD1D2
    const val EosPropFocusInfoEx: Int = 0xD1D3
    const val EosPropDepthOfField: Int = 0xD1D4
    const val EosPropBrightness: Int = 0xD1D5
    const val EosPropLensAdjustParams: Int = 0xD1D6
    const val EosPropEFComp: Int = 0xD1D7
    const val EosPropLensName: Int = 0xD1D8
    const val EosPropAEB: Int = 0xD1D9
    const val EosPropStroboSetting: Int = 0xD1DA
    const val EosPropStroboWirelessSetting: Int = 0xD1DB
    const val EosPropStroboFiring: Int = 0xD1DC
    const val EosPropLensID: Int = 0xD1DD


    const val PTP_OC_CANON_EOS_Zoom: Int = 0x9158
    const val PTP_OC_CANON_EOS_ZoomPosition: Int = 0x9159
    const val PTP_OC_CANON_EOS_DoAf: Int = 0x9154

    /**
     * ISO speed
     */
    const val ISO_Auto: Int = 0x00
    const val ISO_50: Int = 0x40
    const val ISO_100: Int = 0x48
    const val ISO_125: Int = 0x4b
    const val ISO_160: Int = 0x4d
    const val ISO_200: Int = 0x50
    const val ISO_250: Int = 0x53
    const val ISO_320: Int = 0x55
    const val ISO_400: Int = 0x58
    const val ISO_500: Int = 0x5b
    const val ISO_640: Int = 0x5d
    const val ISO_800: Int = 0x60
    const val ISO_1000: Int = 0x63
    const val ISO_1250: Int = 0x65
    const val ISO_1600: Int = 0x68
    const val ISO_3200: Int = 0x70


    /**
     * Aperture
     */
    const val APERTURE_F1_2: Int = 0x0d
    const val APERTURE_F1_4: Int = 0x10
    const val APERTURE_F1_6: Int = 0x13
    const val APERTURE_F1_8: Int = 0x15
    const val APERTURE_F2_0: Int = 0x18
    const val APERTURE_F2_2: Int = 0x1b
    const val APERTURE_F2_5: Int = 0x1d
    const val APERTURE_F2_8: Int = 0x20
    const val APERTURE_F3_2: Int = 0x23
    const val APERTURE_F3_5: Int = 0x25
    const val APERTURE_F4_0: Int = 0x28
    const val APERTURE_F4_5: Int = 0x2b
    const val APERTURE_F5_0: Int = 0x2d
    const val APERTURE_F5_6: Int = 0x30
    const val APERTURE_F6_3: Int = 0x33
    const val APERTURE_F7_1: Int = 0x35
    const val APERTURE_F8: Int = 0x38
    const val APERTURE_F9: Int = 0x3b
    const val APERTURE_F10: Int = 0x3d
    const val APERTURE_F11: Int = 0x40
    const val APERTURE_F13: Int = 0x43
    const val APERTURE_F14: Int = 0x45
    const val APERTURE_F16: Int = 0x48
    const val APERTURE_F18: Int = 0x4b
    const val APERTURE_F20: Int = 0x4d
    const val APERTURE_F22: Int = 0x50
    const val APERTURE_F25: Int = 0x53
    const val APERTURE_F29: Int = 0x55
    const val APERTURE_F32: Int = 0x58



    const val EosAperture_4: Int = 0x28
    const val EosAperture_4_5: Int = 0x2B
    const val EosAperture_5: Int = 0x2D
    const val EosAperture_5_6: Int = 0x30
    const val EosAperture_6_3: Int = 0x33
    const val EosAperture_7_1: Int = 0x35
    const val EosAperture_8: Int = 0x38
    const val EosAperture_9: Int = 0x3B
    const val EosAperture_10: Int = 0x3D
    const val EosAperture_11: Int = 0x40
    const val EosAperture_13: Int = 0x43
    const val EosAperture_14: Int = 0x45
    const val EosAperture_16: Int = 0x48
    const val EosAperture_18: Int = 0x4B
    const val EosAperture_20: Int = 0x4D
    const val EosAperture_22: Int = 0x50
    const val EosAperture_25: Int = 0x53
    const val EosAperture_29: Int = 0x55
    const val EosAperture_32: Int = 0x58
    /**
     * Shutter Speed
     */
    //const val SHUTTER_SPEED_BULB: Int = 0x04
    const val SHUTTER_SPEED_BULB: Int = 0x0c
    const val SHUTTER_SPEED_30_SEC: Int = 0x10
    const val SHUTTER_SPEED_25_SEC: Int = 0x13
    const val SHUTTER_SPEED_20_SEC: Int = 0x15
    const val SHUTTER_SPEED_15_SEC: Int = 0x18
    const val SHUTTER_SPEED_13_SEC: Int = 0x1b
    const val SHUTTER_SPEED_10_SEC: Int = 0x1d
    const val SHUTTER_SPEED_8_SEC: Int = 0x20
    const val SHUTTER_SPEED_6_SEC: Int = 0x23
    const val SHUTTER_SPEED_5_SEC: Int = 0x25
    const val SHUTTER_SPEED_4_SEC: Int = 0x28
    const val SHUTTER_SPEED_3_2_SEC: Int = 0x2b
    const val SHUTTER_SPEED_2_5_SEC: Int = 0x2d
    const val SHUTTER_SPEED_2_SEC: Int = 0x30
    const val SHUTTER_SPEED_1_6_SEC: Int = 0x32
    const val SHUTTER_SPEED_1_3_SEC: Int = 0x35
    const val SHUTTER_SPEED_1_SEC: Int = 0x38
    const val SHUTTER_SPEED_0_8_SEC: Int = 0x3b
    const val SHUTTER_SPEED_0_6_SEC: Int = 0x3d
    const val SHUTTER_SPEED_0_5_SEC: Int = 0x40
    const val SHUTTER_SPEED_0_4_SEC: Int = 0x43
    const val SHUTTER_SPEED_0_3_SEC: Int = 0x45
    const val SHUTTER_SPEED_1_4: Int = 0x48
    const val SHUTTER_SPEED_1_5: Int = 0x4b
    const val SHUTTER_SPEED_1_6: Int = 0x4d
    const val SHUTTER_SPEED_1_8: Int = 0x50
    const val SHUTTER_SPEED_1_10: Int = 0x53
    const val SHUTTER_SPEED_1_13: Int = 0x55
    const val SHUTTER_SPEED_1_15: Int = 0x58
    const val SHUTTER_SPEED_1_20: Int = 0x5b
    const val SHUTTER_SPEED_1_25: Int = 0x5d
    const val SHUTTER_SPEED_1_30: Int = 0x60
    const val SHUTTER_SPEED_1_40: Int = 0x63
    const val SHUTTER_SPEED_1_50: Int = 0x65
    const val SHUTTER_SPEED_1_60: Int = 0x68
    const val SHUTTER_SPEED_1_80: Int = 0x6b
    const val SHUTTER_SPEED_1_100: Int = 0x6d
    const val SHUTTER_SPEED_1_125: Int = 0x70
    const val SHUTTER_SPEED_1_160: Int = 0x73
    const val SHUTTER_SPEED_1_200: Int = 0x75
    const val SHUTTER_SPEED_1_250: Int = 0x78
    const val SHUTTER_SPEED_1_320: Int = 0x7b
    const val SHUTTER_SPEED_1_400: Int = 0x7d
    const val SHUTTER_SPEED_1_500: Int = 0x80
    const val SHUTTER_SPEED_1_640: Int = 0x83
    const val SHUTTER_SPEED_1_800: Int = 0x85
    const val SHUTTER_SPEED_1_1000: Int = 0x88
    const val SHUTTER_SPEED_1_1250: Int = 0x8b
    const val SHUTTER_SPEED_1_1600: Int = 0x8d
    const val SHUTTER_SPEED_1_2000: Int = 0x90
    const val SHUTTER_SPEED_1_2500: Int = 0x93
    const val SHUTTER_SPEED_1_3200: Int = 0x95
    const val SHUTTER_SPEED_1_4000: Int = 0x98
    const val SHUTTER_SPEED_1_5000: Int = 0x9a
    const val SHUTTER_SPEED_1_6400: Int = 0x9d
    const val SHUTTER_SPEED_1_8000: Int = 0xA0

    /**
     * Eos Exposure Compensation
     */
    const val EXPOSURE_P_5_0d0: Int = 0x28      //+5
    const val EXPOSURE_P_4_2d30: Int = 0x25 //+4 2/30
    const val EXPOSURE_P_4_1d30: Int = 0x23 //+4 1/30
    const val EXPOSURE_P_4_0d0: Int = 0x20     //+4
    const val EXPOSURE_P_3_2d30: Int = 0x1d // +3 2/30;
    const val EXPOSURE_P_3_1d30: Int = 0x1b // +3 1/30;
    const val EXPOSURE_P_3_0d0: Int = 0x18     // +3    0;
    const val EXPOSURE_P_2_2d30: Int = 0x15 // +2 2/30;
    const val EXPOSURE_P_2_1d20: Int = 0x14 // +2 1/20;
    const val EXPOSURE_P_2_1d30: Int = 0x13 // +2 1/30;
    const val EXPOSURE_P_2_0d0: Int = 0x10     // +2    0;
    const val EXPOSURE_P_1_2d30: Int = 0x0d // +1 2/30;
    const val EXPOSURE_P_1_1d20: Int = 0x0c // +1 1/20;
    const val EXPOSURE_P_1_1d30: Int = 0x0b // +1 1/30;
    const val EXPOSURE_P_1_0d0: Int = 0x08     // +1    ;
    const val EXPOSURE_P_0_2d3: Int = 0x05     // +2/3  ;
    const val EXPOSURE_P_0_1d2: Int = 0x04     // +1/2  ;
    const val EXPOSURE_P_0_1d3: Int = 0x03     // +1/3  ;
    const val EXPOSURE_0: Int = 0x00          // 0;
    const val EXPOSURE_N_0_1d3: Int = 0xfd     // -1/3  ;
    const val EXPOSURE_N_0_1d2: Int = 0xfc     // -1/2  ;
    const val EXPOSURE_N_0_2d3: Int = 0xfb     // -2/3  ;
    const val EXPOSURE_N_1_0d0: Int = 0xf8     // -1    0;
    const val EXPOSURE_N_1_1d30: Int = 0xf5 // -1 1/30;
    const val EXPOSURE_N_1_1d20: Int = 0xf4 // -1 1/20;
    const val EXPOSURE_N_1_2d30: Int = 0xf3 // -1 2/30;
    const val EXPOSURE_N_2_0d0: Int = 0xf0     // -2    0;
    const val EXPOSURE_N_2_1d30: Int = 0xed // -2 1/30;
    const val EXPOSURE_N_2_1d20: Int = 0xec // -2 1/20;
    const val EXPOSURE_N_2_2d30: Int = 0xeb // -2 2/30;
    const val EXPOSURE_N_3_0d0: Int = 0xe8     // -3    0;
    const val EXPOSURE_N_3_1d30: Int = 0xe5 // -3 1/30;
    const val EXPOSURE_N_3_2d30: Int = 0xe3 // -3 2/30;
    const val EXPOSURE_N_4_0d0: Int = 0xe0     // -4    0;
    const val EXPOSURE_N_4_1d30: Int = 0xdd // -4 1/30;
    const val EXPOSURE_N_4_2d30: Int = 0xdb // -4 2/30;
    const val EXPOSURE_N_5_0d0: Int = 0xd8     // -5    0;

    /**
     * Eos White Balance
     */
    const val AutoWhiteBalance: Int = 0
    const val Daylight: Int = 1
    const val Clouds: Int = 2
    const val Tungsteen: Int = 3
    const val Fluoriscent: Int = 4
    const val Strobe: Int = 5
    const val WhitePaper: Int = 6
    const val Shade: Int = 7

    /**
     * User picture style type
     */
    const val PictureStyle_User1: Int = 0x21
    const val PictureStyle_User2: Int = 0x22
    const val PictureStyle_User3: Int = 0x23

    const val PictureStyle_Standard: Int = 0x81
    const val PictureStyle_Portrait: Int = 0x82
    const val PictureStyle_Landscape: Int = 0x83
    const val PictureStyle_Neutral: Int = 0x84
    const val PictureStyle_Faithful: Int = 0x85
    const val PictureStyle_Monochrome: Int = 0x86

    /**
     * User picture style type
     */
    const val EosPropPictureStyleUserTypeUser1: Int = 0x21
    const val EosPropPictureStyleUserTypeUser2: Int = 0x22
    const val EosPropPictureStyleUserTypeUser3: Int = 0x23

    const val EosPropPictureStyleUserTypeStandard: Int = 0x81
    const val EosPropPictureStyleUserTypePortrait: Int = 0x82
    const val EosPropPictureStyleUserTypeLandscape: Int = 0x83
    const val EosPropPictureStyleUserTypeNeutral: Int = 0x84
    const val EosPropPictureStyleUserTypeFaithful: Int = 0x85
    const val EosPropPictureStyleUserTypeMonochrome: Int = 0x86


    /**
     * Image formats
     */
    const val ImageFormatEXIF_JPEG: Int = 0x3801
    const val ImageFormatTIFF_EP: Int = 0x3802
    const val ImageFormatFlashPix: Int = 0x3803
    const val ImageFormatBMP: Int = 0x3804
    const val ImageFormatCIFF: Int = 0x3805
    const val ImageFormatUndefined_0x3806: Int = 0x3806
    const val ImageFormatGIF: Int = 0x3807
    const val ImageFormatJFIF: Int = 0x3808
    const val ImageFormatPCD: Int = 0x3809
    const val ImageFormatPICT: Int = 0x380A
    const val ImageFormatPNG: Int = 0x380B
    const val ImageFormatUndefined_0x380C: Int = 0x380C
    const val ImageFormatTIFF: Int = 0x380D
    const val ImageFormatTIFF_IT: Int = 0x380E
    const val ImageFormatJP2: Int = 0x380F
    const val ImageFormatJPX: Int = 0x3810
    /* ptp v1.1 has only DNG new */
    const val ImageFormatDNG: Int = 0x3811
    /* Eastman Kodak extension ancillary format */
    const val ImageFormatEK_M3U: Int = 0xB002
    /* Canon extension */
    const val ImageFormatCANON_CRW: Int = 0xB101
    const val ImageFormatCANON_CRW3: Int = 0xB103
    const val ImageFormatCANON_MOV: Int = 0xB104
    /* CHDK specific raw mode */
    const val ImageFormatCANON_CHDK_CRW: Int = 0xB1FF
    /* MTP extensions */
    const val ImageFormatMTP_MediaCard: Int = 0xB211
    const val ImageFormatMTP_MediaCardGroup: Int = 0xb212
    const val ImageFormatMTP_Encounter: Int = 0xb213
    const val ImageFormatMTP_EncounterBox: Int = 0xb214
    const val ImageFormatMTP_M4A: Int = 0xb215
    const val ImageFormatMTP_ZUNEUNDEFINED: Int = 0xb217
    const val ImageFormatMTP_Firmware: Int = 0xb802
    const val ImageFormatMTP_WindowsImageFormat: Int = 0xb881
    const val ImageFormatMTP_UndefinedAudio: Int = 0xb900
    const val ImageFormatMTP_WMA: Int = 0xb901
    const val ImageFormatMTP_OGG: Int = 0xb902
    const val ImageFormatMTP_AAC: Int = 0xb903
    const val ImageFormatMTP_AudibleCodec: Int = 0xb904
    const val ImageFormatMTP_FLAC: Int = 0xb906
    const val ImageFormatMTP_SamsungPlaylist: Int = 0xb909
    const val ImageFormatMTP_UndefinedVideo: Int = 0xb980
    const val ImageFormatMTP_WMV: Int = 0xb981
    const val ImageFormatMTP_MP4: Int = 0xb982
    const val ImageFormatMTP_MP2: Int = 0xb983
    const val ImageFormatMTP_3GP: Int = 0xb984
    const val ImageFormatMTP_UndefinedCollection: Int = 0xba00
    const val ImageFormatMTP_AbstractMultimediaAlbum: Int = 0xba01
    const val ImageFormatMTP_AbstractImageAlbum: Int = 0xba02
    const val ImageFormatMTP_AbstractAudioAlbum: Int = 0xba03
    const val ImageFormatMTP_AbstractVideoAlbum: Int = 0xba04
    const val ImageFormatMTP_AbstractAudioVideoPlaylist: Int = 0xba05
    const val ImageFormatMTP_AbstractContactGroup: Int = 0xba06
    const val ImageFormatMTP_AbstractMessageFolder: Int = 0xba07
    const val ImageFormatMTP_AbstractChapteredProduction: Int = 0xba08
    const val ImageFormatMTP_AbstractAudioPlaylist: Int = 0xba09
    const val ImageFormatMTP_AbstractVideoPlaylist: Int = 0xba0a
    const val ImageFormatMTP_AbstractMediacast: Int = 0xba0b
    const val ImageFormatMTP_WPLPlaylist: Int = 0xba10
    const val ImageFormatMTP_M3UPlaylist: Int = 0xba11
    const val ImageFormatMTP_MPLPlaylist: Int = 0xba12
    const val ImageFormatMTP_ASXPlaylist: Int = 0xba13
    const val ImageFormatMTP_PLSPlaylist: Int = 0xba14
    const val ImageFormatMTP_UndefinedDocument: Int = 0xba80
    const val ImageFormatMTP_AbstractDocument: Int = 0xba81
    const val ImageFormatMTP_XMLDocument: Int = 0xba82
    const val ImageFormatMTP_MSWordDocument: Int = 0xba83
    const val ImageFormatMTP_MHTCompiledHTMLDocument: Int = 0xba84
    const val ImageFormatMTP_MSExcelSpreadsheetXLS: Int = 0xba85
    const val ImageFormatMTP_MSPowerpointPresentationPPT: Int = 0xba86
    const val ImageFormatMTP_UndefinedMessage: Int = 0xbb00
    const val ImageFormatMTP_AbstractMessage: Int = 0xbb01
    const val ImageFormatMTP_UndefinedContact: Int = 0xbb80
    const val ImageFormatMTP_AbstractContact: Int = 0xbb81
    const val ImageFormatMTP_vCard2: Int = 0xbb82
    const val ImageFormatMTP_vCard3: Int = 0xbb83
    const val ImageFormatMTP_UndefinedCalendarItem: Int = 0xbe00
    const val ImageFormatMTP_AbstractCalendarItem: Int = 0xbe01
    const val ImageFormatMTP_vCalendar1: Int = 0xbe02
    const val ImageFormatMTP_vCalendar2: Int = 0xbe03
    const val ImageFormatMTP_UndefinedWindowsExecutable: Int = 0xbe80
    const val ImageFormatMTP_MediaCast: Int = 0xbe81
    const val ImageFormatMTP_Section: Int = 0xbe82

    const val driveSingleShot: Int = 0x0000
    const val driveContinuous: Int = 0x0001
    const val driveContinuousHighSpeed: Int = 0x0004
    const val driveContinuousLowSpeed: Int = 0x0005
    const val driveTimer10Sec: Int = 0x0010
    const val driveTimer2Sec: Int = 0x0010
    const val driveSingleSilent: Int = 0x0013
    const val driveContinuousSilent: Int = 0x0014

    const val pictSFine: Int = 0x00000321
    const val pictSNormal: Int = 0x00000221
    const val pictMFine: Int = 0x00000311
    const val pictMNormal: Int = 0x00000211
    const val pictLFine: Int = 0x00000301
    const val pictLNormal: Int = 0x00000201
    const val pictRaw: Int = 0x00000406
    const val pictRawPlusL: Int = 0x00301406

    const val centerWeightedMetering: Int = 0
    const val SpotMetering: Int = 1 //Good
    const val AverageMetering: Int = 2
    const val EvaluativeMetering: Int = 3 //Good
    const val PartialMetering: Int = 4 //Good
    const val CenterWeightedAverageMetering: Int = 5 //Good
    const val spotMeteringInterlockedWithAFframeMetering: Int = 6
    const val multiSpotMetering: Int = 7
}
