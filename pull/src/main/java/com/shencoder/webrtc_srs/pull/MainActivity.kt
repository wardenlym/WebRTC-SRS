package com.shencoder.webrtc_srs.pull

import android.content.Context
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.elvishew.xlog.XLog
import com.shencoder.mvvmkit.base.view.BaseSupportActivity
import com.shencoder.mvvmkit.base.viewmodel.DefaultViewModel
import com.shencoder.mvvmkit.util.MoshiUtil
import com.shencoder.mvvmkit.util.toastError
import com.shencoder.mvvmkit.util.toastWarning
import com.shencoder.webrtc_srs.pull.constant.Constant
import com.shencoder.webrtc_srs.pull.databinding.ActivityMainBinding
import com.shencoder.webrtc_srs.pull.http.RetrofitClient
import com.shencoder.webrtc_srs.pull.http.bean.SrsRequestBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.webrtc.*

class MainActivity : BaseSupportActivity<DefaultViewModel, ActivityMainBinding>() {
    private val retrofitClient by inject<RetrofitClient>()

    private val eglBaseContext = EglBase.create().eglBaseContext
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var pullConnection: PeerConnection? = null
    private var videoTrack: VideoTrack? = null
    private var cameraVideoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var peerConnection: PeerConnection? = null

    private companion object {
        private const val URL =
            "webrtc://${Constant.SRS_SERVER_IP}/live/livestream"

        private const val URLuser1 =
            "webrtc://${Constant.SRS_SERVER_IP}/live/user1"
        private const val URLuser2 =
            "webrtc://${Constant.SRS_SERVER_IP}/live/user2"
    }

    override fun getLayoutId(): Int {
        return R.layout.activity_main
    }

    override fun injectViewModel(): Lazy<DefaultViewModel> {
        return viewModel()
    }

    override fun getViewModelId(): Int {
        return 0
    }

    override fun initView() {
        mBinding.etUrl.setText(URLuser1)
        mBinding.etUrl2.setText(URLuser2)

        mBinding.svr.init(eglBaseContext, null)
        mBinding.btnPull.setOnClickListener {
            val url = mBinding.etUrl.text.toString().trim()
            if (url.isBlank()) {
                toastWarning("请输入拉流地址")
                return@setOnClickListener
            }
            initPullRTC(url)
        }
        mBinding.svrpush.init(eglBaseContext, null)
        mBinding.btnPush.setOnClickListener {
            val url = mBinding.etUrl2.text.toString().trim()
            if (url.isBlank()) {
                toastWarning("请输入推流地址")
                return@setOnClickListener
            }
            initPushRTC(url)
        }


    }

