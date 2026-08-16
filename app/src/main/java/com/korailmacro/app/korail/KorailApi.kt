package com.korailmacro.app.korail

import android.util.Base64
import android.util.Log
import okhttp3.CertificatePinner
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Unofficial client for the KORAIL (letskorail) mobile app API, ported from the
 * behavior of the actively-maintained yakisoba0728/korail-mobile-api Python
 * project (itself decompiled from KORAIL app v6.5.0). This is a reverse-engineered,
 * undocumented API — endpoints, field names, the login crypto scheme, or the
 * DynaPath anti-automation token algorithm may change without notice on Korail's
 * side. Authentication is cookie-based (JSESSIONID), so one instance of this class
 * must be reused for the whole session.
 */
class KorailException(message: String, val rawResponse: String? = null) : Exception(message)

data class Train(
    val trainTypeCode: String,      // h_trn_clsf_cd
    val trainTypeName: String,      // h_trn_clsf_nm
    val trainGroup: String,         // h_trn_gp_cd
    val trainNo: String,            // h_trn_no
    val depStationName: String,
    val depStationCode: String,
    val depDate: String,            // h_dpt_dt
    val depTime: String,            // h_dpt_tm (HHmmss)
    val arrStationName: String,
    val arrStationCode: String,
    val arrTime: String,            // h_arv_tm
    val runDate: String,            // h_run_dt
    val depConstructionOrder: String, // h_dpt_stn_cons_ordr
    val arrConstructionOrder: String, // h_arv_stn_cons_ordr
    val depRunOrder: String,          // h_dpt_stn_run_ordr
    val arrRunOrder: String,          // h_arv_stn_run_ordr
    val generalSeatCode: String,    // h_gen_rsv_cd : 11 = available
    val specialSeatCode: String,    // h_spe_rsv_cd : 11 = available
    val reservePossibleName: String
) {
    val hasGeneralSeat get() = generalSeatCode == "11"
    val hasSpecialSeat get() = specialSeatCode == "11"

    fun summary(): String =
        "[$trainTypeName $trainNo] $depStationName $depTime -> $arrStationName $arrTime " +
            "(일반:$generalSeatCode/$reservePossibleName 특실:$specialSeatCode)"
}

/** User-facing train-class filter, matched against the server's free-text h_trn_clsf_nm (e.g. "KTX-산천", "ITX-새마을"). */
enum class TrainType(val label: String) {
    KTX("KTX") { override fun matches(name: String) = name.contains("KTX") },
    ITX_SAEMAEUL("ITX/새마을") { override fun matches(name: String) = name.contains("새마을") },
    MUGUNGHWA("무궁화호") { override fun matches(name: String) = name.contains("무궁화") },
    ITX_CHEONGCHUN("ITX-청춘") { override fun matches(name: String) = name.contains("청춘") };

    abstract fun matches(name: String): Boolean
}

/** In-memory cookie jar so the login session cookie (JSESSIONID) survives for this client's lifetime. */
private class MemoryCookieJar : CookieJar {
    private val store = mutableMapOf<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isNotEmpty()) store[url.host] = cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host] ?: emptyList()
}

class KorailApi {

    companion object {
        private const val TAG = "KorailApi"
        private const val SCHEME_HOST = "https://smart.letskorail.com:443/classes/com.korail.mobile"
        private const val URL_CODE = "$SCHEME_HOST.common.code.do"
        private const val URL_LOGIN = "$SCHEME_HOST.login.Login"
        private const val URL_SEARCH = "$SCHEME_HOST.seatMovie.ScheduleView"
        private const val URL_RESERVE = "$SCHEME_HOST.certification.TicketReservation"
        private const val LOGIN_PATH = "/classes/com.korail.mobile.login.Login"
        private const val RESERVE_PATH = "/classes/com.korail.mobile.certification.TicketReservation"

        private const val DEVICE = "AD"
        private const val VERSION = "250601003"
        // Static app key expected on Login/TicketReservation; the server tracks the
        // actual session via the login cookie, not this value.
        private const val APP_KEY = "korail1234567890"
        private const val OS_RELEASE = "15"
        private const val DEVICE_MODEL = "Android"
        private const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android $OS_RELEASE; $DEVICE_MODEL)"

        // txtInputFlg values expected by the server
        const val LOGIN_TYPE_MEMBERSHIP = "2"
        const val LOGIN_TYPE_PHONE = "4"
        const val LOGIN_TYPE_EMAIL = "5"

        const val SEAT_GENERAL = "1"
        const val SEAT_SPECIAL = "2"

        // "전체" (all train types) train group/type code used by the mobile app search screen
        private const val TRAIN_GROUP_ALL = "109"

