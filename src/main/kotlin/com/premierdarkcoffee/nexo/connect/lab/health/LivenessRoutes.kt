package com.premierdarkcoffee.nexo.connect.lab.health

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.livenessRoutes() {
    get("/health/live") {
        call.respondText(
            text = "LIVE",
            contentType = ContentType.Text.Plain,
            status = HttpStatusCode.OK,
        )
    }
}
