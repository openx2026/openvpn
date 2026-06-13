package com.openvpn.client.api

class ApiException(
    val status: Int,
    override val message: String,
) : Exception(message)
