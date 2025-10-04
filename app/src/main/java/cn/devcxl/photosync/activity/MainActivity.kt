package cn.devcxl.photosync.activity

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
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
    }


}