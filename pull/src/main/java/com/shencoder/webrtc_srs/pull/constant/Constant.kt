package com.shencoder.webrtc_srs.pull.constant

/**
 *
 * @author  ShenBen
 * @date    2021/8/26 16:59
 * @email   714081644@qq.com
 */
object Constant {
    /**
     * SRS服务器IP
     */
    const val SRS_SERVER_IP = "121.37.103.111"

    /**
     * SRS服务http请求端口，默认1985
     */
    const val SRS_SERVER_HTTP_PORT = "80"

    /**
     * SRS服务https请求端口，默认1990
     */
    const val SRS_SERVER_HTTPS_PORT = "443"

    const val SRS_SERVER_HTTP = "$SRS_SERVER_IP:$SRS_SERVER_HTTP_PORT"

    const val SRS_SERVER_HTTPS = "$SRS_SERVER_IP:$SRS_SERVER_HTTPS_PORT"
}