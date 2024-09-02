package com.kiylx.camerax_lib.main.manager

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.CallSuper
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Lifecycle.Event.ON_CREATE
import androidx.lifecycle.Lifecycle.Event.ON_DESTROY
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import com.kiylx.camerax_lib.main.camera2.Camera2Helper
import com.kiylx.camerax_lib.main.camera2.FormatItem
import com.kiylx.camerax_lib.main.manager.ManagerUtil.Companion.hasBackCamera
import com.kiylx.camerax_lib.main.manager.ManagerUtil.Companion.hasFrontCamera
import com.kiylx.camerax_lib.main.manager.model.CameraManagerEventListener
import com.kiylx.camerax_lib.main.manager.model.DisplayRotation
import com.kiylx.camerax_lib.main.manager.model.FlashModel
import com.kiylx.camerax_lib.main.manager.model.ManagerConfig
import com.kiylx.camerax_lib.main.manager.model.SensorRotation
import com.kiylx.camerax_lib.main.manager.model.UseCaseHexStatus
import com.kiylx.camerax_lib.main.manager.model.UseCaseMode
import com.kiylx.camerax_lib.main.manager.util.HexStatusManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


/*
如果视图是强制竖屏或横屏，就不该旋转预览视图
如果是自由旋转，就应该旋转预览视图
*/
abstract class CameraXManager(
    val cameraPreview: PreviewView,
    var cameraConfig: ManagerConfig,
    var cameraListener: CameraManagerEventListener? = null,//用于在这里做某些处理后，通知外层
) : LifecycleEventObserver {
    internal lateinit var context: FragmentActivity

    //相机相关
    var preview: Preview? = null
        internal set
    var camera: Camera? = null
        internal set
        get() {
            if (concurrentCamera != null) {
                if (currentUseCamera != null) {
                    return currentUseCamera
                } else {
                    return concurrentCamera!!.cameras[0]
                }
            } else {
                return field
            }
        }

    /**
     * 同时使用多个相机模式
     */
    private var concurrentCamera: ConcurrentCamera? = null

    /**
     * 同时使用多个相机时，通过此变量切换操纵的相机
     */
    private var currentUseCamera: Camera? = null

    lateinit var cameraProvider: ProcessCameraProvider
        internal set
    var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    //三种实例
    lateinit var imageAnalyzer: ImageAnalysis
    lateinit var imageCapture: ImageCapture
    lateinit var videoCapture: VideoCapture<Recorder> //新版本录像用例

    //状态
    lateinit var cameraSelector: CameraSelector
        internal set
    var lensFacing = CameraSelector.LENS_FACING_BACK

//    var currentStatus: Int = ManagerRunningState.IDLE//指示当前状态
//    var whichInstance: WhichInstanceBind = WhichInstanceBind.NONE//记录当前绑定的哪一个相机实例

    var useCaseBundle: Int = UseCaseHexStatus.USE_CASE_NONE //记录当前绑定的用例
    var useCaseRunning: Boolean = false

    var deviceOrientationMode = 0

    //传感器角度值
    private var sensorRotation: SensorRotation? = null
    private val orientationEventListener = object : SensorRotation.RotationChangeListener {
        //使用 OrientationEventListener
        //可以让您随着设备屏幕方向的变化持续更新相机用例的目标旋转角度。
        //方向传感器带来的设备旋转角度
        override fun angleChanged(rotation: Int, angle: Int) {
            sensorAngleChanged(rotation, angle)
        }

        override fun rotationChanged(rotation: Int) {
            updateCaseRotation(rotation)
        }
    }

    //display的角度变化
    private var displayRotation: DisplayRotation? = null
    private val displayRotationListener = object : DisplayRotation.DisplayRotationChangeListener {
        override fun rotationChanged(rotation: Int) {
            //使用 DisplayListener 可以让您在特定情况下更新相机用例的目标旋转角度，
            //例如在设备旋转了 180 度后系统没有销毁并重新创建 Activity 的情况下。
            preview?.targetRotation = rotation
            //updateCaseRotation(rotation)
        }
    }

    //其他
    lateinit var handler: Handler
    private var myZoomValue: Float = 1F//缩放值
    var zoomState: LiveData<ZoomState>? = null
    private var lastStreamState = PreviewView.StreamState.IDLE//预览画面的状态

    fun bindLifecycle(fragmentActivity: FragmentActivity) {
        this.context = fragmentActivity
        handler = Handler(context.mainLooper)
        context.lifecycle.addObserver(this)
    }

    /**
     * 设置曝光补偿
     */
    fun setExposure(value: Int) {
        try {
            camera?.cameraControl?.setExposureCompensationIndex(value)
        } catch (e: java.lang.Exception) {
            Log.e(TAG, e.message, e)//错误日志打印
        }
    }

    /**
     * 查询曝光补偿范围
     */
    fun queryExposureRange(): Range<Int> =
        camera?.cameraInfo?.exposureState?.exposureCompensationRange ?: Range(0, 0)

    /**
     * 曝光值(EV)=(曝光补偿)exposure_compensation_index * (步长)compensation_step
     *
     * 注： compensation_step 的 step_size 取值通常为 ⅓ 或者 ½，较少情况下，有些设备可能会支持 1 或者甚至 ¼。
     * 所能支持的最大曝光值一般是 2 EV 或者 3 EV。
     */
    fun queryExposureState() = camera?.cameraInfo?.exposureState

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            ON_CREATE -> {
                initManager()
            }

            ON_DESTROY -> {
                destroy()
            }

            else -> {}
        }
    }

    private fun initManager() = checkPerm {//权限全部通过后执行下面代码
        cameraListener?.initCameraStart(this)
        initCamera()
        deviceOrientationMode = context.resources.configuration.orientation
    }

    private fun destroy() {
        cameraExecutor.awaitTermination(1, TimeUnit.SECONDS)
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * 初始化相机参数
     */
    @SuppressLint("RestrictedApi")
    private fun initCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            Runnable {
                cameraProvider = cameraProviderFuture.get()

                //设置默认摄像头，默认是后置的摄像头，没有后面的再切换到前面的来
                parseCameraSelector(cameraProvider)
                initUseCase()
                //配置传感器方向监听
                if (sensorRotation == null) {
                    sensorRotation = SensorRotation(context).apply {
                        listener = orientationEventListener
                    }
                }
                //配置屏幕方向监听
                if (displayRotation == null) {
                    displayRotation = DisplayRotation(context, displayRotationListener)
                }
                //setUpPinchToZoom()
                setCamera(cameraConfig.useCaseMode)//绑定实例

                // Attach the viewfinder's surface provider to preview use case
                preview?.setSurfaceProvider(cameraPreview.surfaceProvider)
                cameraPreview.previewStreamState.observe(
                    context
                ) { streamState ->
                    //有可能会多次调用，那怎么办呢？结合规律，他会多次调用PreviewView.StreamState.IDLE或者PreviewView.StreamState.STREAMING
                    //所以很简单，我们记录上一次的状态如果是IDLE，而当前这一次回调是STREAMING，那么就是成功切换后的第一次调用。就只会执行一次
                    if (lastStreamState == PreviewView.StreamState.IDLE && streamState == PreviewView.StreamState.STREAMING) {
                        cameraListener?.previewStreamStart()
                    }
                    lastStreamState = streamState
                }
                //cameraProviderFuture.addListener是异步的，必须在这里调用才是正确的时机
                cameraListener?.initCameraFinished(this)
            },
            ContextCompat.getMainExecutor(context)
        )
        cameraListener?.initCameraStarting(this)//(2)表明正在初始化
        // cameraListener?.initCameraFinished(this) (1) 在这里调用会导致时机不正确，因为cameraProviderFuture.addListener是异步的
    }

    /**
     * 重新构建用例，并且绑定用例
     */
    @CallSuper
    open fun reBindUseCase() {
        initUseCase()
        setCamera(cameraConfig.useCaseMode)
    }

    /**
     * 初始化全部的用例
     */
    fun initUseCase() {
        val screenAspectRatio: Int//屏幕缩放比率
        val size: Size//屏幕尺寸
        var rotation: Int//屏幕方向

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics2 = context.windowManager.currentWindowMetrics
            screenAspectRatio =
                ManagerUtil.aspectRatio(metrics2.bounds.width(), metrics2.bounds.height())
            size = Size(metrics2.bounds.width(), metrics2.bounds.height())
            rotation = context.display!!.rotation
        } else {
            if (cameraPreview.display == null) {
                screenAspectRatio =
                    ManagerUtil.aspectRatio(
                        cameraConfig.size.width,
                        cameraConfig.size.height
                    )
                size = cameraConfig.size
                rotation = context.windowManager.defaultDisplay.rotation
            } else {
                val metrics = DisplayMetrics().also {
                    cameraPreview.display.getRealMetrics(it)
                }
                screenAspectRatio =
                    ManagerUtil.aspectRatio(metrics.widthPixels, metrics.heightPixels)
                size = Size(metrics.widthPixels, metrics.heightPixels)
                rotation = cameraPreview.display.rotation
            }
        }
        if (cameraConfig.rotation > 0) {
            rotation = cameraConfig.rotation
        }
        preview = UseCaseHolder.caseHelper.initPreView(
            cameraExecutor,
            screenAspectRatio,
            rotation,
            size,
            cameraConfig
        )
        imageAnalyzer = UseCaseHolder.caseHelper.initImageAnalyzer(
            cameraExecutor,
            screenAspectRatio,
            rotation,
            size,
            cameraConfig
        )
        imageCapture = UseCaseHolder.caseHelper.initImageCapture(
            cameraExecutor,
            screenAspectRatio,
            rotation,
            size,
            cameraConfig
        )
        videoCapture = UseCaseHolder.caseHelper.initVideoCapture(
            cameraExecutor,
            screenAspectRatio,
            rotation,
            size,
            cameraConfig
        )
