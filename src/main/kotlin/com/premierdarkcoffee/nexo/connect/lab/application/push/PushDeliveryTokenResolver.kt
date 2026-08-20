package com.premierdarkcoffee.nexo.connect.lab.application.push

import com.premierdarkcoffee.nexo.connect.lab.domain.push.NotificationOutboxIntent
import com.premierdarkcoffee.nexo.connect.lab.domain.push.PushTokenSecret

sealed interface PushDeliveryTokenResolution<out T> {
    data class Resolved<T>(val value: T) : PushDeliveryTokenResolution<T>

    data object NotFoundOrDenied : PushDeliveryTokenResolution<Nothing>
}

interface PushDeliveryTokenResolver {
    fun <T> withActiveToken(
        intent: NotificationOutboxIntent,
        action: (PushTokenSecret, tokenVersion: Long) -> T,
    ): PushDeliveryTokenResolution<T>
}