    private fun createVideoCapture(context: Context): CameraVideoCapturer? {
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            Camera1Enumerator()
        }
        for (name in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(name)) {
                return enumerator.createCapturer(name, null)
            }
        }
        for (name in enumerator.deviceNames) {
            if (enumerator.isBackFacing(name)) {
                return enumerator.createCapturer(name, null)
            }
        }
        return null
    }

    private fun initPushRTC(url: String) {
        val createAudioSource = peerConnectionFactory.createAudioSource(createAudioConstraints())
        val audioTrack =
            peerConnectionFactory.createAudioTrack("local_audio_track", createAudioSource)

        cameraVideoCapturer = createVideoCapture(this)
        cameraVideoCapturer?.let { capture ->
            val videoSource = peerConnectionFactory.createVideoSource(capture.isScreencast)
            videoTrack =
                peerConnectionFactory.createVideoTrack("local_video_track", videoSource).apply {
                    addSink(mBinding.svrpush)
                }
            surfaceTextureHelper =
                SurfaceTextureHelper.create("surface_texture_thread", eglBaseContext)
            capture.initialize(surfaceTextureHelper, this, videoSource.capturerObserver)
            capture.startCapture(640, 480, 20)
        }

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            PeerConnectionObserver()
        )?.apply {
            videoTrack?.let {
                addTransceiver(
                    it,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                )
            }
            addTransceiver(
                audioTrack,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
            )
        }

        peerConnection?.let { connection ->
            connection.createOffer(object : SdpAdapter("createOffer") {
                override fun onCreateSuccess(description: SessionDescription?) {
                    super.onCreateSuccess(description)
                    description?.let {
                        if (it.type == SessionDescription.Type.OFFER) {
                            val offerSdp = it.description
                            connection.setLocalDescription(SdpAdapter("setLocalDescription"), it)

                            val srsBean = SrsRequestBean(
                                it.description,
                                url
                            )

                            val toJson = MoshiUtil.toJson(srsBean)
                            println("push-json:${toJson}")
                            //请求srs
                            lifecycleScope.launch {
                                val result = try {
                                    withContext(Dispatchers.IO) {
                                        retrofitClient.apiService.publish(srsBean)
                                    }
                                } catch (e: Exception) {
                                    println("push网络请求出错：${e.printStackTrace()}")
                                    toastError("push网络请求出错：${e.printStackTrace()}")
                                    null
                                }

                                result?.let { bean ->
                                    if (bean.code == 0) {
                                        XLog.i("push网络成功，code：${bean.code}")
                                        val remoteSdp = SessionDescription(
                                            SessionDescription.Type.ANSWER,
                                            convertAnswerSdp(offerSdp, bean.sdp)
                                        )
                                        connection.setRemoteDescription(
                                            SdpAdapter("setRemoteDescription"),
                                            remoteSdp
                                        )
                                    } else {
                                        XLog.w("push网络请求失败，code：${bean.code}")
                                        toastWarning("push网络请求失败，code：${bean.code}")
                                    }
                                }
                            }
                        }
                    }
                }
            }, MediaConstraints())
        }
    }

    private fun createAudioConstraints(): MediaConstraints {
        val audioConstraints = MediaConstraints()
        //回声消除
        audioConstraints.mandatory.add(
            MediaConstraints.KeyValuePair(
                "googEchoCancellation",
                "true"
            )
        )
        //自动增益
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        //高音过滤
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        //噪音处理
        audioConstraints.mandatory.add(
            MediaConstraints.KeyValuePair(
                "googNoiseSuppression",
                "true"
            )
        )
        return audioConstraints
    }

    override fun initData(savedInstanceState: Bundle?) {
        val options = PeerConnectionFactory.Options()
        val encoderFactory = DefaultVideoEncoderFactory(eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBaseContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private fun initPullRTC(url: String) {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        /*
         <p>For users who wish to send multiple audio/video streams and need to stay interoperable with legacy WebRTC implementations, specify PLAN_B.
         <p>For users who wish to send multiple audio/video streams and/or wish to use the new RtpTransceiver API, specify UNIFIED_PLAN.
         */
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        val peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            object : PeerConnectionObserver() {
                override fun onAddStream(mediaStream: MediaStream?) {
                    super.onAddStream(mediaStream)
                    mediaStream?.let {
                        if (it.videoTracks.isEmpty().not()) {
                            it.videoTracks[0].addSink(mBinding.svr)
                        }
                    }
                }
            })?.apply {
            addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        }

        peerConnection?.let { connection ->
            connection.createOffer(object : SdpAdapter("createOffer") {
                override fun onCreateSuccess(description: SessionDescription?) {
                    super.onCreateSuccess(description)
                    description?.let {
                        if (it.type == SessionDescription.Type.OFFER) {
                            val offerSdp = it.description
                            connection.setLocalDescription(SdpAdapter("setLocalDescription"), it)
                            val srsBean = SrsRequestBean(
                                it.description,
                                url
                            )
                            val toJson = MoshiUtil.toJson(srsBean)
                            println("pull-json:${toJson}")
                            //请求srs
                            lifecycleScope.launch {
                                val result = try {
                                    withContext(Dispatchers.IO) {
                                        retrofitClient.apiService.play(srsBean)
                                    }
                                } catch (e: Exception) {
                                    println("pull网络请求出错：${e.printStackTrace()}")
                                    toastError("pull网络请求出错：${e.printStackTrace()}")
                                    null
                                }

                                result?.let { bean ->
                                    if (bean.code == 0) {
                                        XLog.i("pull网络成功，code：${bean.code}")
                                        val remoteSdp = SessionDescription(
                                            SessionDescription.Type.ANSWER,
                                            convertAnswerSdp(offerSdp, bean.sdp)
                                        )
                                        connection.setRemoteDescription(
                                            SdpAdapter("setRemoteDescription"),
                                            remoteSdp
                                        )
                                    } else {
                                        XLog.w("pull网络请求失败，code：${bean.code}")
                                    }
                                }
                            }
                        }
                    }
                }
            }, MediaConstraints())
        }

        pullConnection = peerConnection
    }

    /**
     * 转换AnswerSdp
     * @param offerSdp offerSdp：创建offer时生成的sdp
     * @param answerSdp answerSdp：网络请求srs服务器返回的sdp
     * @return 转换后的AnswerSdp
     */
    private fun convertAnswerSdp(offerSdp: String, answerSdp: String?): String {
        if (answerSdp.isNullOrBlank()) {
            return ""
        }
        val indexOfOfferVideo = offerSdp.indexOf("m=video")
        val indexOfOfferAudio = offerSdp.indexOf("m=audio")
        if (indexOfOfferVideo == -1 || indexOfOfferAudio == -1) {
            return answerSdp
        }
        val indexOfAnswerVideo = answerSdp.indexOf("m=video")
        val indexOfAnswerAudio = answerSdp.indexOf("m=audio")
        if (indexOfAnswerVideo == -1 || indexOfAnswerAudio == -1) {
            return answerSdp
        }

        val isFirstOfferVideo = indexOfOfferVideo < indexOfOfferAudio
        val isFirstAnswerVideo = indexOfAnswerVideo < indexOfAnswerAudio
        return if (isFirstOfferVideo == isFirstAnswerVideo) {
            //顺序一致
            answerSdp
        } else {
            //需要调换顺序
            buildString {
                append(answerSdp.substring(0, indexOfAnswerVideo.coerceAtMost(indexOfAnswerAudio)))
                append(
                    answerSdp.substring(
                        indexOfAnswerVideo.coerceAtLeast(indexOfOfferVideo),
                        answerSdp.length
                    )
                )
                append(
                    answerSdp.substring(
                        indexOfAnswerVideo.coerceAtMost(indexOfAnswerAudio),
                        indexOfAnswerVideo.coerceAtLeast(indexOfOfferVideo)
                    )
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mBinding.svr.release()
        mBinding.svrpush.release()
        pullConnection?.dispose()
        peerConnectionFactory.dispose()
    }
}