//        //初始化自定义用例
//        UseCaseHolder.caseHelper.initCustomUseCaseList(
//            cameraExecutor,
//            screenAspectRatio,
//            rotation,
//            size,
//            cameraConfig,
//            selectAnalyzer()
//        )
    }

    /**
     * 绑定相机实例之类的
     *
     * @param useCaseBundle 用例组合 [ManagerConfig.useCaseMode]
     */
    internal fun setCamera(useCaseBundle: Int) {
        this.useCaseBundle = useCaseBundle
        useCaseRunning = false

        //再次重新绑定前应该先解绑 , imageAnalyzer
        cameraProvider.unbindAll()
        try {
            val lifeOwner = context
            try {
                //绑定自定义用例组合
                val arr = parseUseCase(status = useCaseBundle)
                camera = cameraProvider.bindToLifecycle(
                    lifeOwner,
                    cameraSelector,
                    *arr
                )
            } catch (exc: Exception) {
                Log.e(TAG, "custom use case binding failed", exc)
            }
            if (UseCaseHexStatus.canAnalyze(useCaseBundle)) {
                useCaseRunning = true
            }
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    /**
     * 查询所有可用的的相机信息
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun queryAllCameraInfo(): List<Camera2CameraInfo> {
        //或者
        return cameraProvider.availableCameraInfos.map { it: CameraInfo ->
            Camera2CameraInfo.from(it).also {
                Log.d(TAG, "available: ${it.cameraId}")
            }
        }
    }

    /**
     * 查询所有的相机信息
     */
    fun queryAllCameraInfo2(): List<FormatItem> {
        return Camera2Helper.enumerateCameras(context).onEach {
            Log.d(TAG, "cameraInfo:$it")
        }
    }

    /**
     * 多相机模式，例如同时绑定前置摄像头和后置摄像头。
     *
     * @param primaryCameraSelector 默认为后置摄像头
     * @param secondaryCameraSelector 默认为前置摄像头
     * @param useCaseBundle 用例组合
     * @param action 自定义用例组合的其他设置，例如设置视口，mEffects等。
     *
     * ```
     *     val primaryCamera: Camera = concurrentCamera.cameras[0]
     *     val secondaryCamera: Camera = concurrentCamera.cameras[1]
     * ```
     */
    fun useTwoCamera(
        primaryCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
        secondaryCameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA,
        useCaseBundle: Int = UseCaseMode.takePhoto,
        action: UseCaseGroup.Builder.() -> Unit = {},
    ): ConcurrentCamera {
        val useCaseGroup = UseCaseGroup.Builder().apply {
            parseUseCase(useCaseBundle).forEach {
                addUseCase(it)
            }
            this.action()
        }.build()


        // Build ConcurrentCameraConfig
        val primary = ConcurrentCamera.SingleCameraConfig(
            primaryCameraSelector,
            useCaseGroup,
            context
        )

        val secondary = ConcurrentCamera.SingleCameraConfig(
            secondaryCameraSelector,
            useCaseGroup,
            context
        )
        setMultiCamera(useCaseBundle, primary, secondary)
        return concurrentCamera!!
    }

    private fun setMultiCamera(
        useCaseBundle: Int,
        vararg cameraConfigs: ConcurrentCamera.SingleCameraConfig,
    ) {
        this.useCaseBundle = useCaseBundle
        useCaseRunning = false

        cameraProvider.unbindAll()
        try {
            concurrentCamera = cameraProvider.bindToLifecycle(cameraConfigs.toList())
            if (UseCaseHexStatus.canAnalyze(useCaseBundle)) {
                useCaseRunning = true
            }
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }


    /**
     * 使用多相机时，可以通过此方法切换将要操纵的相机
     */
    fun setCurrentUseCamera(camera: Camera) {
        concurrentCamera?.let {
            currentUseCamera = camera
        } ?: let {
            throw IllegalStateException("concurrentCamera is null")
        }
    }

    /**
     * 设置相机选择器。 筛选符合特定条件后置相机，前置相机，符合条件的外置相机等。 又或者不用逻辑相机，绑定某个物理相机
     *
     * ```
     *  val selector = CameraSelector.Builder()
     *             .requireLensFacing()
     *             .addCameraFilter()
     *             .setPhysicalCameraId()
     *             .build()
     * ```
     */
    @SuppressLint("RestrictedApi")
    fun setCameraSelector(selector: CameraSelector) {
        cameraSelector = selector
        lensFacing = selector.lensFacing ?: CameraSelector.LENS_FACING_UNKNOWN
    }

    /**
     * 获取可以读取相机状态的LiveData
     */
    fun getCameraStateLiveData(): LiveData<CameraState> {
        return camera!!.cameraInfo.cameraState
    }

    /**
     * 切换闪光模式, 打开，关闭，自动，长亮
     */
    fun setFlashMode(flashMode: Int): Int {
        if (flashMode == FlashModel.CAMERA_FLASH_ALL_ON) {
            cameraConfig.flashMode = FlashModel.CAMERA_FLASH_ALL_ON
            setFlashAlwaysOn(true)
        } else {
            cameraConfig.flashMode = flashMode
            setFlashAlwaysOn(false)
            imageCapture.flashMode = cameraConfig.flashMode
        }
        return cameraConfig.flashMode
    }

    /**
     * 设置闪关灯常亮
     */
    fun setFlashAlwaysOn(status: Boolean) {
        camera?.cameraControl?.enableTorch(status)
    }

    /**
     * provide bitmap from CameraPreview 实际上，从预览图象里拿到的bitmap，有时候是缺损的
     */
    fun provideBitmap(): Bitmap? {
        val value = cameraPreview.previewStreamState.value
        return if (value == PreviewView.StreamState.STREAMING)
            cameraPreview.bitmap
        else
            null
    }

    /**
     * 获取当前使用的摄像头，并设置变量
     */
    private fun parseCameraSelector(cameraProvider: ProcessCameraProvider) {
        lensFacing = when {
            cameraProvider.hasBackCamera() -> CameraSelector.LENS_FACING_BACK
            cameraProvider.hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
            else -> throw IllegalStateException("Back and front camera are unavailable")
        }
        setCameraSelector(lensFacing)
    }

    private fun setCameraSelector(lensFacing: Int) {
        cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
    }

    fun canSwitchCamera(): Boolean {
        return if (!this::cameraProvider.isInitialized)
            false
        else
            cameraProvider.hasBackCamera() && cameraProvider.hasFrontCamera()
    }

    /**
     * 切换前后摄像头
     */
    open fun switchCamera() {
        lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        setCameraSelector(lensFacing)
        setCamera(cameraConfig.useCaseMode)//绑定实例
        initZoomState()//翻转相机后，相机实例发生变化，所以重新获取缩放状态
        cameraListener?.switchCamera(lensFacing)
    }

    /**
     * 基于当前放大缩小
     */
    fun changeZoom() {
        // 放大缩小
        val currentZoomRatio = zoomState?.value!!.zoomRatio
        if (currentZoomRatio > zoomState?.value!!.minZoomRatio) {
            camera?.cameraControl!!.setLinearZoom(0f)
        } else {
            camera?.cameraControl!!.setLinearZoom(0.5f)
        }
    }

    /**
     * 基于当前的值再次缩放
     */
    fun zoomBasedOnCurrent(delta: Float) {
        zoomState?.value?.let {
            val currentZoomRatio = it.zoomRatio
            camera?.cameraControl!!.setZoomRatio(currentZoomRatio * delta)
        }
    }

    /**
     * 直接按照输入的参数进行缩放。
     */
    fun zoomDirectly(zoomValue: Float) {
        myZoomValue = zoomValue
        zoomState?.value?.let {
            camera?.cameraControl!!.setZoomRatio(zoomValue)
        }
    }

    /**
     * 初始化缩放状态 注意：反转相机后，因为camera实例发生变化，所以需要重新获取缩放状态。
     * 从拍照绑定切换到视频录制绑定时，因为经历解绑再绑定的过程，画面的缩放值会丢失，这是正常的。
     * 缩放值可以在绑定的时候就设置，不过目前我没有在绑定时重新设置缩放值，说不定以后会这么做
     */
    fun initZoomState() {
        zoomState = camera?.cameraInfo?.zoomState
    }

    /**
     * 返回缩放的最大值和最小值
     *
     * @return Pair<MAX,MIN>
     */
    fun getZoomRange(): Pair<Float?, Float?> {
        return Pair(zoomState?.value?.maxZoomRatio, zoomState?.value?.minZoomRatio)
    }

    /**
     * 更新用例的方向
     */
    @CallSuper
    open fun updateCaseRotation(rotation: Int) {
//        Log.e("旋转1", "$rotation")
        //横屏时，动态设置他们的方向
        imageAnalyzer.targetRotation = rotation
        imageCapture.targetRotation = rotation
    }

    fun parseUseCase(status: Int): Array<UseCase> {
        val list = mutableListOf<UseCase>()
        if (UseCaseHexStatus.canPreview(status)) {
            list.add(preview!!)
        }
        if (UseCaseHexStatus.canTakePicture(status)) {
            list.add(imageCapture)
        }
        if (UseCaseHexStatus.canTakeVideo(status)) {
            list.add(videoCapture)
        }
        if (UseCaseHexStatus.canAnalyze(status)) {
            //LEVEL_3（或更好）的相机设备才支持“预览”、“视频拍摄”、“图像分析” 三个同时绑定。这里暂定，未来可能会增加更多种绑定
            imageAnalyzer.setAnalyzer(cameraExecutor, selectAnalyzer())
            list.add(imageAnalyzer)
        }
        if (list.size > 3) {
            throw IllegalArgumentException("Unsupported combinations")
        }
        if (HexStatusManager.isAllContain(
                status,
                UseCaseHexStatus.USE_CASE_PREVIEW,
                UseCaseHexStatus.USE_CASE_IMAGE_CAPTURE,
                UseCaseHexStatus.USE_CASE_VIDEO_CAPTURE
            )
        ) {
            if (!checkDeviceCameraLevel(
                    cameraProvider,
                    cameraSelector,
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
                )
            )
                throw IllegalArgumentException("Unsupported combinations")
        }
        if (HexStatusManager.isAllContain(
                status,
                UseCaseHexStatus.USE_CASE_PREVIEW,
                UseCaseHexStatus.USE_CASE_VIDEO_CAPTURE,
                UseCaseHexStatus.USE_CASE_IMAGE_ANALYZE,
            )
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
                throw IllegalArgumentException("Unsupported combinations")
            if (!checkDeviceCameraLevel(
                    cameraProvider,
                    cameraSelector,
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3
                )
            )
                throw IllegalArgumentException("Unsupported combinations")
        }
        return list.toTypedArray()
    }

    /**
     * 方向传感器具体的角度值变化 方向是顺时针，0-359度
     */
    open fun sensorAngleChanged(rotation: Int, angle: Int) {
        cameraListener?.cameraRotationChanged(rotation, angle)
    }

    /**
     * 由子类提供分析器
     */
    abstract fun selectAnalyzer(): ImageAnalysis.Analyzer

    abstract fun checkPerm(block: () -> Unit)

    companion object {
        const val TAG = "tty1-相机管理器"

        /**
         * 检查某摄像头是否支持持某些特性，如果小于Android N
         */
        @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
        fun checkDeviceCameraLevel(
            cameraProvider: ProcessCameraProvider,
            cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
            level: Int = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
        ): Boolean {
            return cameraSelector
                .filter(cameraProvider.availableCameraInfos)
                .firstOrNull()
                ?.let { Camera2CameraInfo.from(it) }
                ?.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ==
                    level
        }
    }
}
