package com.aijec.izmirtransit.core.data.repository

import com.aijec.izmirtransit.core.database.AnnouncementEntity
import com.aijec.izmirtransit.core.database.DepartureEntity
import com.aijec.izmirtransit.core.database.LineEntity
import com.aijec.izmirtransit.core.database.StopEntity
import com.aijec.izmirtransit.core.model.Announcement
import com.aijec.izmirtransit.core.model.Departure
import com.aijec.izmirtransit.core.model.Direction
import com.aijec.izmirtransit.core.model.Line
import com.aijec.izmirtransit.core.model.ServiceDay
import com.aijec.izmirtransit.core.model.Stop
import com.aijec.izmirtransit.core.model.TransitMode
import com.aijec.izmirtransit.core.model.geo.LatLng
import java.time.LocalDateTime

internal fun LineEntity.toModel() = Line(
    mode = TransitMode.valueOf(mode),
    code = code,
    displayCode = displayCode,
    color = color,
    name = name,
    routeDescription = routeDescription,
    notes = notes,
    origin = origin,
    destination = destination,
)

internal fun StopEntity.toModel() = Stop(TransitMode.valueOf(mode), code, name, LatLng(latitude, longitude))

internal fun DepartureEntity.toModel() = Departure(
    lineCode = lineCode,
    direction = Direction.fromDatasetCode(direction) ?: Direction.OUTBOUND,
    serviceDay = ServiceDay.entries[serviceDay],
    minutesAfterMidnight = minutesAfterMidnight,
    wheelchairAccessible = wheelchairAccessible,
    bikeRack = bikeRack,
    electric = electric,
)

internal fun AnnouncementEntity.toModel() = Announcement(
    lineCode = lineCode,
    title = title,
    startsAt = startsAt?.let(LocalDateTime::parse),
    endsAt = endsAt?.let(LocalDateTime::parse),
)
