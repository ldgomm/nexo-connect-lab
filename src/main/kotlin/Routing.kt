package com.premierdarkcoffee.nexo

import com.premierdarkcoffee.nexo.connect.lab.health.livenessRoutes
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        livenessRoutes()
        get("/") {
            call.respondText("Hello, World!")
        }
    }
}