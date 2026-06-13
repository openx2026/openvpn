package com.openvpn.client.api

import com.google.gson.annotations.SerializedName

data class AuthResponse(
    @SerializedName("accessToken") val accessToken: String,
    val user: AuthUser,
)

data class AuthUser(
    val id: Long,
    val username: String,
    @SerializedName("isMember") val isMember: Boolean,
    @SerializedName("membershipExpiredAt") val membershipExpiredAt: String? = null,
)

data class UserProfile(
    val id: Long,
    val username: String,
    @SerializedName("inviteCode") val inviteCode: String? = null,
    @SerializedName("isMember") val isMember: Boolean,
    @SerializedName("membershipExpiredAt") val membershipExpiredAt: String? = null,
    @SerializedName("createdAt") val createdAt: String,
)

data class CatalogPlan(
    val id: Long,
    val name: String,
    val description: String? = null,
    @SerializedName("planTier") val planTier: String,
    @SerializedName("planType") val planType: String,
    @SerializedName("priceUsdt") val priceUsdt: Double,
    @SerializedName("sortOrder") val sortOrder: Int = 0,
)

data class CatalogChain(
    @SerializedName("chainId") val chainId: Long,
    @SerializedName("usdtDecimals") val usdtDecimals: Int,
)

data class OrderPlanSummary(
    val id: Long,
    val name: String,
    @SerializedName("planTier") val planTier: String,
    @SerializedName("planType") val planType: String,
    @SerializedName("priceUsdt") val priceUsdt: Double,
)

data class UserOrder(
    val id: Long,
    @SerializedName("userId") val userId: Long,
    @SerializedName("orderNo") val orderNo: String,
    @SerializedName("subscriptionPlanId") val subscriptionPlanId: Long,
    val amount: Double,
    @SerializedName("receiveAddressId") val receiveAddressId: Long? = null,
    @SerializedName("txHash") val txHash: String? = null,
    @SerializedName("paidAt") val paidAt: String? = null,
    @SerializedName("expiredAt") val expiredAt: String,
    @SerializedName("createdAt") val createdAt: String,
    @SerializedName("updatedAt") val updatedAt: String? = null,
    @SerializedName("chainId") val chainId: Long? = null,
    @SerializedName("toAddress") val toAddress: String,
    val status: String,
    @SerializedName("subscriptionPlan") val subscriptionPlan: OrderPlanSummary,
)

data class UserMembershipInfo(
    @SerializedName("planTier") val planTier: String,
    val status: String,
    @SerializedName("expiredAt") val expiredAt: String? = null,
    @SerializedName("remainingDays") val remainingDays: Int? = null,
)

data class UserMembershipSnapshot(
    val membership: UserMembershipInfo? = null,
    @SerializedName("inviteInvitedCount") val inviteInvitedCount: Int = 0,
    @SerializedName("inviteRewardDaysEarned") val inviteRewardDaysEarned: Int = 0,
)

data class SubscriptionFeedResponse(
    @SerializedName("feedUrl") val feedUrl: String? = null,
)

data class CreateOrderRequest(
    @SerializedName("subscriptionPlanId") val subscriptionPlanId: Long,
    @SerializedName("chainId") val chainId: Long,
)

data class RegisterRequest(
    val username: String,
    val password: String,
    @SerializedName("inviteCode") val inviteCode: String? = null,
)

data class LoginRequest(
    val username: String,
    val password: String,
)

data class ChangePasswordRequest(
    @SerializedName("currentPassword") val currentPassword: String,
    @SerializedName("newPassword") val newPassword: String,
)

data class OkResponse(
    val ok: Boolean = false,
)
