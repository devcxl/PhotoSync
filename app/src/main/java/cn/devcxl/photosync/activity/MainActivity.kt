package cn.devcxl.photosync.activity

import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import cn.devcxl.photosync.databinding.ActivityMainBinding


class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 查看USB设备是否连接 如果已经连接 则直接进入TakingPhotoActivity
        val usbManager = getSystemService(USB_SERVICE) as android.hardware.usb.UsbManager
        val deviceList: HashMap<String, UsbDevice> = usbManager.deviceList
        if (deviceList.isNotEmpty()) {
            Log.d(TAG, "USB设备已连接，设备列表：$deviceList")
            // 直接进入TakingPhotoActivity
            val intent = android.content.Intent(this, TakingPhotoActivity::class.java)
            startActivity(intent)
            finish()
        } else {
            Log.d(TAG, "没有检测到USB设备")
            // 显示等待连接界面
            binding.mainText.text = "请连接相机设备"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
    }

}