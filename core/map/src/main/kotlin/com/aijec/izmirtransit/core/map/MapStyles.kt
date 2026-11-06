package com.aijec.izmirtransit.core.map

import com.google.android.gms.maps.model.MapStyleOptions

/**
 * A monochrome base map in the spirit of ride-hailing apps: greys only and quiet labels, so
 * the coloured routes, stops and vehicles stand out. Places (shops, cafés, offices) keep their
 * icons and names, in grey: people use the map to find where they're going.
 *
 * Google's own transit station icons stay hidden in both: they duplicated our stop badges.
 * A JSON style replaces Google's built-in dark scheme, so dark mode has its own style.
 */
internal object MapStyles {
    val Light = MapStyleOptions(
        """
        [
          {"elementType":"geometry","stylers":[{"color":"#f2f2f2"}]},
          {"elementType":"labels.icon","stylers":[{"saturation":-100},{"lightness":15}]},
          {"elementType":"labels.text.fill","stylers":[{"color":"#6b6b6b"}]},
          {"elementType":"labels.text.stroke","stylers":[{"color":"#f2f2f2"}]},
          {"featureType":"administrative","elementType":"geometry","stylers":[{"color":"#d6d6d6"}]},
          {"featureType":"administrative.land_parcel","stylers":[{"visibility":"off"}]},
          {"featureType":"administrative.neighborhood","elementType":"labels.text.fill","stylers":[{"color":"#8c8c8c"}]},
          {"featureType":"landscape.man_made","elementType":"geometry","stylers":[{"color":"#ebebeb"}]},
          {"featureType":"poi","elementType":"geometry","stylers":[{"color":"#e8e8e8"}]},
          {"featureType":"poi","elementType":"labels.text.fill","stylers":[{"color":"#7a7a7a"}]},
          {"featureType":"poi.park","elementType":"geometry","stylers":[{"color":"#e0e0e0"}]},
          {"featureType":"road","elementType":"geometry","stylers":[{"color":"#ffffff"}]},
          {"featureType":"road","elementType":"labels.text.fill","stylers":[{"color":"#8a8a8a"}]},
          {"featureType":"road.arterial","elementType":"geometry","stylers":[{"color":"#ffffff"}]},
          {"featureType":"road.highway","elementType":"geometry.fill","stylers":[{"color":"#dcdcdc"}]},
          {"featureType":"road.highway","elementType":"geometry.stroke","stylers":[{"color":"#cfcfcf"}]},
          {"featureType":"road.highway","elementType":"labels.text.fill","stylers":[{"color":"#6b6b6b"}]},
          {"featureType":"road.local","elementType":"labels.text.fill","stylers":[{"color":"#a3a3a3"}]},
          {"featureType":"transit.line","elementType":"geometry","stylers":[{"color":"#e0e0e0"}]},
          {"featureType":"transit.station","stylers":[{"visibility":"off"}]},
          {"featureType":"water","elementType":"geometry","stylers":[{"color":"#cdd1d4"}]},
          {"featureType":"water","elementType":"labels.text.fill","stylers":[{"color":"#9a9a9a"}]}
        ]
        """.trimIndent(),
    )

    val Dark = MapStyleOptions(
        """
        [
          {"elementType":"geometry","stylers":[{"color":"#1c1c1e"}]},
          {"elementType":"labels.icon","stylers":[{"saturation":-100},{"lightness":-35}]},
          {"elementType":"labels.text.fill","stylers":[{"color":"#8a8a8e"}]},
          {"elementType":"labels.text.stroke","stylers":[{"color":"#1c1c1e"}]},
          {"featureType":"administrative","elementType":"geometry","stylers":[{"color":"#3a3a3c"}]},
          {"featureType":"administrative.land_parcel","stylers":[{"visibility":"off"}]},
          {"featureType":"administrative.locality","elementType":"labels.text.fill","stylers":[{"color":"#a5a5aa"}]},
          {"featureType":"landscape.man_made","elementType":"geometry","stylers":[{"color":"#202022"}]},
          {"featureType":"poi","elementType":"geometry","stylers":[{"color":"#222224"}]},
          {"featureType":"poi","elementType":"labels.text.fill","stylers":[{"color":"#86868a"}]},
          {"featureType":"poi.park","elementType":"geometry","stylers":[{"color":"#232625"}]},
          {"featureType":"road","elementType":"geometry.fill","stylers":[{"color":"#2c2c2e"}]},
          {"featureType":"road","elementType":"geometry.stroke","stylers":[{"color":"#1c1c1e"}]},
          {"featureType":"road","elementType":"labels.text.fill","stylers":[{"color":"#7c7c80"}]},
          {"featureType":"road.arterial","elementType":"geometry.fill","stylers":[{"color":"#353537"}]},
          {"featureType":"road.highway","elementType":"geometry.fill","stylers":[{"color":"#434345"}]},
          {"featureType":"road.highway","elementType":"labels.text.fill","stylers":[{"color":"#9a9a9e"}]},
          {"featureType":"road.local","elementType":"labels.text.fill","stylers":[{"color":"#636366"}]},
          {"featureType":"transit.line","elementType":"geometry","stylers":[{"color":"#2a2a2c"}]},
          {"featureType":"transit.station","stylers":[{"visibility":"off"}]},
          {"featureType":"water","elementType":"geometry","stylers":[{"color":"#0e0f11"}]},
          {"featureType":"water","elementType":"labels.text.fill","stylers":[{"color":"#4a4a4d"}]}
        ]
        """.trimIndent(),
    )
}