        private val LOGIN_SUCCESS_CODES = setOf("IRZ000001", "S200")

        private val COMMON_CODE_BOOTSTRAP_CODES = listOf(
            "app.display.image", "app.menu.railpoint", "app.main.popup",
            "app.easyLogin.isShow", "app.korail.boss", "app.menu.buynow",
            "app.menu.lost112", "app.event.easyPay", "app.hndy.athn",
            "app.view.visibility", "app.menu.biz", "app.event.point",
            "app.var.data", "app.login.cphd", "app.illegal.report",
            "app.holiday.popup", "app.MaaS.test", "app.limousine.mainMsg"
        )

        // The 8 passenger rows KORAIL's reservation request always carries
        // (attribute index -> passenger-type code, discount code). Only the
        // adult row is populated by this app; the rest ride as "0".
        private val PASSENGER_ROWS = listOf(
            Triple(1, "1", "000"), // 어른
            Triple(2, "1", "P11"), // 청소년
            Triple(3, "3", "000"), // 어린이
            Triple(4, "3", "321"), // 동반유아
            Triple(5, "1", "131"), // 경로
            Triple(6, "1", "111"), // 1~3급 장애
            Triple(7, "1", "112"), // 4~6급 장애
            Triple(8, "1", "173")  // 안내견
        )

        // Pinned leaf + issuing CA + root for smart.letskorail.com (fetched 2026-08-17).
        // Pinning all three means the leaf cert renewing on its normal schedule won't
        // break the app (the CA/root pins still match) — only a switch to a wholly
        // different CA, or an actual MITM, would trip this and fail the connection.
        private val KORAIL_CERT_PINNER = CertificatePinner.Builder()
            .add(
                "smart.letskorail.com",
                "sha256/+aKPhqKL0hOK1/1r5KYM4uKXDQ5kSOf5/2iSbcLtNms=", // leaf, expires 2026-11-21
                "sha256/hETpgVvaLC0bvcGG3t0cuqiHvr4XyP2MTwCiqhgRWwU=", // GlobalSign RSA OV SSL CA 2018
                "sha256/cGuxAXyFXFkWm61cF4HPWX8S0srS9j0aSqN0k4AP+4A="  // GlobalSign Root CA - R3
            )
            .build()
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(MemoryCookieJar())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .certificatePinner(KORAIL_CERT_PINNER)
        .build()

    // One synthetic device identity + app-start timestamp per login session, matching
    // how the real app keeps a stable DynaPath device id for the process lifetime.
    private val dynaPathSettings = DynaPath.buildDefaultTokenSettings(OS_RELEASE, DEVICE_MODEL)

