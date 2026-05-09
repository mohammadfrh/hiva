package org.linphone.incomingcall.hiva

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

data class CaptchaImageResponse(
    @SerializedName("captcha_key") val captchaKey: String,
    @SerializedName("image_url") val imageUrl: String
)

data class CaptchaVerifyRequest(
    @SerializedName("captcha_key") val captchaKey: String,
    @SerializedName("captcha_value") val captchaValue: String
)

data class LoginRequest(
    val username: String,
    val password: String,
    @SerializedName("captcha_key") val captchaKey: String,
    @SerializedName("captcha_value") val captchaValue: String
)

data class LoginResponse(
    val refresh: String?,
    val access: String?,
    val user: LoginUser?,
    val message: String?
)

data class UserInfoResponse(
    val username: String,
    @SerializedName("referral_code") val referralCode: String?,
    val balance: Long,
    @SerializedName("is_verified") val isVerified: Boolean,
    @SerializedName("is_superuser") val isSuperuser: Boolean
)

data class TetherRateResponse(
    val status: Boolean,
    val rate: Long
)

data class BalanceResponse(
    val balance: Long
)

data class LoginUser(
    val username: String,
    @SerializedName("referral_code") val referralCode: String?
)

data class ProfileResponse(
    @SerializedName("phone_number") val phoneNumber: String?,
    val balance: Long,
    @SerializedName("gift_balance") val giftBalance: Long,
    @SerializedName("verification_status") val verificationStatus: String?,
    @SerializedName("notifications_count") val notificationsCount: Int?,
    @SerializedName("active_portfolio") val activePortfolio: Any?,
    @SerializedName("transaction_stats") val transactionStats: TransactionStats,
    val transactions: PagedTransactions,
    val portfolios: PagedPortfolios
)

data class TransactionStats(
    @SerializedName("total_profit_loss") val totalProfitLoss: Long,
    @SerializedName("transaction_count") val transactionCount: Int,
    @SerializedName("open_transactions_count") val openTransactionsCount: Int
)

data class PagedTransactions(
    val page: Int,
    val pages: Int,
    val count: Int,
    @SerializedName("has_next") val hasNext: Boolean,
    @SerializedName("has_prev") val hasPrev: Boolean,
    val results: List<TransactionItem>
)

data class TransactionItem(
    val id: Long,
    val units: Double,
    @SerializedName("entry_price") val entryPrice: Double,
    @SerializedName("close_price") val closePrice: Double,
    @SerializedName("take_profit") val takeProfit: Double,
    @SerializedName("stop_loss") val stopLoss: Double,
    val action: String, // buy / sell
    val pnl: Double,
    val fee: Double,
    val status: String, // open / closed
    @SerializedName("status_display") val statusDisplay: String?
)

data class PagedPortfolios(
    val page: Int,
    val pages: Int,
    val count: Int,
    @SerializedName("has_next") val hasNext: Boolean,
    @SerializedName("has_prev") val hasPrev: Boolean,
    val results: List<PortfolioItem>
)

data class PortfolioItem(
    val id: Long,
    val type: String,
    val metric: Double,
    @SerializedName("amount_in") val amountIn: Double,
    @SerializedName("amount_out") val amountOut: Double,
    @SerializedName("profit_loss") val profitLoss: Double,
    val status: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("end_time") val endTime: String?
)

data class MazanehStatus(
    val active: Boolean,
    val reason: String?,
    val notification: String?
)

data class MazanehStaticValues(
    @SerializedName("pip_value") val pipValue: Long,
    @SerializedName("pip_value_half") val pipValueHalf: Long,
    @SerializedName("pip_value_half_pip_mode") val pipValueHalfPipMode: Long,
    @SerializedName("margin_per_unit") val marginPerUnit: Long,
    @SerializedName("margin_per_unit_half_pip_mode") val marginPerUnitHalfPipMode: Long
)

data class MazanehBar(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

data class MazanehTransactions(
    val open: List<JsonObject>,
    val closed: List<JsonObject>
)

data class MazanehPortfolio(
    val id: Long,
    @SerializedName("portfolio_type") val portfolioType: String,
    @SerializedName("total_balance") val totalBalance: Double,
    @SerializedName("initial_balance") val initialBalance: Double,
    @SerializedName("available_units") val availableUnits: Double,
    @SerializedName("user_profile") val userProfile: Long,
    val status: String
)
