package com.v2ray.ang.ui.main

/** A point on the earth, in degrees. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * The middle of a country, enough to put a marker on the dotted map; a country that is not listed has no marker.
 * Rough on purpose: the map shows a region, not an address.
 */
internal fun countryCentroid(countryCode: String?): GeoPoint? {
    val pair: Pair<Double, Double> = when (countryCode?.uppercase()) {
    "AE" -> 24.0 to 54.0
    "AL" -> 41.0 to 20.0
    "AM" -> 40.2 to 45.0
    "AR" -> -35.0 to -65.0
    "AT" -> 47.6 to 14.1
    "AU" -> -25.0 to 134.0
    "AZ" -> 40.3 to 47.7
    "BA" -> 44.0 to 17.8
    "BD" -> 23.8 to 90.3
    "BE" -> 50.6 to 4.6
    "BG" -> 42.7 to 25.5
    "BR" -> -10.0 to -52.0
    "BY" -> 53.5 to 28.0
    "CA" -> 56.0 to -106.0
    "CH" -> 46.8 to 8.2
    "CL" -> -33.0 to -71.0
    "CO" -> 4.0 to -73.0
    "CY" -> 35.0 to 33.0
    "CZ" -> 49.8 to 15.5
    "DE" -> 51.0 to 10.0
    "DK" -> 56.0 to 10.0
    "EE" -> 58.7 to 25.5
    "EG" -> 26.5 to 30.0
    "ES" -> 40.0 to -3.7
    "FI" -> 64.0 to 26.0
    "FR" -> 46.5 to 2.5
    "GB" -> 54.0 to -2.0
    "GE" -> 42.2 to 43.5
    "GR" -> 39.0 to 22.0
    "HK" -> 22.3 to 114.2
    "HR" -> 45.2 to 15.5
    "HU" -> 47.1 to 19.5
    "ID" -> -2.0 to 118.0
    "IE" -> 53.2 to -8.0
    "IL" -> 31.4 to 35.0
    "IN" -> 22.0 to 79.0
    "IR" -> 32.0 to 53.0
    "IS" -> 65.0 to -18.0
    "IT" -> 42.8 to 12.5
    "JP" -> 36.0 to 138.0
    "KE" -> 0.5 to 38.0
    "KR" -> 36.5 to 128.0
    "KW" -> 29.3 to 47.6
    "KZ" -> 48.0 to 67.0
    "LK" -> 7.8 to 80.7
    "LT" -> 55.3 to 23.9
    "LU" -> 49.8 to 6.1
    "LV" -> 56.9 to 24.9
    "MD" -> 47.2 to 28.5
    "ME" -> 42.7 to 19.3
    "MK" -> 41.6 to 21.7
    "MT" -> 35.9 to 14.4
    "MX" -> 23.0 to -102.0
    "MY" -> 4.0 to 102.0
    "NG" -> 9.5 to 8.0
    "NL" -> 52.3 to 5.3
    "NO" -> 61.0 to 9.0
    "NZ" -> -41.0 to 172.0
    "PH" -> 12.5 to 122.0
    "PK" -> 30.0 to 70.0
    "PL" -> 52.0 to 19.0
    "PT" -> 39.6 to -8.0
    "QA" -> 25.3 to 51.2
    "RO" -> 45.9 to 25.0
    "RS" -> 44.0 to 20.8
    "RU" -> 60.0 to 90.0
    "SA" -> 24.0 to 45.0
    "SE" -> 62.0 to 15.0
    "SG" -> 1.35 to 103.8
    "SI" -> 46.1 to 14.8
    "SK" -> 48.7 to 19.5
    "TH" -> 15.0 to 101.0
    "TR" -> 39.0 to 35.0
    "TW" -> 23.7 to 121.0
    "UA" -> 49.0 to 31.0
    "US" -> 39.0 to -98.0
    "VN" -> 16.0 to 107.0
    "ZA" -> -29.0 to 24.0
        else -> return null
    }
    return GeoPoint(pair.first, pair.second)
}