    private fun baseRequest(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Connection", "close")

    private data class LoginCryptoInfo(val idx: String, val key: String, val pwdAesCphd: String)

    private fun getLoginCryptoInfo(): LoginCryptoInfo {
        val formBuilder = FormBody.Builder()
            .add("Device", DEVICE)
            .add("Version", VERSION)
            .add("Key", APP_KEY)
        for (code in COMMON_CODE_BOOTSTRAP_CODES) formBuilder.add("code", code)
        formBuilder.add("deviceWidth", "1080")
        formBuilder.add("deviceHeight", "2400")
        formBuilder.add("OSVersion", "35")

        val req = baseRequest(URL_CODE)
            .post(formBuilder.build())
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .build()
        val text = client.newCall(req).execute().use { resp ->
            resp.body?.string() ?: throw KorailException("암호화 키 응답이 비어 있습니다")
        }
        val root = JSONObject(text)
        val payload = root.optJSONObject("app.login.cphd")
            ?: root.optJSONObject("login")
            ?: root.optJSONObject("data")?.optJSONObject("app.login.cphd")
            ?: root.optJSONObject("data")?.optJSONObject("login")
            ?: root
        val idx = payload.optString("idx", "")
        val key = payload.optString("key", "")
        val pwdAesCphd = (payload.optString("pwdAESCphd", payload.optString("loginFlg", ""))).uppercase()
        if (pwdAesCphd != "Y" && pwdAesCphd != "N") {
            throw KorailException("암호화 파라미터(pwdAESCphd)를 확인할 수 없습니다", text)
        }
        if (pwdAesCphd == "Y" && (idx.isBlank() || key.isBlank())) {
            throw KorailException("암호화 키/idx가 비어 있습니다", text)
        }
        return LoginCryptoInfo(idx, key, pwdAesCphd)
    }

    /** Mirrors S4/C0812l.getAmountEncrypt: AES-CBC(PKCS7) -> Base64 DEFAULT -> Base64 NO_WRAP, or plain Base64 NO_WRAP. */
    private fun transformLoginPassword(password: String, info: LoginCryptoInfo): String {
        if (info.pwdAesCphd == "Y") {
            val keyBytes = info.key.toByteArray(Charsets.UTF_8)
            if (keyBytes.size !in setOf(16, 24, 32)) {
                throw KorailException("로그인 암호화 키 길이가 올바르지 않습니다")
            }
            val iv = keyBytes.copyOfRange(0, 16)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
            val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val step1 = Base64.encodeToString(encrypted, Base64.DEFAULT)
            return Base64.encodeToString(step1.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
        return Base64.encodeToString(password.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    /** Mirrors S4/C0812l.getSid: AES-CBC("AD"+epochMillis, fixed key==iv) -> Base64 DEFAULT. */
    private fun generateSid(): String {
        val sidKey = "2485dd54d9deaa36".toByteArray(Charsets.UTF_8)
        val plaintext = "AD${System.currentTimeMillis()}".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sidKey, "AES"), IvParameterSpec(sidKey))
        val encrypted = cipher.doFinal(plaintext)
        return Base64.encodeToString(encrypted, Base64.DEFAULT)
    }

    /** Logs in. Throws [KorailException] on failure. Session is tracked via cookies afterwards. */
    fun login(loginType: String, loginId: String, password: String) {
        val cryptoInfo = getLoginCryptoInfo()
        val transformedPwd = transformLoginPassword(password, cryptoInfo)

        val formBuilder = FormBody.Builder()
            .add("Device", DEVICE)
            .add("Version", VERSION)
            .add("Key", APP_KEY)
            .add("txtMemberNo", loginId)
            .add("txtPwd", transformedPwd)
            .add("txtInputFlg", loginType)
            .add("checkValidPw", "Y")
        if (cryptoInfo.idx.isNotBlank()) formBuilder.add("idx", cryptoInfo.idx)

        val dynapathToken = DynaPath.generateToken(dynaPathSettings)
        val req = baseRequest(URL_LOGIN)
            .post(formBuilder.build())
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header(DynaPath.HEADER_NAME, dynapathToken)
            .build()

        val text = client.newCall(req).execute().use { resp ->
            resp.body?.string() ?: throw KorailException("로그인 응답이 비어 있습니다")
        }
        val json = JSONObject(text)
        val msgCd = json.optString("h_msg_cd", "")
        if (msgCd !in LOGIN_SUCCESS_CODES) {
            val msg = json.optString("h_msg_txt", "로그인 실패")
            throw KorailException("$msgCd: $msg", text)
        }
    }

    fun searchTrain(
        dep: String,
        arr: String,
        date: String,
        time: String,
        adultCount: Int
    ): List<Train> {
        val formBuilder = FormBody.Builder()
            .add("Device", DEVICE)
            .add("Version", VERSION)
            .add("Sid", generateSid())
            .add("txtMenuId", "11")
            .add("radJobId", "1")
            .add("selGoTrain", TRAIN_GROUP_ALL)
            .add("txtTrnGpCd", TRAIN_GROUP_ALL)
            .add("txtGoStart", dep)
            .add("txtGoEnd", arr)
            .add("txtGoAbrdDt", date)
            .add("txtGoHour", time)
            .add("txtPsgFlg_1", adultCount.toString())
            .add("txtPsgFlg_2", "0")
            .add("txtPsgFlg_3", "0")
            .add("txtPsgFlg_4", "0")
            .add("txtPsgFlg_5", "0")
            .add("txtSeatAttCd_2", "000")
            .add("txtSeatAttCd_3", "000")
            .add("txtSeatAttCd_4", "015")
            .add("ebizCrossCheck", "N")
            .add("srtCheckYn", "N")
            .add("rtYn", "N")
            .add("adjStnScdlOfrFlg", "N")
            .add("qryDvCd", "1")
            .add("qryStNo", "0")
            .add("qryStTrnNo", "00000")
            .add("qryStTrnNo2", "")
            .add("pgPrCnt", "10")

        val dynapathToken = DynaPath.generateToken(dynaPathSettings)
        val req = baseRequest(URL_SEARCH)
            .post(formBuilder.build())
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header(DynaPath.HEADER_NAME, dynapathToken)
            .build()
        val text = client.newCall(req).execute().use { resp ->
            resp.body?.string() ?: throw KorailException("검색 응답이 비어 있습니다")
        }
        val json = JSONObject(text)
        if (json.optString("strResult") == "FAIL") {
            Log.w(TAG, "search fail raw=$text")
            throw KorailException(json.optString("h_msg_txt", "조회 실패"), text)
        }
        val trnInfos = json.opt("trn_infos")
        val list = when (trnInfos) {
            is JSONObject -> normalizeToArray(trnInfos.opt("trn_info"))
            is JSONArray -> trnInfos
            else -> JSONArray()
        }

        val trains = mutableListOf<Train>()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            trains.add(
                Train(
                    trainTypeCode = o.optString("h_trn_clsf_cd"),
                    trainTypeName = o.optString("h_trn_clsf_nm"),
                    trainGroup = o.optString("h_trn_gp_cd"),
                    trainNo = o.optString("h_trn_no"),
                    depStationName = o.optString("h_dpt_rs_stn_nm"),
                    depStationCode = o.optString("h_dpt_rs_stn_cd"),
                    depDate = o.optString("h_dpt_dt"),
                    depTime = o.optString("h_dpt_tm"),
                    arrStationName = o.optString("h_arv_rs_stn_nm"),
                    arrStationCode = o.optString("h_arv_rs_stn_cd"),
                    arrTime = o.optString("h_arv_tm"),
                    runDate = o.optString("h_run_dt"),
                    depConstructionOrder = o.optString("h_dpt_stn_cons_ordr"),
                    arrConstructionOrder = o.optString("h_arv_stn_cons_ordr"),
                    depRunOrder = o.optString("h_dpt_stn_run_ordr"),
                    arrRunOrder = o.optString("h_arv_stn_run_ordr"),
                    generalSeatCode = o.optString("h_gen_rsv_cd"),
                    specialSeatCode = o.optString("h_spe_rsv_cd"),
                    reservePossibleName = o.optString("h_rsv_psb_nm")
                )
            )
        }
        return trains
    }

    /** Reserves [train] for [adultCount] adults. Returns the PNR (reservation) number on success. */
    fun reserve(train: Train, adultCount: Int, seatType: String): String {
        val formBuilder = FormBody.Builder()
            .add("Device", DEVICE)
            .add("Version", VERSION)
            .add("Key", APP_KEY)
            .add("txtMenuId", "11")
            .add("txtJobId", "1101")
            .add("txtGdNo", "")
            .add("hidFreeFlg", "N")
            .add("txtStndFlg", "N")
            .add("txtTotPsgCnt", adultCount.toString())

        for ((index, typeCode, discCode) in PASSENGER_ROWS) {
            val count = if (index == 1) adultCount else 0
            formBuilder.add("txtCompaCnt$index", count.toString())
            formBuilder.add("txtPsgTpCd$index", typeCode)
            formBuilder.add("txtDiscKndCd$index", discCode)
        }

        formBuilder
            .add("txtSeatAttCd1", "000")
            .add("txtSeatAttCd2", "000")
            .add("txtSeatAttCd3", "000")
            .add("txtSeatAttCd4", "015")
            .add("txtSeatAttCd5", "000")
            .add("txtPsrmClCd1", seatType)
            .add("txtJrnyCnt", "1")
            .add("txtJrnyTpCd1", "11")
            .add("txtJrnySqno1", "001")
            .add("txtTrnNo1", train.trainNo)
            .add("txtTrnClsfCd1", train.trainTypeCode)
            .add("txtTrnGpCd1", train.trainGroup)
            .add("txtRunDt1", train.runDate)
            .add("txtDptDt1", train.depDate)
            .add("txtDptTm1", train.depTime)
            .add("arvTm_1", train.arrTime)
            .add("txtDptRsStnCd1", train.depStationCode)
            .add("txtDptStnConsOrdr1", train.depConstructionOrder)
            .add("txtDptStnRunOrdr1", train.depRunOrder)
            .add("txtArvRsStnCd1", train.arrStationCode)
            .add("txtArvStnConsOrdr1", train.arrConstructionOrder)
            .add("txtArvStnRunOrdr1", train.arrRunOrder)
            .add("txtChgFlg1", "N")

        val dynapathToken = DynaPath.generateToken(dynaPathSettings)
        val req = baseRequest(URL_RESERVE)
            .post(formBuilder.build())
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header(DynaPath.HEADER_NAME, dynapathToken)
            .build()
        val text = client.newCall(req).execute().use { resp ->
            resp.body?.string() ?: throw KorailException("예약 응답이 비어 있습니다")
        }
        val json = JSONObject(text)
        val pnr = json.optString("h_pnr_no", "")
        if (json.optString("strResult") == "FAIL" || pnr.isBlank()) {
            val msg = json.optString("h_msg_txt", "예약 실패")
            throw KorailException(msg, text)
        }
        return pnr
    }

    /** The server returns a single JSONObject when there's exactly one result, or a JSONArray otherwise. */
    private fun normalizeToArray(value: Any?): JSONArray {
        return when (value) {
            null -> JSONArray()
            is JSONArray -> value
            is JSONObject -> JSONArray().put(value)
            else -> JSONArray()
        }
    }
}
