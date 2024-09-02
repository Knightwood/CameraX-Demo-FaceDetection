package com.kiylx.camerax_lib.main.ui

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import androidx.annotation.CallSuper
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updatePadding
import com.blankj.utilcode.util.LogUtils
import com.kiylx.camerax_lib.R
import com.kiylx.camerax_lib.databinding.BottomControllerPanelBinding
import com.kiylx.camerax_lib.databinding.FragmentCameraxExampleBinding
import com.kiylx.camerax_lib.main.buttons.DefaultCaptureListener
import com.kiylx.camerax_lib.main.manager.CAMERA_CONFIG
import com.kiylx.camerax_lib.main.manager.CameraXManager
import com.kiylx.camerax_lib.main.manager.analyer.graphic_view.GraphicOverlayView
import com.kiylx.camerax_lib.main.manager.model.CaptureResultListener
import com.kiylx.camerax_lib.main.manager.model.ManagerConfig
import com.kiylx.camerax_lib.view.ControllerPanel
import com.kiylx.camerax_lib.view.FlashButton
import com.kiylx.camerax_lib.view.FocusImageView
import com.kiylx.camerax_lib.view.IControllerPanel
import com.kiylx.camerax_lib.view.IControllerPanelEventListener
import com.kiylx.camerax_lib.view.IFlashButtonState

/**
 * Base camera x fragment
 *
 * 如果要按下音量减时，触发拍照，需要activity复制如下代码
 *
 * ```
 *     /**
 *      * 音量减按钮触发拍照，如果需要复制这份代码就可以
 *      *
 *      * When key down event is triggered, relay it via local broadcast so
 *      * fragments can handle it
 *      */
 *     override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
 *         return when (keyCode) {
 *             KeyEvent.KEYCODE_VOLUME_DOWN -> {
 *                 val intent = Intent(KEY_CAMERA_EVENT_ACTION).apply {
 *                     putExtra(
 *                         KEY_CAMERA_EVENT_EXTRA,
 *                         keyCode
 *                     )
 *                 }
 *                 LocalBroadcastManager.getInstance(this.requireContext()).sendBroadcast(intent)
 *                 true
 *             }
 *
 *             else -> super.onKeyDown(keyCode, event)
 *         }
 *     }
 * ```
 *
 * @constructor Create empty Base camera x fragment
 */
class BaseCameraXViewHolder(v: View) : ViewHolder(v) {
    val focusView: FocusImageView? = findViewNull(R.id.focus_view)
    val graphicOverlayView :GraphicOverlayView? =findViewNull<GraphicOverlayView>(R.id.graphicOverlay_finder)
    val flashLightView :FlashButton? =findViewNull(R.id.btn_flush_switch)
}

open class BaseCameraXFragment : CameraXFragment() {
    /** 简化功能调用，复杂功能直接使用cameraHolder或cameraXFragment */
    val cameraXF: CameraXF by lazy { CameraXF(this) }//

    lateinit var mBaseHandler: Handler
    lateinit var xViewHolder: BaseCameraXViewHolder
    lateinit var controllerPanel: IControllerPanel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBaseHandler = Handler(Looper.getMainLooper())
    }

    open fun edge2edge(window: Window) {
        // 1. 使内容区域全屏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 2. 设置 System bar 透明
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        xViewHolder.v.doOnAttach {
            val insets = ViewCompat.getRootWindowInsets(window.decorView)
                ?.getInsets(WindowInsetsCompat.Type.systemBars()) ?: Insets.NONE
            xViewHolder.findViewNull<LinearLayout>(R.id.top_container)
                ?.updatePadding(top = insets.top, bottom = insets.bottom)
        }
    }


    override fun provideView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        //自定义的布局文件
        val viewRoot = FragmentCameraxExampleBinding.inflate(inflater, container, false)
        //处理merge标签
        val controllerPanelBinding = BottomControllerPanelBinding.bind(viewRoot.root)
        //底部控制面板交互功能
        controllerPanel = ControllerPanel(requireActivity(), controllerPanelBinding)
        //初始化
        controllerPanel.initAll()
        //事件监听
        controllerPanel.eventListener = object : IControllerPanelEventListener {
            override fun switchCamera() {
                //要保持闪光灯上一次的模式
                if (canSwitchCamera()) {
                    this@BaseCameraXFragment.switchCamera()
                }
            }

            override fun switchCaptureBtnType(type: Int) {
                //这里可以监听到切换按钮的模式
            }
        }
        //拍照，拍视频的UI 操作的各种状态处理
        (controllerPanel as ControllerPanel).setCaptureListener(object : DefaultCaptureListener() {
            override fun takePictures() {
                //相机信息打印
//                cameraHolder.queryAllCameraInfo()
                takePhoto()
            }

            //开始录制视频
            override fun recordStart() {
                LogUtils.dTag("录制activity", "开始")
                startRecord()
                controllerPanel.showHideCameraSwitch(true)
                //录制视频时隐藏摄像头切换
                if (!cameraConfig.recordConfig.asPersistentRecording) {
                    controllerPanel.showHideUseCaseSwitch(true)
                }
            }

            //1. 录制视频到达预定的时长结束
            //2. 或者手动按下按钮录制结束
            override fun recordShouldEnd(time: Long) {
                LogUtils.dTag("录制activity", "停止")
                stopRecord(time)
                controllerPanel.showHideCameraSwitch(false)
                controllerPanel.showHideUseCaseSwitch(false)
            }
        })

        super.onCreate(savedInstanceState)
        return viewRoot.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        xViewHolder = BaseCameraXViewHolder(view)
        super.onViewCreated(view, savedInstanceState)
        configFlashBtn()
    }

    open fun configFlashBtn() { //闪光灯按钮
        xViewHolder.flashLightView?.stateInference = IFlashButtonState {
            cameraXF.setFlashMode(it)
        }
    }

    //相机管理器初始化之后
    @CallSuper
    override fun initCameraFinished(cameraXManager: CameraXManager) {
        //focusView初始化触摸对焦
        xViewHolder.focusView?.let { this.setupTouchFocus(it) }
    }

    @CallSuper
    override fun switchCamera(lensFacing: Int) {
        xViewHolder.graphicOverlayView?.toggleSelector(lensFacing)
    }

    @CallSuper
    override fun cameraRotationChanged(rotation: Int, angle: Int) {
        xViewHolder.graphicOverlayView?.rotationChanged(rotation, angle)
    }

    override fun onStop() {
        super.onStop()
        mBaseHandler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val TAG = "BaseRecordVideo"

        /**
         * @param cameraConfig [ManagerConfig]
         * @return A new instance of fragment NewCameraXFragment.
         */
        @JvmStatic
        fun newInstance(
            cameraConfig: ManagerConfig,
            eventListener: CameraXFragmentEventListener? = null,
            captureResultListener: CaptureResultListener? = null,
        ) = BaseCameraXFragment().apply {
            this.arguments = Bundle().apply {
                putParcelable(CAMERA_CONFIG, cameraConfig)
            }
            this.eventListener = eventListener
            this.captureResultListener = captureResultListener
        }
    }
}