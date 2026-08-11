package com.premierdarkcoffee.nexo.connect.lab.backend.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        livenessRoutes()
        readinessRoutes(this@configureRouting)
        get("/") {
            call.respondText("Hello, World!")
        }
    }
}